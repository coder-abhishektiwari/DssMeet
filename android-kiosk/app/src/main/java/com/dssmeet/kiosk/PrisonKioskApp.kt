package com.dssmeet.kiosk

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import com.dssmeet.kiosk.api.TrustApiService
import com.dssmeet.kiosk.core.Logger
import com.dssmeet.kiosk.hardware.DeviceInfoProvider
import com.dssmeet.kiosk.models.auth.KioskHeartbeatRequest
import com.dssmeet.kiosk.recordings.RecordingVaultWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DssMeet Inmate Kiosk Entry Application Class.
 */
@HiltAndroidApp
class PrisonKioskApp : Application(), Configuration.Provider {

    @Inject lateinit var trustApiService: TrustApiService
    @Inject lateinit var deviceInfoProvider: DeviceInfoProvider
    /** Injected so the connectivity callback registers at process start and
     *  survives navigation across every screen. */
    @Inject lateinit var networkMonitor: com.dssmeet.kiosk.core.NetworkMonitor
    @Inject lateinit var authRepository: com.dssmeet.kiosk.repository.AuthRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** WorkManager needs no custom factory: the recording worker has no deps. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    // Device identity and OS facts never change while the process lives, so
    // they are resolved once instead of on every ping.
    private val deviceId by lazy { deviceInfoProvider.getRegistrationDeviceId() }
    private val deviceFingerprint by lazy { deviceInfoProvider.getDeviceFingerprint() }
    private val androidVersion by lazy { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }

    override fun onCreate() {
        super.onCreate()

        // Two-layer network quality for the header signal bars: local-link
        // (ConnectivityManager — instant on/off) + latency probe (quality).
        com.dssmeet.kiosk.core.NetworkQualityMonitor.start(this)

        // Fetch this kiosk's registered identity (serial, prison id/name) from
        // the public /kiosks/verify by KIOSK_ID and persist it in SessionManager.
        // Runs on the APP scope, not a splash ViewModel's scope: the splash
        // screen pops in ~1 s and a viewModelScope coroutine gets cancelled
        // mid-flight, which is why this data never used to stick.
        appScope.launch {
            authRepository.hydrateKioskInfo().collect { result ->
                when (result) {
                    is com.dssmeet.kiosk.network.NetworkResult.Success ->
                        Logger.i("Kiosk info hydrated: ${result.data.kioskId} / ${result.data.prisonId}")
                    is com.dssmeet.kiosk.network.NetworkResult.Failure ->
                        Logger.w("Kiosk info hydration failed: ${result.error.message}")
                    else -> {}
                }
            }
        }

        // Any recording that was still plaintext when the last process died
        // (crash, reboot) is queued for encryption again; anything already in
        // the vault is skipped by construction.
        RecordingVaultWorker.scanPending(this)

        // Single background ping. This is what the dashboard reads to decide
        // Online/Offline and Last Seen — without it a kiosk that is sitting idle
        // would never report in. A kiosk roams between Wi-Fi networks, so a
        // failed ping retries in a few seconds instead of waiting out the whole
        // interval and looking offline for an extra half minute.
        appScope.launch {
            var intervalMs = HEARTBEAT_INTERVAL_MS
            while (isActive) {
                delay(intervalMs)
                intervalMs = if (sendHeartbeat()) HEARTBEAT_INTERVAL_MS else HEARTBEAT_RETRY_MS
            }
        }
    }

    private suspend fun sendHeartbeat(): Boolean {
        return try {
            val response = trustApiService.heartbeat(
                KioskHeartbeatRequest(
                    deviceSerialNumber = deviceId,
                    deviceFingerprint = deviceFingerprint,
                    androidVersion = androidVersion,
                    appVersion = com.dssmeet.kiosk.BuildConfig.VERSION_NAME
                )
            )
            true
        } catch (t: Throwable) {
            Logger.w("Heartbeat failed: ${t.message}")
            false
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val HEARTBEAT_RETRY_MS = 5_000L
    }
}
