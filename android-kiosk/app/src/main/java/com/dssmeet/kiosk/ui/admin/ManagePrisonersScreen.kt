package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy

import com.dssmeet.kiosk.ui.theme.AccentGreen
import com.dssmeet.kiosk.ui.theme.WarningOrange

import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.LightBg
import com.dssmeet.kiosk.ui.theme.MoneyRedBg

import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.models.admin.Prisoner
import com.dssmeet.kiosk.models.wallet.WalletStatement
import com.dssmeet.kiosk.models.wallet.WalletTransaction
import com.dssmeet.kiosk.models.wallet.safeBalance
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.ui.components.ConfirmDeleteDialog
import com.dssmeet.kiosk.ui.components.KioskPasswordField
import com.dssmeet.kiosk.ui.components.KioskTopBar
import com.dssmeet.kiosk.ui.theme.PrisonKioskTheme
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ManagePrisonersScreen(
    windowSizeClass: WindowSizeClass,
    onBackClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    onPrisonerClick: (String) -> Unit,
    onEditClick: (String) -> Unit,
    onManageContactsClick: (String) -> Unit,
    onBiometricsClick: (String, String) -> Unit,
    onAddClick: () -> Unit = {},
    viewModel: ManagePrisonersViewModel = hiltViewModel()
) {
    val prisoners by viewModel.prisoners.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val deletingPrisonerId by viewModel.deletingPrisonerId.collectAsState()
    val togglingPrisonerId by viewModel.togglingPrisonerId.collectAsState()
    val resetPinResult by viewModel.resetPinState.collectAsState()
    val walletBalances by viewModel.walletBalances.collectAsState()
    val ledgerState by viewModel.ledgerState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var resetPinTarget by remember { mutableStateOf<Prisoner?>(null) }
    var ledgerPrisoner by remember { mutableStateOf<Prisoner?>(null) }

    // Refresh list when screen becomes visible
    LaunchedEffect(Unit) {
        viewModel.refreshPrisoners()
    }

    if (showDeleteDialog != null) {
        val (prisonerId, prisonerName) = showDeleteDialog!!
        ConfirmDeleteDialog(
            title = "Delete Prisoner",
            message = "Are you sure you want to delete $prisonerName? This action cannot be undone.",
            onConfirm = {
                viewModel.deletePrisoner(prisonerId)
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }

    if (ledgerPrisoner != null) {
        InmateLedgerDialog(
            prisoner = ledgerPrisoner!!,
            state = ledgerState,
            onClose = {
                ledgerPrisoner = null
                viewModel.closeLedger()
            }
        )
    }

    ManagePrisonersContent(
        prisoners = viewModel.getFilteredPrisoners(),
        searchQuery = searchQuery,
        isLoading = isLoading,
        error = error,
        deletingPrisonerId = deletingPrisonerId,
        togglingPrisonerId = togglingPrisonerId,
        resetPinResult = resetPinResult,
        resetPinTarget = resetPinTarget,
        walletBalances = walletBalances,
        onLedgerClick = { prisoner ->
            ledgerPrisoner = prisoner
            viewModel.loadLedger(prisoner.inmateId)
        },
        onBackClick = onBackClick,
        onLogoutClick = onLogoutClick,
        onPrisonerClick = onPrisonerClick,
        onEditClick = onEditClick,
        onToggleActive = { prisonerId, active -> viewModel.setPrisonerActive(prisonerId, active) },
        onManageContactsClick = onManageContactsClick,
        onBiometricsClick = onBiometricsClick,
        onAddClick = onAddClick,
        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
        onDeleteClick = { prisonerId, prisonerName -> showDeleteDialog = Pair(prisonerId, prisonerName) },
        onResetPinClick = { prisoner ->
            resetPinTarget = prisoner
            viewModel.resetResetPinState()
        },
        onResetPinSubmit = { prisonerId, pin -> viewModel.resetPrisonerPin(prisonerId, pin) },
        onResetPinDismissed = {
            resetPinTarget = null
            viewModel.resetResetPinState()
        }
    )
}

@Composable
fun ManagePrisonersContent(
    prisoners: List<Prisoner>,
    searchQuery: String,
    isLoading: Boolean = false,
    error: String? = null,
    deletingPrisonerId: String? = null,
    togglingPrisonerId: String? = null,
    resetPinResult: NetworkResult<String>? = null,
    resetPinTarget: Prisoner? = null,
    walletBalances: Map<String, Double> = emptyMap(),
    onLedgerClick: (Prisoner) -> Unit = {},
    onBackClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    onPrisonerClick: (String) -> Unit,
    onEditClick: (String) -> Unit = {},
    onToggleActive: (String, Boolean) -> Unit,
    onManageContactsClick: (String) -> Unit = {},
    onBiometricsClick: (String, String) -> Unit,
    onAddClick: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onDeleteClick: (String, String) -> Unit,
    onResetPinClick: (Prisoner) -> Unit = {},
    onResetPinSubmit: (String, String) -> Unit = { _, _ -> },
    onResetPinDismissed: () -> Unit = {}
) {
    var resetPinValue by remember(resetPinTarget) { mutableStateOf("") }
    var resetPinError by remember(resetPinTarget) { mutableStateOf("") }
    var resetPinSuccess by remember(resetPinTarget) { mutableStateOf(false) }
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

    if (resetPinTarget != null) {
        AlertDialog(
            onDismissRequest = {
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
                        Text("Enter a new 6-digit PIN for ${resetPinTarget.displayName}", fontSize = 14.sp)
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
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
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
                                onResetPinSubmit(resetPinTarget.inmateId, resetPinValue)
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
                } else {
                    TextButton(onClick = {
                        resetPinSuccess = false
                        onResetPinDismissed()
                    }) {
                        Text("Done", color = PrimaryNavy)
                    }
                }
            },
            dismissButton = {
                if (!resetPinSuccess && !resetPinLoading) {
                    TextButton(onClick = {
                        resetPinValue = ""
                        resetPinError = ""
                        onResetPinDismissed()
                    }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            KioskTopBar(
                title = "Manage Inmates",
                showBackButton = true,
                onBackClick = onBackClick,
                trailingActions = {
                    com.dssmeet.kiosk.ui.components.KioskLogoutButton(onClick = onLogoutClick)
                }
            )
        },
        bottomBar = {
            com.dssmeet.kiosk.ui.components.KioskBottomActionBar(onBackClick = onBackClick)
        },
        containerColor = LightBg
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (maxWidth >= 900.dp) {
                ManagePrisonersWideContent(
                    prisoners = prisoners,
                    searchQuery = searchQuery,
                    isLoading = isLoading,
                    error = error,
                    deletingPrisonerId = deletingPrisonerId,
                    togglingPrisonerId = togglingPrisonerId,
                    onPrisonerClick = onPrisonerClick,
                    onEditClick = onEditClick,
                    onToggleActive = onToggleActive,
                    onManageContactsClick = onManageContactsClick,
                    onBiometricsClick = onBiometricsClick,
                    onAddClick = onAddClick,
                    onSearchQueryChange = onSearchQueryChange,
                    onDeleteClick = onDeleteClick,
                    onResetPinClick = onResetPinClick,
                    walletBalances = walletBalances,
                    onLedgerClick = onLedgerClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            } else {
        com.dssmeet.kiosk.ui.components.KioskResponsiveBox(
            maxWidth = 960.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
            // Search Bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextGray,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            color = PrimaryDarkNavy
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(PrimaryNavy),
                        decorationBox = { inner ->
                            Box {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search inmates...",
                                        color = TextGray,
                                        fontSize = 15.sp
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp)
                    )
                }
            }

            // Error Banner
            if (error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error ?: "",
                            color = DangerRed,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Inmates List
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = PrimaryNavy)
                }
            } else if (prisoners.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextGray.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No inmates found",
                            fontSize = 18.sp,
                            color = TextGray
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 480.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    gridItems(prisoners) { prisoner ->
                        PrisonerCard(
                            prisoner = prisoner,
                            isDeleting = deletingPrisonerId == prisoner.inmateId,
                            isToggling = togglingPrisonerId == prisoner.inmateId,
                            onClick = { onPrisonerClick(prisoner.inmateId) },
                            onEditClick = { onEditClick(prisoner.inmateId) },
                            onToggleActive = { onToggleActive(prisoner.inmateId, it) },
                            onManageContactsClick = { onManageContactsClick(prisoner.inmateId) },
                            onBiometricsClick = { onBiometricsClick(prisoner.inmateId, prisoner.displayName) },
                            onResetPinClick = { onResetPinClick(prisoner) },
                            onDeleteClick = { onDeleteClick(prisoner.inmateId, prisoner.displayName) }
                        )
                    }
                }
            }
            }
        }
        }
    }
    }
}

@Composable
private fun ManagePrisonersWideContent(
    prisoners: List<Prisoner>,
    searchQuery: String,
    isLoading: Boolean,
    error: String?,
    deletingPrisonerId: String?,
    togglingPrisonerId: String?,
    onPrisonerClick: (String) -> Unit,
    onEditClick: (String) -> Unit,
    onToggleActive: (String, Boolean) -> Unit,
    onManageContactsClick: (String) -> Unit,
    onBiometricsClick: (String, String) -> Unit,
    onAddClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onDeleteClick: (String, String) -> Unit,
    onResetPinClick: (Prisoner) -> Unit,
    walletBalances: Map<String, Double>,
    onLedgerClick: (Prisoner) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Manage inmates",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDarkNavy
                )
                Text(
                    text = "${prisoners.size} ${if (prisoners.size == 1) "inmate" else "inmates"}",
                    fontSize = 13.sp,
                    color = TextGray
                )
            }
            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add inmate", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        ManagePrisonersSearchCard(searchQuery, onSearchQueryChange)
        if (error != null) {
            ManagePrisonersErrorCard(error)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tableWidth = maxOf(maxWidth, 1400.dp)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                ) {
                    ManagePrisonersTableHeader(
                        modifier = Modifier.width(tableWidth)
                    )
                    HorizontalDivider(color = Color(0xFFE8ECF1))
                    when {
                        isLoading -> Box(
                            modifier = Modifier
                                .width(tableWidth)
                                .weight(1f)
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PrimaryNavy)
                        }
                        prisoners.isEmpty() -> Box(
                            modifier = Modifier
                                .width(tableWidth)
                                .weight(1f)
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = TextGray.copy(alpha = 0.5f),
                                    modifier = Modifier.size(56.dp)
                                )
                                Text("No inmates found", fontSize = 16.sp, color = TextGray)
                            }
                        }
                        else -> LazyColumn(
                            modifier = Modifier
                                .width(tableWidth)
                                .weight(1f)
                        ) {
                            listItems(prisoners, key = { it.inmateId }) { prisoner ->
                                ManagePrisonersTableRow(
                                    prisoner = prisoner,
                                    isDeleting = deletingPrisonerId == prisoner.inmateId,
                                    isToggling = togglingPrisonerId == prisoner.inmateId,
                                    onClick = { onPrisonerClick(prisoner.inmateId) },
                                    onEditClick = { onEditClick(prisoner.inmateId) },
                                    onToggleActive = { onToggleActive(prisoner.inmateId, it) },
                                    onManageContactsClick = { onManageContactsClick(prisoner.inmateId) },
                                    onBiometricsClick = {
                                        onBiometricsClick(prisoner.inmateId, prisoner.displayName)
                                    },
                                    onResetPinClick = { onResetPinClick(prisoner) },
                                    walletBalancePaise = walletBalances[prisoner.inmateId],
                                    onLedgerClick = { onLedgerClick(prisoner) },
                                    onDeleteClick = {
                                        onDeleteClick(prisoner.inmateId, prisoner.displayName)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                HorizontalDivider(color = Color(0xFFE8ECF1))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagePrisonersSearchCard(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = TextGray,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = PrimaryDarkNavy),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PrimaryNavy),
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text("Search inmates...", color = TextGray, fontSize = 15.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun ManagePrisonersErrorCard(
    error: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MoneyRedBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, contentDescription = null, tint = DangerRed, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = error ?: "", color = DangerRed, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ManagePrisonersTableHeader(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xFFF4F6F9))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text("INMATE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray, modifier = Modifier.weight(2f))
        Text("INMATE NO / DETAILS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray, modifier = Modifier.weight(2f))
        Text("CITY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray, modifier = Modifier.weight(1f))
        Text("STATE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray, modifier = Modifier.weight(1f))
        Text("KIOSK(S)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray, modifier = Modifier.weight(1.5f))
        Text("WALLET", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray, modifier = Modifier.weight(1.1f))
        Text("STATUS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray, modifier = Modifier.weight(0.8f))
        Text("ACTIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextGray, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun ManagePrisonersTableRow(
    prisoner: Prisoner,
    isDeleting: Boolean,
    isToggling: Boolean,
    walletBalancePaise: Double?,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onManageContactsClick: () -> Unit,
    onBiometricsClick: () -> Unit,
    onResetPinClick: () -> Unit,
    onLedgerClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = prisoner.status.equals("active", ignoreCase = true)
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.weight(2f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = PrimaryNavy.copy(alpha = 0.12f),
                modifier = Modifier.size(36.dp)
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
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = prisoner.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = prisonerIdLine(prisoner),
            fontSize = 12.sp,
            color = TextGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f)
        )
        Text(
            text = prisoner.city?.takeIf { it.isNotBlank() } ?: prisoner.district ?: "—",
            fontSize = 12.sp,
            color = PrimaryDarkNavy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = prisoner.state?.takeIf { it.isNotBlank() } ?: "—",
            fontSize = 12.sp,
            color = PrimaryDarkNavy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = prisonerKioskLabel(prisoner),
            fontSize = 12.sp,
            color = PrimaryDarkNavy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f)
        )
        Box(modifier = Modifier.weight(1.1f)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE8F5EE))
                    .clickable(onClick = onLedgerClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = formatPaise(walletBalancePaise),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
            }
        }
        Box(modifier = Modifier.weight(0.8f), contentAlignment = Alignment.CenterStart) {
            if (isToggling) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = PrimaryNavy)
            } else {
                Switch(
                    checked = isActive,
                    onCheckedChange = onToggleActive,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentGreen,
                        checkedBorderColor = AccentGreen,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFCFD8DC),
                        uncheckedBorderColor = Color(0xFFB0BEC5)
                    )
                )
            }
        }
        Row(
            modifier = Modifier.weight(2f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onManageContactsClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Group, contentDescription = "Contacts", tint = PrimaryNavy, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onBiometricsClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Fingerprint, contentDescription = "Biometrics", tint = PrimaryNavy, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onEditClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = PrimaryNavy, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onResetPinClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.LockReset, contentDescription = "Reset PIN", tint = WarningOrange, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDeleteClick, enabled = !isDeleting, modifier = Modifier.size(36.dp)) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = DangerRed)
                } else {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun prisonerKioskLabel(prisoner: Prisoner): String {
    if (prisoner.allKiosks) return "All kiosks"
    val name = prisoner.kioskName?.takeIf { it.isNotBlank() }
    if (!name.isNullOrBlank()) return name
    val ids = prisoner.assignedKioskIdsOrLegacy
    return when {
        ids.isEmpty() -> "—"
        ids.size == 1 -> ids.first()
        else -> "${ids.first()} +${ids.size - 1}"
    }
}

private fun formatPaise(paise: Double?): String {
    if (paise == null) return "—"
    return String.format(Locale.getDefault(), "₹%.2f", paise / 100.0)
}

@Composable
private fun InmateLedgerDialog(
    prisoner: Prisoner,
    state: InmateLedgerUiState,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 720.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PrimaryNavy)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wallet Ledger",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${prisoner.displayName}  •  ${prisoner.prisonerNumber ?: prisoner.inmateId}",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }

                when (state) {
                    InmateLedgerUiState.Idle,
                    InmateLedgerUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PrimaryNavy)
                        }
                    }

                    is InmateLedgerUiState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = state.message, color = DangerRed, fontSize = 14.sp)
                        }
                    }

                    is InmateLedgerUiState.Success -> {
                        val statement = state.statement
                        val currentBalance = statement.wallet.safeBalance() ?: 0.0
                        val transactions = statement.transactions.orEmpty()
                        val runningRows = remember(statement) {
                            var running = currentBalance
                            transactions
                                .sortedByDescending { parseLedgerTime(it.timestamp) }
                                .map { transaction ->
                                    val row = transaction to running
                                    running -= ledgerSignedAmount(transaction)
                                    row
                                }
                        }
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFF4F8FB))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Current balance", fontSize = 12.sp, color = TextGray)
                                    Text(
                                        text = formatPaise(currentBalance),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryDarkNavy
                                    )
                                }
                                Text(
                                    text = "${transactions.size} entries",
                                    fontSize = 12.sp,
                                    color = TextGray
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            if (runningRows.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No wallet activity for this inmate", color = TextGray, fontSize = 13.sp)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 500.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listItems(runningRows, key = { (transaction, _) ->
                                        "${transaction.transactionId}-${transaction.timestamp}"
                                    }) { (transaction, balanceAfter) ->
                                        LedgerEntryRow(transaction, balanceAfter)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerEntryRow(transaction: WalletTransaction, balanceAfter: Double) {
    val signedAmount = ledgerSignedAmount(transaction)
    val isCredit = signedAmount >= 0
    val type = transaction.type?.lowercase(Locale.getDefault()).orEmpty()
    val detailParts = buildList {
        transaction.sourceName?.takeIf { it.isNotBlank() }?.let { add(it) }
        transaction.callDetails?.type?.takeIf { it.isNotBlank() }?.let { add("${it.replaceFirstChar { c -> c.uppercaseChar() }} call") }
        transaction.location?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    val timeLabel = formatLedgerTime(transaction.timestamp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF8FAFC))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isCredit) Color(0xFFE8F5EE) else MoneyRedBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isCredit) "+" else "-",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isCredit) AccentGreen else DangerRed
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ledgerSourceLabel(transaction),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )
            Text(
                text = detailParts.joinToString("  •  ").ifBlank { transaction.description ?: type },
                fontSize = 11.sp,
                color = TextGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (type == "charge" && transaction.callDetails != null) {
                val call = transaction.callDetails
                Text(
                    text = buildString {
                        append("Call details: ")
                        append(call.type?.takeIf { it.isNotBlank() } ?: "call")
                        call.contactName?.takeIf { it.isNotBlank() }?.let { append(" with ").append(it) }
                        call.startTime?.let { append(" • ").append(formatLedgerTime(it)) }
                    },
                    fontSize = 11.sp,
                    color = PrimaryNavy
                )
            }
            Text(
                text = "Balance after: ${formatPaise(balanceAfter)}",
                fontSize = 11.sp,
                color = TextGray
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatSignedPaise(signedAmount),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isCredit) AccentGreen else DangerRed
            )
            Text(text = timeLabel, fontSize = 10.sp, color = TextGray)
        }
    }
}

private fun ledgerSignedAmount(transaction: WalletTransaction): Double {
    val amount = Math.abs(runCatching { transaction.amount }.getOrNull() ?: 0.0)
    return if (transaction.type?.equals("charge", ignoreCase = true) == true) -amount else amount
}

private fun ledgerSourceLabel(transaction: WalletTransaction): String {
    val source = transaction.sourceName?.takeIf { it.isNotBlank() }
    if (!source.isNullOrBlank()) return source
    return when (transaction.type?.lowercase(Locale.getDefault())) {
        "recharge" -> "Wallet recharge"
        "charge" -> transaction.callDetails?.contactName?.let { "Call with $it" } ?: "Call charge"
        else -> transaction.description?.takeIf { it.isNotBlank() } ?: transaction.type ?: "Transaction"
    }
}

private fun formatSignedPaise(paise: Double): String {
    val sign = if (paise >= 0) "+" else "-"
    return "$sign${formatPaise(Math.abs(paise))}"
}

private fun parseLedgerTime(timestamp: String?): Long {
    if (timestamp.isNullOrBlank()) return 0L
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX"
    )
    patterns.forEach { pattern ->
        try {
            SimpleDateFormat(pattern, Locale.getDefault()).parse(timestamp)?.let { return it.time }
        } catch (_: Exception) {
        }
    }
    return 0L
}

private fun formatLedgerTime(timestamp: String?): String {
    val millis = parseLedgerTime(timestamp)
    if (millis == 0L) return "—"
    return try {
        SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(java.util.Date(millis))
    } catch (_: Exception) {
        timestamp ?: "—"
    }
}

private fun prisonerIdLine(prisoner: Prisoner): String = buildString {
    append("Inmate no: ")
    append(prisoner.prisonerNumber ?: prisoner.inmateId)
    val gender = prisoner.gender
    if (!gender.isNullOrBlank()) {
        append(" - ")
        append(gender.replaceFirstChar { it.uppercaseChar() })
    }
    val age = prisoner.age
    if (age != null) {
        append(" - ")
        append(age)
        append("years")
    }
}

@Composable
private fun PrisonerCard(
    prisoner: Prisoner,
    isDeleting: Boolean = false,
    isToggling: Boolean = false,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onManageContactsClick: () -> Unit,
    onBiometricsClick: () -> Unit,
    onResetPinClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val isActive = prisoner.status.equals("active", ignoreCase = true)
    val initial = prisoner.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val idLine = prisonerIdLine(prisoner)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with initial
                Surface(
                    shape = CircleShape,
                    color = if (isActive) PrimaryNavy.copy(alpha = 0.1f) else Color(0xFF90A4AE).copy(alpha = 0.2f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isActive) PrimaryNavy else TextGray
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Name + number with gender/age in the same style
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = prisoner.displayName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDarkNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = idLine,
                        fontSize = 13.sp,
                        color = TextGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Active / inactive toggle
                if (isToggling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = PrimaryNavy
                    )
                } else {
                    Switch(
                        checked = isActive,
                        onCheckedChange = onToggleActive,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentGreen,
                            checkedBorderColor = AccentGreen,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFCFD8DC),
                            uncheckedBorderColor = Color(0xFFB0BEC5)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onManageContactsClick) {
                        Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    TextButton(onClick = onBiometricsClick) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = PrimaryNavy,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onResetPinClick) {
                        Icon(
                            imageVector = Icons.Default.LockReset,
                            contentDescription = "Reset PIN",
                            tint = WarningOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onDeleteClick,
                        enabled = !isDeleting
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = DangerRed
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = DangerRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- PREVIEWS ---

@Preview(name = "Mobile View", device = "spec:width=360dp,height=800dp", showBackground = true)
@Composable
fun PreviewManagePrisonersMobile() {
    PrisonKioskTheme {
        ManagePrisonersContent(
            prisoners = listOf(
                Prisoner(
                    inmateId = "INM123456",
                    firstName = "RAHUL",
                    lastName = "KUMAR",
                    prisonerNumber = "PR-0042",
                    idNumber = "4521 7788 9900",
                    gender = "male",
                    age = 34,
                    district = "Lucknow",
                    status = "active"
                ),
                Prisoner(
                    inmateId = "INM654321",
                    firstName = "AMIT",
                    lastName = "SHARMA",
                    prisonerNumber = "PR-0043",
                    idNumber = "7781 2233 4455",
                    gender = "male",
                    age = 28,
                    district = "Kanpur",
                    status = "suspended"
                ),
                Prisoner(
                    inmateId = "INM999888",
                    firstName = "VIJAY",
                    lastName = "SINGH",
                    prisonerNumber = "PR-0044",
                    idNumber = "3390 5566 1122",
                    gender = "male",
                    age = 41,
                    district = "Varanasi",
                    status = "active"
                )
            ),
            searchQuery = "",
            onBackClick = {},
            onPrisonerClick = {},
            onToggleActive = { _, _ -> },
            onBiometricsClick = { _, _ -> },
            onDeleteClick = { _, _ -> },
            onSearchQueryChange = {}
        )
    }
}
