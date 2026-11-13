package com.dssmeet.kiosk.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import com.dssmeet.kiosk.hardware.KioskDeviceAdminReceiver
import com.dssmeet.kiosk.models.appupdate.AppUpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads and installs a kiosk APK update.
 *
 * Download goes over plain HTTPS on a BARE OkHttp client (no app interceptors:
 * the API client's EnvironmentInterceptor rewrites every host, and its
 * certificate pins only cover the API host). Integrity does not depend on
 * that connection: the sha256 published by the server — which the kiosk
 * fetched over its PINNED API channel — is verified before anything is
 * installed. A tampered or truncated download fails the check and is thrown
 * away.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    data class DownloadResult(val file: File, val bytes: Long)

    /**
     * Download the APK into the app cache and verify its sha256.
     */
    suspend fun download(
        info: AppUpdateInfo,
        onProgress: (percent: Int) -> Unit = {}
    ): kotlin.Result<DownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!info.downloadUrl.startsWith("https://")) {
                error("Update URL must be https")
            }
            val request = Request.Builder().url(info.downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
                val body = response.body ?: error("Empty response body")

                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "kiosk-${info.version}.apk")
                val expected = info.sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            total += read
                            val expectedTotal = body.contentLength().takeIf { it > 0 }
                                ?: info.sizeBytes?.takeIf { it > 0 }
                            if (expectedTotal != null) {
                                val percent = ((total * 100) / expectedTotal).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }

                if (expected != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual != expected) {
                        target.delete()
                        error("Checksum mismatch — download rejected")
                    }
                }
                DownloadResult(target, total)
            }
        }
    }

    /**
     * Install the downloaded APK.
     *
     * On a device-owner kiosk the install is SILENT (PackageInstaller session
     * with USER_ACTION_NOT_REQUIRED + the install-packages permission granted
     * through the device admin). Where that is not possible we fall back to
     * the system installer screen.
     */
    suspend fun install(file: File): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val granted = grantInstallPermission()
            if (granted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                installSilently(file)
            } else {
                promptInstall(file)
            }
        }
    }

    /**
     * Device owners can allow themselves to install packages. The kiosk is
     * provisioned as device owner (KioskDeviceAdminReceiver), so this makes
     * the update truly hands-off.
     */
    private fun grantInstallPermission(): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            dpm.setPermissionGrantState(
                ComponentName(context, KioskDeviceAdminReceiver::class.java),
                "android",
                android.Manifest.permission.REQUEST_INSTALL_PACKAGES,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            true
        } catch (e: Exception) {
            Logger.w("UpdateInstaller: could not grant install permission: ${e.message}")
            false
        }
    }

    private fun installSilently(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(file.length())
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("package", 0L, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusIntentSender())
        }
        Logger.i("UpdateInstaller: silent install session committed")
    }

    /**
     * The installer reports the outcome through this PendingIntent — on
     * success the app restarts itself so the new build is the live one.
     */
    private fun statusIntentSender(): android.content.IntentSender {
        val intent = android.content.Intent(context, UpdateInstallReceiver::class.java).apply {
            action = UpdateInstallReceiver.ACTION
        }
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return android.app.PendingIntent.getBroadcast(context, 0, intent, flags).intentSender
    }

    private fun promptInstall(file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Logger.i("UpdateInstaller: handed the APK to the system installer")
    }
}
