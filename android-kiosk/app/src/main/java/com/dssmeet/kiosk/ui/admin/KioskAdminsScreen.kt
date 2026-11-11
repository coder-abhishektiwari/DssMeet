package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy

import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.LightBg

import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private val EMPLOYEE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{3,40}$")
private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Composable
fun KioskAdminsScreen(
    windowSizeClass: WindowSizeClass,
    onBackClick: () -> Unit,
    viewModel: KioskAdminsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    KioskAdminsContent(
        state = state,
        onBackClick = onBackClick,
        onRetry = viewModel::load,
        onCreate = { name, employeeId, email, password ->
            viewModel.create(name, employeeId, email, password)
        },
        onUpdate = { adminId, name, employeeId, email ->
            viewModel.update(adminId, name, employeeId, email)
        },
        onDialogDismissed = viewModel::clearSaveError,
        onMessageShown = viewModel::consumeMessage
    )
}

@Composable
fun KioskAdminsContent(
    state: KioskAdminsViewModel.UiState,
    onBackClick: () -> Unit,
    onRetry: () -> Unit,
    onCreate: (String, String, String?, String) -> Unit,
    onUpdate: (String, String, String, String?) -> Unit,
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

    Scaffold(
        topBar = {
            KioskTopBar(
                title = "Kiosk Admins",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = PrimaryNavy,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Kiosk Admin")
            }
        },
        snackbarHost = {
            snackbarMessage?.let { message -> Snackbar { Text(message) } }
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
                    KioskAdminsList(
                        admins = state.admins,
                        onEdit = { editing = it }
                    )
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
            onDismiss = {
                showAddDialog = false
                onDialogDismissed()
            },
            onConfirm = onCreate
        )
    }

    editing?.let { admin ->
        KioskAdminDialog(
            title = "Edit Kiosk Admin",
            initial = admin,
            saving = state.isSaving,
            serverError = state.saveError,
            onDismiss = {
                editing = null
                onDialogDismissed()
            },
            onConfirm = { name, employeeId, email, _ ->
                onUpdate(admin.adminId, name, employeeId, email)
            }
        )
    }
}

@Composable
private fun KioskAdminsList(
    admins: List<KioskAdmin>,
    onEdit: (KioskAdmin) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(admins, key = { it.adminId }) { admin ->
            KioskAdminRow(admin = admin, onEdit = { onEdit(admin) })
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
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?, String) -> Unit
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
                    placeholder = "e.g. Neha Gupta",
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
                    helperText = "What the operator types on the kiosk to sign in.",
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
                            password
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
        onCreate = { _, _, _, _ -> },
        onUpdate = { _, _, _, _ -> },
        onDialogDismissed = {},
        onMessageShown = {}
    )
}
