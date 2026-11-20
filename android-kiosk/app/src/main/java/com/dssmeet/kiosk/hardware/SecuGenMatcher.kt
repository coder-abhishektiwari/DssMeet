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
            val stored = decode(candidateBase64, "candidate[$index]", validateCandidate = true)
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

    private fun openAlgorithm(): JSGFPLib? = try {
        // The capture thread closes its own JSGFPLib after every scan. This
        // SDK keeps global native state, so a cached matcher instance becomes
        // stale after that Close() and can crash inside MatchTemplate. Always
        // open a fresh algorithm session once the capture port is free.
        fingerprintHardwareManager.awaitDriverIdle()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val lib = JSGFPLib(context, usbManager)
        val err = lib.Init(SGFDxDeviceName.SG_DEV_AUTO)
        if (err != SGFDxErrorCode.SGFDX_ERROR_NONE.toLong()) {
            Logger.w("SecuGenMatcher: Init failed ($err) — fingerprint matching unavailable")
            closeAlgorithm(lib)
            null
        } else {
            // Must match the format FingerprintHardwareManager enrolls
            // with, or MatchTemplate rejects every pair.
            lib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_SG400)
            Logger.i("SecuGenMatcher: algorithm session ready")
            lib
        }
    } catch (t: Throwable) {
        Logger.e("SecuGenMatcher: Init threw ${t.message}", t)
        null
    }

    private fun closeAlgorithm(lib: JSGFPLib) {
        try {
            lib.Close()
        } catch (t: Throwable) {
            Logger.w("SecuGenMatcher: Close failed — ${t.message}")
        }
    }

    private fun decode(value: String, label: String, validateCandidate: Boolean = false): ByteArray? = try {
        val normalized = value.trim()
        when {
            validateCandidate && hasRepeatedBase64Chunk(normalized) -> {
                Logger.w("SecuGenMatcher: $label template is corrupt (repeated base64 block) — re-enroll required")
                null
            }
            else -> {
                val bytes = Base64.decode(normalized, Base64.DEFAULT)
                when {
                    bytes.size != TEMPLATE_SIZE -> {
                        Logger.w("SecuGenMatcher: $label template has ${bytes.size} bytes (expected $TEMPLATE_SIZE)")
                        null
                    }
                    validateCandidate && hasShortPeriod(bytes) -> {
                        Logger.w("SecuGenMatcher: $label template is corrupt (repeating pattern) — re-enroll required")
                        null
                    }
                    else -> bytes
                }
            }
        }
    } catch (t: IllegalArgumentException) {
        Logger.w("SecuGenMatcher: $label template is not base64 — ${t.message}")
        null
    }

    private fun hasRepeatedBase64Chunk(value: String): Boolean {
        if (value.length < BASE64_CHUNK_SIZE * MIN_CHUNK_REPEATS) return false
        val counts = HashMap<String, Int>()
        for (index in 0..value.length - BASE64_CHUNK_SIZE) {
            val chunk = value.substring(index, index + BASE64_CHUNK_SIZE)
            val count = (counts[chunk] ?: 0) + 1
            if (count >= MIN_CHUNK_REPEATS) return true
            counts[chunk] = count
        }
        return false
    }

    private fun hasShortPeriod(bytes: ByteArray): Boolean {
        for (period in MIN_PERIOD..MAX_PERIOD) {
            var matches = 0
            for (i in period until bytes.size) {
                if (bytes[i] == bytes[i - period]) matches++
            }
            val total = bytes.size - period
            if (matches * 100 >= total * PERIOD_RATIO_PERCENT) return true
        }
        return false
    }

    companion object {
        const val NO_MATCH = -1
        private const val TEMPLATE_SIZE = 400
        private const val MIN_PERIOD = 4
        private const val MAX_PERIOD = 32
        private const val PERIOD_RATIO_PERCENT = 60
        private const val BASE64_CHUNK_SIZE = 64
        private const val MIN_CHUNK_REPEATS = 3
    }
}
