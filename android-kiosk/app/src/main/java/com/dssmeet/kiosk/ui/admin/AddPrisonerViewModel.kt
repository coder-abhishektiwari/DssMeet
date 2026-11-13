package com.dssmeet.kiosk.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dssmeet.kiosk.hardware.FingerprintHardwareManager
import com.dssmeet.kiosk.hardware.RfidReaderManager
import com.dssmeet.kiosk.models.admin.CreatePrisonerRequest
import com.dssmeet.kiosk.models.admin.KioskDevice
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.repository.AdminRepository
import com.dssmeet.kiosk.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddPrisonerViewModel @Inject constructor(
    private val adminRepository: AdminRepository,
    private val rfidReaderManager: RfidReaderManager,
    private val fingerprintHardwareManager: FingerprintHardwareManager,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _kioskOptions = MutableStateFlow<List<KioskDevice>>(emptyList())
    val kioskOptions: StateFlow<List<KioskDevice>> = _kioskOptions.asStateFlow()

    /**
     * The kiosk this device IS — the admin is standing at it, so it is the
     * default "Assigned Kiosk" for a new inmate.
     */
    private val _currentKioskId = MutableStateFlow("")
    val currentKioskId: StateFlow<String> = _currentKioskId.asStateFlow()

    init {
        viewModelScope.launch {
            adminRepository.getKiosks().collect { result ->
                if (result is NetworkResult.Success) _kioskOptions.value = result.data
            }
        }
        _currentKioskId.value = authRepository.getVerifiedKiosk()?.kioskId.orEmpty()
    }

    /** Live hardware sessions consumed by the Security step's capture dialogs. */
    val rfidState = rfidReaderManager.state
    val fingerprintState = fingerprintHardwareManager.captureState

    fun startCapture(type: String) {
        if (type == "rfid") rfidReaderManager.startSession(HARDWARE_OWNER)
        else fingerprintHardwareManager.startCapture(HARDWARE_OWNER)
    }

    fun stopCapture() {
        rfidReaderManager.stopSession(HARDWARE_OWNER)
        fingerprintHardwareManager.stopCapture(HARDWARE_OWNER)
    }

    fun acknowledgeCapture(type: String) {
        if (type == "rfid") rfidReaderManager.acknowledgeRead() else fingerprintHardwareManager.acknowledgeCapture()
    }

    /** HID wedge reader typed a card number into the dialog's hidden field. */
    fun onRfidCardTapped(token: String) {
        rfidReaderManager.onCardRead(token)
    }

    fun registerPrisoner(
        values: com.dssmeet.kiosk.models.admin.PrisonerFormValues,
        pin: String,
        fingerprintTemplate: String? = null,
        rfidTag: String? = null,
        familyContacts: List<com.dssmeet.kiosk.models.admin.FamilyContactPayload>? = null
    ) {
        if (values.name.isEmpty()) {
            _registrationState.value = RegistrationState.Error("Prisoner name is required")
            return
        }
        if (pin.length != 6) {
            _registrationState.value = RegistrationState.Error("PIN must be exactly 6 digits")
            return
        }
        if (fingerprintTemplate.isNullOrBlank() && rfidTag.isNullOrBlank()) {
            _registrationState.value = RegistrationState.Error(
                "Register at least one biometric — fingerprint or RFID"
            )
            return
        }

        _registrationState.value = RegistrationState.Loading

        val request = CreatePrisonerRequest(
            prisonerNumber = values.prisonerNumber.ifBlank { "PN-${System.currentTimeMillis()}" },
            name = values.name,
            gender = values.gender,
            age = values.age?.toIntOrNull(),
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
            pin = pin.ifBlank { null },
            assignedKioskId = values.assignedKioskId,
            fingerprintTemplate = fingerprintTemplate?.ifBlank { null },
            rfidTag = rfidTag?.ifBlank { null },
            familyContacts = familyContacts?.ifEmpty { null }
        )

        viewModelScope.launch {
            adminRepository.createPrisoner(request).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _registrationState.value = RegistrationState.Success(
                            prisonerId = result.data.inmateId,
                            message = "Prisoner registered successfully",
                            familyContactIds = result.data.familyContactIds
                        )
                    }
                    is NetworkResult.Failure -> {
                        _registrationState.value = RegistrationState.Error(
                            result.error.message ?: "Failed to register prisoner"
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    fun resetState() {
        _registrationState.value = RegistrationState.Idle
    }

    sealed class RegistrationState {
        object Idle : RegistrationState()
        object Loading : RegistrationState()
        data class Success(val prisonerId: String, val message: String, val familyContactIds: List<String>? = null) : RegistrationState()
        data class Error(val message: String) : RegistrationState()
    }

    companion object {
        /**
         * Owns the shared R307/RFID sessions while the registration form is
         * up. A startCapture from here force-releases anything the Login or
         * Inmate-Card screens left open, so embedded biometric capture always
         * gets a clean, fresh connection.
         */
        private const val HARDWARE_OWNER = "AddPrisoner"
    }
}
