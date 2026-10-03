package com.danielealbano.androidremotecontrolmcp.services.identity

import java.security.MessageDigest

/**
 * `device_id = "dt_" + lowercase hex SHA-256` of the public key's X.509 SubjectPublicKeyInfo DER
 * bytes (EC P-256) — never chosen by the device (droidthumb-server: `src/devices/device-id.ts`;
 * test vectors: `droidthumb-protocol/examples/device-id-vectors.json`). Pure JVM code (no Android
 * Keystore dependency) so it's directly unit-testable; the DER bytes it's called with come from
 * [DeviceIdentityKeyStore.ensurePublicKeyBase64] base64-decoded.
 */
fun deriveDeviceId(publicKeySpkiDer: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKeySpkiDer)
    return "dt_" + digest.joinToString("") { "%02x".format(it) }
}
