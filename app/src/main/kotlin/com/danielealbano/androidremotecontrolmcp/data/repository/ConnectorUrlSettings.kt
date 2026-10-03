package com.danielealbano.androidremotecontrolmcp.data.repository

import kotlinx.coroutines.flow.Flow

/** The per-device secret connector URL (plan 03 milestone 3, D-29): `https://<host>/d/<secret>/mcp`,
 *  returned once by `POST /devices/register` and recoverable via `regenerate_secret` if lost. Stored
 *  encrypted at rest via [com.danielealbano.androidremotecontrolmcp.services.identity.ConnectorSecretCrypto]. */
interface ConnectorUrlSettings {
    val connectorUrl: Flow<String?>

    suspend fun getConnectorUrl(): String?

    suspend fun updateConnectorUrl(url: String)
}
