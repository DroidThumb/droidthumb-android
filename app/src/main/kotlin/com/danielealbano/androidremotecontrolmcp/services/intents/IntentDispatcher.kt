package com.danielealbano.androidremotecontrolmcp.services.intents

/**
 * Service for dispatching Android intents and opening URIs.
 *
 * Abstracts [android.content.Context] intent operations behind a testable interface.
 * Returns [Result] to signal success or failure without throwing.
 */
interface IntentDispatcher {
    /** Opens [uri] via `ACTION_VIEW`, optionally targeting [packageName] with [mimeType]. */
    suspend fun openUri(
        uri: String,
        packageName: String? = null,
        mimeType: String? = null,
    ): Result<Unit>
}
