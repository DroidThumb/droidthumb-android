package com.danielealbano.androidremotecontrolmcp.services.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256/GCM encryption for the connector URL at rest (server#16: "store it durably... Keystore-
 * encrypted, never logged, not in backups/crash reports"). The key never leaves the Android
 * Keystore. Ciphertext is stored as `base64(iv):base64(bytes)`
 * ([com.danielealbano.androidremotecontrolmcp.data.repository.ConnectorUrlSettingsImpl]).
 */
interface ConnectorSecretCrypto {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String
}

@Singleton
class ConnectorSecretCryptoImpl
    @Inject
    constructor() : ConnectorSecretCrypto {
        private val keyStore: KeyStore by lazy {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        }

        private val secretKey: SecretKey
            get() {
                (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
                val spec =
                    KeyGenParameterSpec
                        .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                generator.init(spec)
                return generator.generateKey()
            }

        override fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
            val bytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

        override fun decrypt(ciphertext: String): String {
            val (ivPart, bytesPart) = ciphertext.split(":", limit = 2)
            val iv = Base64.decode(ivPart, Base64.NO_WRAP)
            val bytes = Base64.decode(bytesPart, Base64.NO_WRAP)
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                }
            return String(cipher.doFinal(bytes), Charsets.UTF_8)
        }

        private companion object {
            const val PROVIDER = "AndroidKeyStore"
            const val ALIAS = "droidthumb_connector_secret"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val GCM_TAG_LENGTH_BITS = 128
        }
    }
