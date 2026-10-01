package com.danielealbano.androidremotecontrolmcp.services.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's persistent identity (D-27): an EC (P-256) key pair generated once in the Android
 * Keystore, hardware-backed where the device supports it (`AndroidKeyStore` uses StrongBox/TEE
 * automatically when available for this key's purposes; no explicit opt-in needed), and never
 * exported. [ensurePublicKeyBase64] returns the base64 X.509 SPKI DER public key sent to
 * `POST /devices/register` and used to derive `device_id` ([deriveDeviceId]); [signNonce] answers
 * the connect-time `challenge` with an ASN.1 DER ECDSA signature
 * (droidthumb-protocol/schema/challenge-response.schema.json) — the default output shape of
 * `Signature.getInstance("SHA256withECDSA")` against a Keystore-backed key, matching the schema's
 * own documented expectation.
 */
interface DeviceIdentityKeyStore {
    fun ensurePublicKeyBase64(): String

    fun signNonce(nonceBase64: String): String
}

@Singleton
class DeviceIdentityKeyStoreImpl
    @Inject
    constructor() : DeviceIdentityKeyStore {
        private val keyStore: KeyStore by lazy {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        }

        override fun ensurePublicKeyBase64(): String {
            if (!keyStore.containsAlias(ALIAS)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
                val spec =
                    KeyGenParameterSpec
                        .Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build()
                generator.initialize(spec)
                generator.generateKeyPair()
            }
            val publicKey = keyStore.getCertificate(ALIAS).publicKey
            return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        }

        override fun signNonce(nonceBase64: String): String {
            val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
            val nonceBytes = Base64.decode(nonceBase64, Base64.NO_WRAP)
            val signature =
                Signature.getInstance("SHA256withECDSA").apply {
                    initSign(privateKey)
                    update(nonceBytes)
                }
            return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        }

        private companion object {
            const val PROVIDER = "AndroidKeyStore"
            const val ALIAS = "droidthumb_device_identity"
        }
    }
