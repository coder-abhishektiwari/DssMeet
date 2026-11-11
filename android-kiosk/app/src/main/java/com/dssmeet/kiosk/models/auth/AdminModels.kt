package com.dssmeet.kiosk.models.auth

import com.google.gson.annotations.SerializedName

data class AdminProfile(
    @SerializedName("adminId") val adminId: String,
    @SerializedName("employeeId") val employeeId: String,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("role") val role: String,
    @SerializedName("permissions") val permissions: List<String>,
    @SerializedName("status") val status: String
)

data class AdminLoginRequest(
    @SerializedName("kioskId") val kioskId: String,
    @SerializedName("adminId") val adminId: String? = null,
    @SerializedName("rfidToken") val rfidToken: String? = null,
    @SerializedName("biometricToken") val biometricToken: String? = null
)

data class AdminVerifyPasswordRequest(
    @SerializedName("adminId") val adminId: String,
    @SerializedName("password") val password: String,
    @SerializedName("kioskId") val kioskId: String
)

/**
 * GET kiosk-admins/bootstrap-status — asked with the device credential only.
 * True while this kiosk's jail has no kiosk admin yet, i.e. the welcome
 * screen must offer "Create Admin" instead of "Admin Login".
 */
data class BootstrapStatusResponse(
    @SerializedName("needsBootstrap") val needsBootstrap: Boolean,
    @SerializedName("kioskId") val kioskId: String? = null,
    @SerializedName("prisonId") val prisonId: String? = null
)

/** Same four fields (and rules) the warden dashboard's "Add Kiosk Admin" form posts. */
data class AdminBootstrapCreateRequest(
    @SerializedName("name") val name: String,
    @SerializedName("employeeId") val employeeId: String,
    @SerializedName("email") val email: String? = null,
    @SerializedName("password") val password: String
)

/**
 * POST kiosk-admins/bootstrap 201 response: session half shaped like
 * auth/admin/verify-pin plus the identify-shaped [profile], so one call both
 * creates the jail's first admin and signs the creator in.
 */
data class AdminBootstrapResponse(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("expiresIn") val expiresIn: Long,
    @SerializedName("adminId") val adminId: String,
    @SerializedName("role") val role: String,
    @SerializedName("permissions") val permissions: List<String> = emptyList(),
    @SerializedName("kioskId") val kioskId: String? = null,
    @SerializedName("prisonId") val prisonId: String? = null,
    @SerializedName("profile") val profile: AdminProfile? = null
)
