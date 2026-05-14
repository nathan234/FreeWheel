package org.freewheel.core.domain

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android implementation of [WheelPasswordStore] backed by AndroidKeyStore +
 * AES-GCM. The Keystore-bound key never leaves secure hardware (when the
 * device has a Secure Element / TEE); only the resulting ciphertext + IV
 * lands in [prefs], so a backup or device-clone leak yields no plaintext.
 *
 * Storage layout in [prefs]:
 *   `vlock_pwd_{address}` → Base64(IV || ciphertext)
 *
 * The IV length is fixed at 12 bytes (NIST-recommended for GCM). The auth
 * tag length is the JCA default of 128 bits.
 *
 * **API gate (SDK 23+):** Constructing this directly on API 21–22 will throw
 * `NoClassDefFoundError` for `KeyGenParameterSpec`. Always go through
 * [AndroidWheelPasswordStoreFactory] which falls back to a [NoOpWheelPasswordStore]
 * (passwords disabled) below SDK 23.
 */
class KeystoreWheelPasswordStore(
    private val prefs: SharedPreferences,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
) : WheelPasswordStore {

    override fun getPassword(address: String): String? {
        val blob = prefs.getString(prefKey(address), null) ?: return null
        return try {
            decrypt(blob)
        } catch (e: Throwable) {
            // Keystore key was rotated/wiped (factory reset, app uninstalled+reinstalled
            // on some OEMs, biometric reset). Treat the entry as gone — the user will
            // be re-prompted to enter the password.
            prefs.edit().remove(prefKey(address)).apply()
            null
        }
    }

    override fun hasPassword(address: String): Boolean =
        getPassword(address)?.isNotBlank() == true

    override fun setPassword(address: String, password: String) {
        if (password.isBlank()) {
            clearPassword(address)
            return
        }
        val blob = encrypt(password)
        prefs.edit().putString(prefKey(address), blob).apply()
    }

    override fun clearPassword(address: String) {
        prefs.edit().remove(prefKey(address)).apply()
    }

    override fun clearAll() {
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(keyPrefix)) editor.remove(key)
        }
        editor.apply()
    }

    private fun prefKey(address: String): String = keyPrefix + address

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size).also {
            iv.copyInto(it, destinationOffset = 0)
            ciphertext.copyInto(it, destinationOffset = iv.size)
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val combined = Base64.decode(blob, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keystore.getKey(keyAlias, null)?.let { return it as SecretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "freewheel_password_store_v1"
        const val DEFAULT_KEY_PREFIX = "vlock_pwd_"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
