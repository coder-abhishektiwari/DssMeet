package com.dssmeet.kiosk.ui.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.viewModelScope
import com.dssmeet.kiosk.core.BaseViewModel
import com.dssmeet.kiosk.core.Logger
import com.dssmeet.kiosk.core.UiState
import com.dssmeet.kiosk.hardware.FingerprintCaptureState
import com.dssmeet.kiosk.hardware.FingerprintHardwareManager
import com.dssmeet.kiosk.hardware.RfidReaderManager
import com.dssmeet.kiosk.hardware.RfidReaderState
import com.dssmeet.kiosk.models.auth.AdminBootstrapCreateRequest
import com.dssmeet.kiosk.models.auth.AdminProfile
import com.dssmeet.kiosk.models.auth.AdminVerifyPasswordRequest
import com.dssmeet.kiosk.models.auth.LoginRequest
import com.dssmeet.kiosk.models.inmate.InmateProfile
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

enum class LoginStage {
    METHOD_SELECTION, FINGERPRINT_SCANNING, RFID_SCANNING, PRISONER_ID_ENTRY, PIN_ENTRY,
    ADMIN_USERNAME_ENTRY, ADMIN_PIN_ENTRY, ADMIN_CREATE_ENTRY
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val fingerprintHardwareManager: FingerprintHardwareManager,
    private val rfidReaderManager: RfidReaderManager
) : BaseViewModel() {

    private val _loginStage = MutableStateFlow(LoginStage.METHOD_SELECTION)
    val loginStage = _loginStage.asStateFlow()

    private val _identifiedInmate = MutableStateFlow<InmateProfile?>(null)
    val identifiedInmate = _identifiedInmate.asStateFlow()

    private val _identifiedAdmin = MutableStateFlow<AdminProfile?>(null)
    val identifiedAdmin = _identifiedAdmin.asStateFlow()

    /**
     * True while this kiosk's jail has no kiosk admin yet — the welcome screen
     * swaps its "Admin Login" button for "Create Login". Refreshed on every
     * entry to METHOD_SELECTION; a failed check simply leaves the normal
     * Admin Login shown (never blocks signing in).
     */
    private val _needsBootstrap = MutableStateFlow(false)
    val needsBootstrap = _needsBootstrap.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<LoginNavigation>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    val connectedScanner = fingerprintHardwareManager.connectedScanner
    val usbPermissionGranted = fingerprintHardwareManager.hasPermission

    /** Live hardware sessions consumed by the fingerprint / RFID screens. */
    val fingerprintCaptureState = fingerprintHardwareManager.captureState
    val rfidState = rfidReaderManager.state

    private var fingerprintSessionJob: Job? = null
    private var rfidSessionJob: Job? = null

    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable = _isNetworkAvailable.asStateFlow()

    private val connectivityManager: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isNetworkAvailable.value = true
        }

        override fun onLost(network: Network) {
            _isNetworkAvailable.value = false
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            // CRITICAL: when the SAME network loses and regains INTERNET
            // capability (WiFi blip), onAvailable is NOT re-fired — only this
            // callback is. Without it the app stays "offline" forever until
            // restarted.
            _isNetworkAvailable.value =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        refreshBootstrapStatus()
    }

    override fun onCleared() {
        super.onCleared()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        // viewModelScope jobs die with the VM — close any open hardware driver.
        // Owner-gated: if the admin already moved on to another screen (e.g.
        // Add Prisoner took the scanner), this late stop is a no-op there.
        rfidReaderManager.stopSession(HARDWARE_OWNER)
        fingerprintHardwareManager.stopCapture(HARDWARE_OWNER)
    }

    private fun getFriendlyErrorMessage(statusCode: Int?, defaultMessage: String): String {
        return when (statusCode) {
            401, 403 -> "Unauthorized access. Please contact administrator."
            404 -> "Record not found. Please try again."
            else -> defaultMessage // Always show user-friendly message, never raw backend errors
        }
    }

    /** Kiosk is not verified — leave login for the registration/lock gate. */
    private fun redirectUnverified() {
        Logger.w("LoginViewModel: kiosk not verified — leaving login for the registration gate")
        launch { _navigationEvent.emit(LoginNavigation.KioskNotVerified) }
    }

    /** Repo hard-fail (DEVICE_NOT_REGISTERED) inside a collect → redirect, not an error toast. */
    private suspend fun redirectIfDeviceUnverified(result: NetworkResult.Failure): Boolean {
        if (result.error.code == "DEVICE_NOT_REGISTERED") {
            Logger.w("LoginViewModel: repo reports kiosk unverified — leaving login for the registration gate")
            _navigationEvent.emit(LoginNavigation.KioskNotVerified)
            return true
        }
        return false
    }

    fun startFingerprintAuth() {
        _loginStage.value = LoginStage.FINGERPRINT_SCANNING
        fingerprintHardwareManager.startCapture(HARDWARE_OWNER)
        startFingerprintSession()
    }

    /**
     * Hardware path: the external scanner pushes a template → identify.
     * The session stays in Searching until consumed, so a retry after a
     * failed identify is just placing the finger again.
     */
    private fun startFingerprintSession() {
        fingerprintSessionJob?.cancel()
        fingerprintSessionJob = viewModelScope.launch {
            fingerprintHardwareManager.captureState.collect { state ->
                if (state is FingerprintCaptureState.Captured && uiState.value !is UiState.Loading) {
                    fingerprintHardwareManager.acknowledgeCapture()
                    identifyFingerprint(state.template)
                }
            }
        }
    }

    private fun identifyFingerprint(template: String) {
        if (uiState.value is UiState.Loading) return

        launch {
            setLoading()
            authRepository.identifyFingerprint(template.toByteArray(Charsets.UTF_8)).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _identifiedInmate.value = result.data
                        _loginStage.value = LoginStage.PIN_ENTRY
                        setSuccess()
                        cancelBiometricSessions()
                    }
                    is NetworkResult.Failure -> {
                        if (redirectIfDeviceUnverified(result)) return@collect
                        val errorMessage = getFriendlyErrorMessage(
                            statusCode = result.statusCode,
                            defaultMessage = "Fingerprint not recognized. Please try again."
                        )
                        setError(errorMessage)
                        delay(2000.milliseconds)
                        setIdle()
                        // Resume scanning so the user can simply try again.
                        fingerprintHardwareManager.startCapture(HARDWARE_OWNER)
                    }
                    else -> {}
                }
            }
        }
    }

    fun requestUsbPermission() {
        val device = fingerprintHardwareManager.connectedScanner.value
        if (device != null) {
            fingerprintHardwareManager.requestPermission(device)
        }
    }

    fun onPinSubmit(pin: String) {
        val inmateId = _identifiedInmate.value?.inmateId ?: return
        if (uiState.value is UiState.Loading) return

        launch {
            setLoading()

            authRepository.verifyPin(inmateId, pin).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        setSuccess()
                        delay(200.milliseconds) // Wait for session persistence
                        _navigationEvent.emit(LoginNavigation.NavigateToDashboard)
                    }
                    is NetworkResult.Failure -> {
                        if (redirectIfDeviceUnverified(result)) return@collect
                        val errorMessage = getFriendlyErrorMessage(
                            statusCode = result.statusCode,
                            defaultMessage = "Incorrect PIN. Please try again."
                        )
                        setError(errorMessage)
                        delay(1500.milliseconds)
                        setIdle()
                    }
                    else -> {}
                }
            }
        }
    }

    fun onAdminPasswordSubmit(password: String) {
        val adminId = _identifiedAdmin.value?.adminId
        if (uiState.value is UiState.Loading) return

        val kioskId = authRepository.getVerifiedKiosk()?.kioskId
        if (kioskId.isNullOrBlank()) {
            redirectUnverified()
            return
        }

        Logger.d("LoginViewModel: Admin password submission started for adminId: $adminId, kioskId: $kioskId")

        if (adminId == null) {
            Logger.e("LoginViewModel: Cannot submit Admin password - adminId is NULL")
            setError("Session expired. Please start again.")
            _loginStage.value = LoginStage.METHOD_SELECTION
            return
        }

        launch {
            setLoading()

            val request = AdminVerifyPasswordRequest(adminId, password, kioskId)
            authRepository.adminVerifyPassword(request).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        Logger.i("LoginViewModel: Admin login SUCCESS")
                        setSuccess()
                        delay(200.milliseconds) // Wait for session persistence
                        _navigationEvent.emit(LoginNavigation.NavigateToAdminDashboard)
                    }
                    is NetworkResult.Failure -> {
                        Logger.w("LoginViewModel: Admin login FAILED: ${result.error.message}")
                        val errorMessage = getFriendlyErrorMessage(
                            statusCode = result.statusCode,
                            defaultMessage = "Incorrect password. Please try again."
                        )
                        setError(errorMessage)
                        delay(1500.milliseconds)
                        setIdle()
                    }
                    else -> {}
                }
            }
        }
    }

    fun startAdminAuth() {
        _loginStage.value = LoginStage.ADMIN_USERNAME_ENTRY
        _identifiedAdmin.value = null
    }

    /** Welcome screen needs to know which admin button to show — asked silently. */
    fun refreshBootstrapStatus() {
        launch {
            authRepository.adminBootstrapStatus().collect { result ->
                if (result is NetworkResult.Success) {
                    Logger.d("LoginViewModel: needsBootstrap=${result.data.needsBootstrap}")
                    _needsBootstrap.value = result.data.needsBootstrap
                }
                // Any failure keeps the current value — the default (false) is the
                // normal Admin Login, so a flaky status check never blocks sign-in.
            }
        }
    }

    fun startAdminCreate() {
        Logger.d("LoginViewModel: opening first-admin create form")
        _loginStage.value = LoginStage.ADMIN_CREATE_ENTRY
        _identifiedAdmin.value = null
    }

    /**
     * Submit the jail's first kiosk admin — same fields and rules as the warden
     * dashboard's "Add Kiosk Admin" form. On success the backend session in the
     * response signs the creator straight in, so navigation goes straight to the
     * admin dashboard.
     */
    fun onAdminCreateSubmit(name: String, employeeId: String, email: String, password: String) {
        if (uiState.value is UiState.Loading) return

        val kioskId = authRepository.getVerifiedKiosk()?.kioskId
        if (kioskId.isNullOrBlank()) {
            redirectUnverified()
            return
        }

        val trimmedName = name.trim()
        val trimmedEmployeeId = employeeId.trim()
        val trimmedEmail = email.trim()
        // The form applies the same rules live — this is the belt to its braces,
        // not the primary validation.
        if (trimmedName.isBlank() || trimmedEmployeeId.isBlank() || password.length < 6) {
            setError("Please fill all required fields")
            return
        }

        Logger.d("LoginViewModel: creating first admin '$trimmedEmployeeId' for kiosk $kioskId")

        launch {
            setLoading()
            val request = AdminBootstrapCreateRequest(
                name = trimmedName,
                employeeId = trimmedEmployeeId,
                email = trimmedEmail.ifBlank { null },
                password = password
            )
            authRepository.adminBootstrapCreate(request).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        Logger.i("LoginViewModel: first admin created — ${result.data.adminId} signed in")
                        setSuccess()
                        delay(200.milliseconds) // Wait for session persistence
                        _navigationEvent.emit(LoginNavigation.NavigateToAdminDashboard)
                    }
                    is NetworkResult.Failure -> {
                        if (redirectIfDeviceUnverified(result)) return@collect
                        Logger.w("LoginViewModel: bootstrap create failed: ${result.error.code} ${result.error.message}")
                        when (result.error.code) {
                            "NOT_BOOTSTRAPPABLE" -> {
                                // Someone (another kiosk, or the warden) won the race —
                                // the jail has an admin now, so hand them to Admin Login.
                                _needsBootstrap.value = false
                                setError(result.error.message)
                                delay(1500.milliseconds)
                                setIdle()
                                _loginStage.value = LoginStage.ADMIN_USERNAME_ENTRY
                            }
                            "DUPLICATE", "INVALID_REQUEST" -> {
                                // Form-level refusals (username taken, bad field): stay on
                                // the form and show the backend's exact wording.
                                setError(result.error.message)
                                delay(1500.milliseconds)
                                setIdle()
                            }
                            else -> {
                                setError(getFriendlyErrorMessage(
                                    statusCode = result.statusCode,
                                    defaultMessage = "Could not create the admin. Please try again."
                                ))
                                delay(1500.milliseconds)
                                setIdle()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun onAdminUsernameSubmit(username: String) {
        if (username.isNullOrBlank()) return
        if (uiState.value is UiState.Loading) return

        Logger.d("LoginViewModel: Admin username submitted: $username")

        val kioskId = authRepository.getVerifiedKiosk()?.kioskId
        if (kioskId.isNullOrBlank()) {
            redirectUnverified()
            return
        }
        setLoading()

        val request = LoginRequest(kioskId, null, null, null, username)

        launch {
            authRepository.adminLogin(request).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        Logger.i("LoginViewModel: Admin identified: ${result.data.name}")
                        _identifiedAdmin.value = result.data
                        _loginStage.value = LoginStage.ADMIN_PIN_ENTRY
                        setSuccess()
                    }
                    is NetworkResult.Failure -> {
                        Logger.w("LoginViewModel: Admin identification failed: ${result.error.message}")
                        val errorMessage = getFriendlyErrorMessage(
                            statusCode = result.statusCode,
                            defaultMessage = "Admin not found for this kiosk"
                        )
                        setError(errorMessage)
                        delay(1500.milliseconds)
                        setIdle()
                    }
                    else -> {}
                }
            }
        }
    }

    fun startRfidAuth() {
        _loginStage.value = LoginStage.RFID_SCANNING
        rfidReaderManager.startSession(HARDWARE_OWNER)
        startRfidSession()
    }

    /**
     * Hardware path: a tap on the reader produces CardRead → identify.
     * The read is acknowledged immediately so the next tap re-emits.
     */
    private fun startRfidSession() {
        rfidSessionJob?.cancel()
        rfidSessionJob = viewModelScope.launch {
            rfidReaderManager.state.collect { state ->
                if (state is RfidReaderState.CardRead && uiState.value !is UiState.Loading) {
                    rfidReaderManager.acknowledgeRead()
                    onRfidScanned(state.token)
                }
            }
        }
    }

    /**
     * The HID wedge reader typed a card number + Enter into the hidden
     * text field on the login screen. Feed it through the same hardware
     * path as a driver read so the existing session collector handles
     * identify + the "Card detected" UI state.
     */
    fun onRfidCardTapped(token: String) {
        if (uiState.value is UiState.Loading) return
        rfidReaderManager.onCardRead(token)
    }

    fun onRfidScanned(rfidToken: String) {
        if (uiState.value is UiState.Loading) return

        val kioskId = authRepository.getVerifiedKiosk()?.kioskId
        if (kioskId.isNullOrBlank()) {
            redirectUnverified()
            return
        }
        val request = LoginRequest(kioskId, null, rfidToken, null)

        launch {
            setLoading()

            authRepository.identifyRfid(request).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _identifiedInmate.value = result.data
                        _loginStage.value = LoginStage.PIN_ENTRY
                        setSuccess()
                        cancelBiometricSessions()
                    }
                    is NetworkResult.Failure -> {
                        if (redirectIfDeviceUnverified(result)) return@collect
                        val errorMessage = getFriendlyErrorMessage(
                            statusCode = result.statusCode,
                            defaultMessage = "RFID card not recognized. Please try again."
                        )
                        setError(errorMessage)
                        delay(2000.milliseconds)
                        setIdle()
                    }
                    else -> {}
                }
            }
        }
    }

    fun startPrisonerIdEntry() {
        _loginStage.value = LoginStage.PRISONER_ID_ENTRY
    }

    fun onPrisonerIdSubmit(prisonerId: String) {
        if (prisonerId.isNullOrBlank()) return
        if (uiState.value is UiState.Loading) return

        launch {
            setLoading()
            authRepository.identifyPrisoner(prisonerId).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _identifiedInmate.value = result.data
                        _loginStage.value = LoginStage.PIN_ENTRY
                        setSuccess()
                    }
                    is NetworkResult.Failure -> {
                        if (redirectIfDeviceUnverified(result)) return@collect
                        val errorMessage = getFriendlyErrorMessage(
                            statusCode = result.statusCode,
                            defaultMessage = "Prisoner ID not recognized. Please try again."
                        )
                        setError(errorMessage)
                        delay(2000.milliseconds)
                        setIdle()
                    }
                    else -> {}
                }
            }
        }
    }

    fun resetToSelection() {
        cancelBiometricSessions()
        _loginStage.value = LoginStage.METHOD_SELECTION
        _identifiedInmate.value = null
        _identifiedAdmin.value = null
        setIdle()
        // Re-ask: an admin may have been created from elsewhere since entry.
        refreshBootstrapStatus()
    }

    /** Stop both hardware sessions and their collectors (leaving the biometric stages). */
    private fun cancelBiometricSessions() {
        fingerprintSessionJob?.cancel()
        fingerprintSessionJob = null
        rfidSessionJob?.cancel()
        rfidSessionJob = null
        fingerprintHardwareManager.stopCapture(HARDWARE_OWNER)
        rfidReaderManager.stopSession(HARDWARE_OWNER)
    }

    companion object {
        /** Owns the shared R307/RFID sessions while the login screen is up. */
        private const val HARDWARE_OWNER = "Login"
    }

    sealed class LoginNavigation {
        data object NavigateToDashboard : LoginNavigation()
        data object NavigateToAdminDashboard : LoginNavigation()
        /** Kiosk device is not verified — NavHost routes to registration/lock. */
        data object KioskNotVerified : LoginNavigation()
    }
}
