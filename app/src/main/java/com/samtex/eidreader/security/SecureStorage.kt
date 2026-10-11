package com.samtex.eidreader.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Authenticated encryption for data persisted on the device (scan history). */
interface SecureStorage {
    /** Returns IV || ciphertext+tag. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** @throws GeneralSecurityException if the data was tampered with or the key is gone. */
    fun decrypt(payload: ByteArray): ByteArray

    /** Destroys the key, making all previously encrypted data unreadable. */
    fun destroyKey()
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore.
 * The key never leaves secure hardware (when available) and is lost on app uninstall.
 */
@Singleton
class KeystoreSecureStorage @Inject constructor() : SecureStorage {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "Unexpected GCM IV length" }
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size <= IV_LENGTH) throw GeneralSecurityException("Encrypted payload too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH))
        return cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH)
    }

    @Synchronized
    override fun destroyKey() {
        try {
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        } catch (_: GeneralSecurityException) {
            // Nothing to destroy.
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "samtex_history_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
