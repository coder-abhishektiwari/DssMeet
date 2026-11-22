package com.dssmeet.kiosk.ui.auth

import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.ControlBtnBg
import com.dssmeet.kiosk.ui.theme.DangerRed


import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.core.UiState
import com.dssmeet.kiosk.hardware.FingerprintCaptureState
import com.dssmeet.kiosk.hardware.RfidReaderState
import com.dssmeet.kiosk.ui.components.KioskBottomActionBar
import com.dssmeet.kiosk.ui.components.KioskPasswordField
import com.dssmeet.kiosk.ui.components.KioskTextField
import com.dssmeet.kiosk.ui.components.KioskTopBar
import com.dssmeet.kiosk.ui.theme.PrisonKioskTheme
import androidx.compose.ui.text.input.ImeAction

@Composable
private fun LoginSubmitArrow(
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (enabled) PrimaryNavy else Color.Gray)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Default.ArrowForward,
            contentDescription = "Continue",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun LoginScreen(
    windowSizeClass: WindowSizeClass,
    sessionMessage: String? = null,
    onLoginSuccess: () -> Unit,
    onAdminLoginSuccess: () -> Unit,
    onKioskNotVerified: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel()
) {
    val stage by viewModel.loginStage.collectAsState()
    val inmate by viewModel.identifiedInmate.collectAsState()
    val admin by viewModel.identifiedAdmin.collectAsState()
    val needsBootstrap by viewModel.needsBootstrap.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isNetworkAvailable by viewModel.isNetworkAvailable.collectAsState()
    val fingerprintCaptureState by viewModel.fingerprintCaptureState.collectAsState()
    val fingerprintMismatch by viewModel.fingerprintMismatch.collectAsState()
    val adminCreateFingerprints by viewModel.adminCreateFingerprints.collectAsState()
    val adminCreateFingerprintError by viewModel.adminCreateFingerprintError.collectAsState()
    val fingerDetected by viewModel.fingerDetected.collectAsState()
    val rfidState by viewModel.rfidState.collectAsState()
    val rfidManualMode by viewModel.rfidManualMode.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var lastErrorShown by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState) {
        val msg = (uiState as? UiState.Error)?.message
        if (msg != null && msg != lastErrorShown) {
            lastErrorShown = msg
            snackbarHostState.showSnackbar(msg)
        }
        if (uiState !is UiState.Error) lastErrorShown = null
    }

    // The session ended (expired / inactivity timeout) — say why the inmate
    // is back at the login screen instead of silently dropping them here.
    LaunchedEffect(sessionMessage) {
        val msg = sessionMessage ?: return@LaunchedEffect
        if (msg.isNotBlank()) snackbarHostState.showSnackbar(msg)
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is LoginViewModel.LoginNavigation.NavigateToDashboard -> onLoginSuccess()
                is LoginViewModel.LoginNavigation.NavigateToAdminDashboard -> onAdminLoginSuccess()
                is LoginViewModel.LoginNavigation.KioskNotVerified -> onKioskNotVerified()
            }
        }
    }

    if (!isNetworkAvailable) {
        NoInternetScreen(onRetry = {
            viewModel.resetToSelection()
        })
    } else {
        Scaffold(
            topBar = { KioskTopBar(title = "PRISON KIOSK LOGIN") },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (stage == LoginStage.FINGERPRINT_SCANNING || stage == LoginStage.RFID_SCANNING) {
                    KioskBottomActionBar(onBackClick = { viewModel.resetToSelection() })
                }
            },
            containerColor = ControlBtnBg
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    fadeIn() + slideInHorizontally { it } togetherWith fadeOut() + slideOutHorizontally { -it }
                },
                label = "LoginTransition"
            ) { targetStage ->
                when (targetStage) {
                    LoginStage.METHOD_SELECTION -> WelcomeSelectionLayout(
                        windowSizeClass = windowSizeClass,
                        onFingerprintClick = { viewModel.startFingerprintAuth() },
                        onRfidClick = { viewModel.startRfidAuth() },
                        onAdminClick = { viewModel.startAdminAuth() },
                        needsBootstrap = needsBootstrap,
                        onCreateLoginClick = { viewModel.startAdminCreate() }
                    )
                    LoginStage.FINGERPRINT_SCANNING -> FingerprintScanningLayout(
                        viewModel = viewModel,
                        captureState = fingerprintCaptureState,
                        fingerprintMismatch = fingerprintMismatch,
                        fingerDetected = fingerDetected
                    )
                    LoginStage.RFID_SCANNING -> RfidScanningLayout(
                        state = rfidState,
                        manualMode = rfidManualMode,
                        onCardTap = { viewModel.onRfidCardTapped(it) },
                        onManualModeChange = { viewModel.setRfidManualMode(it) },
                        onManualSubmit = { viewModel.onRfidScanned(it) }
                    )
                    LoginStage.PRISONER_ID_ENTRY -> PrisonerIdEntryLayout(
                        uiState = uiState,
                        onPrisonerIdSubmit = { viewModel.onPrisonerIdSubmit(it) },
                        onCancel = { viewModel.resetToSelection() }
                    )
                    LoginStage.PIN_ENTRY -> PinEntryLayout(
                        name = inmate?.let { it.displayName } ?: "Prisoner",
                        uiState = uiState,
                        onPinSubmit = { viewModel.onPinSubmit(it) },
                        onCancel = { viewModel.resetToSelection() }
                    )
                    LoginStage.ADMIN_USERNAME_ENTRY -> AdminUsernameEntryLayout(
                        windowSizeClass = windowSizeClass,
                        uiState = uiState,
                        fingerprintMismatch = fingerprintMismatch,
                        fingerDetected = fingerDetected,
                        captureState = fingerprintCaptureState,
                        onUsernameSubmit = { viewModel.onAdminUsernameSubmit(it) },
                        onCancel = { viewModel.resetToSelection() }
                    )
                    LoginStage.ADMIN_PIN_ENTRY -> AdminPinEntryLayout(
                        windowSizeClass = windowSizeClass,
                        adminName = admin?.name ?: "Admin",
                        uiState = uiState,
                        fingerprintMismatch = fingerprintMismatch,
                        fingerDetected = fingerDetected,
                        captureState = fingerprintCaptureState,
                        onPinSubmit = { viewModel.onAdminPasswordSubmit(it) },
                        onCancel = { viewModel.resetToSelection() }
                    )
                    LoginStage.ADMIN_CREATE_ENTRY -> AdminCreateLoginLayout(
                        windowSizeClass = windowSizeClass,
                        uiState = uiState,
                        fingerprintCount = adminCreateFingerprints.size,
                        fingerprintError = adminCreateFingerprintError,
                        onCreateSubmit = { n, u, e, p -> viewModel.onAdminCreateSubmit(n, u, e, p) },
                        onCancel = { viewModel.resetToSelection() }
                    )
                }
            }

            if (uiState is UiState.Loading && stage != LoginStage.METHOD_SELECTION) {
                PremiumLoadingOverlay(
                    message = when (stage) {
                        LoginStage.PRISONER_ID_ENTRY -> "Identifying Prisoner..."
                        LoginStage.ADMIN_USERNAME_ENTRY -> "Identifying Admin..."
                        LoginStage.ADMIN_CREATE_ENTRY -> "Creating Admin Account..."
                        LoginStage.PIN_ENTRY, LoginStage.ADMIN_PIN_ENTRY -> "Verifying PIN..."
                        LoginStage.FINGERPRINT_SCANNING, LoginStage.RFID_SCANNING -> "Authenticating..."
                        else -> "Please wait..."
                    }
                )
            }
        }
    }
}
}

@Composable
fun RfidScanningLayout(
    state: RfidReaderState,
    manualMode: Boolean,
    onCardTap: (String) -> Unit,
    onManualModeChange: (Boolean) -> Unit,
    onManualSubmit: (String) -> Unit
) {
    var showManualDialog by remember { mutableStateOf(false) }
    var manualDigits by remember { mutableStateOf("") }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val compactHeight = maxHeight < 560.dp
        val contentWidth = minOf(maxWidth - 48.dp, 520.dp).coerceAtLeast(240.dp)
        val animationSize = (maxHeight * 0.26f).coerceIn(110.dp, 180.dp)
        val titleSize = if (maxWidth < 420.dp) 22.sp else 26.sp

        Column(
            modifier = Modifier
                .width(contentWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (compactHeight) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HiddenRfidTapInput(
                enabled = !showManualDialog && !manualMode,
                onCardDetected = onCardTap
            )

            RfidTapAnimation(modifier = Modifier.size(animationSize))

            Spacer(modifier = Modifier.height(if (compactHeight) 6.dp else 12.dp))

            Text(
                text = "RFID Card Authentication",
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(if (compactHeight) 4.dp else 8.dp))

            Text(
                text = "Tap your RFID card on the reader",
                fontSize = if (compactHeight) 15.sp else 17.sp,
                color = TextGray,
                textAlign = TextAlign.Center
            )

            when (state) {
                is RfidReaderState.CardRead -> {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryNavy
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Card detected — verifying…",
                            fontWeight = FontWeight.Medium,
                            color = PrimaryDarkNavy
                        )
                    }
                }
                is RfidReaderState.Failed -> {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = state.message,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = DangerRed,
                        fontSize = 14.sp
                    )
                }
                else -> Unit
            }

            Spacer(modifier = Modifier.height(if (compactHeight) 14.dp else 24.dp))

            TextButton(onClick = {
                manualDigits = ""
                showManualDialog = true
                onManualModeChange(true)
            }) {
                Text("Enter card number instead", color = PrimaryNavy, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = {
                showManualDialog = false
                onManualModeChange(false)
            },
            title = { Text("Enter Card Number") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Enter the 10-digit RFID card number.",
                        fontSize = 14.sp,
                        color = TextGray,
                        textAlign = TextAlign.Center
                    )
                    RfidKeypadEntry(
                        digits = manualDigits,
                        onDigitsChange = { new ->
                            manualDigits = new
                            if (new.length == 10) {
                                onManualSubmit(new)
                                manualDigits = ""
                                showManualDialog = false
                            }
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showManualDialog = false
                    onManualModeChange(false)
                }) { Text("Cancel") }
            },
            confirmButton = {}
        )
    }
}

@Composable
fun AdminUsernameEntryLayout(
    windowSizeClass: WindowSizeClass,
    uiState: UiState<Unit>,
    fingerprintMismatch: Boolean,
    fingerDetected: Boolean,
    captureState: FingerprintCaptureState,
    onUsernameSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var username by remember { mutableStateOf("") }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val compactHeight = maxHeight < 560.dp ||
            windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        val wideScreen = maxWidth >= 600.dp ||
            windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
        val contentWidth = if (wideScreen) {
            minOf(maxWidth - 96.dp, 820.dp)
        } else {
            minOf(maxWidth - 48.dp, 520.dp)
        }.coerceAtLeast(240.dp)
        val iconSize = if (wideScreen) 96.dp else (maxHeight * 0.16f).coerceIn(52.dp, 80.dp)
        val titleSize = if (wideScreen) 34.sp else if (maxWidth < 420.dp) 24.sp else 28.sp
        val subtitleSize = if (wideScreen) 18.sp else if (compactHeight) 14.sp else 18.sp
        val fieldGap = if (wideScreen) 32.dp else if (compactHeight) 16.dp else 24.dp
        val buttonHeight = if (compactHeight) 48.dp else 56.dp
        val canSubmit = username.isNotBlank() && uiState !is UiState.Loading
        val submit = { if (canSubmit) onUsernameSubmit(username.trim()) }

        Column(
            modifier = Modifier
                .width(contentWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (compactHeight) 10.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = PrimaryNavy,
                modifier = Modifier.size(iconSize)
            )

            Spacer(modifier = Modifier.height(fieldGap))

            Text("Admin Login", fontSize = titleSize, fontWeight = FontWeight.Bold, color = PrimaryDarkNavy)
            Text(
                "Enter your username or Employee ID",
                fontSize = subtitleSize,
                color = TextGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.height(fieldGap))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KioskTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Username / Employee ID",
                    modifier = Modifier.weight(1f),
                    required = true,
                    placeholder = "e.g. empsa003",
                    leadingIcon = Icons.Default.Person,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )
                LoginSubmitArrow(enabled = canSubmit, onClick = submit)
            }

            Spacer(modifier = Modifier.height(fieldGap + 8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(buttonHeight),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Back to Login Methods",
                    fontSize = if (wideScreen) 16.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextGray
                )
            }

            Spacer(modifier = Modifier.height(fieldGap))
            AdminFingerprintIcon(
                isScanning = fingerDetected || captureState is FingerprintCaptureState.Captured || uiState is UiState.Loading,
                isMismatch = fingerprintMismatch,
                size = if (wideScreen) 64.dp else 52.dp
            )
        }
    }
}

@Composable
private fun AdminFingerprintIcon(
    isScanning: Boolean,
    isMismatch: Boolean,
    size: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        FingerprintFeedbackIcon(
            modifier = Modifier.size(size),
            isScanning = isScanning,
            isMismatch = isMismatch,
            isDeviceError = false
        )
    }
}

@Composable
fun FingerprintScanningLayout(
    viewModel: LoginViewModel,
    captureState: FingerprintCaptureState,
    fingerprintMismatch: Boolean,
    fingerDetected: Boolean
) {
    val device by viewModel.connectedScanner.collectAsState()
    val hasPermission by viewModel.usbPermissionGranted.collectAsState()
    // While identify is running the session has already been stopped (the USB
    // port is handed to SecuGenMatcher), so captureState alone reads as Idle.
    val uiState by viewModel.uiState.collectAsState()

    val isScanning = fingerDetected ||
        captureState is FingerprintCaptureState.Captured ||
        uiState is UiState.Loading
    val isDeviceError = device == null ||
        !hasPermission ||
        (captureState is FingerprintCaptureState.Failed && captureState.deviceFault)

    val statusText = when {
        fingerprintMismatch -> "Fingerprint not recognized. Try again."
        fingerDetected -> "Finger detected — reading…"
        isScanning -> "Reading fingerprint…"
        captureState is FingerprintCaptureState.Failed -> captureState.message
        device == null -> "Scanner not ready. Please call the admin."
        !hasPermission -> "USB access required."
        else -> "Place finger on scanner and hold"
    }
    val statusColor = when {
        fingerprintMismatch || isDeviceError -> DangerRed
        isScanning -> Color(0xFF2E7D32)
        else -> PrimaryDarkNavy
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val compactHeight = maxHeight < 560.dp
        val contentWidth = minOf(maxWidth - 48.dp, 520.dp).coerceAtLeast(240.dp)
        val iconSize = (maxHeight * 0.3f).coerceIn(120.dp, 190.dp)
        val titleSize = if (maxWidth < 420.dp) 22.sp else 28.sp

        Column(
            modifier = Modifier
                .width(contentWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (compactHeight) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Fingerprint Login",
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(if (compactHeight) 6.dp else 12.dp))

            FingerprintFeedbackIcon(
                modifier = Modifier.size(iconSize),
                isScanning = isScanning,
                isMismatch = fingerprintMismatch,
                isDeviceError = isDeviceError
            )

            Spacer(modifier = Modifier.height(if (compactHeight) 10.dp else 16.dp))

            Text(
                text = statusText,
                fontSize = if (compactHeight) 15.sp else 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = statusColor
            )

            if (isDeviceError) {
                Spacer(modifier = Modifier.height(20.dp))

                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color.LightGray, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Please call the admin.",
                            textAlign = TextAlign.Center,
                            color = TextGray,
                            fontSize = 13.sp
                        )

                        if (!hasPermission) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.requestUsbPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy, contentColor = Color.White)
                            ) {
                                Text("Allow USB Access", color = Color.White)
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
fun WelcomeSelectionLayout(
    windowSizeClass: WindowSizeClass,
    onFingerprintClick: () -> Unit,
    onRfidClick: () -> Unit,
    onAdminClick: () -> Unit,
    needsBootstrap: Boolean = false,
    onCreateLoginClick: () -> Unit = {}
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val compactHeight = maxHeight < 560.dp ||
            windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        val wideScreen = maxWidth >= 600.dp ||
            windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

        val contentWidth = when {
            compactHeight && wideScreen -> minOf(maxWidth - 80.dp, 900.dp)
            wideScreen -> minOf(maxWidth - 80.dp, 820.dp)
            else -> minOf(maxWidth - 32.dp, 460.dp)
        }.coerceAtLeast(240.dp)

        val cardHeight = when {
            !wideScreen && compactHeight && maxWidth < 480.dp -> 108.dp
            !wideScreen && compactHeight -> (maxHeight * 0.44f).coerceIn(118.dp, 150.dp)
            compactHeight && wideScreen -> (maxHeight * 0.26f).coerceIn(150.dp, 190.dp)
            wideScreen -> (maxHeight * 0.24f).coerceIn(180.dp, 230.dp)
            else -> (maxHeight * 0.22f).coerceIn(140.dp, 180.dp)
        }
        val compactCards = cardHeight < 124.dp
        val cardSpacing = if (wideScreen) 20.dp else 16.dp
        val titleSize = when {
            maxWidth < 420.dp -> 24.sp
            wideScreen -> 40.sp
            else -> 28.sp
        }
        val subtitleSize = when {
            wideScreen -> 18.sp
            compactHeight -> 14.sp
            else -> 15.sp
        }
        val subtitleGap = if (wideScreen) 16.dp else (maxHeight * 0.02f).coerceIn(8.dp, 12.dp)
        val methodsGap = if (wideScreen) 48.dp else (maxHeight * 0.06f).coerceIn(24.dp, 40.dp)
        val adminGap = if (wideScreen) 36.dp else (maxHeight * 0.04f).coerceIn(16.dp, 32.dp)

        Column(
            modifier = Modifier
                .width(contentWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (compactHeight) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Login Methods",
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Choose a method to login",
                fontSize = subtitleSize,
                color = TextGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = subtitleGap)
            )

            Spacer(modifier = Modifier.height(methodsGap))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(cardSpacing)
            ) {
                PremiumAuthCard(
                    title = "Fingerprint",
                    icon = Icons.Default.Fingerprint,
                    description = "Finger Scanning",
                    onClick = onFingerprintClick,
                    modifier = Modifier.weight(1f).height(cardHeight),
                    compact = compactCards,
                    large = wideScreen
                )
                PremiumAuthCard(
                    title = "RFID Card",
                    icon = Icons.Default.CreditCard,
                    description = "Tap Card",
                    onClick = onRfidClick,
                    modifier = Modifier.weight(1f).height(cardHeight),
                    compact = compactCards,
                    large = wideScreen
                )
                // Prisoner ID login disabled - kiosk only allows fingerprint + RFID.
                // PremiumAuthCard(
                //     title = "Prisoner ID",
                //     icon = Icons.Default.Badge,
                //     description = "Enter ID",
                //     onClick = onPrisonerIdClick,
                //     modifier = Modifier.weight(1f)
                // )
            }

            Spacer(modifier = Modifier.height(adminGap))

            // Jail has no kiosk admin yet → this button becomes the bootstrap entry
            // ("Create Admin"); the moment one exists, it flips back to Admin Login.
            if (needsBootstrap) {
                Text(
                    text = "No admin account exists for this jail yet",
                    fontSize = if (wideScreen) 15.sp else 13.sp,
                    color = TextGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            TextButton(
                onClick = if (needsBootstrap) onCreateLoginClick else onAdminClick,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (needsBootstrap) PrimaryNavy else TextGray
                )
            ) {
                Icon(
                    Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    modifier = Modifier.size(if (wideScreen) 24.dp else if (compactHeight) 18.dp else 20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (needsBootstrap) "Create Admin" else "Admin Login",
                    fontSize = if (wideScreen) 18.sp else if (compactHeight) 14.sp else 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun PinEntryLayout(
    name: String,
    uiState: UiState<Unit>,
    onPinSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    com.dssmeet.kiosk.ui.components.KioskResponsiveBox(maxWidth = 560.dp) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Spacer(modifier = Modifier.height(16.dp))

        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = PrimaryNavy,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Welcome,", fontSize = 22.sp, color = TextGray)
        Text(name, fontSize = 32.sp, fontWeight = FontWeight.Black, color = PrimaryDarkNavy)
        Text("Enter your 6-digit PIN to confirm", fontSize = 18.sp, color = TextGray, modifier = Modifier.padding(top = 6.dp))

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = onCancel) {
            Text("<- Back to Login Methods", color = TextGray, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, PrimaryNavy, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(6) { index ->
                    val filled = pin.length > index
                    Box(
                        modifier = Modifier.size(18.dp).clip(CircleShape)
                            .background(if (filled) PrimaryDarkNavy else Color.LightGray.copy(alpha = 0.5f))
                            .border(1.dp, if (filled) PrimaryDarkNavy else Color.Gray, CircleShape)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        IPhoneKeypad(
            onNumberClick = { if (pin.length < 6) { pin += it; if (pin.length == 6) onPinSubmit(pin) } },
            onDeleteClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
        )
        }
    }
}

@Composable
fun AdminPinEntryLayout(
    windowSizeClass: WindowSizeClass,
    adminName: String,
    uiState: UiState<Unit>,
    fingerprintMismatch: Boolean,
    fingerDetected: Boolean,
    captureState: FingerprintCaptureState,
    onPinSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    val passwordFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        passwordFocusRequester.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val compactHeight = maxHeight < 560.dp ||
            windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        val wideScreen = maxWidth >= 600.dp ||
            windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
        val contentWidth = if (wideScreen) {
            minOf(maxWidth - 96.dp, 820.dp)
        } else {
            minOf(maxWidth - 48.dp, 520.dp)
        }.coerceAtLeast(240.dp)
        val iconSize = if (wideScreen) 96.dp else (maxHeight * 0.16f).coerceIn(52.dp, 80.dp)
        val titleSize = if (wideScreen) 34.sp else if (maxWidth < 420.dp) 24.sp else 28.sp
        val subtitleSize = if (wideScreen) 20.sp else if (compactHeight) 14.sp else 18.sp
        val bodySize = if (wideScreen) 17.sp else if (compactHeight) 13.sp else 15.sp
        val fieldGap = if (wideScreen) 32.dp else if (compactHeight) 16.dp else 24.dp
        val buttonHeight = if (compactHeight) 48.dp else 56.dp
        val canSubmit = password.isNotBlank() && uiState !is UiState.Loading
        val submit = { if (canSubmit) onPinSubmit(password) }

        Column(
            modifier = Modifier
                .width(contentWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (compactHeight) 10.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = PrimaryNavy,
                modifier = Modifier.size(iconSize)
            )

            Spacer(modifier = Modifier.height(fieldGap))

            Text("Admin Access", fontSize = titleSize, fontWeight = FontWeight.Bold, color = PrimaryDarkNavy)
            Text(
                "Welcome, $adminName",
                fontSize = subtitleSize,
                color = TextGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                "Enter your password to continue",
                fontSize = bodySize,
                color = TextGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = if (compactHeight) 8.dp else 12.dp)
            )

            Spacer(modifier = Modifier.height(fieldGap))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KioskPasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(passwordFocusRequester),
                    required = true,
                    errorText = if (uiState is UiState.Error) "Incorrect password" else null,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )
                LoginSubmitArrow(enabled = canSubmit, onClick = submit)
            }

            Spacer(modifier = Modifier.height(fieldGap + 8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(buttonHeight),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Back to Login Methods",
                    fontSize = if (wideScreen) 16.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextGray
                )
            }

            Spacer(modifier = Modifier.height(fieldGap))
            AdminFingerprintIcon(
                isScanning = fingerDetected || captureState is FingerprintCaptureState.Captured || uiState is UiState.Loading,
                isMismatch = fingerprintMismatch,
                size = if (wideScreen) 64.dp else 52.dp
            )
        }
    }
}

/** Same rule as the backend EMPLOYEE_ID_RE and the warden dashboard's username field. */
private val EmployeeIdRegex = Regex("^[A-Za-z0-9._-]{3,40}$")

/** Same rule as the warden dashboard's optional email field. */
private val AdminEmailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/**
 * First-admin bootstrap form — the four fields (and wording) of the warden
 * dashboard's "Add Kiosk Admin" modal: Full name, Username (employee ID),
 * Email (optional), Password. Creates this jail's first kiosk admin and
 * signs the creator straight in.
 */
@Composable
fun AdminCreateLoginLayout(
    windowSizeClass: WindowSizeClass,
    uiState: UiState<Unit>,
    fingerprintCount: Int,
    fingerprintError: String?,
    onCreateSubmit: (name: String, employeeId: String, email: String, password: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    val nameError = if (submitted && name.isBlank()) "Name is required" else null
    val employeeIdError = when {
        submitted && employeeId.isBlank() -> "Username is required"
        submitted && !EmployeeIdRegex.matches(employeeId.trim()) ->
            "3-40 characters: letters, digits, dot, dash or underscore"
        else -> null
    }
    val emailError =
        if (email.isNotBlank() && !AdminEmailRegex.matches(email.trim())) "Enter a valid email" else null
    val passwordError = when {
        submitted && password.isEmpty() -> "Password is required"
        password.isNotEmpty() && password.length < 6 -> "At least 6 characters"
        else -> null
    }
    val isValid = name.isNotBlank() &&
        EmployeeIdRegex.matches(employeeId.trim()) &&
        password.length >= 6 &&
        (email.isBlank() || AdminEmailRegex.matches(email.trim()))

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val compactHeight = maxHeight < 560.dp ||
            windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        val contentWidth = minOf(maxWidth - 48.dp, 600.dp).coerceAtLeast(240.dp)
        val iconSize = (maxHeight * 0.12f).coerceIn(44.dp, 64.dp)
        val titleSize = if (maxWidth < 420.dp) 24.sp else 28.sp
        val subtitleSize = if (compactHeight) 13.sp else 15.sp
        val fieldGap = if (compactHeight) 10.dp else 14.dp
        val buttonHeight = if (compactHeight) 48.dp else 56.dp

        Column(
            modifier = Modifier
                .width(contentWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (compactHeight) 10.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = PrimaryNavy,
                modifier = Modifier.size(iconSize)
            )

            Spacer(modifier = Modifier.height(if (compactHeight) 8.dp else 12.dp))

            Text("Create Admin Login", fontSize = titleSize, fontWeight = FontWeight.Bold, color = PrimaryDarkNavy)
            Text(
                "Set up the first admin account for this jail",
                fontSize = subtitleSize,
                color = TextGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.height(if (compactHeight) 16.dp else 24.dp))

            KioskTextField(
                value = name,
                onValueChange = { name = it },
                label = "Full name",
                required = true,
                placeholder = "e.g. Neha Gupta",
                errorText = nameError,
                leadingIcon = Icons.Default.Person,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(fieldGap))

            KioskTextField(
                value = employeeId,
                onValueChange = { employeeId = it },
                label = "Username (employee ID)",
                required = true,
                placeholder = "e.g. empsa003",
                helperText = "What the operator types on the kiosk to sign in.",
                errorText = employeeIdError,
                leadingIcon = Icons.Default.Badge,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(fieldGap))

            KioskTextField(
                value = email,
                onValueChange = { email = it },
                label = "Email (optional)",
                placeholder = "name@dssmeet.io",
                errorText = emailError,
                leadingIcon = Icons.Default.Email,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(fieldGap))

            KioskPasswordField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                required = true,
                placeholder = "At least 6 characters",
                errorText = passwordError,
                imeAction = ImeAction.Done
            )

            Spacer(modifier = Modifier.height(fieldGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                AdminFingerprintIcon(
                    isScanning = fingerprintCount < 3 && uiState !is UiState.Loading,
                    isMismatch = fingerprintError != null,
                    size = 40.dp
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Fingerprint (optional) — $fingerprintCount/3",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDarkNavy
                    )
                    fingerprintError?.let {
                        Text(it, fontSize = 11.sp, color = DangerRed)
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (compactHeight) 16.dp else 24.dp))

            Button(
                onClick = {
                    submitted = true
                    if (isValid) onCreateSubmit(name, employeeId, email, password)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(buttonHeight),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy, contentColor = Color.White),
                enabled = uiState !is UiState.Loading
            ) {
                Text("Create & Sign In", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Default.ArrowForward, contentDescription = null, tint = Color.White)
            }

            Spacer(modifier = Modifier.height(if (compactHeight) 10.dp else 16.dp))
            TextButton(onClick = onCancel) {
                Text("<- Back to Login Methods", color = TextGray, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PrisonerIdEntryLayout(
    uiState: UiState<Unit>,
    onPrisonerIdSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var prisonerId by remember { mutableStateOf("") }

    com.dssmeet.kiosk.ui.components.KioskResponsiveBox(maxWidth = 560.dp) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Spacer(modifier = Modifier.height(16.dp))

        Icon(
            imageVector = Icons.Default.Badge,
            contentDescription = null,
            tint = PrimaryNavy,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Prisoner Login", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = PrimaryDarkNavy)
        Text("Enter your Prisoner ID", fontSize = 18.sp, color = TextGray, modifier = Modifier.padding(top = 6.dp))

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = onCancel) {
            Text("<- Back to Login Methods", color = TextGray, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, PrimaryNavy, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(6) { index ->
                    val filled = prisonerId.length > index
                    Box(
                        modifier = Modifier.size(18.dp).clip(CircleShape)
                            .background(if (filled) PrimaryDarkNavy else Color.LightGray.copy(alpha = 0.5f))
                            .border(1.dp, if (filled) PrimaryDarkNavy else Color.Gray, CircleShape)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        IPhoneKeypad(
            onNumberClick = {
                if (prisonerId.length < 6) {
                    val newId = prisonerId + it
                    prisonerId = newId
                    if (newId.length == 6) {
                        onPrisonerIdSubmit(newId)
                    }
                }
            },
            onDeleteClick = {
                if (prisonerId.isNotEmpty()) {
                    prisonerId = prisonerId.dropLast(1)
                }
            }
        )
        }
    }
}

@Composable
fun NoInternetScreen(onRetry: () -> Unit = {}) {
    // Gentle pulse animation for the icon rings — signals "we're on it".
    val infiniteTransition = rememberInfiniteTransition(label = "netPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "netPulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "netPulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1628), Color(0xFF10233F), Color(0xFF0A1628))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Pulsing rings behind the wifi-off badge
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(pulseScale)
                        .alpha(pulseAlpha)
                        .background(Color(0xFF3B82F6), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(118.dp)
                        .background(Color(0xFF132A4A), CircleShape)
                )
                Icon(
                    imageVector = Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = Color(0xFF7EB3FF),
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Connection Lost",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "The kiosk lost its internet connection.\nTrying to reconnect automatically…",
                fontSize = 16.sp,
                color = Color(0xFF9DB2CC),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            OutlinedButton(
                onClick = onRetry,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, Color(0xFF3B82F6)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF7EB3FF))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Again", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Preview(name = "Mobile View", device = "spec:width=360dp,height=800dp", showBackground = true)
@Composable
fun PreviewLoginMobile() {
    PrisonKioskTheme {
        Surface(color = ControlBtnBg, modifier = Modifier.fillMaxSize()) {
            PinEntryLayout(name = "RAHUL KUMAR", uiState = UiState.Idle, onPinSubmit = {}, onCancel = {})
        }
    }
}

