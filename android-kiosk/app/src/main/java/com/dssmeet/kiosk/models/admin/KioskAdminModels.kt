package com.dssmeet.kiosk.models.admin

import com.google.gson.annotations.SerializedName

/** Staff account that signs in on a kiosk with username + password. */
data class KioskAdmin(
    @SerializedName("adminId") val adminId: String,
    @SerializedName("employeeId") val employeeId: String? = null,
    @SerializedName("name") val name: String = "",
    @SerializedName("email") val email: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("role") val role: String? = null,
    @SerializedName("permissions") val permissions: List<String>? = null,
    @SerializedName("prisonId") val prisonId: String? = null,
    @SerializedName("kioskId") val kioskId: String? = null,
    @SerializedName("location") val location: String? = null,
    @SerializedName("ward") val ward: String? = null,
    @SerializedName("createdAt") val createdAt: String? = null,
    @SerializedName("fingerprintCount") val fingerprintCount: Int = 0,
    @SerializedName("fingerprints") val fingerprints: List<AdminFingerprintInfo> = emptyList()
)

data class AdminFingerprintInfo(
    @SerializedName("biometricId") val biometricId: String,
    @SerializedName("label") val label: String = "Fingerprint",
    @SerializedName("registeredAt") val registeredAt: String? = null
)

data class CreateKioskAdminRequest(
    @SerializedName("name") val name: String,
    @SerializedName("employeeId") val employeeId: String,
    @SerializedName("email") val email: String?,
    @SerializedName("password") val password: String
)

data class UpdateKioskAdminRequest(
    @SerializedName("name") val name: String,
    @SerializedName("employeeId") val employeeId: String,
    @SerializedName("email") val email: String?
)

data class AddAdminFingerprintRequest(
    @SerializedName("capture") val capture: String
)

data class AdminFingerprintResult(
    @SerializedName("adminId") val adminId: String,
    @SerializedName("fingerprintCount") val fingerprintCount: Int,
    @SerializedName("fingerprints") val fingerprints: List<AdminFingerprintInfo> = emptyList()
)
