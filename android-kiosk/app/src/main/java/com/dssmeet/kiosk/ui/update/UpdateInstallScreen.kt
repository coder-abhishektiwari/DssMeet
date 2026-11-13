package com.dssmeet.kiosk.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dssmeet.kiosk.models.appupdate.AppUpdateInfo
import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.White

/**
 * Download + install screen. Shown once an ADMIN session exists — installing
 * a build is an administrative action, so an inmate (or a logged-out kiosk)
 * has to sign in as admin first.
 */
@Composable
fun UpdateInstallScreen(
    info: AppUpdateInfo,
    phase: String,
    percent: Int,
    error: String?,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
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
                imageVector = Icons.Default.Downloading,
                contentDescription = null,
                tint = White,
                modifier = Modifier.size(64.dp)
            )

            Spacer24()

            Text(
                text = "Installing update ${info.version}",
                color = White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer16()

            if (error != null) {
                Text(
                    text = error,
                    color = DangerRed,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer16()
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                ) {
                    Text("Try again", fontWeight = FontWeight.Bold, color = White)
                }
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = Color(0xFFBBBBBB))
                }
            } else {
                Text(
                    text = phase,
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp
                )
                Spacer16()
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = PrimaryNavy,
                    trackColor = Color(0xFF333333)
                )
                Spacer8()
                Text(
                    text = "$percent%",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun Spacer24() = Box(Modifier.height(24.dp))

@Composable
private fun Spacer16() = Box(Modifier.height(16.dp))

@Composable
private fun Spacer8() = Box(Modifier.height(8.dp))
