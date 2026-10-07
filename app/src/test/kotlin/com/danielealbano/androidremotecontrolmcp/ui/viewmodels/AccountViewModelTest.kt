package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.account.AccountApiClient
import com.danielealbano.androidremotecontrolmcp.services.account.AccountConnection
import com.danielealbano.androidremotecontrolmcp.services.account.ClaimTokenResult
import com.danielealbano.androidremotecontrolmcp.services.account.ConnectionsResult
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInClient
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInResult
import com.danielealbano.androidremotecontrolmcp.services.account.RevokeResult
import com.danielealbano.androidremotecontrolmcp.services.transport.ClaimResult
import com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AccountViewModel")
class AccountViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val googleSignInClient = mockk<GoogleSignInClient>()
    private val accountApiClient = mockk<AccountApiClient>()
    private val transportClient = mockk<DeviceTransportClient>()
    private val context = mockk<Context>(relaxed = true)

    private val accountIdFlow = MutableStateFlow<String?>(null)
    private val transportConfig = TransportConfig(host = "h", port = 1, tls = false)

    private lateinit var viewModel: AccountViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.accountId } returns accountIdFlow
        coEvery { settingsRepository.getTransportConfig() } returns transportConfig
        viewModel =
            AccountViewModel(settingsRepository, googleSignInClient, accountApiClient, transportClient, testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signInAndClaim runs the full chain and ends Claimed on success`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) } returns
                GoogleSignInResult.Success("id-token")
            // The claim's own success path triggers loadConnections internally, which tries
            // silently first (freshIdTokenOrNull) - stubbed here too, not just the interactive call.
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken("h", 1, false, "id-token") } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.Claimed("acc_1")
            coEvery { accountApiClient.listConnections("h", 1, false, "id-token") } returns
                ConnectionsResult.Success(emptyList())

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.Claimed("acc_1"), viewModel.claimState.value)
            coVerify { settingsRepository.updateAccountId("acc_1") }
        }

    @Test
    fun `signInAndClaim surfaces AlreadyClaimedByOther, not a generic failure`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) } returns
                GoogleSignInResult.Success("id-token")
            coEvery { accountApiClient.mintClaimToken(any(), any(), any(), any()) } returns
                ClaimTokenResult.Success(claimToken = "clt_x", accountId = "acc_1")
            coEvery { transportClient.claimAccount("clt_x") } returns ClaimResult.Rejected("already_claimed")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.AlreadyClaimedByOther, viewModel.claimState.value)
            coVerify(exactly = 0) { settingsRepository.updateAccountId(any()) }
        }

    @Test
    fun `signInAndClaim stops at Failed when Google sign-in itself fails`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) } returns
                GoogleSignInResult.Failed("denied")

            viewModel.signInAndClaim(context)
            advanceUntilIdle()

            assertEquals(AccountClaimState.Failed("denied"), viewModel.claimState.value)
            coVerify(exactly = 0) { accountApiClient.mintClaimToken(any(), any(), any(), any()) }
        }

    @Test
    fun `loadConnections tries silently first, only shows the picker if that finds nothing`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { accountApiClient.listConnections("h", 1, false, "silent-token") } returns
                ConnectionsResult.Success(listOf(AccountConnection("c1", "Claude", "2026-10-01")))

            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            val expected = ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "2026-10-01")))
            assertEquals(expected, viewModel.connectionsState.value)
            coVerify(exactly = 0) { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) }
        }

    @Test
    fun `loadConnections falls back to the picker when silent fails and interactive is allowed`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.NoCredential
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) } returns
                GoogleSignInResult.Success("interactive-token")
            coEvery { accountApiClient.listConnections("h", 1, false, "interactive-token") } returns
                ConnectionsResult.Success(emptyList())

            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            assertTrue(viewModel.connectionsState.value is ConnectionsState.Loaded)
        }

    @Test
    fun `loadConnections never shows the picker when interactive is not allowed, even if silent finds nothing`() =
        runTest {
            // An automatic, non-user-initiated refresh (app reopened, phone rebooted) must never
            // put up Google's own account-picker UI on its own - being claimed is this device's
            // own durable state and must not look like a sign-out just because a background
            // connections refresh needed a fresh token (founder feedback, PR #8 round 5).
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.NoCredential

            viewModel.loadConnections(context, allowInteractive = false)
            advanceUntilIdle()

            assertEquals(
                ConnectionsState.Failed("Sign in to view your AI connections"),
                viewModel.connectionsState.value,
            )
            coVerify(exactly = 0) { googleSignInClient.signIn(context, filterByAuthorizedAccounts = false) }
        }

    @Test
    fun `revokeConnection removes the row from the loaded list on success`() =
        runTest {
            coEvery { googleSignInClient.signIn(context, filterByAuthorizedAccounts = true) } returns
                GoogleSignInResult.Success("token")
            coEvery { accountApiClient.listConnections("h", 1, false, "token") } returns
                ConnectionsResult.Success(listOf(AccountConnection("c1", "Claude", "2026-10-01")))
            viewModel.loadConnections(context, allowInteractive = true)
            advanceUntilIdle()

            coEvery { accountApiClient.revokeConnection("h", 1, false, "token", "c1") } returns RevokeResult.Revoked

            viewModel.revokeConnection(context, "c1")
            advanceUntilIdle()

            assertEquals(ConnectionsState.Loaded(emptyList()), viewModel.connectionsState.value)
        }
}
