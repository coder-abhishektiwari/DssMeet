package com.dssmeet.kiosk.ui.call

import com.dssmeet.kiosk.ui.theme.DangerRed

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row of the progress checklist. */
@Composable
private fun StageRow(label: String, state: StageState, isTablet: Boolean = false) {
    val (color, symbol) = when (state) {
        StageState.DONE -> Color(0xFF22C55E) to "✓"
        StageState.ACTIVE -> Color(0xFFF59E0B) to "•"
        StageState.FAILED -> DangerRed to "✗"
        StageState.PENDING -> Color(0xFF525252) to ""
    }
    val stateDescription = when (state) {
        StageState.DONE -> "completed"
        StageState.ACTIVE -> "in progress"
        StageState.FAILED -> "failed"
        StageState.PENDING -> "pending"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (isTablet) 14.dp else 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (isTablet) 44.dp else 34.dp)
                .background(color, CircleShape)
        ) {
            Text(symbol, color = Color.White, fontSize = if (isTablet) 20.sp else 16.sp)
        }
        Spacer(Modifier.size(if (isTablet) 18.dp else 14.dp))
        Text(
            label,
            color = if (state == StageState.PENDING) Color(0xFF9CA3AF) else Color.White,
            fontSize = if (isTablet) 20.sp else 18.sp,
            modifier = Modifier.semantics {
                contentDescription = "$label, $stateDescription"
            }
        )
    }
}

private enum class StageState { PENDING, ACTIVE, DONE, FAILED }

@Composable
fun CallProgressScreen(
    contactName: String,
    roomId: String,
    isVideoCall: Boolean,
    onConnected: () -> Unit,
    onFailed: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: CallViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val callState by viewModel.callState.collectAsState()
    val stage by viewModel.familyStage.collectAsState()
    val deviceVerifyFailed by viewModel.deviceVerifyFailed.collectAsState()
    val otpVerifyFailed by viewModel.otpVerifyFailed.collectAsState()
    val familyLeft by viewModel.familyLeft.collectAsState()
    val smsSent by viewModel.smsSent.collectAsState()
    val smsFailed by viewModel.smsFailed.collectAsState()

    var showFailure by remember { mutableStateOf(false) }
    var everConnected by remember { mutableStateOf(false) }
    // The engine is a Singleton: its callState may still be CONNECTED/FAILED
    // from a PREVIOUS session when this screen first composes. Navigation
    // must stay blocked until THIS screen has run initCall().
    var sessionStarted by remember { mutableStateOf(false) }

    // Start the call session the moment this screen appears.
    LaunchedEffect(roomId) {
        viewModel.initCall(context, roomId, isVideoCall)
        sessionStarted = true
    }

    // Navigate into the real call screen only once media is CONNECTED.
    LaunchedEffect(callState, sessionStarted) {
        if (!sessionStarted) return@LaunchedEffect
        when (callState) {
            CallUIState.CONNECTED -> {
                everConnected = true
                onConnected()
            }
            CallUIState.FAILED -> {
                showFailure = true
                kotlinx.coroutines.delay(1500)
                onFailed()
            }
            else -> {}
        }
    }

    // Family member closed the verification screen mid-flow: tell the inmate
    // and return to the lobby.
    LaunchedEffect(familyLeft) {
        if (familyLeft) {
            kotlinx.coroutines.delay(2500)
            viewModel.endCall()
            onFailed()
        }
    }

    val stages: List<Pair<String, StageState>> = run {
        val s = stage
        listOf(
            (if (smsSent) "Call link sent to $contactName" else "Sending call link to $contactName") to when {
                smsSent -> StageState.DONE
                smsFailed -> StageState.FAILED
                else -> StageState.ACTIVE
            },
            "$contactName opened the link" to when {
                s.ordinal >= FamilyStage.LINK_OPENED.ordinal -> StageState.DONE
                else -> StageState.PENDING
            },
            "Device verification" to when {
                s.ordinal >= FamilyStage.DEVICE_VERIFIED.ordinal -> StageState.DONE
                deviceVerifyFailed -> StageState.FAILED
                s.ordinal >= FamilyStage.LINK_OPENED.ordinal -> StageState.ACTIVE
                else -> StageState.PENDING
            },
            "OTP verification" to when {
                s.ordinal >= FamilyStage.OTP_VERIFIED.ordinal -> StageState.DONE
                otpVerifyFailed -> StageState.FAILED
                s.ordinal >= FamilyStage.DEVICE_VERIFIED.ordinal -> StageState.ACTIVE
                else -> StageState.PENDING
            },
            "Connecting call..." to when {
                callState == CallUIState.CONNECTED -> StageState.DONE
                s == FamilyStage.OTP_VERIFIED || callState == CallUIState.WAITING &&
                    s.ordinal >= FamilyStage.OTP_VERIFIED.ordinal -> StageState.ACTIVE
                else -> StageState.PENDING
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111827))
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTablet = maxWidth >= 600.dp
            val contentWidth = if (isTablet) {
                minOf(maxWidth - 64.dp, 900.dp)
            } else {
                minOf(maxWidth - 32.dp, 560.dp)
            }.coerceAtLeast(240.dp)

            Column(
                modifier = Modifier
                    .width(contentWidth)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .padding(horizontal = if (isTablet) 24.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when {
                        familyLeft -> "Family member left"
                        showFailure -> "Call Failed"
                        else -> "Calling $contactName"
                    },
                    color = when {
                        familyLeft -> Color(0xFFF59E0B)
                        showFailure -> DangerRed
                        else -> Color.White
                    },
                    fontSize = if (isTablet) 36.sp else 28.sp,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(if (isTablet) 36.dp else 28.dp))
                stages.forEach { (label, state) -> StageRow(label, state, isTablet) }
                Spacer(Modifier.height(if (isTablet) 44.dp else 36.dp))
                Button(
                    onClick = {
                        viewModel.endCall()
                        onCancel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    modifier = Modifier.size(if (isTablet) 88.dp else 72.dp),
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = "End call",
                        tint = Color.White,
                        modifier = Modifier.size(if (isTablet) 40.dp else 32.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Cancel", color = Color(0xFF9CA3AF), fontSize = if (isTablet) 16.sp else 14.sp)
            }
        }
    }
}
