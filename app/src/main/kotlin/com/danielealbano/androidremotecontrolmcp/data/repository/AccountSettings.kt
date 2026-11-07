package com.danielealbano.androidremotecontrolmcp.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Account slice of the settings surface (design doc D-33), same split as [TransportSettings].
 * `accountId` is an opaque identifier, not a credential — unlike [ConnectorUrlSettings]'s secret,
 * it's stored in plain preferences, no encryption needed.
 */
interface AccountSettings {
    /** Observes the account this phone is signed in to, or `null` if never signed in. */
    val accountId: Flow<String?>

    suspend fun getAccountId(): String?

    suspend fun updateAccountId(accountId: String)

    /** Clears the local account association ("Sign out", plan 70 US3's account avatar menu) —
     *  this device's own association is untouched server-side; signing back in with the same
     *  Google account reaches the same signed-in device, not a new one. */
    suspend fun clearAccountId()

    /** Observes whether a `sign_out` was requested but never acknowledged by the server — set as
     *  soon as a sign-out is asked for, and cleared only when `signed_out` arrives, so a phone
     *  that signed out offline sends `sign_out` on its next connection (sign-out.schema.json). */
    val pendingSignOut: Flow<Boolean>

    suspend fun getPendingSignOut(): Boolean

    suspend fun setPendingSignOut(pending: Boolean)
}
