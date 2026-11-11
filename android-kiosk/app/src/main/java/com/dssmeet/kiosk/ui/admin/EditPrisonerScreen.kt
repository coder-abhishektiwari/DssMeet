package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.PrimaryNavy

import com.dssmeet.kiosk.ui.theme.AccentGreen

import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.LightBg

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.models.admin.EditPrisonerRequest
import com.dssmeet.kiosk.models.admin.KioskDevice
import com.dssmeet.kiosk.models.admin.Prisoner
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.ui.components.KioskLoadingState
import com.dssmeet.kiosk.ui.components.KioskPasswordField
import com.dssmeet.kiosk.ui.components.KioskTopBar
import com.dssmeet.kiosk.ui.components.KioskTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction

private val EDIT_STEP_TITLES = listOf("Personal", "Identity", "Address", "Access")

@Composable
fun EditPrisonerScreen(
    prisonerId: String,
    windowSizeClass: WindowSizeClass,
    onBackClick: () -> Unit,
    viewModel: EditPrisonerViewModel = hiltViewModel()
) {
    val prisonerResult by viewModel.prisoner.collectAsState()
    val updateResult by viewModel.updateState.collectAsState()
    val resetPinResult by viewModel.resetPinState.collectAsState()
    val kioskOptions by viewModel.kioskOptions.collectAsState()

    LaunchedEffect(prisonerId) {
        viewModel.loadPrisoner(prisonerId)
    }

    Scaffold(
        topBar = {
            KioskTopBar(
                title = "Edit Inmate",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        containerColor = LightBg
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (val result = prisonerResult) {
                is NetworkResult.Loading -> {
                    KioskLoadingState(modifier = Modifier.align(Alignment.Center))
                }
                is NetworkResult.Success -> {
                    EditPrisonerForm(
                        prisoner = result.data,
                        kioskOptions = kioskOptions,
                        onUpdate = { request -> viewModel.updatePrisoner(prisonerId, request) },
                        onResetPin = { pin -> viewModel.resetPin(prisonerId, pin) },
                        onResetPinDismissed = { viewModel.resetResetPinState() },
                        resetPinResult = resetPinResult
                    )
                }
                is NetworkResult.Failure -> {
                    Text(
                        text = result.error.message ?: "Failed to load inmate",
                        color = DangerRed,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {}
            }

            if (updateResult is NetworkResult.Loading) {
                KioskLoadingState(modifier = Modifier.align(Alignment.Center))
            }

            LaunchedEffect(updateResult) {
                if (updateResult is NetworkResult.Success) {
                    onBackClick()
                }
            }
        }
    }
}

@Composable
fun EditPrisonerForm(
    prisoner: Prisoner,
    kioskOptions: List<KioskDevice> = emptyList(),
    onUpdate: (EditPrisonerRequest) -> Unit,
    onResetPin: (String) -> Unit,
    onResetPinDismissed: () -> Unit,
    resetPinResult: NetworkResult<String>
) {
    val formState = remember(prisoner.inmateId) { PrisonerFormState(prisoner) }
    var step by remember(prisoner.inmateId) { mutableStateOf(0) }
    var status by remember(prisoner.inmateId) { mutableStateOf(prisoner.status) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    var showResetPinDialog by remember { mutableStateOf(false) }
    var resetPinValue by remember { mutableStateOf("") }
    var resetPinError by remember { mutableStateOf("") }
    var resetPinSuccess by remember { mutableStateOf(false) }

    val resetPinLoading = resetPinResult is NetworkResult.Loading
    LaunchedEffect(resetPinResult) {
        if (resetPinResult is NetworkResult.Success) {
            resetPinSuccess = true
            resetPinValue = ""
            resetPinError = ""
        } else if (resetPinResult is NetworkResult.Failure) {
            resetPinError = resetPinResult.error.message ?: "Failed to reset PIN"
        }
    }

    val submit: () -> Unit = {
        val values = formState.toValues()
        val ageNum = values.age?.toIntOrNull()
        saveError = when {
            values.name.isEmpty() -> "Full name is required"
            values.age != null && ageNum == null -> "Age must be a number"
            ageNum != null && (ageNum < 1 || ageNum > 120) -> "Age must be between 1 and 120"
            else -> {
                onUpdate(
                    EditPrisonerRequest(
                        name = values.name,
                        prisonerNumber = values.prisonerNumber.ifBlank { null },
                        gender = values.gender,
                        age = ageNum,
                        dateOfAdmission = values.dateOfAdmission,
                        fatherName = values.fatherName,
                        motherName = values.motherName,
                        idProof = values.idProof,
                        idNumber = values.idNumber,
                        religion = values.religion,
                        nationality = values.nationality,
                        state = values.state,
                        district = values.district,
                        address = values.address,
                        assignedKioskId = formState.assignedKioskId.trim().ifBlank { null },
                        status = status,
                        active = formState.active
                    )
                )
                ""
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Changes") },
            text = { Text("Are you sure you want to save these changes?") },
            confirmButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
                TextButton(onClick = {
                    showSaveDialog = false
                    submit()
                }) {
                    Text("Save", color = PrimaryNavy)
                }
            },
            dismissButton = {}
        )
    }

    if (showResetPinDialog) {
        AlertDialog(
            onDismissRequest = {
                showResetPinDialog = false
                resetPinValue = ""
                resetPinError = ""
                resetPinSuccess = false
                onResetPinDismissed()
            },
            title = { Text("Reset PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (resetPinSuccess) {
                        Text("PIN reset successfully!", color = AccentGreen)
                    } else {
                        if (resetPinError.isNotEmpty()) {
                            Text(resetPinError, color = DangerRed, fontSize = 13.sp)
                        }
                        Text("Enter a new 6-digit PIN for ${prisoner.displayName}", fontSize = 14.sp)
                        KioskPasswordField(
                            value = resetPinValue,
                            onValueChange = {
                                if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                                    resetPinValue = it
                                }
                            },
                            label = "New PIN",
                            required = true,
                            errorText = if (resetPinValue.isNotEmpty() && resetPinValue.length != 6)
                                "PIN must be exactly 6 digits" else null,
                            imeAction = ImeAction.Done
                        )
                    }
                }
            },
            confirmButton = {
                if (!resetPinSuccess) {
                    TextButton(
                        enabled = !resetPinLoading,
                        onClick = {
                            if (resetPinValue.length != 6) {
                                resetPinError = "PIN must be exactly 6 digits"
                            } else {
                                resetPinError = ""
                                onResetPin(resetPinValue)
                            }
                        }
                    ) {
                        if (resetPinLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Reset")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showResetPinDialog = false
                    resetPinValue = ""
                    resetPinError = ""
                    resetPinSuccess = false
                    onResetPinDismissed()
                }) {
                    Text(if (resetPinSuccess) "Close" else "Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PrisonerStepHeader(titles = EDIT_STEP_TITLES, currentStep = step)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (saveError.isNotEmpty()) {
                ErrorCard(saveError)
            }

            when (step) {
                0 -> PersonalDetailsCard(formState)
                1 -> IdentityAdmissionCard(formState, kioskOptions)
                2 -> AddressCard(formState)
                else -> {
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
                                "Access",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
                            )

                            Text(
                                "Manage authentication for this inmate.",
                                fontSize = 14.sp,
                                color = com.dssmeet.kiosk.ui.theme.TextGray
                            )

                            OutlinedButton(
                                onClick = {
                                    showResetPinDialog = true
                                    resetPinSuccess = false
                                    resetPinError = ""
                                    resetPinValue = ""
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.LockReset,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reset PIN", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
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
                            step -= 1
                            saveError = ""
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back")
                    }
                }

                Button(
                    onClick = {
                        if (step == EDIT_STEP_TITLES.lastIndex) {
                            val allErrors = (0 until EDIT_STEP_TITLES.lastIndex)
                                .map { formState.validateStep(it) }
                                .firstOrNull { it.isNotEmpty() } ?: ""
                            if (allErrors.isNotEmpty()) {
                                saveError = allErrors
                            } else {
                                saveError = ""
                                showSaveDialog = true
                            }
                        } else {
                            val stepError = formState.validateStep(step)
                            if (stepError.isNotEmpty()) {
                                saveError = stepError
                            } else {
                                saveError = ""
                                step += 1
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(if (step > 0) 1f else 2f)
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                ) {
                    if (step == EDIT_STEP_TITLES.lastIndex) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Changes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Next", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}
