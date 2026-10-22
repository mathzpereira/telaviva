package app.telaviva.androidtv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CameraCredentialCipher {
    private const val KEY_ALIAS = "tela-viva-camera-credentials-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8

    @Synchronized
    fun encrypt(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        if (iv.size != IV_LENGTH_BYTES) {
            throw GeneralSecurityException("Android Keystore returned an invalid GCM IV")
        }

        val encrypted = cipher.doFinal(value)
        return ByteArray(iv.size + encrypted.size).also { result ->
            iv.copyInto(result, destinationOffset = 0)
            encrypted.copyInto(result, destinationOffset = iv.size)
            encrypted.fill(0)
        }
    }

    @Synchronized
    fun decrypt(value: ByteArray): ByteArray {
        if (value.size < IV_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            throw GeneralSecurityException("Encrypted camera credentials are truncated")
        }

        val iv = value.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = value.copyOfRange(IV_LENGTH_BYTES, value.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, iv),
            )
            cipher.doFinal(encrypted)
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
