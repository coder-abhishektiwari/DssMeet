package com.dssmeet.kiosk.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.dssmeet.kiosk.api.TrustApiService
import com.dssmeet.kiosk.core.Logger
import com.dssmeet.kiosk.hardware.FingerprintCaptureState
import com.dssmeet.kiosk.hardware.FingerprintHardwareManager
import com.dssmeet.kiosk.hardware.FingerprintTemplateValidator
import com.dssmeet.kiosk.models.admin.AddAdminFingerprintRequest
import com.dssmeet.kiosk.models.admin.AdminFingerprintResult
import com.dssmeet.kiosk.models.admin.CreateKioskAdminRequest
import com.dssmeet.kiosk.models.admin.KioskAdmin
import com.dssmeet.kiosk.models.admin.UpdateKioskAdminRequest
import com.dssmeet.kiosk.models.common.ApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Kiosk admin staff list for this prison - the same add / edit the warden panel
 * offers, scoped by the signed-in admin's own token.
 */
@HiltViewModel
class KioskAdminsViewModel @Inject constructor(
    private val apiService: TrustApiService,
    private val fingerprintHardwareManager: FingerprintHardwareManager
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val admins: List<KioskAdmin> = emptyList(),
        val error: String? = null,
        val isSaving: Boolean = false,
        val saveError: String? = null,
        val message: String? = null,
        val captureState: FingerprintCaptureState = FingerprintCaptureState.Idle,
        val pendingFingerprints: List<String> = emptyList(),
        val fingerprintError: String? = null
    )

    companion object {
        private const val HARDWARE_OWNER = "KioskAdminsViewModel"
        private const val MAX_FINGERPRINTS = 3
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var fingerprintJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            fingerprintHardwareManager.captureState.collect { captureState ->
                _state.update { it.copy(captureState = captureState) }
            }
        }
    }

    fun load() {
        if (_state.value.admins.isEmpty()) _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val response = apiService.getKioskAdmins()
                val admins = response.data
                if (!response.success || admins == null) {
                    _state.update {
                        it.copy(isLoading = false, error = response.error?.message ?: "Could not load kiosk admins")
                    }
                    return@launch
                }
                _state.update { it.copy(isLoading = false, error = null, admins = admins) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = describe(e)) }
            }
        }
    }

    fun create(
        name: String,
        employeeId: String,
        email: String?,
        password: String,
        fingerprints: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            try {
                val response = apiService.createKioskAdmin(
                    CreateKioskAdminRequest(name = name, employeeId = employeeId, email = email, password = password)
                )
                if (!response.success || response.data == null) {
                    _state.update {
                        it.copy(isSaving = false, saveError = response.error?.message ?: "Could not add kiosk admin")
                    }
                    return@launch
                }
                val adminId = response.data.adminId
                val failures = saveFingerprints(adminId, fingerprints.take(MAX_FINGERPRINTS))
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveError = null,
                        message = if (failures == 0) "Kiosk admin added" else "Kiosk admin added, but $failures fingerprint(s) could not be saved",
                        pendingFingerprints = emptyList()
                    )
                }
                stopFingerprintCapture()
                load()
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, saveError = describe(e)) }
            }
        }
    }

    fun update(
        adminId: String,
        name: String,
        employeeId: String,
        email: String?,
        fingerprints: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            try {
                val response = apiService.updateKioskAdmin(
                    adminId = adminId,
                    // The backend only clears an email when it receives "" - a null
                    // would be rejected as an invalid address.
                    request = UpdateKioskAdminRequest(name = name, employeeId = employeeId, email = email ?: "")
                )
                if (!response.success || response.data == null) {
                    _state.update {
                        it.copy(isSaving = false, saveError = response.error?.message ?: "Could not save changes")
                    }
                    return@launch
                }
                val failures = saveFingerprints(adminId, fingerprints.take(MAX_FINGERPRINTS))
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveError = null,
                        message = if (failures == 0) "Changes saved" else "Changes saved, but $failures fingerprint(s) could not be saved",
                        pendingFingerprints = emptyList()
                    )
                }
                stopFingerprintCapture()
                load()
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, saveError = describe(e)) }
            }
        }
    }

    fun startFingerprintCapture(adminId: String? = null) {
        if (fingerprintJob?.isActive == true || _state.value.isSaving) return
        val currentCount = if (adminId == null) {
            _state.value.pendingFingerprints.size
        } else {
            _state.value.admins.firstOrNull { it.adminId == adminId }?.fingerprintCount ?: 0
        }
        if (currentCount >= MAX_FINGERPRINTS) {
            _state.update { it.copy(fingerprintError = "Maximum $MAX_FINGERPRINTS fingerprints can be registered") }
            return
        }
        _state.update { it.copy(fingerprintError = null, saveError = null) }
        Logger.i("KioskAdminsViewModel: fingerprint capture started for ${adminId ?: "new admin"}")
        fingerprintHardwareManager.startCapture(HARDWARE_OWNER)
        fingerprintJob = viewModelScope.launch {
            fingerprintHardwareManager.captureState.collect { capture ->
                when (capture) {
                    is FingerprintCaptureState.Captured -> {
                        fingerprintHardwareManager.stopCapture(HARDWARE_OWNER)
                        val error = FingerprintTemplateValidator.validationError(capture.template)
                            ?: if (currentCount >= MAX_FINGERPRINTS) {
                                "Maximum $MAX_FINGERPRINTS fingerprints can be registered"
                            } else null
                        if (error != null) {
                            _state.update { it.copy(fingerprintError = error) }
                            return@collect
                        }
                        if (adminId == null) {
                            _state.update {
                                it.copy(pendingFingerprints = it.pendingFingerprints + capture.template)
                            }
                            if (_state.value.pendingFingerprints.size < MAX_FINGERPRINTS) {
                                delay(700)
                                fingerprintHardwareManager.startCapture(HARDWARE_OWNER)
                            }
                        } else {
                            Logger.i("KioskAdminsViewModel: fingerprint captured, saving for $adminId")
                            _state.update { it.copy(isSaving = true) }
                            val failures = saveFingerprints(adminId, listOf(capture.template))
                            _state.update {
                                it.copy(
                                    isSaving = false,
                                    message = if (failures == 0) "Fingerprint added" else "Fingerprint could not be saved",
                                    saveError = if (failures == 0) null else "Fingerprint could not be saved. Check connection/server."
                                )
                            }
                            fingerprintJob?.cancel()
                            fingerprintJob = null
                        }
                    }
                    is FingerprintCaptureState.Failed -> {
                        _state.update { it.copy(fingerprintError = capture.message) }
                    }
                    else -> {}
                }
            }
        }
    }

    fun stopFingerprintCapture() {
        fingerprintJob?.cancel()
        fingerprintJob = null
        fingerprintHardwareManager.stopCapture(HARDWARE_OWNER)
    }

    fun clearPendingFingerprints() {
        _state.update { it.copy(pendingFingerprints = emptyList(), fingerprintError = null) }
    }

    fun savePendingFingerprints(adminId: String) {
        val templates = _state.value.pendingFingerprints.take(MAX_FINGERPRINTS)
        if (templates.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            val failures = saveFingerprints(adminId, templates)
            _state.update {
                it.copy(
                    isSaving = false,
                    pendingFingerprints = emptyList(),
                    message = if (failures == 0) "Fingerprint saved" else "$failures fingerprint(s) could not be saved"
                )
            }
            stopFingerprintCapture()
        }
    }

    fun removeFingerprint(adminId: String, biometricId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            try {
                val response = apiService.deleteKioskAdminFingerprint(adminId, biometricId)
                if (response.success && response.data != null) {
                    applyFingerprintResult(adminId, response.data)
                    _state.update { it.copy(isSaving = false, message = "Fingerprint removed") }
                } else {
                    _state.update {
                        it.copy(isSaving = false, saveError = response.error?.message ?: "Could not remove fingerprint")
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, saveError = describe(e)) }
            }
        }
    }

    private suspend fun saveFingerprints(adminId: String, templates: List<String>): Int {
        var failures = 0
        templates.forEach { template ->
            try {
                val response = apiService.addKioskAdminFingerprint(adminId, AddAdminFingerprintRequest(template))
                if (response.success && response.data != null) {
                    applyFingerprintResult(adminId, response.data)
                } else {
                    failures++
                    Logger.w("KioskAdminsViewModel: add fingerprint failed for $adminId: ${response.error?.code} ${response.error?.message}")
                }
            } catch (e: Exception) {
                failures++
                Logger.w("KioskAdminsViewModel: add fingerprint error for $adminId: ${describe(e)}")
            }
        }
        return failures
    }

    private fun applyFingerprintResult(adminId: String, result: AdminFingerprintResult) {
        _state.update { current ->
            current.copy(
                admins = current.admins.map { admin ->
                    if (admin.adminId == adminId) {
                        admin.copy(fingerprintCount = result.fingerprintCount, fingerprints = result.fingerprints)
                    } else admin
                }
            )
        }
    }

    override fun onCleared() {
        stopFingerprintCapture()
        super.onCleared()
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clearSaveError() {
        _state.update { it.copy(saveError = null) }
    }

    private fun describe(error: Throwable): String {
        if (error is HttpException) {
            val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            val envelope = runCatching {
                Gson().fromJson(body, ErrorEnvelope::class.java)
            }.getOrNull()
            return envelope?.error?.message ?: "Request failed (HTTP ${error.code()})"
        }
        return error.message ?: "Network error"
    }

    private data class ErrorEnvelope(val error: ApiError?)
}
