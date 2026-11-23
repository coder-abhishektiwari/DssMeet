package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.BorderColor
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.SuccessGreen

import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.LightBg
import com.dssmeet.kiosk.ui.theme.MoneyRedBg

import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.core.ReportPrinter
import com.dssmeet.kiosk.models.report.CallsMisReport
import com.dssmeet.kiosk.models.report.MisDuration
import com.dssmeet.kiosk.models.report.MisSummary
import com.dssmeet.kiosk.ui.components.KioskTopBar
import com.dssmeet.kiosk.ui.theme.PrisonKioskTheme
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DISPLAY_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

@Composable
fun MisReportScreen(
    windowSizeClass: WindowSizeClass,
    onBackClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    viewModel: MisReportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    MisReportContent(
        state = state,
        onBackClick = onBackClick,
        onLogoutClick = onLogoutClick,
        onPresetClick = viewModel::applyPreset,
        onFromSelected = viewModel::setFrom,
        onToSelected = viewModel::setTo,
        onGenerate = viewModel::generate,
        onPrint = { text -> ReportPrinter.print(context, "MIS Calls Report", text) }
    )
}

@Composable
fun MisReportContent(
    state: MisReportViewModel.UiState,
    onBackClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    onPresetClick: (MisReportViewModel.Preset) -> Unit,
    onFromSelected: (Long) -> Unit,
    onToSelected: (Long) -> Unit,
    onGenerate: () -> Unit,
    onPrint: (String) -> Unit
) {
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

    if (showFromPicker) {
        ReportDatePicker(
            initialDate = state.from,
            title = "From",
            onConfirmed = { millis ->
                showFromPicker = false
                if (millis != null) onFromSelected(millis)
            },
            onDismissed = { showFromPicker = false }
        )
    }
    if (showToPicker) {
        ReportDatePicker(
            initialDate = state.to,
            title = "To",
            onConfirmed = { millis ->
                showToPicker = false
                if (millis != null) onToSelected(millis)
            },
            onDismissed = { showToPicker = false }
        )
    }
    Scaffold(
        topBar = {
            KioskTopBar(
                title = "MIS Calls Report",
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
            val wide = maxWidth >= 900.dp
            // MisReportContent ke wide (`maxWidth >= 900.dp`) branch mein:
if (wide) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top
    ) {
        MisReportFilterColumn(
            state = state,
            modifier = Modifier
                .weight(0.4f, fill = false)
                .widthIn(min = 340.dp, max = 460.dp)
                .fillMaxHeight(),
            modernFilters = true,
            onPresetClick = onPresetClick,
            onFromClick = { showFromPicker = true },
            onToClick = { showToPicker = true },
            onGenerate = onGenerate,
            onPrint = onPrint
        )
        MisReportPreviewPanel(
            reportText = state.reportText,
            isLoading = state.isLoading,
            modifier = Modifier
                .weight(0.6f, fill = false)
                .fillMaxHeight()
        )
    }
} else {
                com.dssmeet.kiosk.ui.components.KioskResponsiveBox(
                    maxWidth = 900.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    MisReportFilterColumn(
                        state = state,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        modernFilters = false,
                        onPresetClick = onPresetClick,
                        onFromClick = { showFromPicker = true },
                        onToClick = { showToPicker = true },
                        onGenerate = onGenerate,
                        onPrint = onPrint
                    )
                }
            }
        }
    }
}

@Composable
private fun MisReportFilterColumn(
    state: MisReportViewModel.UiState,
    modifier: Modifier = Modifier,
    modernFilters: Boolean,
    onPresetClick: (MisReportViewModel.Preset) -> Unit,
    onFromClick: () -> Unit,
    onToClick: () -> Unit,
    onGenerate: () -> Unit,
    onPrint: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (modernFilters) {
            item { MisReportModernFiltersCard(state, onPresetClick, onFromClick, onToClick) }
            item { MisReportActions(state, onGenerate, onPrint) }
            if (state.error != null && !state.isLoading) {
                item { MisReportErrorCard(state.error) }
            }
        } else {
            item { MisReportFiltersCard(state, onPresetClick, onFromClick, onToClick) }
            item { MisReportGenerateButton(state, onGenerate) }
            if (state.error != null && !state.isLoading) {
                item { MisReportErrorCard(state.error) }
            }
            if (state.reportText != null) {
                item { MisReportPreviewCard(state.reportText) }
                item { MisReportPrintButton(state.reportText, onPrint) }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun MisReportPreviewPanel(
    reportText: String?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 320.dp, max = 680.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(3.dp)
        ) {
            Column(modifier = Modifier.wrapContentSize()) {
                Row(
                    modifier = Modifier
                        .widthIn(min = 320.dp)
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "REPORT PREVIEW",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryDarkNavy
                        )
                        Text(text = "80 mm receipt", fontSize = 12.sp, color = TextGray)
                    }
                    if (reportText != null) {
                        Spacer(modifier = Modifier.width(16.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFE6F7EE)
                        ) {
                            Text(
                                text = "READY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF17875B),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = BorderColor, thickness = 1.dp)

                if (reportText != null) {
                    Box(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF8FAFC),
                            modifier = Modifier.wrapContentWidth()
                        ) {
                            Text(
                                text = reportText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                color = PrimaryDarkNavy,
                                softWrap = false,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.widthIn(min = 320.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                tint = TextGray,
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = if (isLoading) "Generating report…" else "Report preview",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryDarkNavy
                            )
                            Text(
                                text = if (isLoading) {
                                    "Please wait while the report is prepared."
                                } else {
                                    "Select a period and generate the report to see it here."
                                },
                                fontSize = 13.sp,
                                color = TextGray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MisReportModernFiltersCard(
    state: MisReportViewModel.UiState,
    onPresetClick: (MisReportViewModel.Preset) -> Unit,
    onFromClick: () -> Unit,
    onToClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PrimaryNavy.copy(alpha = 0.1f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            tint = PrimaryNavy,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Report setup",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDarkNavy
                    )
                    Text(
                        text = "Choose a period, then generate or print.",
                        fontSize = 13.sp,
                        color = TextGray
                    )
                }
            }

            HorizontalDivider(color = BorderColor, thickness = 1.dp)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "QUICK SELECT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextGray
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetChip("Today", MisReportViewModel.Preset.TODAY, state.preset, onPresetClick)
                    PresetChip("Yesterday", MisReportViewModel.Preset.YESTERDAY, state.preset, onPresetClick)
                    PresetChip("This week", MisReportViewModel.Preset.THIS_WEEK, state.preset, onPresetClick)
                    PresetChip("This month", MisReportViewModel.Preset.THIS_MONTH, state.preset, onPresetClick)
                    PresetChip("Custom", MisReportViewModel.Preset.CUSTOM, state.preset, onPresetClick)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MisModernDateField(
                    label = "FROM DATE",
                    value = state.from.format(DISPLAY_DAY),
                    onClick = onFromClick,
                    modifier = Modifier.weight(1f)
                )
                MisModernDateField(
                    label = "TO DATE",
                    value = state.to.format(DISPLAY_DAY),
                    onClick = onToClick,
                    modifier = Modifier.weight(1f)
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F7FB))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = PrimaryNavy, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Report uses calls recorded between these two dates.",
                        fontSize = 12.sp,
                        color = TextGray
                    )
                }
            }
        }
    }
}

@Composable
private fun MisModernDateField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null, tint = PrimaryNavy, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextGray)
                Text(
                    text = value,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDarkNavy,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MisReportFiltersCard(
    state: MisReportViewModel.UiState,
    onPresetClick: (MisReportViewModel.Preset) -> Unit,
    onFromClick: () -> Unit,
    onToClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "REPORT PERIOD",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetChip("Today", MisReportViewModel.Preset.TODAY, state.preset, onPresetClick)
                PresetChip("Yesterday", MisReportViewModel.Preset.YESTERDAY, state.preset, onPresetClick)
                PresetChip("This week", MisReportViewModel.Preset.THIS_WEEK, state.preset, onPresetClick)
                PresetChip("This month", MisReportViewModel.Preset.THIS_MONTH, state.preset, onPresetClick)
                PresetChip("Custom", MisReportViewModel.Preset.CUSTOM, state.preset, onPresetClick)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DateField(
                    label = "From",
                    value = state.from.format(DISPLAY_DAY),
                    onClick = onFromClick,
                    modifier = Modifier.weight(1f)
                )
                DateField(
                    label = "To",
                    value = state.to.format(DISPLAY_DAY),
                    onClick = onToClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MisReportGenerateButton(
    state: MisReportViewModel.UiState,
    onGenerate: () -> Unit
) {
    Button(
        onClick = onGenerate,
        enabled = !state.isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Generating...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        } else {
            Icon(Icons.Default.DateRange, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Generate Report", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MisReportActions(
    state: MisReportViewModel.UiState,
    onGenerate: () -> Unit,
    onPrint: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onGenerate,
            enabled = !state.isLoading,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Generating...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.DateRange, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generate Report", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Button(
            onClick = { state.reportText?.let(onPrint) },
            enabled = state.reportText != null,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SuccessGreen,
                disabledContainerColor = Color(0xFFB7D9C6)
            )
        ) {
            Icon(Icons.Default.Print, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Print Report", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun MisReportErrorCard(error: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MoneyRedBg)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = Icons.Default.Error, contentDescription = null, tint = DangerRed)
            Text(
                text = error ?: "",
                fontSize = 14.sp,
                color = Color(0xFFB71C1C),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MisReportPreviewCard(reportText: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REPORT PREVIEW",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDarkNavy
                )
                Text(text = "80 mm receipt", fontSize = 12.sp, color = TextGray)
            }
            HorizontalDivider(color = BorderColor, thickness = 1.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = reportText ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = PrimaryDarkNavy,
                    softWrap = false,
                    maxLines = 500
                )
            }
        }
    }
}

@Composable
private fun MisReportPrintButton(
    reportText: String?,
    onPrint: (String) -> Unit
) {
    Button(
        onClick = { if (reportText != null) onPrint(reportText) },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
    ) {
        Icon(Icons.Default.Print, contentDescription = null, tint = Color.White)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Print Report",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun PresetChip(
    label: String,
    preset: MisReportViewModel.Preset,
    selected: MisReportViewModel.Preset,
    onPresetClick: (MisReportViewModel.Preset) -> Unit
) {
    FilterChip(
        selected = selected == preset,
        onClick = { onPresetClick(preset) },
        label = { Text(label, fontSize = 13.sp) }
    )
}

@Composable
private fun DateField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextGray
        )
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = PrimaryDarkNavy,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportDatePicker(
    initialDate: LocalDate,
    title: String,
    onConfirmed: (Long?) -> Unit,
    onDismissed: () -> Unit
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )

    DatePickerDialog(
        onDismissRequest = onDismissed,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(min = 320.dp, max = 420.dp)
            .heightIn(max = 580.dp),
        dismissButton = {
            OutlinedButton(
                onClick = onDismissed,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancel", fontWeight = FontWeight.SemiBold, color = PrimaryDarkNavy)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmed(pickerState.selectedDateMillis) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
            ) {
                Text("OK", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Select $title",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
            )
            DatePicker(
                state = pickerState,
                showModeToggle = false
            )
        }
    }
}

// --- PREVIEWS ---

@Preview(name = "Mobile View", device = "spec:width=360dp,height=800dp", showBackground = true)
@Composable
fun PreviewMisReportMobile() {
    PrisonKioskTheme {
        val report = CallsMisReport(
            kioskId = "KIOSK-A1B2C3D4",
            prisonName = "Central Jail Pune",
            summary = MisSummary(
                totalCalls = 428,
                completed = 361,
                notAnswered = 52,
                forceEnded = 15,
                video = 244,
                audio = 184
            ),
            duration = MisDuration(
                connectedCalls = 361,
                totalTalkSeconds = 1_485_000,
                averageSeconds = 432,
                longestSeconds = 1_785,
                shortestSeconds = 8
            )
        )
        MisReportContent(
            state = MisReportViewModel.UiState(
                preset = MisReportViewModel.Preset.THIS_WEEK,
                from = LocalDate.of(2025, 10, 1),
                to = LocalDate.of(2025, 10, 7),
                report = report,
                reportText = com.dssmeet.kiosk.core.MisReportFormatter.build(report, "EMP-SA001")
            ),
            onBackClick = {},
            onPresetClick = {},
            onFromSelected = {},
            onToSelected = {},
            onGenerate = {},
            onPrint = {}
        )
    }
}