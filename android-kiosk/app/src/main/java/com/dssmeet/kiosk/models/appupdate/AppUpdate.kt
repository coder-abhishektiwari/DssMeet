package com.dssmeet.kiosk.models.appupdate

import com.google.gson.annotations.SerializedName

/**
 * A published build of the kiosk app, as carried by the heartbeat response
 * (and by GET /app-releases/latest). The server owns this data — the device
 * only compares [versionCode] with its own and decides whether the update is
 * mandatory (block the screen) or optional (a dismissible notice).
 */
data class AppUpdateInfo(
    @SerializedName("releaseId") val releaseId: String? = null,
    @SerializedName("app") val app: String? = null,
    @SerializedName("platform") val platform: String? = null,
    @SerializedName("version") val version: String = "",
    @SerializedName("versionCode") val versionCode: Int? = null,
    @SerializedName("mandatory") val mandatory: Boolean = false,
    @SerializedName("downloadUrl") val downloadUrl: String = "",
    @SerializedName("sha256") val sha256: String? = null,
    @SerializedName("sizeBytes") val sizeBytes: Long? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("releasedAt") val releasedAt: String? = null
)
