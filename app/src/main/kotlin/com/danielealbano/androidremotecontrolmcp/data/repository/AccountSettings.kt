package com.danielealbano.androidremotecontrolmcp.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Account slice of the settings surface (design doc D-33), same split as [TransportSettings].
 * `accountId` is an opaque identifier, not a credential — unlike [ConnectorUrlSettings]'s secret,
 * it's stored in plain preferences, no encryption needed.
 */
interface AccountSettings {
    /** Observes the account this phone is claimed by, or `null` if never claimed. */
    val accountId: Flow<String?>

    suspend fun getAccountId(): String?

    suspend fun updateAccountId(accountId: String)
}
