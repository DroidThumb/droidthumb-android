package com.danielealbano.androidremotecontrolmcp.services.account

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GoogleSignInResult {
    data class Success(
        val idToken: String,
    ) : GoogleSignInResult

    /** No credential available for a silent (`filterByAuthorizedAccounts = true`) request — the
     *  expected outcome the first time, not an error; the caller falls back to an interactive one. */
    data object NoCredential : GoogleSignInResult

    data class Failed(
        val message: String,
    ) : GoogleSignInResult
}

interface GoogleSignInClient {
    /**
     * Requests a Google id_token via Credential Manager. `filterByAuthorizedAccounts = true` tries
     * silently, with no UI, against an account the user has already signed in with on this app
     * before — used to re-fetch a fresh token for the AI-connections list without demanding an
     * interactive sign-in every time (design doc D-33/D-38). `false` shows the account picker; this
     * is the first sign-in, and the fallback when a silent request returns [GoogleSignInResult.NoCredential].
     */
    suspend fun signIn(
        context: Context,
        filterByAuthorizedAccounts: Boolean,
    ): GoogleSignInResult

    /** Clears Credential Manager's own remembered sign-in state (sign-out) — does not touch
     *  anything server-side; revoking an AI connection is a separate, explicit action. */
    suspend fun signOut(context: Context)
}

@Singleton
class GoogleSignInClientImpl
    @Inject
    constructor() : GoogleSignInClient {
        @Suppress("TooGenericExceptionCaught")
        override suspend fun signIn(
            context: Context,
            filterByAuthorizedAccounts: Boolean,
        ): GoogleSignInResult {
            if (BuildConfig.GOOGLE_SERVER_CLIENT_ID.isBlank()) {
                return GoogleSignInResult.Failed("GOOGLE_SERVER_CLIENT_ID is not configured for this build")
            }
            val option =
                GetGoogleIdOption
                    .Builder()
                    .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                    .setServerClientId(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
                    .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            return try {
                val response = CredentialManager.create(context).getCredential(context, request)
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
                GoogleSignInResult.Success(googleIdTokenCredential.idToken)
            } catch (e: GetCredentialException) {
                if (filterByAuthorizedAccounts) {
                    GoogleSignInResult.NoCredential
                } else {
                    GoogleSignInResult.Failed(e.message ?: "sign-in failed")
                }
            } catch (e: GoogleIdTokenParsingException) {
                GoogleSignInResult.Failed(e.message ?: "could not parse the Google id_token")
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun signOut(context: Context) {
            try {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                // Best-effort: this clears Credential Manager's own state, nothing server-side -
                // nothing meaningful to recover or surface if the platform call itself fails.
                Unit
            }
        }
    }
