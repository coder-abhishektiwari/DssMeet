package com.dssmeet.kiosk.ui.call

import com.dssmeet.kiosk.ui.theme.ControlBtnBg

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.models.call.FamilyLocation
import com.dssmeet.kiosk.models.contacts.Contact
import com.dssmeet.kiosk.models.inmate.InmateProfile
import com.dssmeet.kiosk.models.inmate.InmateStatus
import com.dssmeet.kiosk.ui.components.KioskButton
import com.dssmeet.kiosk.ui.components.KioskProgressIndicator
import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.PrimaryBlue
import com.dssmeet.kiosk.ui.theme.PrisonKioskTheme
import com.dssmeet.kiosk.ui.theme.TextDark
import com.dssmeet.kiosk.ui.theme.TextGray
import com.dssmeet.kiosk.ui.theme.WarningOrange
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

private val DarkControlsBg = Color(0xD90F172A)
private val VideoAccentGreen = Color(0xFF10B981)

@Composable
fun WebRtcSurfaceView(
    videoTrack: VideoTrack?,
    eglContext: org.webrtc.EglBase.Context?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fit: Boolean = false
) {
    val attachedTrack = remember { mutableStateOf<VideoTrack?>(null) }
    val rendererInit = remember { mutableStateOf(false) }
    val targetScaling = if (fit) {
        RendererCommon.ScalingType.SCALE_ASPECT_FIT
    } else {
        RendererCommon.ScalingType.SCALE_ASPECT_FILL
    }

    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                setMirror(mirror)
                setEnableHardwareScaler(true)
                setScalingType(targetScaling)
            }
        },
        update = { view ->
            if (!rendererInit.value && eglContext != null) {
                view.init(eglContext, null)
                rendererInit.value = true
                com.dssmeet.kiosk.core.Logger.d(
                    "WebRtcSurfaceView: renderer init view=${System.identityHashCode(view)}"
                )
            }
            val previous = attachedTrack.value
            if (previous !== videoTrack) {
                if (previous != null) {
                    previous.removeSink(view)
                    com.dssmeet.kiosk.core.Logger.d(
                        "WebRtcSurfaceView: removeSink track=${System.identityHashCode(previous)} view=${System.identityHashCode(view)}"
                    )
                }
                if (videoTrack != null) {
                    videoTrack.addSink(view)
                    com.dssmeet.kiosk.core.Logger.d(
                        "WebRtcSurfaceView: addSink track=${System.identityHashCode(videoTrack)} view=${System.identityHashCode(view)} init=${rendererInit.value}"
                    )
                }
                attachedTrack.value = videoTrack
            }
            view.setScalingType(targetScaling)
            view.setMirror(mirror)
        },
        onRelease = { view ->
            attachedTrack.value?.removeSink(view)
            com.dssmeet.kiosk.core.Logger.d(
                "WebRtcSurfaceView: released view=${System.identityHashCode(view)} hadTrack=${attachedTrack.value != null}"
            )
            view.clearImage()
            view.release()
            attachedTrack.value = null
            rendererInit.value = false
        }
    )
}

@Composable
fun VideoCallScreen(
    contactName: String,
    roomId: String,
    @Suppress("UNUSED_PARAMETER") windowSizeClass: WindowSizeClass,
    onEndCall: () -> Unit,
    onBack: () -> Unit = onEndCall,
    viewModel: CallViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val timerSeconds by viewModel.timerSeconds.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isCameraOn by viewModel.isCameraOn.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()
    val callState by viewModel.callState.collectAsState()
    val isNetworkAvailable by viewModel.isNetworkAvailable.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val inmateProfile by viewModel.inmateProfile.collectAsState()
    val contactProfile by viewModel.contactProfile.collectAsState()

    val localTrack by viewModel.localVideoTrack.collectAsState()
    val remoteTrack by viewModel.remoteVideoTrack.collectAsState()
    val liveCost by viewModel.liveCost.collectAsState()
    val maxCallSeconds by viewModel.maxCallSeconds.collectAsState()
    val endLabel by viewModel.endLabel.collectAsState()
    val connectionQuality by viewModel.connectionQuality.collectAsState()
    val familyLocation by viewModel.familyLocation.collectAsState()

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionDeniedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.CAMERA] == true &&
            permissions[Manifest.permission.RECORD_AUDIO] == true
        hasPermissions = granted
        if (!granted) permissionDeniedOnce = true
    }

    LaunchedEffect(roomId, hasPermissions) {
        if (!hasPermissions && !permissionDeniedOnce) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    var everConnected by remember { mutableStateOf(false) }

    LaunchedEffect(callState) {
        if (callState == CallUIState.CONNECTED) everConnected = true
    }

    LaunchedEffect(callState) {
        when (callState) {
            CallUIState.FAILED -> {
                delay(2500)
                onBack()
            }
            CallUIState.DISCONNECTED -> {
                delay(1500)
                if (everConnected) onEndCall() else onBack()
            }
            else -> {}
        }
    }

    if (!hasPermissions && permissionDeniedOnce) {
        PermissionDeniedUI(onRetry = {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        })
    } else {
        VideoCallContent(
            contactName = contactName,
            timerSeconds = timerSeconds,
            isMuted = isMuted,
            isCameraOn = isCameraOn,
            isSpeakerOn = isSpeakerOn,
            callState = callState,
            isNetworkAvailable = isNetworkAvailable,
            isRecording = isRecording,
            inmateProfile = inmateProfile,
            contactProfile = contactProfile,
            localTrack = localTrack,
            remoteTrack = remoteTrack,
            eglContext = viewModel.eglContext,
            liveCost = liveCost,
            maxCallSeconds = maxCallSeconds,
            endLabel = endLabel,
            connectionQuality = connectionQuality,
            familyLocation = familyLocation,
            onMuteToggle = { viewModel.toggleMute() },
            onVideoToggle = { viewModel.toggleCamera() },
            onSpeakerToggle = { viewModel.toggleSpeaker() },
            onSwitchCamera = { viewModel.switchCamera() },
            onEndCall = {
                viewModel.endCall()
                onEndCall()
            }
        )
    }
}

@Composable
fun PermissionDeniedUI(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .fillMaxWidth(0.9f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = DangerRed,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Access to Camera and Microphone is required for secure video calling.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextGray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                KioskButton(
                    text = "Grant Permissions",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun VideoCallContent(
    contactName: String,
    timerSeconds: Int,
    isMuted: Boolean,
    isCameraOn: Boolean,
    isSpeakerOn: Boolean,
    callState: CallUIState,
    isNetworkAvailable: Boolean,
    isRecording: Boolean,
    inmateProfile: InmateProfile?,
    contactProfile: Contact?,
    localTrack: VideoTrack?,
    remoteTrack: VideoTrack?,
    eglContext: org.webrtc.EglBase.Context?,
    liveCost: Double,
    maxCallSeconds: Int,
    endLabel: String? = null,
    connectionQuality: String = "unknown",
    familyLocation: FamilyLocation? = null,
    onMuteToggle: () -> Unit,
    onVideoToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEndCall: () -> Unit
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val density = LocalDensity.current
    val remainingSeconds = (maxCallSeconds - timerSeconds).coerceAtLeast(0)

    val formatTime = { seconds: Int ->
        val mins = seconds / 60
        val secs = seconds % 60
        String.format("%02d:%02d", mins, secs)
    }

    val currentCost = liveCost

    LaunchedEffect(lastInteractionTime) {
        delay(6000)
        controlsVisible = false
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = true
                lastInteractionTime = System.currentTimeMillis()
            }
    ) {
        val isTablet = maxWidth >= 600.dp
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        val overlayWidth = if (isTablet) {
            minOf(maxWidth - 96.dp, 880.dp)
        } else {
            minOf(maxWidth - 40.dp, 500.dp)
        }.coerceAtLeast(240.dp)

        // 1. REMOTE FULLSCREEN VIDEO FEED
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D16)),
            contentAlignment = Alignment.Center
        ) {
            if (remoteTrack != null && callState == CallUIState.CONNECTED) {
                WebRtcSurfaceView(
                    videoTrack = remoteTrack,
                    eglContext = eglContext,
                    fit = true,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    KioskProgressIndicator()
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = when (callState) {
                            CallUIState.WAITING -> "Waiting for $contactName..."
                            CallUIState.RECONNECTING -> "Reconnecting..."
                            CallUIState.FAILED -> "Call Failed"
                            CallUIState.DISCONNECTED -> endLabel ?: "Call ended"
                            else -> "Connecting..."
                        },
                        color = Color.White,
                        fontSize = if (isTablet) 24.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 2. DRAGGABLE PIP LOCAL CAMERA PREVIEW
        val pipWidth = if (isTablet) 210.dp else 108.dp
        val pipHeight = if (isTablet) 270.dp else 148.dp
        val pipWidthPx = with(density) { pipWidth.toPx() }
        val pipHeightPx = with(density) { pipHeight.toPx() }
        val initialTopOffsetPx = with(density) { if (isTablet) 136.dp.toPx().toInt() else 112.dp.toPx().toInt() }
        var pipOffset by remember { mutableStateOf(IntOffset(0, initialTopOffsetPx)) }

        Surface(
            modifier = Modifier
                .zIndex(3f)
                .offset { pipOffset }
                .padding(end = if (isTablet) 20.dp else 12.dp)
                .size(pipWidth, pipHeight)
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(if (isTablet) 20.dp else 16.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(if (isTablet) 20.dp else 16.dp))
                .pointerInput(containerWidthPx, containerHeightPx, pipWidthPx, pipHeightPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val minX = -(containerWidthPx - pipWidthPx - 24f).coerceAtLeast(0f)
                        val maxY = (containerHeightPx - pipHeightPx - 120f).coerceAtLeast(0f)
                        val newX = (pipOffset.x + dragAmount.x).coerceIn(minX, 0f)
                        val newY = (pipOffset.y + dragAmount.y).coerceIn(0f, maxY)
                        pipOffset = IntOffset(newX.roundToInt(), newY.roundToInt())
                    }
                },
            color = Color(0xFF1E293B),
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (!isCameraOn) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(if (isTablet) 36.dp else 24.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Off",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = if (isTablet) 15.sp else 12.sp
                        )
                    }
                } else if (localTrack != null) {
                    WebRtcSurfaceView(
                        videoTrack = localTrack,
                        eglContext = eglContext,
                        mirror = true,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "Preview",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = if (isTablet) 15.sp else 12.sp
                    )
                }
            }
        }

        // 3. TOP HEADER BAR
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(2f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)
                        )
                    )
                    .padding(
                        horizontal = if (isTablet) 24.dp else 12.dp,
                        vertical = if (isTablet) 16.dp else 10.dp
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isRecording) {
                                Surface(
                                    color = DangerRed,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            horizontal = if (isTablet) 8.dp else 6.dp,
                                            vertical = if (isTablet) 4.dp else 2.dp
                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(if (isTablet) 8.dp else 6.dp)
                                                .background(Color.White, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "REC",
                                            color = Color.White,
                                            fontSize = if (isTablet) 14.sp else 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = VideoAccentGreen,
                                modifier = Modifier.size(if (isTablet) 18.dp else 14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "MONITORED",
                                color = Color.White,
                                fontSize = if (isTablet) 15.sp else 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Inmate: ${inmateProfile?.displayName ?: "Unknown"} | $contactName",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = if (isTablet) 15.sp else 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))
                    QualityChip(connectionQuality, isTablet = isTablet)
                }
            }
        }

        // ALWAYS-VISIBLE CALL DURATION
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (isTablet) 82.dp else 68.dp)
                .zIndex(2f),
            color = Color.Black.copy(alpha = 0.75f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = if (isTablet) 16.dp else 12.dp,
                    vertical = if (isTablet) 8.dp else 6.dp
                )
            ) {
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(if (isTablet) 9.dp else 7.dp)
                            .background(DangerRed, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = formatTime(timerSeconds),
                    color = Color.White,
                    fontSize = if (isTablet) 18.sp else 14.sp,
                    fontWeight = FontWeight.Bold
                )
                if (maxCallSeconds > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "/ ${formatTime(maxCallSeconds)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = if (isTablet) 15.sp else 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // FAMILY LOCATION PILL
        familyLocation?.let { loc ->
            com.dssmeet.kiosk.ui.components.FamilyLocationPill(
                location = loc,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (isTablet) 128.dp else 112.dp)
                    .zIndex(2f)
            )
        }

        // 4. NETWORK WARNING
        if (!isNetworkAvailable) {
            Surface(
                modifier = Modifier
                    .width(overlayWidth)
                    .align(Alignment.Center)
                    .zIndex(4f),
                color = WarningOrange,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(if (isTablet) 20.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (isTablet) 24.dp else 18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Connection lost. Reconnecting...",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isTablet) 17.sp else 13.sp
                    )
                }
            }
        }

        // 4b. POOR NETWORK QUALITY BANNER
        if (connectionQuality == "poor") {
            Surface(
                modifier = Modifier
                    .width(overlayWidth)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isTablet) 175.dp else 125.dp)
                    .zIndex(4f),
                color = DangerRed,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(if (isTablet) 18.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (isTablet) 22.dp else 18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Poor network quality — audio may break up",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isTablet) 16.sp else 13.sp
                    )
                }
            }
        }

        // 5. BOTTOM CONTROL BAR
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f))
                        )
                    )
                    .padding(
                        bottom = if (isTablet) 28.dp else 16.dp,
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (isTablet) 14.dp else 10.dp)
            ) {
                Surface(
                    color = VideoAccentGreen,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "Live Billing: ₹${String.format("%.2f", currentCost)}",
                        color = Color.White,
                        fontSize = if (isTablet) 16.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            horizontal = if (isTablet) 18.dp else 12.dp,
                            vertical = if (isTablet) 6.dp else 4.dp
                        )
                    )
                }

                Surface(
                    modifier = Modifier.wrapContentWidth(),
                    shape = RoundedCornerShape(if (isTablet) 32.dp else 24.dp),
                    color = DarkControlsBg
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (isTablet) 28.dp else 14.dp,
                            vertical = if (isTablet) 16.dp else 10.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(if (isTablet) 32.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VideoActionButton(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isMuted) "Unmute" else "Mute",
                            isActive = isMuted,
                            activeColor = DangerRed,
                            isTablet = isTablet,
                            onClick = {
                                onMuteToggle()
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        VideoActionButton(
                            icon = if (!isCameraOn) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            label = if (!isCameraOn) "Cam Off" else "Cam On",
                            isActive = !isCameraOn,
                            activeColor = DangerRed,
                            isTablet = isTablet,
                            onClick = {
                                onVideoToggle()
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        VideoActionButton(
                            icon = Icons.Default.CallEnd,
                            label = "End",
                            isActive = true,
                            activeColor = DangerRed,
                            isEndCall = true,
                            isTablet = isTablet,
                            onClick = onEndCall
                        )

                        VideoActionButton(
                            icon = if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            label = "Speaker",
                            isActive = isSpeakerOn,
                            activeColor = PrimaryBlue,
                            isTablet = isTablet,
                            onClick = {
                                onSpeakerToggle()
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        VideoActionButton(
                            icon = Icons.Default.Info,
                            label = "Info",
                            isActive = showInfoDialog,
                            activeColor = PrimaryBlue,
                            isTablet = isTablet,
                            onClick = {
                                showInfoDialog = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )
                    }
                }
            }
        }

        // 6. CALL INFO DIALOG
        if (showInfoDialog) {
            Dialog(onDismissRequest = { showInfoDialog = false }) {
                Card(
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 560.dp)
                        .fillMaxWidth(if (isTablet) 0.8f else 0.95f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (isTablet) 24.dp else 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Call Information",
                                fontSize = if (isTablet) 22.sp else 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            IconButton(
                                onClick = { showInfoDialog = false },
                                modifier = Modifier.size(if (isTablet) 36.dp else 28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextGray)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ControlBtnBg, RoundedCornerShape(14.dp))
                                .padding(if (isTablet) 16.dp else 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Inmate:", fontSize = if (isTablet) 15.sp else 12.sp, color = TextGray)
                                Text(
                                    text = inmateProfile?.displayName ?: "Inmate",
                                    fontSize = if (isTablet) 15.sp else 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Contact:", fontSize = if (isTablet) 15.sp else 12.sp, color = TextGray)
                                Text(
                                    text = contactName,
                                    fontSize = if (isTablet) 15.sp else 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Time Remaining:", fontSize = if (isTablet) 15.sp else 12.sp, color = TextGray)
                                Text(
                                    text = formatTime(remainingSeconds),
                                    fontSize = if (isTablet) 15.sp else 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DangerRed
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    isTablet: Boolean = false,
    isEndCall: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val buttonSize = when {
        isTablet && isEndCall -> 76.dp
        isTablet -> 66.dp
        isEndCall -> 54.dp
        else -> 46.dp
    }
    val iconSize = when {
        isTablet && isEndCall -> 34.dp
        isTablet -> 30.dp
        isEndCall -> 24.dp
        else -> 22.dp
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (isEndCall) DangerRed else if (isActive) activeColor else Color.White.copy(alpha = 0.15f),
            modifier = Modifier.size(buttonSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        Spacer(modifier = Modifier.height(if (isTablet) 8.dp else 4.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = if (isTablet) 15.sp else 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun QualityChip(quality: String, isTablet: Boolean = false) {
    val (label, tint) = when (quality) {
        "excellent" -> "Excellent" to VideoAccentGreen
        "good" -> "Stable" to VideoAccentGreen
        "fair" -> "Fair" to WarningOrange
        "poor" -> "Poor network" to DangerRed
        else -> "Measuring…" to TextGray
    }
    Surface(
        color = tint.copy(alpha = 0.22f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = if (isTablet) 12.dp else 8.dp,
                vertical = if (isTablet) 6.dp else 4.dp
            )
        ) {
            Icon(
                imageVector = Icons.Default.SignalCellularAlt,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(if (isTablet) 18.dp else 12.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = if (isTablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Preview(
    name = "Mobile - Portrait Video Call",
    device = "spec:width=360dp,height=800dp,dpi=440",
    showBackground = true
)
@Composable
fun VideoCallContentMobilePreview() {
    PrisonKioskTheme {
        VideoCallContent(
            contactName = "Suresh Kumar",
            timerSeconds = 145,
            isMuted = false,
            isCameraOn = true,
            isSpeakerOn = true,
            callState = CallUIState.CONNECTED,
            isNetworkAvailable = true,
            isRecording = true,
            liveCost = 7.50,
            maxCallSeconds = 300,
            inmateProfile = InmateProfile(
                inmateId = "INM123",
                firstName = "Rahul",
                lastName = "Kumar",
                prisonId = "PRIS1",
                facility = "Central Jail",
                status = InmateStatus.ACTIVE
            ),
            contactProfile = Contact(
                id = "CON1",
                name = "Suresh Kumar",
                phoneNumber = "+919876543210",
                relationship = "Father"
            ),
            localTrack = null,
            remoteTrack = null,
            eglContext = null,
            onMuteToggle = {},
            onVideoToggle = {},
            onSpeakerToggle = {},
            onSwitchCamera = {},
            onEndCall = {}
        )
    }
}

@Preview(
    name = "Tablet - Landscape Video Call",
    device = "spec:width=1280dp,height=800dp,dpi=240",
    showBackground = true
)
@Composable
fun VideoCallContentTabletPreview() {
    PrisonKioskTheme {
        VideoCallContent(
            contactName = "Suresh Kumar",
            timerSeconds = 210,
            isMuted = true,
            isCameraOn = true,
            isSpeakerOn = true,
            callState = CallUIState.CONNECTED,
            isNetworkAvailable = true,
            isRecording = true,
            liveCost = 10.00,
            maxCallSeconds = 300,
            inmateProfile = InmateProfile(
                inmateId = "INM123",
                firstName = "Rahul",
                lastName = "Kumar",
                prisonId = "PRIS1",
                facility = "Central Jail",
                status = InmateStatus.ACTIVE
            ),
            contactProfile = Contact(
                id = "CON1",
                name = "Suresh Kumar",
                phoneNumber = "+919876543210",
                relationship = "Father"
            ),
            localTrack = null,
            remoteTrack = null,
            eglContext = null,
            onMuteToggle = {},
            onVideoToggle = {},
            onSpeakerToggle = {},
            onSwitchCamera = {},
            onEndCall = {}
        )
    }
}