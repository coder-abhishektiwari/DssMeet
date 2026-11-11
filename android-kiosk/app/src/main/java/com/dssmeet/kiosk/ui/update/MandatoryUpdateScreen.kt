package com.dssmeet.kiosk.ui.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dssmeet.kiosk.models.appupdate.AppUpdateInfo
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.White

/**
 * Mandatory update gate — the kiosk does NOT keep working on an outdated
 * build. There is no way past this screen: the back button is swallowed and
 * the only action is "install the update".
 */
@Composable
fun MandatoryUpdateScreen(
    info: AppUpdateInfo,
    isWorking: Boolean,
    progressText: String?,
    errorText: String?,
    onInstall: () -> Unit
) {
    // No way out — the kiosk stays blocked until the new APK is installed.
    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = Color(0xFFFFC107),
                modifier = Modifier.size(72.dp)
            )

            Spacer32()

            Text(
                text = "UPDATE REQUIRED",
                color = Color(0xFFFFC107),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = 2.sp
            )

            Spacer16()

            Text(
                text = "A new kiosk version (${info.version}) must be installed before this device can be used again.",
                color = White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            if (!info.notes.isNullOrBlank()) {
                Spacer16()
                Surface(
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = info.notes,
                        color = Color(0xFFCCCCCC),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer24()

            Button(
                onClick = onInstall,
                enabled = !isWorking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = White)
                    Spacer8()
                    Text("Install update", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = White)
                }
            }

            if (!progressText.isNullOrBlank()) {
                Spacer16()
                Text(progressText, color = Color(0xFFAAAAAA), fontSize = 12.sp)
            }
            if (!errorText.isNullOrBlank()) {
                Spacer8()
                Text(errorText, color = Color(0xFFFF5252), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Spacer32() = Box(Modifier.height(32.dp))

@Composable
private fun Spacer24() = Box(Modifier.height(24.dp))

@Composable
private fun Spacer16() = Box(Modifier.height(16.dp))

@Composable
private fun Spacer8() = Box(Modifier.width(8.dp))
