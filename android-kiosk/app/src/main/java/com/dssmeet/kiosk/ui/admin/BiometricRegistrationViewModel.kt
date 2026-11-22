package com.dssmeet.kiosk.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dssmeet.kiosk.hardware.FingerprintHardwareManager
import com.dssmeet.kiosk.hardware.FingerprintTemplateValidator
import com.dssmeet.kiosk.hardware.RfidReaderManager
import com.dssmeet.kiosk.models.admin.BiometricRegistration
import com.dssmeet.kiosk.models.admin.MAX_FINGERPRINT_TEMPLATES
import com.dssmeet.kiosk.models.admin.RegisterBiometricRequest
import com.dssmeet.kiosk.models.common.ApiError
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.repository.AdminRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BiometricRegistrationViewModel @Inject constructor(
    private val adminRepository: AdminRepository,
    private val rfidReaderManager: RfidReaderManager,
    private val fingerprintHardwareManager: FingerprintHardwareManager
) : ViewModel() {

    private val _biometrics = MutableStateFlow<NetworkResult<List<BiometricRegistration>>>(NetworkResult.Idle)
    val biometrics: StateFlow<NetworkResult<List<BiometricRegistration>>> = _biometrics.asStateFlow()

    private val _registerState = MutableStateFlow<NetworkResult<BiometricRegistration>>(NetworkResult.Idle)
    val registerState: StateFlow<NetworkResult<BiometricRegistration>> = _registerState.asStateFlow()

    private val _deleteState = MutableStateFlow<NetworkResult<Unit>>(NetworkResult.Idle)
    val deleteState: StateFlow<NetworkResult<Unit>> = _deleteState.asStateFlow()

    private val _fingerprintLimitReached = MutableStateFlow(false)
    val fingerprintLimitReached: StateFlow<Boolean> = _fingerprintLimitReached.asStateFlow()

    /** Live hardware sessions consumed by the capture dialogs. */
    val rfidState = rfidReaderManager.state
    val fingerprintState = fingerprintHardwareManager.captureState
    val fingerDetected = fingerprintHardwareManager.fingerDetected

    /**
     * Open a hardware capture session for the given biometric type
     * ("fingerprint" | "rfid") while its registration dialog is open.
     */
    fun startCapture(type: String) {
        if (type == "rfid") rfidReaderManager.startSession(HARDWARE_OWNER)
        else fingerprintHardwareManager.startCapture(HARDWARE_OWNER)
    }

    /** Close both capture sessions (dialog dismissed, screen left). */
    fun stopCapture() {
        rfidReaderManager.stopSession(HARDWARE_OWNER)
        fingerprintHardwareManager.stopCapture(HARDWARE_OWNER)
    }

    /** Consume a hardware read so the session keeps emitting for the next one. */
    fun acknowledgeCapture(type: String) {
        if (type == "rfid") rfidReaderManager.acknowledgeRead() else fingerprintHardwareManager.acknowledgeCapture()
    }

    fun loadBiometrics(prisonerId: String) {
        viewModelScope.launch {
            adminRepository.getPrisonerBiometrics(prisonerId).collect { result ->
                if (result is NetworkResult.Loading && _biometrics.value is NetworkResult.Success) return@collect
                _biometrics.value = result
                if (result is NetworkResult.Success) {
                    val count = result.data.count { it.type == "fingerprint" && it.status == "registered" }
                    _fingerprintLimitReached.value = count >= MAX_FINGERPRINT_TEMPLATES
                }
            }
        }
    }

    fun registerFingerprint(prisonerId: String, template: String) {
        if (_registerState.value is NetworkResult.Loading) return
        if (_fingerprintLimitReached.value) {
            _registerState.value = fingerprintLimitError()
            return
        }
        val fingerprintCount = (_biometrics.value as? NetworkResult.Success)?.data
            ?.count { it.type == "fingerprint" && it.status == "registered" } ?: 0
        if (fingerprintCount >= MAX_FINGERPRINT_TEMPLATES) {
            _fingerprintLimitReached.value = true
            _registerState.value = fingerprintLimitError()
            stopCapture()
            return
        }
        val templateError = FingerprintTemplateValidator.validationError(template)
        if (templateError != null) {
            _registerState.value = NetworkResult.Failure(
                ApiError(
                    code = "INVALID_BIOMETRIC",
                    message = "Fingerprint template rejected: $templateError"
                )
            )
            return
        }
        _registerState.value = NetworkResult.Loading
        viewModelScope.launch {
            val result = adminRepository.registerBiometric(
                prisonerId,
                RegisterBiometricRequest(type = "fingerprint", capture = template)
            )
            _registerState.value = result
            if (result is NetworkResult.Success) {
                loadBiometrics(prisonerId)
            } else if (result is NetworkResult.Failure && result.error.code == "FINGERPRINT_LIMIT") {
                _fingerprintLimitReached.value = true
                loadBiometrics(prisonerId)
                stopCapture()
            }
        }
    }

    fun registerRfid(prisonerId: String, token: String) {
        if (_registerState.value is NetworkResult.Loading) return
        _registerState.value = NetworkResult.Loading
        viewModelScope.launch {
            val result = adminRepository.registerBiometric(
                prisonerId,
                RegisterBiometricRequest(type = "rfid", rfidToken = token)
            )
            _registerState.value = result
            if (result is NetworkResult.Success) loadBiometrics(prisonerId)
        }
    }

    /**
     * The HID wedge reader typed a card number + Enter into the hidden text
     * field of the RFID dialog. Route it through the hardware manager so the
     * screen's CardRead handler (detect → auto-register) does the rest.
     */
    fun onRfidCardTapped(token: String) {
        if (_registerState.value is NetworkResult.Loading) return
        rfidReaderManager.onCardRead(token)
    }

    fun deleteBiometric(biometricId: String, prisonerId: String) {
        _deleteState.value = NetworkResult.Loading
        viewModelScope.launch {
            adminRepository.deleteBiometric(biometricId, prisonerId).collect { result ->
                _deleteState.value = result
                if (result is NetworkResult.Success) loadBiometrics(prisonerId)
            }
        }
    }

    fun resetRegisterState() { _registerState.value = NetworkResult.Idle }
    fun resetDeleteState() { _deleteState.value = NetworkResult.Idle }

    private fun fingerprintLimitError(): NetworkResult.Failure = NetworkResult.Failure(
        ApiError(
            code = "FINGERPRINT_LIMIT",
            message = "Maximum $MAX_FINGERPRINT_TEMPLATES fingerprints are allowed"
        )
    )

    companion object {
        /** Owns the shared R307/RFID sessions while the Inmate Card biometrics screen is up. */
        private const val HARDWARE_OWNER = "InmateBiometrics"
    }
}
