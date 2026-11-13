package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.BorderColor
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.SuccessGreen

import com.dssmeet.kiosk.ui.theme.InfoBg

import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.LightBg
import com.dssmeet.kiosk.ui.theme.MoneyRedBg

import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.hardware.FingerprintCaptureState
import com.dssmeet.kiosk.hardware.RfidReaderState
import com.dssmeet.kiosk.models.admin.FamilyContactPayload
import com.dssmeet.kiosk.models.admin.KioskDevice
import com.dssmeet.kiosk.models.admin.PrisonerFormValues
import com.dssmeet.kiosk.ui.auth.HiddenRfidTapInput
import com.dssmeet.kiosk.ui.auth.RfidKeypadEntry
import com.dssmeet.kiosk.ui.auth.RfidTapAnimation
import com.dssmeet.kiosk.ui.components.KioskPasswordField
import com.dssmeet.kiosk.ui.components.KioskPhoneField
import com.dssmeet.kiosk.ui.components.KioskSelectField
import com.dssmeet.kiosk.ui.components.KioskTextField
import com.dssmeet.kiosk.ui.components.KioskTopBar
import com.dssmeet.kiosk.ui.theme.PrisonKioskTheme

private val ADD_STEP_TITLES = listOf("Personal", "Identity", "Address", "Family", "Security")

@Composable
fun AddPrisonerScreen(
    windowSizeClass: WindowSizeClass,
    onBackClick: () -> Unit,
    onComplete: () -> Unit,
    viewModel: AddPrisonerViewModel = hiltViewModel()
) {
    val registrationState by viewModel.registrationState.collectAsState()
    val fingerprintState by viewModel.fingerprintState.collectAsState()
    val rfidState by viewModel.rfidState.collectAsState()
    val kioskOptions by viewModel.kioskOptions.collectAsState()
    val currentKioskId by viewModel.currentKioskId.collectAsState()

    // Screen left mid-capture — no hardware session may keep running.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopCapture() }
    }

    AddPrisonerContent(
        windowWidthSizeClass = windowSizeClass.widthSizeClass,
        onBackClick = onBackClick,
        onComplete = onComplete,
        onRegister = { values, pin, finger, rfid, family ->
            viewModel.registerPrisoner(values, pin, finger, rfid, family)
        },
        registrationState = registrationState,
        fingerprintState = fingerprintState,
        rfidState = rfidState,
        kioskOptions = kioskOptions,
        currentKioskId = currentKioskId,
        onStartCapture = viewModel::startCapture,
        onStopCapture = viewModel::stopCapture,
        onAcknowledgeCapture = viewModel::acknowledgeCapture,
        onRfidCardTapped = viewModel::onRfidCardTapped
    )
}

@Composable
fun AddPrisonerContent(
    windowWidthSizeClass: WindowWidthSizeClass,
    onBackClick: () -> Unit,
    onComplete: () -> Unit,
    onRegister: (PrisonerFormValues, String, String?, String?, List<FamilyContactPayload>?) -> Unit,
    registrationState: AddPrisonerViewModel.RegistrationState = AddPrisonerViewModel.RegistrationState.Idle,
    fingerprintState: FingerprintCaptureState = FingerprintCaptureState.Idle,
    rfidState: RfidReaderState = RfidReaderState.Idle,
    kioskOptions: List<KioskDevice> = emptyList(),
    currentKioskId: String = "",
    onStartCapture: (String) -> Unit = {},
    onStopCapture: () -> Unit = {},
    onAcknowledgeCapture: (String) -> Unit = {},
    onRfidCardTapped: (String) -> Unit = {}
) {
    val formState = remember { PrisonerFormState(null) }
    var step by remember { mutableStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var fingerprintTemplate by remember { mutableStateOf("") }
    var rfidTag by remember { mutableStateOf("") }
    var familyMembers by remember { mutableStateOf<List<FamilyContactPayload>>(emptyList()) }
    var familyDraftName by remember { mutableStateOf("") }
    var familyDraftRelation by remember { mutableStateOf("") }
    var familyDraftMobile by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }
    var showFingerprintDialog by remember { mutableStateOf(false) }
    var showRfidDialog by remember { mutableStateOf(false) }

    // Hardware read while its dialog is open → keep the capture locally.
    // The actual template/tag is sent with the prisoner at Register time.
    LaunchedEffect(fingerprintState, showFingerprintDialog) {
        val state = fingerprintState
        if (showFingerprintDialog && state is FingerprintCaptureState.Captured) {
            onAcknowledgeCapture("fingerprint")
            fingerprintTemplate = state.template
            showFingerprintDialog = false
            onStopCapture()
        }
    }
    LaunchedEffect(rfidState, showRfidDialog) {
        val state = rfidState
        if (showRfidDialog && state is RfidReaderState.CardRead) {
            onAcknowledgeCapture("rfid")
            rfidTag = state.token
            showRfidDialog = false
            onStopCapture()
        }
    }

    var completed by remember { mutableStateOf(false) }
    LaunchedEffect(registrationState) {
        if (registrationState is AddPrisonerViewModel.RegistrationState.Success) {
            completed = true
        }
    }

    // Default the assignment to the kiosk this device IS.
    LaunchedEffect(currentKioskId) {
        formState.applyDefaultKiosk(currentKioskId)
    }

    val loading = registrationState is AddPrisonerViewModel.RegistrationState.Loading

    Scaffold(
        topBar = {
            KioskTopBar(
                title = "Add New Inmate",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        containerColor = LightBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (completed) {
                CompleteStep(
                    familyAdded = (registrationState as? AddPrisonerViewModel.RegistrationState.Success)
                        ?.familyContactIds?.isNotEmpty() == true,
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                )

                Button(
                    onClick = onComplete,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Go to Dashboard", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                PrisonerStepHeader(titles = ADD_STEP_TITLES, currentStep = step)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (step) {
    0 -> PersonalDetailsCard(formState)
    1 -> IdentityAdmissionCard(formState, kioskOptions)
    2 -> AddressCard(formState)
    3 -> FamilyContactStep(
        members = familyMembers,
        draftName = familyDraftName,
        draftRelationship = familyDraftRelation,
        draftMobile = familyDraftMobile,
        onDraftNameChange = { familyDraftName = it; formError = "" },
        onDraftRelationshipChange = { familyDraftRelation = it },
        onDraftMobileChange = { mobile ->
            // Keep only the national digits; KioskPhoneField re-prefixes +91.
            familyDraftMobile = mobile.filter { it.isDigit() }.takeLast(10)
            formError = ""
        },
        onAddMember = {
            familyMembers = familyMembers + FamilyContactPayload(
                name = familyDraftName.trim(),
                mobileNumber = familyDraftMobile.trim(),
                relationship = familyDraftRelation.trim().ifBlank { "Family" }
            )
            familyDraftName = ""
            familyDraftRelation = ""
            familyDraftMobile = ""
        },
        onRemoveMember = { index -> familyMembers = familyMembers.filterIndexed { i, _ -> i != index } }
    )
                        else -> BiometricDataStep(
                            pin = pin,
                            confirmPin = confirmPin,
                            fingerprintTemplate = fingerprintTemplate,
                            rfidTag = rfidTag,
                            onPinChange = { pin = it; formError = "" },
                            onConfirmPinChange = { confirmPin = it; formError = "" },
                            onRegisterFingerprint = {
                                showFingerprintDialog = true
                                onStartCapture("fingerprint")
                            },
                            onClearFingerprint = { fingerprintTemplate = "" },
                            onRegisterRfid = {
                                showRfidDialog = true
                                onStartCapture("rfid")
                            },
                            onClearRfid = { rfidTag = "" }
                        )
                    }

                    val effectiveError = when {
                        registrationState is AddPrisonerViewModel.RegistrationState.Error ->
                            (registrationState as AddPrisonerViewModel.RegistrationState.Error).message
                        formError.isNotEmpty() -> formError
                        else -> ""
                    }
                    if (effectiveError.isNotEmpty()) {
                        ErrorCard(effectiveError)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (step > 0) {
                            OutlinedButton(
                                onClick = {
                                    if (!loading) {
                                        step -= 1
                                        formError = ""
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !loading
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Back")
                            }
                        }

                        Button(
                            onClick = {
                                if (step == ADD_STEP_TITLES.lastIndex) {
                                    val personalError = formState.validatePersonal()
                                    val identityError = formState.validateIdentity()
                                    val addressError = formState.validateAddress()
                                    formError = when {
                                        personalError.isNotEmpty() -> personalError
                                        identityError.isNotEmpty() -> identityError
                                        addressError.isNotEmpty() -> addressError
                                        pin.length != 6 -> "PIN must be exactly 6 digits"
                                        pin != confirmPin -> "PINs do not match"
                                        fingerprintTemplate.isBlank() && rfidTag.isBlank() ->
                                            "Register at least one biometric — fingerprint or RFID"
                                        else -> ""
                                    }
                                    if (formError.isEmpty()) {
                                        onRegister(
                                            formState.toValues(),
                                            pin,
                                            fingerprintTemplate.ifBlank { null },
                                            rfidTag.ifBlank { null },
                                            familyMembers.ifEmpty { null }
                                        )
                                    }
                                } else {
                                    val stepError = formState.validateStep(step)
                                    if (stepError.isNotEmpty()) {
                                        formError = stepError
                                    } else {
                                        formError = ""
                                        step += 1
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(if (step > 0) 1f else 2f)
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                            enabled = !loading
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else if (step == ADD_STEP_TITLES.lastIndex) {
                                Text(
                                    text = "Register Inmate",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = "Next",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.Default.ArrowForward, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }

    // Capture dialogs — same hardware flow as the Biometrics screen.
    if (showFingerprintDialog) {
        BiometricCaptureDialog(
            title = "Fingerprint",
            icon = Icons.Default.Fingerprint,
            instruction = "Place the finger on the USB fingerprint scanner",
            hint = "Connect the scanner to the kiosk, then hold the finger still until it is detected.",
            showManualEntry = false,
            status = when (val s = fingerprintState) {
                is FingerprintCaptureState.Captured -> BiometricCaptureStatus.Detected
                is FingerprintCaptureState.Failed -> BiometricCaptureStatus.Error(s.message)
                else -> BiometricCaptureStatus.Waiting
            },
            registerError = null,
            onManualConfirm = {},
            onDismiss = {
                showFingerprintDialog = false
                onStopCapture()
            }
        )
    }
    if (showRfidDialog) {
        BiometricCaptureDialog(
            title = "RFID Card",
            icon = Icons.Default.CreditCard,
            instruction = "Tap the RFID card on the reader",
            hint = "Hold the card on the reader until its number is detected.",
            showManualEntry = true,
            status = when (val s = rfidState) {
                is RfidReaderState.CardRead -> BiometricCaptureStatus.Detected
                is RfidReaderState.Failed -> BiometricCaptureStatus.Error(s.message)
                else -> BiometricCaptureStatus.Waiting
            },
            registerError = null,
            onManualConfirm = {
                rfidTag = it
                showRfidDialog = false
                onStopCapture()
            },
            onCardTap = onRfidCardTapped,
            animation = { RfidTapAnimation(modifier = Modifier.size(130.dp)) },
            onDismiss = {
                showRfidDialog = false
                onStopCapture()
            }
        )
    }
}

@Composable
internal fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MoneyRedBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = DangerRed,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                color = DangerRed,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun BiometricDataStep(
    pin: String,
    confirmPin: String,
    fingerprintTemplate: String,
    rfidTag: String,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onRegisterFingerprint: () -> Unit,
    onClearFingerprint: () -> Unit,
    onRegisterRfid: () -> Unit,
    onClearRfid: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Security Setup",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )

            Text(
                text = "Set up a 6-digit PIN for the inmate. This PIN will be required for authentication.",
                fontSize = 14.sp,
                color = TextGray
            )

            KioskPasswordField(
                value = pin,
                onValueChange = {
                    if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                        onPinChange(it)
                    }
                },
                label = "6-Digit PIN",
                required = true,
                helperText = "Exactly 6 digits",
                errorText = when {
                    pin.isNotEmpty() && confirmPin.isNotEmpty() && pin != confirmPin ->
                        "PINs do not match"
                    pin.isNotEmpty() && pin.length != 6 -> "PIN must be exactly 6 digits"
                    else -> null
                },
                imeAction = androidx.compose.ui.text.input.ImeAction.Next
            )

            KioskPasswordField(
                value = confirmPin,
                onValueChange = {
                    if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                        onConfirmPinChange(it)
                    }
                },
                label = "Confirm PIN",
                required = true,
                helperText = "Re-enter the same 6 digits",
                errorText = if (pin.isNotEmpty() && confirmPin.isNotEmpty() && pin != confirmPin)
                    "PINs do not match" else null,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Biometric Data (Required — at least one)",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )

            BiometricCard(
                title = "Fingerprint",
                icon = Icons.Default.Fingerprint,
                isRegistered = fingerprintTemplate.isNotBlank(),
                hint = "Place the finger on the scanner — no typing needed",
                onRegister = onRegisterFingerprint,
                onDelete = onClearFingerprint
            )

            BiometricCard(
                title = "RFID Card",
                icon = Icons.Default.CreditCard,
                isRegistered = rfidTag.isNotBlank(),
                hint = "Tap the card on the reader",
                onRegister = onRegisterRfid,
                onDelete = onClearRfid
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = InfoBg
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = PrimaryNavy,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Biometric Registration",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = PrimaryNavy
                        )
                        Text(
                            text = "Capture the fingerprint OR tap the RFID card — at least one is required to register the inmate. The other one can be added later from the Biometrics screen.",
                            fontSize = 12.sp,
                            color = PrimaryNavy
                        )
                    }
                }
            }
        }
    }
}

/**
 * Optional family members (Add Inmate step 4). A list of the same three
 * fields the Manage Contacts form collects — any number can be stacked here.
 * The backend creates each contact and fires the wallet-link SMS with the
 * inmate itself. Mobile is the bare 10-digit number, no +91.
 */
@Composable
private fun FamilyContactStep(
    members: List<FamilyContactPayload>,
    draftName: String,
    draftRelationship: String,
    draftMobile: String,
    onDraftNameChange: (String) -> Unit,
    onDraftRelationshipChange: (String) -> Unit,
    onDraftMobileChange: (String) -> Unit,
    onAddMember: () -> Unit,
    onRemoveMember: (Int) -> Unit
) {
    val canAdd = draftName.isNotBlank() && draftMobile.length == 10

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Family Members",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )

            Text(
                text = "You can manage them later from Manage Inmates.",
                fontSize = 14.sp,
                color = TextGray
            )

            if (members.isEmpty()) {
                Text(
                    text = "No family members added yet",
                    fontSize = 13.sp,
                    color = Color(0xFF9AA6B5)
                )
            } else {
                members.forEachIndexed { index, member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LightBg, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = PrimaryNavy,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = member.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = PrimaryDarkNavy
                            )
                            Text(
                                text = "${member.relationship} · ${member.mobileNumber}",
                                fontSize = 12.sp,
                                color = TextGray
                            )
                        }
                        IconButton(onClick = { onRemoveMember(index) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove ${member.name}",
                                tint = DangerRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = BorderColor)

            Text(
                text = "Add a family member",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = PrimaryDarkNavy
            )

            KioskTextField(
                value = draftName,
                onValueChange = onDraftNameChange,
                label = "Full Name",
                required = true,
                imeAction = androidx.compose.ui.text.input.ImeAction.Next
            )

            KioskSelectField(
                value = draftRelationship,
                onValueChange = onDraftRelationshipChange,
                label = "Relationship",
                options = RELATIONSHIP_OPTIONS,
                placeholder = "Select relationship",
                modifier = Modifier.fillMaxWidth()
            )

            KioskPhoneField(
                value = draftMobile,
                onValueChange = onDraftMobileChange,
                label = "Mobile Number",
                required = true,
                errorText = if (draftMobile.isNotEmpty() && draftMobile.length != 10)
                    "Enter a valid 10-digit mobile number" else null,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            )

            Button(
                onClick = onAddMember,
                enabled = canAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Family Member", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CompleteStep(modifier: Modifier = Modifier, familyAdded: Boolean = false) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = SuccessGreen.copy(alpha = 0.1f),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Text(
                text = "Registration Complete!",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy,
                textAlign = TextAlign.Center
            )

            Text(
                text = "The inmate has been successfully registered.",
                fontSize = 14.sp,
                color = TextGray,
                textAlign = TextAlign.Center
            )

        }
    }
}

// --- PREVIEWS ---

@Preview(name = "Mobile View", device = "spec:width=360dp,height=800dp", showBackground = true)
@Composable
fun PreviewAddPrisonerMobile() {
    PrisonKioskTheme {
        AddPrisonerContent(
            windowWidthSizeClass = WindowWidthSizeClass.Compact,
            onBackClick = {},
            onComplete = {},
            onRegister = { _, _, _, _, _ -> },
            registrationState = AddPrisonerViewModel.RegistrationState.Idle
        )
    }
}
