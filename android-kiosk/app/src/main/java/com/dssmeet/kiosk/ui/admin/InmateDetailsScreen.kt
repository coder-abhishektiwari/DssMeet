package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy

import com.dssmeet.kiosk.ui.theme.AccentGreen
import com.dssmeet.kiosk.ui.theme.GraySurface
import com.dssmeet.kiosk.ui.theme.WarningOrange

import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.GreenActiveBg
import com.dssmeet.kiosk.ui.theme.LightBg
import com.dssmeet.kiosk.ui.theme.MoneyRedBg

import com.dssmeet.kiosk.ui.theme.TextGray
import com.dssmeet.kiosk.ui.theme.WarningBg

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.models.admin.Prisoner
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.ui.components.KioskLoadingState
import com.dssmeet.kiosk.ui.components.KioskTopBar

/**
 * Read-only inmate profile: every field on one screen, opened by tapping an
 * inmate card on the Manage Inmates list. Editing lives behind the button.
 */
@Composable
fun InmateDetailsScreen(
    prisonerId: String,
    windowSizeClass: WindowSizeClass,
    onBackClick: () -> Unit,
    onEditClick: (String) -> Unit,
    viewModel: InmateDetailsViewModel = hiltViewModel()
) {
    val prisonerResult by viewModel.prisoner.collectAsState()

    LaunchedEffect(prisonerId) {
        viewModel.loadPrisoner(prisonerId)
    }

    Scaffold(
        topBar = {
            KioskTopBar(
                title = "Inmate Details",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        containerColor = LightBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when (val result = prisonerResult) {
                is NetworkResult.Loading -> {
                    KioskLoadingState(modifier = Modifier.align(Alignment.Center))
                }
                is NetworkResult.Success -> {
                    InmateDetailsContent(
                        prisoner = result.data,
                        onEditClick = { onEditClick(prisonerId) }
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
        }
    }
}

private fun display(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    return value
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )
            content()
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = display(value),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = PrimaryDarkNavy
        )
    }
}

@Composable
private fun DetailRow(labelA: String, valueA: String?, labelB: String, valueB: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DetailItem(labelA, valueA, modifier = Modifier.weight(1f))
        DetailItem(labelB, valueB, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusChip(status: String) {
    val label = status.uppercase()
    val container = when (label) {
        "ACTIVE" -> GreenActiveBg
        "RESTRICTED" -> WarningBg
        "SUSPENDED" -> MoneyRedBg
        else -> GraySurface
    }
    val content = when (label) {
        "ACTIVE" -> AccentGreen
        "RESTRICTED" -> WarningOrange
        "SUSPENDED" -> DangerRed
        else -> Color(0xFF757575)
    }
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun InmateDetailsContent(
    prisoner: Prisoner,
    onEditClick: () -> Unit
) {
    val initial = prisoner.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val gender = prisoner.gender?.replaceFirstChar { it.uppercaseChar() }
    val rfidCardText = when {
        prisoner.biometricData?.rfidRegistered != true -> "Not registered"
        !prisoner.rfidCardNumber.isNullOrBlank() -> prisoner.rfidCardNumber
        else -> "Registered"
    }
    val fingerprintText =
        if (prisoner.biometricData?.fingerprintRegistered == true) "Registered" else "Not registered"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Profile header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(3.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = PrimaryNavy.copy(alpha = 0.1f),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryNavy
                        )
                    }
                }

                Text(
                    text = prisoner.displayName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDarkNavy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = prisoner.prisonerNumber ?: prisoner.inmateId,
                    fontSize = 13.sp,
                    color = TextGray
                )

                StatusChip(prisoner.status)
            }
        }

        InfoCard("Personal Details") {
            DetailRow(
                "Gender", gender,
                "Age", prisoner.age?.let { "$it yrs" }
            )
            DetailRow(
                "Father/Husband Name", prisoner.fatherName,
                "Mother Name", prisoner.motherName
            )
            DetailRow(
                "Date of Admission", prisoner.dateOfAdmission,
                "Inmate Number", prisoner.prisonerNumber
            )
        }

        InfoCard("Identity") {
            DetailRow(
                "ID Proof", prisoner.idProof,
                "ID Number", prisoner.idNumber
            )
            DetailRow(
                "Religion", prisoner.religion,
                "Nationality", prisoner.nationality
            )
        }

        InfoCard("Address") {
            DetailRow(
                "State", prisoner.state,
                "District", prisoner.district
            )
            DetailItem(
                label = "Address",
                value = prisoner.address
            )
        }

        InfoCard("Status & Access") {
            DetailRow(
                "Status", prisoner.status,
                "Assigned Kiosk", prisoner.assignedKioskId
            )
            DetailRow(
                "RFID Card", rfidCardText,
                "Fingerprint", fingerprintText
            )
            DetailItem(
                label = "Registered On",
                value = prisoner.createdAt?.take(10)
            )
        }

        Button(
            onClick = onEditClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Edit Inmate", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
