package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.PrimaryBlue
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy

import com.dssmeet.kiosk.ui.theme.AccentGreen
import com.dssmeet.kiosk.ui.theme.GraySurface
import com.dssmeet.kiosk.ui.theme.TextMuted

import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.GreenActiveBg
import com.dssmeet.kiosk.ui.theme.LightBg
import com.dssmeet.kiosk.ui.theme.MoneyRedBg

import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.hardware.FingerprintCaptureState
import com.dssmeet.kiosk.hardware.RfidReaderState
import com.dssmeet.kiosk.models.admin.BiometricRegistration
import com.dssmeet.kiosk.models.admin.MAX_FINGERPRINT_TEMPLATES
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.ui.auth.FingerprintFeedbackIcon
import com.dssmeet.kiosk.ui.auth.HiddenRfidTapInput
import com.dssmeet.kiosk.ui.auth.RfidKeypadEntry
import com.dssmeet.kiosk.ui.auth.RfidTapAnimation
import com.dssmeet.kiosk.ui.components.KioskLoadingState
import com.dssmeet.kiosk.ui.components.KioskTopBar
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

/** Live hardware status shown inside the capture dialog. */
internal sealed interface BiometricCaptureStatus {
    data object Waiting : BiometricCaptureStatus
    data object Detected : BiometricCaptureStatus
    data class Error(val message: String) : BiometricCaptureStatus
}

@Composable
fun BiometricRegistrationScreen(
    windowSizeClass: WindowSizeClass,
    prisonerId: String,
    prisonerName: String,
    onBackClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    viewModel: BiometricRegistrationViewModel = hiltViewModel()
) {
    val biometricsResult by viewModel.biometrics.collectAsState()
    val registerResult by viewModel.registerState.collectAsState()
    val deleteResult by viewModel.deleteState.collectAsState()
    val fingerprintLimitReached by viewModel.fingerprintLimitReached.collectAsState()
    val fingerprintState by viewModel.fingerprintState.collectAsState()
    val fingerDetected by viewModel.fingerDetected.collectAsState()
    val rfidState by viewModel.rfidState.collectAsState()
    var showFingerprintDialog by remember { mutableStateOf(false) }
    var showRfidDialog by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    val registerError = (registerResult as? NetworkResult.Failure)?.error?.message
    val fingerprintRecords = (biometricsResult as? NetworkResult.Success)?.data
        ?.filter { it.type == "fingerprint" && it.status == "registered" }
        .orEmpty()
    val fingerprintCount = fingerprintRecords.size
    val fingerprintLimitBlocked = fingerprintLimitReached || fingerprintCount >= MAX_FINGERPRINT_TEMPLATES

    // Hardware read → auto-register (dialog must be open for that type).
    LaunchedEffect(fingerprintState, showFingerprintDialog, fingerprintLimitBlocked) {
        val state = fingerprintState
        if (showFingerprintDialog && state is FingerprintCaptureState.Captured) {
            viewModel.acknowledgeCapture("fingerprint")
            if (fingerprintLimitBlocked) {
                showFingerprintDialog = false
                viewModel.stopCapture()
                snackbarMessage = "Maximum $MAX_FINGERPRINT_TEMPLATES fingerprints are allowed"
                return@LaunchedEffect
            }
            viewModel.registerFingerprint(prisonerId, state.template)
        }
    }
    LaunchedEffect(rfidState, showRfidDialog) {
        val state = rfidState
        if (showRfidDialog && state is RfidReaderState.CardRead) {
            viewModel.acknowledgeCapture("rfid")
            viewModel.registerRfid(prisonerId, state.token)
        }
    }

    // Screen left — make sure no hardware session keeps running.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopCapture() }
    }

    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            kotlinx.coroutines.delay(3000L)
            snackbarMessage = null
        }
    }

    LaunchedEffect(prisonerId) {
        viewModel.loadBiometrics(prisonerId)
    }

    LaunchedEffect(registerResult) {
        when (registerResult) {
            is NetworkResult.Success -> {
                snackbarMessage = "Biometric registered"
                showFingerprintDialog = false
                showRfidDialog = false
                viewModel.stopCapture()
                viewModel.resetRegisterState()
            }
            is NetworkResult.Failure -> {
                val failure = registerResult as NetworkResult.Failure
                snackbarMessage = if (failure.error.code == "FINGERPRINT_LIMIT") {
                    "Fingerprint limit reached ($MAX_FINGERPRINT_TEMPLATES). Remove one to add another."
                } else {
                    "Failed to register: ${failure.error.message.ifBlank { "Unknown error" }}"
                }
                showFingerprintDialog = false
                showRfidDialog = false
                viewModel.stopCapture()
                viewModel.resetRegisterState()
            }
            else -> {}
        }
    }
    LaunchedEffect(deleteResult) {
        when (deleteResult) {
            is NetworkResult.Success -> { snackbarMessage = "Biometric removed"; viewModel.resetDeleteState() }
            is NetworkResult.Failure -> {
                val failure = deleteResult as? NetworkResult.Failure
                snackbarMessage = failure?.error?.message ?: "Failed to remove"
                viewModel.resetDeleteState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            KioskTopBar(
                title = "Biometrics",
                showBackButton = true,
                onBackClick = onBackClick,
                trailingActions = {
                    com.dssmeet.kiosk.ui.components.KioskLogoutButton(onClick = onLogoutClick)
                }
            )
        },
        snackbarHost = { snackbarMessage?.let { Snackbar { Text(it) } } },
        bottomBar = {
            com.dssmeet.kiosk.ui.components.KioskBottomActionBar(onBackClick = onBackClick)
        },
        containerColor = LightBg
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            val compactHeight = maxHeight < 560.dp ||
                windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
            val wideScreen = maxWidth >= 600.dp ||
                windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
            val contentWidth = (if (wideScreen) minOf(maxWidth - 96.dp, 820.dp) else minOf(maxWidth - 32.dp, 520.dp))
                .coerceAtLeast(240.dp)
            val cardSpacing = if (compactHeight) 10.dp else 16.dp
            val cardPadding = if (compactHeight) 12.dp else 16.dp
            val avatarSize = (maxHeight * 0.1f).coerceIn(40.dp, 52.dp)
            val headerTitleSize = if (maxWidth < 420.dp) 16.sp else 18.sp
            val historyTitleSize = if (compactHeight) 14.sp else 16.sp
            val compactCards = compactHeight && maxWidth < 600.dp

            Column(
                modifier = Modifier
                    .width(contentWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = if (compactHeight) 10.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(cardSpacing)
            ) {
            // Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(cardPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = PrimaryNavy.copy(alpha = 0.1f),
                        modifier = Modifier.size(avatarSize)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = PrimaryNavy)
                        }
                    }
                    Spacer(modifier = Modifier.width(if (compactHeight) 8.dp else 12.dp))
                    Column {
                        Text(prisonerName, fontWeight = FontWeight.Bold, fontSize = headerTitleSize)
                        Text(prisonerId, fontSize = if (compactHeight) 12.sp else 14.sp, color = TextGray)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(if (compactCards) 12.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(if (compactCards) 12.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (fingerprintCount > 0) GreenActiveBg else GraySurface,
                        modifier = Modifier.size(if (compactCards) 36.dp else 44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = if (fingerprintCount > 0) AccentGreen else TextMuted,
                                modifier = Modifier.size(if (compactCards) 18.dp else 24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(if (compactCards) 8.dp else 12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fingerprints", fontWeight = FontWeight.Bold, fontSize = if (compactCards) 14.sp else 15.sp)
                        Text(
                            "$fingerprintCount of $MAX_FINGERPRINT_TEMPLATES registered",
                            fontSize = if (compactCards) 11.sp else 12.sp,
                            color = if (fingerprintCount > 0) AccentGreen else TextMuted
                        )
                        if (fingerprintCount == 0) {
                            Text(
                                "Place the finger on the scanner to register",
                                fontSize = if (compactCards) 11.sp else 12.sp,
                                color = TextGray
                            )
                        }
                    }
                    Button(
                        onClick = {
                            showFingerprintDialog = true
                            viewModel.startCapture("fingerprint")
                        },
                        enabled = !fingerprintLimitBlocked,
                        modifier = Modifier.height(if (compactCards) 38.dp else 48.dp)
                    ) {
                        Text(
                            when {
                                fingerprintCount == 0 && !fingerprintLimitBlocked -> "Register"
                                fingerprintLimitBlocked -> "Limit reached"
                                else -> "Add another"
                            },
                            fontSize = if (compactCards) 12.sp else 13.sp
                        )
                    }
                }
            }

            // RFID Registration
            BiometricCard(
                title = "RFID Card",
                icon = Icons.Default.CreditCard,
                isRegistered = biometricsResult is NetworkResult.Success &&
                        (biometricsResult as NetworkResult.Success).data.any { it.type == "rfid" && it.status == "registered" },
                hint = "Tap card on reader or enter number",
                compact = compactCards,
                onRegister = {
                    showRfidDialog = true
                    viewModel.startCapture("rfid")
                },
                onDelete = {
                    val bio = (biometricsResult as? NetworkResult.Success)?.data?.find { it.type == "rfid" }
                    if (bio != null) viewModel.deleteBiometric(bio.biometricId, prisonerId)
                }
            )

            // Registration history
            if (biometricsResult is NetworkResult.Success) {
                val bios = (biometricsResult as NetworkResult.Success).data
                if (bios.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(cardPadding)) {
                            Text("Registration History", fontWeight = FontWeight.Bold, fontSize = historyTitleSize)
                            Spacer(modifier = Modifier.height(12.dp))
                            bios.forEach { bio ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        when (bio.type) {
                                            "fingerprint" -> Icons.Default.Fingerprint
                                            else -> Icons.Default.CreditCard
                                        },
                                        contentDescription = null,
                                        tint = PrimaryNavy,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            bio.label ?: bio.type.ifBlank { "unknown" }.uppercase(),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(formatDate(bio.registeredAt), fontSize = 12.sp, color = TextGray)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (bio.status == "registered") GreenActiveBg else MoneyRedBg
                                    ) {
                                        Text(
                                            bio.status.ifBlank { "unknown" }.uppercase(),
                                            fontSize = 12.sp,
                                            color = if (bio.status == "registered") AccentGreen else DangerRed,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { viewModel.deleteBiometric(bio.biometricId, prisonerId) },
                                        enabled = deleteResult !is NetworkResult.Loading
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove biometric",
                                            tint = DangerRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (biometricsResult is NetworkResult.Loading) {
                KioskLoadingState(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            }
        }
    }

    // Dialogs — hardware capture with a small instruction card.
    // Fingerprints register from the scanner only (no number to type);
    // RFID keeps manual card-number entry as a fallback.
    if (showFingerprintDialog) {
        BiometricCaptureDialog(
            title = "Fingerprint",
            instruction = "Place the finger on the USB fingerprint scanner",
            hint = "Connect the scanner to the kiosk, then hold the finger still until it is detected.",
            showManualEntry = false,
            status = when (val s = fingerprintState) {
                is FingerprintCaptureState.Captured -> BiometricCaptureStatus.Detected
                is FingerprintCaptureState.Failed -> BiometricCaptureStatus.Error(s.message)
                else -> BiometricCaptureStatus.Waiting
            },
            registerError = registerError,
            fingerDetected = fingerDetected,
            onManualConfirm = { viewModel.registerFingerprint(prisonerId, it) },
            onDismiss = {
                showFingerprintDialog = false
                viewModel.stopCapture()
                viewModel.resetRegisterState()
            }
        )
    }
    if (showRfidDialog) {
        BiometricCaptureDialog(
            title = "RFID Card",
            instruction = "Tap the RFID card on the reader",
            hint = "Hold the card on the reader until its number is detected.",
            showManualEntry = true,
            status = when (val s = rfidState) {
                is RfidReaderState.CardRead -> BiometricCaptureStatus.Detected
                is RfidReaderState.Failed -> BiometricCaptureStatus.Error(s.message)
                else -> BiometricCaptureStatus.Waiting
            },
            registerError = registerError,
            onManualConfirm = { viewModel.registerRfid(prisonerId, it) },
            onCardTap = { viewModel.onRfidCardTapped(it) },
            animation = { RfidTapAnimation(modifier = Modifier.fillMaxSize()) },
            onDismiss = {
                showRfidDialog = false
                viewModel.stopCapture()
                viewModel.resetRegisterState()
            }
        )
    }
}

@Composable
fun BiometricCard(
    title: String,
    icon: ImageVector,
    isRegistered: Boolean,
    hint: String? = null,
    compact: Boolean = false,
    onRegister: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isRegistered) GreenActiveBg else GraySurface,
                modifier = Modifier.size(if (compact) 36.dp else 44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isRegistered) AccentGreen else TextMuted,
                        modifier = Modifier.size(if (compact) 18.dp else 24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = if (compact) 14.sp else 15.sp)
                Text(
                    if (isRegistered) "Registered" else "Not registered",
                    fontSize = if (compact) 11.sp else 12.sp,
                    color = if (isRegistered) AccentGreen else TextMuted
                )
                if (!isRegistered && hint != null) {
                    Text(hint, fontSize = if (compact) 11.sp else 12.sp, color = TextGray)
                }
            }
            if (isRegistered) {
                TextButton(onClick = onDelete) {
                    Text("Remove", color = DangerRed, fontSize = if (compact) 12.sp else 13.sp)
                }
            } else {
                Button(
                    onClick = onRegister,
                    modifier = Modifier.height(if (compact) 38.dp else 48.dp)
                ) {
                    Text("Register", fontSize = if (compact) 12.sp else 13.sp)
                }
            }
        }
    }
}

@Composable
internal fun BiometricCaptureDialog(
    title: String,
    instruction: String,
    hint: String,
    showManualEntry: Boolean,
    status: BiometricCaptureStatus,
    registerError: String?,
    onManualConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onCardTap: ((String) -> Unit)? = null,
    animation: (@Composable () -> Unit)? = null,
    fingerDetected: Boolean = false
) {
    var showKeypad by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }

    LaunchedEffect(registerError) {
        if (registerError != null) digits = ""
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val compactHeight = maxHeight < 560.dp
            val dialogWidth = (maxWidth - 32.dp).coerceIn(260.dp, 520.dp)
            val animationSize = (maxHeight * 0.24f).coerceIn(96.dp, 160.dp).coerceAtMost(dialogWidth - 48.dp)
            val titleSize = if (maxWidth < 420.dp) 20.sp else 24.sp
            val instructionSize = if (compactHeight) 13.sp else 15.sp
            val statusText = when (status) {
                BiometricCaptureStatus.Waiting ->
                    if (title == "Fingerprint" && fingerDetected) "Finger detected — reading…" else "Waiting for capture…"
                BiometricCaptureStatus.Detected -> "Detected — registering…"
                is BiometricCaptureStatus.Error -> status.message
            }
            val statusColor = when {
                status is BiometricCaptureStatus.Error -> DangerRed
                status is BiometricCaptureStatus.Detected || (title == "Fingerprint" && fingerDetected) -> Color(0xFF2E7D32)
                else -> PrimaryNavy
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 2.dp,
                modifier = Modifier.width(dialogWidth)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(if (compactHeight) 16.dp else 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (onCardTap != null) {
                        HiddenRfidTapInput(onCardDetected = onCardTap)
                    }

                    if (animation != null) {
                        Box(modifier = Modifier.size(animationSize), contentAlignment = Alignment.Center) {
                            animation()
                        }
                    } else {
                        FingerprintFeedbackIcon(
                            modifier = Modifier.size(animationSize),
                            isScanning = status !is BiometricCaptureStatus.Error &&
                                (fingerDetected || status is BiometricCaptureStatus.Detected),
                            isMismatch = status is BiometricCaptureStatus.Error,
                            isDeviceError = false
                        )
                    }

                    Spacer(modifier = Modifier.height(if (compactHeight) 8.dp else 12.dp))

                    Text(
                        text = "Register $title",
                        fontSize = titleSize,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDarkNavy,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = instruction,
                        fontSize = instructionSize,
                        color = TextGray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(if (compactHeight) 12.dp else 18.dp))

                    Surface(
                        color = LightBg,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when {
                                status is BiometricCaptureStatus.Error -> Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = DangerRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                status is BiometricCaptureStatus.Detected ||
                                    (title == "Fingerprint" && fingerDetected) -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                else -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = statusColor
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = statusText,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (compactHeight) 13.sp else 14.sp,
                                color = statusColor,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(if (compactHeight) 10.dp else 14.dp))

                    if (showManualEntry && showKeypad) {
                        RfidKeypadEntry(
                            digits = digits,
                            onDigitsChange = { new ->
                                digits = new
                                if (new.length == 10) onManualConfirm(new)
                            }
                        )
                    } else {
                        Text(hint, fontSize = if (compactHeight) 12.sp else 13.sp, color = TextGray, textAlign = TextAlign.Center)
                        if (showManualEntry) {
                            TextButton(onClick = { showKeypad = true }) {
                                Text(
                                    "Enter card number instead",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryNavy
                                )
                            }
                        }
                    }

                    if (registerError != null) {
                        Text(
                            registerError,
                            fontSize = if (compactHeight) 12.sp else 13.sp,
                            color = DangerRed,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(if (compactHeight) 6.dp else 10.dp))
                    TextButton(onClick = onDismiss) {
                        Text(if (showManualEntry) "Cancel" else "Close", color = PrimaryBlue)
                    }
                }
            }
        }
    }
}

private fun formatDate(iso: String?): String {
    if (iso == null) return "Unknown"
    return try {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        sdf.format(Date.from(java.time.Instant.parse(iso)))
    } catch (_: Exception) { iso }
}
