package com.dssmeet.kiosk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dssmeet.kiosk.hardware.DeviceInfoProvider
import com.dssmeet.kiosk.core.SessionManager
import com.dssmeet.kiosk.navigation.KioskRoutes
import com.dssmeet.kiosk.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val updateManager: com.dssmeet.kiosk.core.UpdateManager
) : ViewModel() {

    /**
     * The kiosk is on the login screen — the mandatory update gate steps
     * aside so the admin CAN sign in (installing a build is an admin action).
     */
    fun setAtLogin(value: Boolean) {
        updateManager.setAtLogin(value)
    }

    /**
     * Observable state of device authorization.
     * Starts with 'true' to avoid flickering, but will be updated immediately by DataStore.
     */
    val isDeviceAuthorized: StateFlow<Boolean> = authRepository.isDeviceAuthorized()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /**
     * Where an UNVERIFIED kiosk must go instead of login:
     * - registration page (the normal path — device not approved/verified yet)
     * - splash (registration already approved locally — re-run the server gate)
     * - unauthorized lock screen (device cannot identify itself, so the
     *   registration page could never reach the backend, or resolution failed)
     */
    suspend fun unverifiedDestination(): String = try {
        val serial = deviceInfoProvider.getRegistrationDeviceId()
        when {
            serial.isNullOrBlank() -> KioskRoutes.UNAUTHORIZED
            sessionManager.getRegistrationStatus() == "approved" -> KioskRoutes.SPLASH
            else -> KioskRoutes.REGISTRATION
        }
    } catch (e: Exception) {
        KioskRoutes.UNAUTHORIZED
    }

    /** Non-suspend wrapper for callbacks (e.g. LoginScreen's onKioskNotVerified). */
    fun resolveUnverifiedDestination(onResolved: (String) -> Unit) {
        viewModelScope.launch { onResolved(unverifiedDestination()) }
    }
}
