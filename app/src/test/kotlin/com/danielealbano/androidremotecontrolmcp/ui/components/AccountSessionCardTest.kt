package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.AccountSessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AccountSessionCard copy mapping (plan A1a)")
class AccountSessionCardTest {
    @Test
    fun `Checking shows the checking copy with no action`() {
        val state = AccountSessionState.Checking
        assertEquals("Checking your sign-in…", sessionMessage(state))
        assertNull(sessionActionLabel(state))
    }

    @Test
    fun `SignedIn claimed shows nothing - the steady signed-in state has no card`() {
        val state = AccountSessionState.SignedIn(claimed = true)
        assertNull(sessionMessage(state))
        assertNull(sessionActionLabel(state))
    }

    @Test
    fun `SignedIn not claimed shows the link copy with the Link this phone action`() {
        val state = AccountSessionState.SignedIn(claimed = false)
        assertEquals("This phone isn't linked to your account.", sessionMessage(state))
        assertEquals("Link this phone", sessionActionLabel(state))
    }

    @Test
    fun `SignedOut shows the signed-out copy with the Sign in with Google action`() {
        val state = AccountSessionState.SignedOut
        assertEquals("You're signed out.", sessionMessage(state))
        assertEquals("Sign in with Google", sessionActionLabel(state))
    }

    @Test
    fun `Unreachable shows the reason with the Retry action`() {
        val state = AccountSessionState.Unreachable("Connection refused")
        assertEquals("Can't reach the server: Connection refused", sessionMessage(state))
        assertEquals("Retry", sessionActionLabel(state))
    }

    @Test
    fun `ServerError shows the HTTP status with the Retry action`() {
        val state = AccountSessionState.ServerError(500)
        assertEquals("The server had a problem (HTTP 500).", sessionMessage(state))
        assertEquals("Retry", sessionActionLabel(state))
    }
}
