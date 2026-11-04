package com.dssmeet.kiosk.datasource

import com.dssmeet.kiosk.models.auth.AdminProfile
import com.dssmeet.kiosk.models.auth.AuthToken
import com.dssmeet.kiosk.models.auth.KioskVerifyRequest
import com.dssmeet.kiosk.models.auth.KioskVerifyResponse
import com.dssmeet.kiosk.models.auth.LoginRequest
import com.dssmeet.kiosk.models.auth.PinVerifyRequest
import com.dssmeet.kiosk.models.common.ApiResponse
import com.dssmeet.kiosk.models.inmate.InmateProfile

interface AuthDataSource {
    suspend fun login(request: LoginRequest): ApiResponse<AuthToken>
    suspend fun refreshToken(refreshToken: String): ApiResponse<AuthToken>
    suspend fun logout(accessToken: String): ApiResponse<Unit>
    suspend fun identifyFingerprint(kioskId: String, capture: ByteArray): ApiResponse<InmateProfile>
    suspend fun identifyRfid(request: LoginRequest): ApiResponse<InmateProfile>
    suspend fun identifyPrisoner(kioskId: String, prisonerId: String): ApiResponse<InmateProfile>
    suspend fun verifyPin(request: PinVerifyRequest): ApiResponse<AuthToken>
    suspend fun verifyKiosk(request: KioskVerifyRequest): ApiResponse<KioskVerifyResponse>

    // Admin authentication
    suspend fun adminIdentify(request: LoginRequest): ApiResponse<AdminProfile>
    suspend fun adminVerifyPassword(adminId: String, password: String, kioskId: String): ApiResponse<AuthToken>

    // Kiosk Registration & Authorization
    suspend fun validateSetupPassword(request: com.dssmeet.kiosk.models.auth.ValidateSetupPasswordRequest): ApiResponse<com.dssmeet.kiosk.models.auth.ValidateSetupPasswordResponse>
    suspend fun registerKiosk(request: com.dssmeet.kiosk.models.auth.KioskRegistrationRequest): ApiResponse<com.dssmeet.kiosk.models.auth.KioskRegistrationResponse>
    suspend fun getRegistrationStatus(serialNumber: String): ApiResponse<com.dssmeet.kiosk.models.auth.RegistrationStatusResponse>
}

