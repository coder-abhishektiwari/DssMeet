package com.dssmeet.kiosk.ui.dashboard

import androidx.lifecycle.viewModelScope
import com.dssmeet.kiosk.core.BaseViewModel
import com.dssmeet.kiosk.core.JailBalanceSync
import com.dssmeet.kiosk.core.UiState
import com.dssmeet.kiosk.models.contacts.Contact
import com.dssmeet.kiosk.models.inmate.InmateProfile
import com.dssmeet.kiosk.models.inmate.InmateBalance
import com.dssmeet.kiosk.models.call.CallHistory
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.repository.AuthRepository
import com.dssmeet.kiosk.repository.ContactRepository
import com.dssmeet.kiosk.repository.InmateRepository
import com.dssmeet.kiosk.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val inmateRepository: InmateRepository,
    private val contactRepository: ContactRepository,
    private val callRepository: CallRepository,
    private val authRepository: AuthRepository,
    private val jailBalanceSync: JailBalanceSync
) : BaseViewModel() {

    private val _dashboardState = MutableStateFlow<UiState<DashboardData>>(UiState.Loading)
    val dashboardState = _dashboardState.asStateFlow()

    private val _inmateProfile = MutableStateFlow<InmateProfile?>(null)
    val inmateProfile = _inmateProfile.asStateFlow()

    private val _inmateBalance = MutableStateFlow<InmateBalance?>(null)
    val inmateBalance = _inmateBalance.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts = _contacts.asStateFlow()

    private val _callHistory = MutableStateFlow<List<CallHistory>>(emptyList())
    val callHistory = _callHistory.asStateFlow()

    /** Single-source jail balance shown in the dashboard header. */
    val jailBalance = jailBalanceSync.balance

    init {
        loadDashboardData()
        viewModelScope.launch { jailBalanceSync.refresh() }
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _dashboardState.value = UiState.Loading
            val inmateId = authRepository.getInmateId()

            if (inmateId == null) {
                _dashboardState.value = UiState.Error("No active session found. Please login again.")
                return@launch
            }

            combine(
                inmateRepository.getProfile(inmateId),
                inmateRepository.getBalance(inmateId),
                contactRepository.getContacts(inmateId),
                callRepository.getCallHistory(inmateId)
            ) { profile, balance, contacts, history ->
                val p = (profile as? NetworkResult.Success)?.data
                val b = (balance as? NetworkResult.Success)?.data
                val c = (contacts as? NetworkResult.Success)?.data ?: emptyList()
                val h = (history as? NetworkResult.Success)?.data ?: emptyList()

                if (p != null) {
                    // Partial data is fine: profile loaded, the rest is best-effort.
                    _inmateProfile.value = p
                    _inmateBalance.value = b
                    _contacts.value = c.take(5) // Show top 5 on dashboard
                    _callHistory.value = h
                    UiState.Success(DashboardData(p, b, c, h))
                } else {
                    // Still waiting on pending reads -> keep loading, don't flicker Error.
                    val anyLoading = profile is NetworkResult.Loading ||
                        balance is NetworkResult.Loading ||
                        contacts is NetworkResult.Loading ||
                        history is NetworkResult.Loading
                    val profileFailed = profile is NetworkResult.Failure
                    when {
                        profileFailed -> {
                            val msg = (profile as NetworkResult.Failure).error?.message
                                ?: (balance as? NetworkResult.Failure)?.error?.message
                                ?: "Failed to load dashboard data"
                            UiState.Error(msg)
                        }
                        anyLoading -> UiState.Loading
                        else -> UiState.Error("Failed to load dashboard data")
                    }
                }
            }.collect {
                _dashboardState.value = it
            }
        }
    }

    /** Refresh header balance + dashboard data. */
    fun refreshAll() {
        viewModelScope.launch {
            jailBalanceSync.refresh()
            loadDashboardData()
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout().collect { }
        }
    }

    data class DashboardData(
        val profile: InmateProfile,
        val balance: InmateBalance?,
        val contacts: List<Contact>,
        val callHistory: List<CallHistory>
    )
}
