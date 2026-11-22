package com.dssmeet.kiosk.ui.dashboard

import com.dssmeet.kiosk.ui.theme.ControlBtnBg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.dssmeet.kiosk.core.BaseViewModel
import com.dssmeet.kiosk.core.UiState
import com.dssmeet.kiosk.models.inmate.InmateProfile
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.repository.AuthRepository
import com.dssmeet.kiosk.repository.InmateRepository
import com.dssmeet.kiosk.ui.components.KioskErrorState
import com.dssmeet.kiosk.ui.components.KioskLoadingState
import com.dssmeet.kiosk.ui.theme.LightBg
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.TextDark
import com.dssmeet.kiosk.ui.theme.TextGray
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InmateProfileDetailsViewModel @Inject constructor(
    private val inmateRepository: InmateRepository,
    private val authRepository: AuthRepository
) : BaseViewModel() {

    private val _state = MutableStateFlow<UiState<InmateProfile>>(UiState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            val inmateId = authRepository.getInmateId()
            if (inmateId.isNullOrBlank()) {
                _state.value = UiState.Error("No active session found. Please login again.")
                return@launch
            }
            // Profile is cached (30s) from the dashboard load, so this is
            // normally instant; a cache miss just waits out one fetch.
            inmateRepository.getProfile(inmateId).collect { result ->
                when (result) {
                    is NetworkResult.Success -> _state.value = UiState.Success(result.data)
                    is NetworkResult.Failure ->
                        _state.value = UiState.Error(result.error?.message ?: "Failed to load details")
                    else -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InmateProfileDetailsScreen(
    onBackClick: () -> Unit,
    viewModel: InmateProfileDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            com.dssmeet.kiosk.ui.components.KioskTopBar(
                title = "Inmate Details",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            com.dssmeet.kiosk.ui.components.KioskBottomActionBar(onBackClick = onBackClick)
        },
        containerColor = LightBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val s = state) {
                is UiState.Loading -> KioskLoadingState()
                is UiState.Error -> KioskErrorState(
                    message = s.message,
                    onRetry = { viewModel.load() }
                )
                is UiState.Success -> InmateDetailsContent(profile = s.data)
                else -> Unit
            }
        }
    }
}

@Composable
private fun InmateDetailsContent(profile: InmateProfile) {
    val rfidValue = profile.rfidCardNumber
        ?: if (profile.rfidRegistered) "Registered" else "Not registered"

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideScreen = maxWidth >= 600.dp
        val contentWidth = if (wideScreen) {
            minOf(maxWidth - 96.dp, 900.dp)
        } else {
            minOf(maxWidth - 32.dp, 560.dp)
        }.coerceAtLeast(240.dp)
        val photoSize = if (wideScreen) 140.dp else 88.dp
        val nameSize = if (wideScreen) 28.sp else 20.sp
        val numberSize = if (wideScreen) 16.sp else 13.sp
        val labelSize = if (wideScreen) 14.sp else 12.sp
        val valueSize = if (wideScreen) 18.sp else 14.sp
        val cardPadding = if (wideScreen) 24.dp else 20.dp

        Column(
            modifier = Modifier
                .width(contentWidth)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                val profilePhoto = @Composable {
                    AsyncImage(
                        model = profile.photoUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(photoSize)
                            .clip(RoundedCornerShape(20.dp))
                            .background(ControlBtnBg),
                        contentScale = ContentScale.Crop,
                        placeholder = rememberVectorPainter(Icons.Default.Person),
                        error = rememberVectorPainter(Icons.Default.Person)
                    )
                }
                val profileText = @Composable {
                    Text(
                        text = profile.displayName,
                        fontSize = nameSize,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDarkNavy,
                        textAlign = if (wideScreen) TextAlign.Start else TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Inmate No: ${profile.displayNumber}",
                        fontSize = numberSize,
                        color = TextGray,
                        textAlign = if (wideScreen) TextAlign.Start else TextAlign.Center
                    )
                }

                if (wideScreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(cardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        profilePhoto()
                        Column(modifier = Modifier.weight(1f)) {
                            profileText()
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(cardPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        profilePhoto()
                        Spacer(modifier = Modifier.height(10.dp))
                        profileText()
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                val details = listOf(
                    "Facility" to profile.facility,
                    "Gender" to titleCase(profile.gender),
                    "Age" to profile.age,
                    "District" to titleCase(profile.district),
                    "State" to titleCase(profile.state),
                    "Religion" to titleCase(profile.religion),
                    "Nationality" to titleCase(profile.nationality),
                    "RFID Card" to rfidValue,
                    "Date of Admission" to formatAdmissionDate(profile.dateOfAdmission)
                )
                val detailRow: @Composable (Pair<String, String?>) -> Unit = { item ->
                    DetailRow(item.first, item.second, labelSize, valueSize)
                }

                if (wideScreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(cardPadding),
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (wideScreen) 20.dp else 14.dp)
                        ) {
                            details.take(5).forEach { detailRow(it) }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (wideScreen) 20.dp else 14.dp)
                        ) {
                            details.drop(5).forEach { detailRow(it) }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        details.forEach { detailRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String?,
    labelSize: androidx.compose.ui.unit.TextUnit,
    valueSize: androidx.compose.ui.unit.TextUnit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = labelSize,
            fontWeight = FontWeight.Medium,
            color = TextGray
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "—",
            fontSize = valueSize,
            fontWeight = FontWeight.SemiBold,
            color = TextDark
        )
    }
}

private fun titleCase(value: String?): String? =
    value?.takeIf { it.isNotBlank() }?.replaceFirstChar { c ->
        if (c.isLowerCase()) c.titlecase() else c.toString()
    }

private fun formatAdmissionDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        if (Regex("""\d{4}-\d{2}-\d{2}""").matches(raw)) {
            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(raw)
            if (parsed != null) {
                java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(parsed)
            } else raw
        } else raw
    } catch (_: Exception) {
        raw
    }
}
