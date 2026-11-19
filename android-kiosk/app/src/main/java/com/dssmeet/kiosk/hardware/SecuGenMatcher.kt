package com.dssmeet.kiosk.hardware

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Base64
import com.dssmeet.kiosk.core.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import SecuGen.FDxSDKPro.JSGFPLib
import SecuGen.FDxSDKPro.SGFDxDeviceName
import SecuGen.FDxSDKPro.SGFDxErrorCode
import SecuGen.FDxSDKPro.SGFDxSecurityLevel
import SecuGen.FDxSDKPro.SGFDxTemplateFormat
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Device-side 1:N fingerprint identification for the SecuGen Hamster Pro 20.
 *
 * WHY THIS EXISTS: the backend has no SecuGen matcher — it only stores the
 * enrolled template (routes/auth.js /fingerprint-candidates hands them out for
 * the kiosk's own prison/assignment scope). A fresh capture is never
 * byte-identical to the enrolled one, so equality/hashing can never match; the
 * vendor's MINEX-tested matcher running here is what actually decides "is this
 * finger already enrolled?".
 *
 * The kiosk only ever LEARNED the inmate's identity from this call — the PIN
 * check that follows (POST /auth/verify-pin) is what authenticates them.
 *
 * This owns its own [JSGFPLib] (algorithm module only, never OpenDevice) so it
 * never contends with FingerprintHardwareManager's capture session for the USB
 * port. All matching runs on one dedicated thread because the SDK's matching
 * calls are not re-entrant.
 */
@Singleton
class SecuGenMatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fingerprintHardwareManager: FingerprintHardwareManager
) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "secugen-match").apply { isDaemon = true }
    }

    private val initLock = Any()

    @Volatile
    private var sgfplib: JSGFPLib? = null

    suspend fun identify(probeTemplateBase64: String, candidates: List<String>): Int =
        suspendCancellableCoroutine { continuation ->
            executor.execute {
                val result = try {
                    runIdentify(probeTemplateBase64, candidates)
                } catch (t: Throwable) {
                    Logger.e("SecuGenMatcher: identify failed: ${t.message}", t)
                    NO_MATCH
                }
                if (continuation.isActive) continuation.resume(result)
            }
        }

    /**
     * @return index of the best matching candidate, or [NO_MATCH].
     */
    private fun runIdentify(probeBase64: String, candidates: List<String>): Int {
        if (candidates.isEmpty()) return NO_MATCH
        val lib = ensureAlgorithm() ?: return NO_MATCH
        val probe = decode(probeBase64)
        if (probe == null) {
            Logger.w("SecuGenMatcher: probe template is not valid base64")
            return NO_MATCH
        }

        val matched = BooleanArray(1)
        val score = IntArray(1)
        var bestIndex = NO_MATCH
        var bestScore = Int.MIN_VALUE

        synchronized(lib) {
            for ((index, candidateBase64) in candidates.withIndex()) {
                val stored = decode(candidateBase64) ?: continue
                matched[0] = false
                val err = lib.MatchTemplate(probe, stored, SGFDxSecurityLevel.SL_NORMAL, matched)
                if (err != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong() || !matched[0]) continue

                // SL_NORMAL can accept more than one candidate in a large
                // roster, so rank the accepted ones by score and keep the best.
                score[0] = 0
                lib.GetMatchingScore(probe, stored, score)
                if (score[0] > bestScore) {
                    bestScore = score[0]
                    bestIndex = index
                }
            }
        }

        Logger.i(
            "SecuGenMatcher: ${candidates.size} candidate(s) compared — " +
                if (bestIndex == NO_MATCH) "no match" else "matched index $bestIndex (score=$bestScore)"
        )
        return bestIndex
    }

    private fun ensureAlgorithm(): JSGFPLib? {
        sgfplib?.let { return it }
        synchronized(initLock) {
            sgfplib?.let { return it }
            return try {
                // The capture session is stopped before identify, but its
                // thread can still be unwinding inside GetImageEx with the
                // port open. Two live JSGFPLib instances sharing one native
                // SDK is how you get a wedged reader, so wait it out first.
                fingerprintHardwareManager.awaitDriverIdle()
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val lib = JSGFPLib(context, usbManager)
                val err = lib.Init(SGFDxDeviceName.SG_DEV_AUTO)
                if (err != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                    Logger.w("SecuGenMatcher: Init failed ($err) — fingerprint matching unavailable")
                    null
                } else {
                    // Must match the format FingerprintHardwareManager enrolls
                    // with, or MatchTemplate rejects every pair.
                    lib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_SG400)
                    sgfplib = lib
                    Logger.i("SecuGenMatcher: algorithm module ready")
                    lib
                }
            } catch (t: Throwable) {
                Logger.e("SecuGenMatcher: Init threw ${t.message}", t)
                null
            }
        }
    }

    private fun decode(value: String): ByteArray? = try {
        Base64.decode(value, Base64.DEFAULT)
    } catch (t: IllegalArgumentException) {
        Logger.w("SecuGenMatcher: template is not base64 — ${t.message}")
        null
    }

    companion object {
        const val NO_MATCH = -1
    }
}
