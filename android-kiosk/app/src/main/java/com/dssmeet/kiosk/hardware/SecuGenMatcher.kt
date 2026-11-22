package com.dssmeet.kiosk.hardware

import android.content.Context
import android.hardware.usb.UsbManager
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

    private val matchLock = Any()

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
        val probe = decode(probeBase64, "probe") ?: return NO_MATCH

        val usable = mutableListOf<Pair<Int, ByteArray>>()
        candidates.forEachIndexed { index, candidateBase64 ->
            val stored = decode(candidateBase64, "candidate[$index]")
            if (stored != null) usable += index to stored
        }
        if (usable.isEmpty()) {
            Logger.w("SecuGenMatcher: no usable candidates — ${candidates.size} skipped")
            return NO_MATCH
        }

        val lib = openAlgorithm() ?: return NO_MATCH
        val matched = BooleanArray(1)
        val score = IntArray(1)
        var bestIndex = NO_MATCH
        var bestScore = Int.MIN_VALUE

        try {
            synchronized(matchLock) {
                for ((index, stored) in usable) {
                    matched[0] = false
                    val matchErr = lib.MatchTemplate(probe, stored, SGFDxSecurityLevel.SL_NORMAL, matched)
                    Logger.d("SecuGenMatcher: MatchTemplate candidate[$index] result=$matchErr matched=${matched[0]}")
                    if (matchErr != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                        Logger.w("SecuGenMatcher: MatchTemplate failed for candidate[$index] ($matchErr)")
                        continue
                    }
                    if (!matched[0]) continue

                    // SL_NORMAL can accept more than one candidate in a large
                    // roster, so rank the accepted ones by score and keep the best.
                    score[0] = 0
                    val scoreErr = lib.GetMatchingScore(probe, stored, score)
                    Logger.d("SecuGenMatcher: GetMatchingScore candidate[$index] result=$scoreErr score=${score[0]}")
                    if (scoreErr != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                        Logger.w("SecuGenMatcher: GetMatchingScore failed for candidate[$index] ($scoreErr)")
                        if (bestIndex == NO_MATCH) bestIndex = index
                        continue
                    }
                    if (score[0] > bestScore) {
                        bestScore = score[0]
                        bestIndex = index
                    }
                }
            }
        } finally {
            closeAlgorithm(lib)
        }

        val skipped = candidates.size - usable.size
        Logger.i(
            "SecuGenMatcher: ${usable.size} candidate(s) compared" +
                (if (skipped > 0) ", $skipped skipped" else "") +
                " — " + if (bestIndex == NO_MATCH) "no match" else "matched index $bestIndex (score=$bestScore)"
        )
        return bestIndex
    }

    private fun openAlgorithm(): JSGFPLib? {
        try {
            if (!fingerprintHardwareManager.awaitDriverIdle()) {
                Logger.w("SecuGenMatcher: capture driver still active — matching aborted")
                return null
            }
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val lib = JSGFPLib(context, usbManager)
            val initErr = lib.Init(SGFDxDeviceName.SG_DEV_AUTO)
            Logger.i("SecuGenMatcher: Init result=$initErr")
            if (initErr != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                Logger.w("SecuGenMatcher: Init failed ($initErr) — fingerprint matching unavailable")
                closeAlgorithm(lib)
                return null
            }
            val formatErr = lib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_SG400)
            Logger.i("SecuGenMatcher: SetTemplateFormat(SG400) result=$formatErr")
            if (formatErr != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
                Logger.w("SecuGenMatcher: SetTemplateFormat failed ($formatErr) — matching unavailable")
                closeAlgorithm(lib)
                return null
            }
            Logger.i("SecuGenMatcher: algorithm session ready")
            return lib
        } catch (t: Throwable) {
            Logger.e("SecuGenMatcher: Init threw ${t.message}", t)
            return null
        }
    }

    private fun closeAlgorithm(lib: JSGFPLib) {
        try {
            lib.Close()
        } catch (t: Throwable) {
            Logger.w("SecuGenMatcher: Close failed — ${t.message}")
        }
    }

    private fun decode(value: String, label: String): ByteArray? {
        val bytes = FingerprintTemplateValidator.decode(value)
        if (bytes == null) {
            Logger.w("SecuGenMatcher: $label template rejected (${FingerprintTemplateValidator.validationError(value)})")
        } else {
            Logger.d("SecuGenMatcher: $label decodedSize=${bytes.size}")
        }
        return bytes
    }

    companion object {
        const val NO_MATCH = -1
    }
}
