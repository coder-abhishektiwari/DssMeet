package com.dssmeet.kiosk.ui.components

import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy

import com.dssmeet.kiosk.ui.theme.InfoText

import com.dssmeet.kiosk.ui.theme.TextGray

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.doOnLayout
import java.net.URLEncoder

/**
 * In-app map of a call's family location.
 *
 * Same recipe as the warden dashboard's LocationMapModal: Leaflet + keyless
 * Esri World Street Map tiles (no API key, no Google Maps). The kiosk cannot
 * hand off to another app, so the map runs inside a WebView in our own
 * dialog — only the tiles/Leaflet come from the network, the UI stays in-app.
 */
@Composable
fun LocationMapDialog(
    lat: Double,
    lng: Double,
    label: String,
    onDismiss: () -> Unit,
) {
    val mapHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = InfoText,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDarkNavy,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close map",
                            tint = TextGray
                        )
                    }
                }
                WebViewMap(
                    lat = lat,
                    lng = lng,
                    label = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapHeight)
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewMap(
    lat: Double,
    lng: Double,
    label: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // Stay inside the map: tiles/Leaflet load over HTTPS, and no
                // navigation event may ever push the kiosk out of this app.
                webViewClient = WebViewClient()
                val query = "lat=$lat&lng=$lng&label=${URLEncoder.encode(label, "UTF-8")}"
                val url = "file:///android_asset/map.html?$query"
                // Loading before the WebView's first layout gives the page a
                // 0px viewport (it never relayouts), which starves the CSS
                // height chain and Leaflet alike. Load only once measured.
                doOnLayout { loadUrl(url) }
            }
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.destroy()
        },
        modifier = modifier
    )
}
