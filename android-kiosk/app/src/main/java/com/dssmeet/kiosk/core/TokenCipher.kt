package com.dssmeet.kiosk.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption for auth tokens at rest, with the key living in the
 * Android Keystore (hardware-backed where available) so it never leaves the
 * device and cannot be extracted even from a rooted filesystem dump.
 *
 * Stored format: `enc1:<base64 iv>:<base64 ciphertext>`. Values without the
 * prefix are legacy plaintext (pre-upgrade installs) and are re-encrypted on
 * next read by SessionManager.
 */
object TokenCipher {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dssmeet.session.v1"
    private const val PREFIX = "enc1:"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private val lock = Any()

    fun isEncrypted(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
    }

    private fun obtainKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /**
     * Returns the encrypted payload, or null if the Keystore is unavailable —
     * callers fall back to storing the value as-is (legacy plaintext path) so a
     * broken Keystore degrades instead of crashing logins.
     */
    fun encrypt(plain: String): String? = try {
        synchronized(lock) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX +
                Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }
    } catch (t: Throwable) {
        Logger.e("TokenCipher: encrypt failed — ${t.message}")
        null
    }

    /** Decrypts a stored value; returns null for legacy plaintext or on any failure. */
    fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return null
        return try {
            val payload = stored.removePrefix(PREFIX)
            val ivB64 = payload.substringBefore(':')
            val ctB64 = payload.substringAfter(':')
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            synchronized(lock) {
                cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Wrong key / tampered value: treat the token as absent so the
            // session dies and the user re-authenticates instead of silently
            // using an unverifiable credential.
            Logger.w("TokenCipher: decrypt failed — ${t.message}")
            null
        }
    }

    /** Test hook: IV length used by [encrypt]. */
    internal val ivLength: Int = GCM_IV_BYTES
}
