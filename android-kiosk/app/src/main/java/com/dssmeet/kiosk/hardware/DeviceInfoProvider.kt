package com.dssmeet.kiosk.hardware

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.dssmeet.kiosk.core.Logger
import com.dssmeet.kiosk.models.admin.KioskHardware
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the physical hardware identity of the kiosk device.
 * Enforces strict serial number usage.
 */
@Singleton
class DeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val RAM_CAPACITIES_GB = listOf(2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0, 64.0)
        private val STORAGE_CAPACITIES_GB = listOf(16.0, 32.0, 64.0, 128.0, 256.0, 512.0)
        private val CAPACITY_PATTERN = Regex("([0-9]+(?:\\.[0-9]+)?)")
    }

    fun normalizeHardware(hardware: KioskHardware?): KioskHardware? = hardware?.copy(
        ram = normalizeCapacityLabel(hardware.ram, RAM_CAPACITIES_GB),
        storage = normalizeCapacityLabel(hardware.storage, STORAGE_CAPACITIES_GB)
    )

    private fun normalizeCapacityLabel(raw: String?, capacities: List<Double>): String? {
        if (raw.isNullOrBlank()) return raw
        val value = CAPACITY_PATTERN.find(raw)?.value?.toDoubleOrNull() ?: return raw
        if (value <= 0.0) return raw
        return roundCapacity(value, capacities)
    }

    private fun roundCapacity(valueGb: Double, capacities: List<Double>): String {
        val closest = capacities.minByOrNull { kotlin.math.abs(it - valueGb) }
        return if (closest != null) {
            "${closest.toInt()} GB"
        } else {
            String.format(java.util.Locale.US, "%.0f GB", valueGb)
        }
    }

    /**
     * Returns the physical hardware serial number.
     * Requires the app to be Device Owner on API 29+.
     */
    @SuppressLint("HardwareIds")
    fun getDeviceSerialNumber(): String? {
        val hardwareSerial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                Build.SERIAL
            }
        } catch (securityException: SecurityException) {
            Logger.e("Hardware serial access restricted. App must be Device Owner. ${securityException.message}")
            null
        } catch (exception: Exception) {
            Logger.e("Unexpected error reading hardware serial: ${exception.message}")
            null
        }

        val filteredSerial = hardwareSerial
            ?.trim()
            ?.takeIf {
                it.isNotEmpty() && !it.equals("unknown", ignoreCase = true)
            }

        Logger.d("Strict Hardware Serial: $filteredSerial")
        return filteredSerial
    }

    /**
     * Gets the active network IPv4 address of the device.
     */
    fun getIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (networkInterface in java.util.Collections.list(interfaces)) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                for (address in java.util.Collections.list(addresses)) {
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            Logger.e("Error getting device IP address: ${e.message}")
            null
        }
    }

    /**
     * Returns a stable registration identity for the device: the strict hardware
     * serial when available (Device Owner), otherwise a fallback derived from
     * ANDROID_ID. ANDROID_ID is unique per device, so every unit keeps its own
     * identity even without Device Owner — Build.SERIAL is the literal
     * "unknown" on all non-DO devices and would collide.
     */
    fun getRegistrationDeviceId(): String {
        val strict = getDeviceSerialNumber()
        if (!strict.isNullOrBlank()) return strict
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "NO_ID"
        } catch (e: Exception) {
            "NO_ID"
        }
        val fallback = "KIOSK-DEV-${androidId.takeLast(8).uppercase()}"
        Logger.w("Registration Device ID fallback: $fallback (app not Device Owner)")
        return fallback
    }

    /**
     * Generates a stable device fingerprint using hardware parameters.
     *
     * ANDROID_ID carries the per-device identity: the hardware serial cannot
     * be read unless the app is Device Owner, so without it every unit of the
     * same model would produce the same fingerprint. The serial is deliberately
     * left out of the input — it becomes readable once the app is made Device
     * Owner and would otherwise change the fingerprint of an already-registered
     * kiosk.
     */
    fun getDeviceFingerprint(): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "NO_ID"
        } catch (e: Exception) {
            "NO_ID"
        }
        val rawFingerprint =
            "${Build.MANUFACTURER}_${Build.MODEL}_${Build.BOARD}_${Build.HARDWARE}_$androidId"
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(rawFingerprint.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            rawFingerprint.replace(" ", "_")
        }
    }

    // ---- local hardware facts for the Device Information screen ----
    // The admin devices API is not reachable with a kiosk token, so the screen
    // reads everything it can straight off the device itself.

    fun hasCamera(): Boolean = try {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
    } catch (e: Exception) {
        false
    }

    fun hasMicrophone(): Boolean = try {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
    } catch (e: Exception) {
        false
    }

    fun getCpuDescription(): String = try {
        "${Runtime.getRuntime().availableProcessors()}-core"
    } catch (e: Exception) {
        "Unknown"
    }

    fun getRamDescription(): String = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        roundCapacity(info.totalMem / (1024.0 * 1024 * 1024), RAM_CAPACITIES_GB)
    } catch (e: Exception) {
        "Unknown"
    }

    fun getStorageDescription(): String = try {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        val totalGb = stat.blockCountLong * stat.blockSizeLong / (1024.0 * 1024 * 1024)
        roundCapacity(totalGb, STORAGE_CAPACITIES_GB)
    } catch (e: Exception) {
        "Unknown"
    }

    fun getScreenResolution(): String = try {
        val dm = context.resources.displayMetrics
        "${dm.widthPixels} × ${dm.heightPixels}"
    } catch (e: Exception) {
        "Unknown"
    }
}
