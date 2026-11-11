package com.dssmeet.kiosk.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Root/jailbreak heuristics. A compromised kiosk could extract session tokens,
 * bypass kiosk mode or tamper with recordings — release builds refuse to run on
 * a device that trips any of these checks (see SplashViewModel).
 *
 * Detection is best-effort by nature (root tooling actively hides itself); it is
 * one layer, not a guarantee. Debug builds skip the block so development
 * devices keep working.
 */
object DeviceIntegrity {

    /** Set when the release build blocks startup; shown on the lock screen. */
    @Volatile
    var blockReason: String? = null

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su"
    )

    private val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.thirdparty.superuser",
        "com.koushikdutta.superuser",
        "com.yellowes.su",
        "com.kingroot.kinguser",
        "com.srx.rootplus"
    )

    /** Returns the list of suspicion reasons; empty list = looks clean. */
    fun detect(context: Context): List<String> {
        val reasons = mutableListOf<String>()

        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) {
            reasons += "build tags contain test-keys"
        }

        SU_PATHS.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
            ?.let { reasons += "su binary present ($it)" }

        // `which su` — catches su on PATH that is not at a known location.
        val which = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val found = process.inputStream.read() != -1
            process.destroy()
            found
        }.getOrDefault(false)
        if (which) reasons += "su found on PATH"

        val pm = context.packageManager
        ROOT_PACKAGES.forEach { pkg ->
            val installed = runCatching {
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
            if (installed) reasons += "root manager installed ($pkg)"
        }

        return reasons
    }
}
