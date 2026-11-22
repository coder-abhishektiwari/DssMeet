package com.dssmeet.kiosk.hardware

import android.util.Base64

internal object FingerprintTemplateValidator {
    private const val TEMPLATE_SIZE = 400
    private const val BASE64_LENGTH = 536
    private const val BASE64_DATA_LENGTH = BASE64_LENGTH - 2
    private const val ENCODE_FLAGS = Base64.NO_WRAP
    private const val DECODE_FLAGS = Base64.DEFAULT
    private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]{$BASE64_DATA_LENGTH}==$")

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, ENCODE_FLAGS)

    fun validationError(value: String): String? {
        val normalized = value.trim()
        if (!BASE64_PATTERN.matches(normalized)) return "expected $BASE64_LENGTH-character SG400 template"
        return try {
            val bytes = decodeUnchecked(normalized)
            when {
                bytes.size != TEMPLATE_SIZE -> "expected $TEMPLATE_SIZE bytes"
                bytes.toSet().size == 1 -> "blank template"
                else -> null
            }
        } catch (t: IllegalArgumentException) {
            "not base64"
        }
    }

    fun decode(value: String): ByteArray? = try {
        val normalized = value.trim()
        if (validationError(normalized) == null) decodeUnchecked(normalized) else null
    } catch (t: IllegalArgumentException) {
        null
    }

    private fun decodeUnchecked(value: String): ByteArray = Base64.decode(value, DECODE_FLAGS)
}
