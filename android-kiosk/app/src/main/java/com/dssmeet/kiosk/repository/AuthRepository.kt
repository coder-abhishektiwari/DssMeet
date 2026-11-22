package com.dssmeet.kiosk.repository

import com.dssmeet.kiosk.models.admin.AdminFingerprintResult
import com.dssmeet.kiosk.models.auth.AdminProfile
import com.dssmeet.kiosk.models.auth.AdminVerifyPasswordRequest
import com.dssmeet.kiosk.models.auth.AuthToken
import com.dssmeet.kiosk.models.auth.KioskVerifyRequest
import com.dssmeet.kiosk.models.auth.KioskVerifyResponse
import com.dssmeet.kiosk.models.auth.LoginRequest
import com.dssmeet.kiosk.models.auth.KioskInfo
import com.dssmeet.kiosk.network.NetworkResult
import kotlinx.coroutines.flow.Flow

import com.dssmeet.kiosk.models.inmate.InmateProfile

interface AuthRepository {
    fun login(request: LoginRequest): Flow<NetworkResult<AuthToken>>
    fun refreshToken(): Flow<NetworkResult<AuthToken>>
    fun logout(): Flow<NetworkResult<Unit>>
    /**
     * Identify an inmate from a freshly captured fingerprint.
     *
     * [probeTemplate] is the base64 SecuGen template the scanner produced. The
     * enrolled templates are fetched for this kiosk's scope and matched
     * locally — the server has no SecuGen matcher, so it cannot compare.
     */
    fun identifyFingerprint(probeTemplate: String): Flow<NetworkResult<InmateProfile>>
    fun fingerprintLogin(inmateId: String): Flow<NetworkResult<AuthToken>>
    fun identifyRfid(request: LoginRequest): Flow<NetworkResult<InmateProfile>>
    fun identifyPrisoner(id: String): Flow<NetworkResult<InmateProfile>>
    fun verifyPin(inmateId: String, pin: String): Flow<NetworkResult<AuthToken>>
    fun verifyKiosk(request: KioskVerifyRequest): Flow<NetworkResult<KioskVerifyResponse>>

    /**
     * Fetch this kiosk's registered identity (serial, prison id/name) by the
     * stable KIOSK_ID and persist it — side-effect free on failure (never
     * touches the login session). Needed because the device-authorization
     * gate can be off, in which case the normal verify path never runs.
     */
    fun hydrateKioskInfo(): Flow<NetworkResult<KioskInfo>>
    fun getVerifiedKiosk(): KioskInfo?
    suspend fun getInmateId(): String?
    suspend fun hasValidSession(): Boolean
    suspend fun hasSession(): Boolean
    fun isDeviceAuthorized(): Flow<Boolean>

    // Admin authentication
    fun adminLogin(request: LoginRequest): Flow<NetworkResult<AdminProfile>>
    fun adminVerifyPassword(request: AdminVerifyPasswordRequest): Flow<NetworkResult<AuthToken>>
    fun identifyAdminFingerprint(probeTemplate: String): Flow<NetworkResult<AdminProfile>>
    fun adminFingerprintLogin(adminId: String): Flow<NetworkResult<AuthToken>>
    fun registerAdminFingerprint(adminId: String, capture: String): Flow<NetworkResult<AdminFingerprintResult>>

    // First-admin bootstrap (kiosk "Create Admin") — device credential only.
    // Status decides which button the welcome screen shows; create makes this
    // jail's first kiosk admin and signs the creator in from one response.
    fun adminBootstrapStatus(): Flow<NetworkResult<com.dssmeet.kiosk.models.auth.BootstrapStatusResponse>>
    fun adminBootstrapCreate(request: com.dssmeet.kiosk.models.auth.AdminBootstrapCreateRequest): Flow<NetworkResult<com.dssmeet.kiosk.models.auth.AdminBootstrapResponse>>

    // Kiosk Registration & Authorization
    fun validateSetupPassword(prisonId: String, password: String): Flow<NetworkResult<com.dssmeet.kiosk.models.auth.ValidateSetupPasswordResponse>>
    fun registerKiosk(request: com.dssmeet.kiosk.models.auth.KioskRegistrationRequest): Flow<NetworkResult<com.dssmeet.kiosk.models.auth.KioskRegistrationResponse>>
    fun getRegistrationStatus(serialNumber: String): Flow<NetworkResult<com.dssmeet.kiosk.models.auth.RegistrationStatusResponse>>
}

