package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy

import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.LightBg

import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.models.admin.KioskAdmin
import com.dssmeet.kiosk.ui.components.KioskLoadingState
import com.dssmeet.kiosk.ui.components.KioskPasswordField
import com.dssmeet.kiosk.ui.components.KioskTextField
import com.dssmeet.kiosk.ui.components.KioskTopBar
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.dssmeet.kiosk.hardware.FingerprintCaptureState
import com.dssmeet.kiosk.ui.auth.FingerprintFeedbackIcon

private val EMPLOYEE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{3,40}$")
private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Composable
fun KioskAdminsScreen(
    windowSizeClass: WindowSizeClass,
    onBackClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    viewModel: KioskAdminsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    KioskAdminsContent(
        state = state,
        onBackClick = onBackClick,
        onLogoutClick = onLogoutClick,
        onRetry = viewModel::load,
        onCreate = { name, employeeId, email, password, fingerprints ->
            viewModel.create(name, employeeId, email, password, fingerprints)
        },
        onUpdate = { adminId, name, employeeId, email, fingerprints ->
            viewModel.update(adminId, name, employeeId, email, fingerprints)
        },
        fingerprintCaptureState = state.captureState,
        pendingFingerprints = state.pendingFingerprints,
        fingerprintError = state.fingerprintError,
        onStartFingerprintCapture = viewModel::startFingerprintCapture,
        onStopFingerprintCapture = viewModel::stopFingerprintCapture,
        onClearPendingFingerprints = viewModel::clearPendingFingerprints,
        onSaveFingerprints = viewModel::savePendingFingerprints,
        onRemoveFingerprint = viewModel::removeFingerprint,
        onDialogDismissed = viewModel::clearSaveError,
        onMessageShown = viewModel::consumeMessage
    )
}

@Composable
fun KioskAdminsContent(
    state: KioskAdminsViewModel.UiState,
    onBackClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    onRetry: () -> Unit,
    onCreate: (String, String, String?, String, List<String>) -> Unit,
    onUpdate: (String, String, String, String?, List<String>) -> Unit,
    fingerprintCaptureState: FingerprintCaptureState,
    pendingFingerprints: List<String>,
    fingerprintError: String?,
    onStartFingerprintCapture: () -> Unit,
    onStopFingerprintCapture: () -> Unit,
    onClearPendingFingerprints: () -> Unit,
    onSaveFingerprints: (String) -> Unit,
    onRemoveFingerprint: (String, String) -> Unit,
    onDialogDismissed: () -> Unit,
    onMessageShown: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<KioskAdmin?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    // A successful save closes whichever dialog was open and confirms with a snackbar.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        showAddDialog = false
        editing = null
        snackbarMessage = message
        onMessageShown()
    }

    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            kotlinx.coroutines.delay(3000L)
            snackbarMessage = null
        }
    }

    LaunchedEffect(showAddDialog, editing?.adminId) {
        if (showAddDialog || editing != null) {
            onStartFingerprintCapture()
        } else {
            onStopFingerprintCapture()
        }
    }

    DisposableEffect(Unit) {
        onDispose { onStopFingerprintCapture() }
    }

    Scaffold(
        topBar = {
            KioskTopBar(
                title = "Kiosk Admins",
                showBackButton = true,
                onBackClick = onBackClick,
                trailingActions = {
                    com.dssmeet.kiosk.ui.components.KioskLogoutButton(onClick = onLogoutClick)
                }
            )
        },
        snackbarHost = {
            snackbarMessage?.let { message -> Snackbar { Text(message) } }
        },
        bottomBar = {
            com.dssmeet.kiosk.ui.components.KioskBottomActionBar(onBackClick = onBackClick)
        },
        containerColor = LightBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when {
                state.isLoading && state.admins.isEmpty() -> {
                    KioskLoadingState(modifier = Modifier.align(Alignment.Center))
                }
                state.error != null && state.admins.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = state.error,
                            color = DangerRed,
                            fontSize = 14.sp
                        )
                        OutlinedButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                state.admins.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.ManageAccounts,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No kiosk admins yet", color = TextGray)
                        Text(
                            text = "Tap + to add one",
                            color = TextGray,
                            fontSize = 13.sp
                        )
                    }
                }
                else -> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        if (maxWidth >= 900.dp) {
                            KioskAdminsWideList(
                                admins = state.admins,
                                onAdd = { showAddDialog = true },
                                onEdit = { editing = it },
                                modifier = Modifier.padding(16.dp)
                            )
                        } else {
                            KioskAdminsList(
                                admins = state.admins,
                                onEdit = { editing = it }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        KioskAdminDialog(
            title = "Add Kiosk Admin",
            initial = null,
            saving = state.isSaving,
            serverError = state.saveError,
            captureState = fingerprintCaptureState,
            pendingFingerprints = pendingFingerprints,
            fingerprintError = fingerprintError,
            onRemoveFingerprint = onRemoveFingerprint,
            onSaveFingerprints = onSaveFingerprints,
            onDismiss = {
                showAddDialog = false
                onClearPendingFingerprints()
                onDialogDismissed()
            },
            onConfirm = { name, employeeId, email, password, fingerprints ->
                onCreate(name, employeeId, email, password, fingerprints)
            }
        )
    }

    editing?.let { admin ->
        KioskAdminDialog(
            title = "Edit Kiosk Admin",
            initial = admin,
            saving = state.isSaving,
            serverError = state.saveError,
            captureState = fingerprintCaptureState,
            pendingFingerprints = pendingFingerprints,
            fingerprintError = fingerprintError,
            onRemoveFingerprint = onRemoveFingerprint,
            onSaveFingerprints = onSaveFingerprints,
            onDismiss = {
                editing = null
                onClearPendingFingerprints()
                onDialogDismissed()
            },
            onConfirm = { name, employeeId, email, _, fingerprints ->
                onUpdate(admin.adminId, name, employeeId, email, fingerprints)
            }
        )
    }
}

@Composable
private fun KioskAdminsList(
    admins: List<KioskAdmin>,
    onEdit: (KioskAdmin) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 480.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        gridItems(admins, key = { it.adminId }) { admin ->
            KioskAdminRow(admin = admin, onEdit = { onEdit(admin) })
        }
    }
}

@Composable
private fun KioskAdminsWideList(
    admins: List<KioskAdmin>,
    onAdd: () -> Unit,
    onEdit: (KioskAdmin) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Kiosk admin accounts",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDarkNavy
                    )
                    Text(
                        text = "${admins.size} ${if (admins.size == 1) "account" else "accounts"} configured on this kiosk",
                        fontSize = 13.sp,
                        color = TextGray
                    )
                }
                Button(
                    onClick = onAdd,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add admin", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF4F6F9))
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ADMIN",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextGray,
                            modifier = Modifier.weight(1.4f)
                        )
                        Text(
                            text = "USERNAME",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextGray,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "EMAIL",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextGray,
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            text = "FINGER",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextGray,
                            modifier = Modifier.weight(0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = "STATUS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextGray,
                            modifier = Modifier.weight(0.8f)
                        )
                        Text(
                            text = "ACTION",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextGray,
                            modifier = Modifier.width(76.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    admins.forEach { admin ->
                        KioskAdminTableRow(admin = admin, onEdit = { onEdit(admin) })
                        HorizontalDivider(color = Color(0xFFE8ECF1))
                    }
                }
            }
        }
    }
}

@Composable
private fun KioskAdminTableRow(
    admin: KioskAdmin,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = PrimaryNavy.copy(alpha = 0.1f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = PrimaryNavy,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = admin.name.ifBlank { "—" },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryDarkNavy,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f)
        )
        Text(
            text = admin.employeeId ?: "—",
            fontSize = 13.sp,
            color = PrimaryNavy,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = admin.email ?: "—",
            fontSize = 13.sp,
            color = TextGray,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f)
        )
        Box(modifier = Modifier.weight(0.7f), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (admin.fingerprintCount > 0) Color(0xFFE6F7EE) else Color(0xFFF4F6F9)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = if (admin.fingerprintCount > 0) Color(0xFF17875B) else TextGray,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${admin.fingerprintCount}/3",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (admin.fingerprintCount > 0) Color(0xFF17875B) else TextGray
                    )
                }
            }
        }
        val active = admin.status?.equals("active", ignoreCase = true) != false
        Box(modifier = Modifier.weight(0.8f), contentAlignment = Alignment.CenterStart) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (active) Color(0xFFE6F7EE) else Color(0xFFFDECEC)
            ) {
                Text(
                    text = if (active) "Active" else (admin.status ?: "Inactive").replaceFirstChar { it.uppercase() },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color(0xFF17875B) else DangerRed,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onEdit,
            modifier = Modifier.width(76.dp).height(36.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Edit", fontSize = 12.sp)
        }
    }
}

@Composable
private fun KioskAdminRow(
    admin: KioskAdmin,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = PrimaryNavy.copy(alpha = 0.1f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = PrimaryNavy
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = admin.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = PrimaryDarkNavy
                )
                Text(
                    text = admin.employeeId ?: "-",
                    color = PrimaryNavy,
                    fontSize = 13.sp
                )
                Text(
                    text = admin.email ?: "-",
                    color = TextGray,
                    fontSize = 13.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = if (admin.fingerprintCount > 0) Color(0xFF17875B) else TextGray,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${admin.fingerprintCount}/3 fingerprints",
                        color = if (admin.fingerprintCount > 0) Color(0xFF17875B) else TextGray,
                        fontSize = 12.sp
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = Color(0xFF666666)
                )
            }
        }
    }
}

/**
 * The same four fields the warden panel offers: username + password only on
 * create, name / username / email editable afterwards.
 */
@Composable
private fun KioskAdminDialog(
    title: String,
    initial: KioskAdmin?,
    saving: Boolean,
    serverError: String?,
    captureState: FingerprintCaptureState,
    pendingFingerprints: List<String>,
    fingerprintError: String?,
    onRemoveFingerprint: (String, String) -> Unit,
    onSaveFingerprints: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?, String, List<String>) -> Unit
) {
    val isAdd = initial == null
    var name by remember(initial?.adminId) { mutableStateOf(initial?.name ?: "") }
    var employeeId by remember(initial?.adminId) { mutableStateOf(initial?.employeeId ?: "") }
    var email by remember(initial?.adminId) { mutableStateOf(initial?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    val trimmedName = name.trim()
    val trimmedUsername = employeeId.trim()
    val trimmedEmail = email.trim()

    val nameError = when {
        submitted && trimmedName.isEmpty() -> "Name is required"
        else -> null
    }
    val employeeIdError = when {
        submitted && trimmedUsername.isEmpty() -> "Username is required"
        submitted && !EMPLOYEE_ID_PATTERN.matches(trimmedUsername) ->
            "3-40 characters: letters, digits, dot, dash or underscore"
        trimmedUsername.isNotEmpty() && !EMPLOYEE_ID_PATTERN.matches(trimmedUsername) ->
            "3-40 characters: letters, digits, dot, dash or underscore"
        else -> null
    }
    val emailError = when {
        trimmedEmail.isNotEmpty() && !EMAIL_PATTERN.matches(trimmedEmail) -> "Enter a valid email"
        else -> null
    }
    val passwordError = when {
        !isAdd -> null
        submitted && password.isEmpty() -> "Password is required"
        password.isNotEmpty() && password.length < 6 -> "At least 6 characters"
        else -> null
    }
    val isValid = trimmedName.isNotEmpty() &&
        EMPLOYEE_ID_PATTERN.matches(trimmedUsername) &&
        (trimmedEmail.isEmpty() || EMAIL_PATTERN.matches(trimmedEmail)) &&
        (!isAdd || password.length >= 6)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                KioskTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Full name",
                    required = true,
                    placeholder = "e.g. Abhishek Tiwari",
                    errorText = nameError,
                    enabled = !saving,
                    imeAction = ImeAction.Next
                )

                KioskTextField(
                    value = employeeId,
                    onValueChange = { employeeId = it },
                    label = "Username (employee ID)",
                    required = true,
                    placeholder = "e.g. empsa003",
                    errorText = employeeIdError,
                    enabled = !saving,
                    imeAction = if (isAdd) ImeAction.Next else ImeAction.Done
                )

                KioskTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email (optional)",
                    placeholder = "name@dssmeet.io",
                    errorText = emailError,
                    enabled = !saving,
                    keyboardType = KeyboardType.Email,
                    imeAction = if (isAdd) ImeAction.Next else ImeAction.Done
                )

                if (isAdd) {
                    KioskPasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        required = true,
                        placeholder = "At least 6 characters",
                        helperText = "Signs in on any kiosk of this prison with username and password.",
                        errorText = passwordError,
                        enabled = !saving,
                        imeAction = ImeAction.Done
                    )
                }

                AdminFingerprintCaptureSection(
                    initial = initial,
                    captureState = captureState,
                    pendingCount = pendingFingerprints.size,
                    error = fingerprintError,
                    enabled = !saving,
                    onRemoveFingerprint = onRemoveFingerprint,
                    onSaveFingerprints = onSaveFingerprints
                )

                serverError?.let {
                    Text(it, color = DangerRed, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    if (isValid) {
                        onConfirm(
                            trimmedName,
                            trimmedUsername,
                            trimmedEmail.ifEmpty { null },
                            password,
                            pendingFingerprints
                        )
                    }
                },
                enabled = !saving
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = PrimaryNavy
                    )
                } else {
                    Text(if (isAdd) "Add Kiosk Admin" else "Save Changes", color = PrimaryNavy)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        }
    )
}

@Composable
private fun AdminFingerprintCaptureSection(
    initial: KioskAdmin?,
    captureState: FingerprintCaptureState,
    pendingCount: Int,
    error: String?,
    enabled: Boolean,
    onRemoveFingerprint: (String, String) -> Unit,
    onSaveFingerprints: (String) -> Unit
) {
    val registered = initial?.fingerprintCount ?: 0
    val total = registered + pendingCount
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F6F9), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FingerprintFeedbackIcon(
                isScanning = captureState is FingerprintCaptureState.Searching ||
                    captureState is FingerprintCaptureState.Captured,
                isMismatch = false,
                isDeviceError = captureState is FingerprintCaptureState.Failed,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Fingerprint (optional)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "$total of 3 registered" + if (pendingCount > 0) " • $pendingCount new" else "",
                    fontSize = 12.sp,
                    color = TextGray
                )
            }
            if (initial != null && pendingCount > 0) {
                TextButton(
                    onClick = { onSaveFingerprints(initial.adminId) },
                    enabled = enabled
                ) {
                    Text("Save", fontSize = 12.sp)
                }
            }
        }
        error?.let { Text(it, color = DangerRed, fontSize = 12.sp) }
        initial?.fingerprints?.forEach { fingerprint ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = PrimaryNavy,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    fingerprint.label.ifBlank { "Fingerprint" },
                    fontSize = 13.sp,
                    color = PrimaryDarkNavy,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onRemoveFingerprint(initial.adminId, fingerprint.biometricId) },
                    enabled = enabled
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove fingerprint",
                        tint = DangerRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewKioskAdminsContent() {
    KioskAdminsContent(
        state = KioskAdminsViewModel.UiState(
            isLoading = false,
            admins = listOf(
                KioskAdmin(
                    adminId = "ADMIN-0001",
                    employeeId = "empsa001",
                    name = "Abhishek",
                    email = "abhishek@dssmeet.com",
                    status = "active"
                )
            )
        ),
        onBackClick = {},
        onRetry = {},
        onCreate = { _, _, _, _, _ -> },
        onUpdate = { _, _, _, _, _ -> },
        fingerprintCaptureState = FingerprintCaptureState.Idle,
        pendingFingerprints = emptyList(),
        fingerprintError = null,
        onStartFingerprintCapture = {},
        onStopFingerprintCapture = {},
        onClearPendingFingerprints = {},
        onSaveFingerprints = { _ -> },
        onRemoveFingerprint = { _, _ -> },
        onDialogDismissed = {},
        onMessageShown = {}
    )
}
