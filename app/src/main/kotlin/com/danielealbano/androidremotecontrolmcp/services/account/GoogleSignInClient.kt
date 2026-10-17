package com.danielealbano.androidremotecontrolmcp.services.account

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GoogleSignInResult {
    /** [displayName]/[email]/[profilePictureUri] are parsed by [GoogleIdTokenCredential] itself
     *  from the same credential this id_token comes from — the account avatar menu's display
     *  fields (plan 70 US3), never sent anywhere; the server only ever sees [idToken]. */
    data class Success(
        val idToken: String,
        val displayName: String? = null,
        val email: String? = null,
        val profilePictureUri: String? = null,
    ) : GoogleSignInResult

    /** The silent request found no already-authorized account — the expected outcome the first
     *  time, not an error. Only ever produced by [GoogleSignInClient.signInSilently]. */
    data object NoCredential : GoogleSignInResult

    /** The user dismissed the interactive sheet without signing in — a choice, not an error;
     *  the caller shows nothing and leaves the session state for the next check. */
    data object Cancelled : GoogleSignInResult

    data class Failed(
        val message: String,
    ) : GoogleSignInResult
}

interface GoogleSignInClient {
    /**
     * One silent Google id_token request via Credential Manager — `filterByAuthorizedAccounts =
     * true` with auto-select on, against an account the user has already signed in with on this
     * app before. No UI on its own: any failure (including a non-[NoCredentialException]
     * credential error such as being offline) is returned as [GoogleSignInResult.Failed] with
     * its reason, never mapped onto "no account" (design doc D-33/D-38; plan A1 review).
     */
    suspend fun signInSilently(context: Context): GoogleSignInResult

    /**
     * One explicit sign-in request via Credential Manager's `GetSignInWithGoogleOption` —
     * Google's own flow for a "Sign in with Google" button the user just tapped (plan A1
     * review). Exactly one request and one sheet: there is deliberately NO fallback to a second
     * attempt of any kind — this result (success, [GoogleSignInResult.Cancelled], or
     * [GoogleSignInResult.Failed] with the reason) is final for the tap that started it.
     */
    suspend fun signInInteractively(context: Context): GoogleSignInResult

    /** Clears Credential Manager's own remembered sign-in state (sign-out) — does not touch
     *  anything server-side; revoking an AI connection is a separate, explicit action. */
    suspend fun signOut(context: Context)
}

@Singleton
class GoogleSignInClientImpl
    @Inject
    constructor() : GoogleSignInClient {
        override suspend fun signInSilently(context: Context): GoogleSignInResult {
            Logger.i(TAG, "Silent Google id_token requested")
            if (BuildConfig.GOOGLE_SERVER_CLIENT_ID.isBlank()) {
                Logger.w(TAG, "Silent Google id_token request failed: server client id not configured")
                return GoogleSignInResult.Failed("GOOGLE_SERVER_CLIENT_ID is not configured for this build")
            }
            // Auto-select on + authorized accounts only: the request resolves with no Google UI
            // whenever an authorized account exists, and never on its own otherwise (it returns
            // NoCredential instead) — Home runs this on every resume.
            val option =
                GetGoogleIdOption
                    .Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setAutoSelectEnabled(true)
                    .setServerClientId(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
                    .build()
            return try {
                requestGoogleIdToken(context, option, "Silent")
            } catch (_: NoCredentialException) {
                // No already-authorized account on this device — the expected first-run outcome,
                // not an error. Every OTHER credential failure (offline, a Google-side error)
                // stays a failure with its reason; it must never read as "signed out".
                Logger.i(TAG, "Silent Google id_token request found no authorized account")
                GoogleSignInResult.NoCredential
            } catch (e: GetCredentialException) {
                Logger.w(TAG, "Silent Google id_token request failed: ${e::class.java.simpleName}")
                GoogleSignInResult.Failed(readableMessage(e))
            }
        }

        override suspend fun signInInteractively(context: Context): GoogleSignInResult {
            Logger.i(TAG, "Sign in with Google button flow requested")
            if (BuildConfig.GOOGLE_SERVER_CLIENT_ID.isBlank()) {
                Logger.w(TAG, "Sign in with Google button flow failed: server client id not configured")
                return GoogleSignInResult.Failed("GOOGLE_SERVER_CLIENT_ID is not configured for this build")
            }
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID).build()
            return try {
                requestGoogleIdToken(context, option, "Sign in with Google button flow")
            } catch (e: GetCredentialException) {
                if (e is GetCredentialCancellationException) {
                    // The user closed the sheet without signing in — a choice, not an error.
                    Logger.i(TAG, "Sign in with Google button flow cancelled by the user")
                    GoogleSignInResult.Cancelled
                } else {
                    Logger.w(TAG, "Sign in with Google button flow failed: ${e::class.java.simpleName}")
                    GoogleSignInResult.Failed(readableMessage(e))
                }
            }
        }

        /** One `getCredential` round-trip for one option — the only place a Google request is
         *  ever made. [GetCredentialException]s are caught by the calling method above (so each
         *  flow's own cancellation/error mapping applies); parse failures are failures here. */
        private suspend fun requestGoogleIdToken(
            context: Context,
            option: CredentialOption,
            flowLabel: String,
        ): GoogleSignInResult {
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            return try {
                val response = CredentialManager.create(context).getCredential(context, request)
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
                googleIdTokenCredential.toSuccessResult()
            } catch (e: GoogleIdTokenParsingException) {
                Logger.w(TAG, "$flowLabel failed: ${e::class.java.simpleName}: ${e.message}")
                GoogleSignInResult.Failed(e.message ?: "could not parse the Google id_token")
            }
        }

        private fun GoogleIdTokenCredential.toSuccessResult(): GoogleSignInResult.Success {
            Logger.i(TAG, "Google id_token obtained")
            return GoogleSignInResult.Success(
                idToken = idToken,
                displayName = displayName,
                email = email,
                profilePictureUri = profilePictureUri?.toString(),
            )
        }

        /** [GetCredentialException]'s own `message` is a platform/debug string (e.g. raw
         *  `NoCredentialException` text) never meant for a user-facing screen - mapped to something
         *  readable instead of surfaced verbatim. */
        private fun readableMessage(e: GetCredentialException): String =
            when (e) {
                is NoCredentialException -> "No Google account is available to sign in with on this device"
                else -> "Google sign-in failed - please try again"
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override suspend fun signOut(context: Context) {
            try {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                // Best-effort: this clears Credential Manager's own state, nothing server-side -
                // nothing meaningful to recover or surface if the platform call itself fails.
                Logger.w(TAG, "Clearing Credential Manager state failed: ${e::class.java.simpleName}")
                Unit
            }
        }

        private companion object {
            private const val TAG = "MCP:GoogleSignIn"
        }
    }
