package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.PauseState
import com.danielealbano.androidremotecontrolmcp.data.model.TransportConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.PauseSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.account.AccountApiClient
import com.danielealbano.androidremotecontrolmcp.services.account.AccountConnection
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInClient
import com.danielealbano.androidremotecontrolmcp.services.account.GoogleSignInResult
import com.danielealbano.androidremotecontrolmcp.services.account.RenameResult
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceIdentityKeyStore
import com.danielealbano.androidremotecontrolmcp.services.identity.DeviceInfoProvider
import com.danielealbano.androidremotecontrolmcp.services.transport.AiConnectionsResult
import com.danielealbano.androidremotecontrolmcp.services.transport.AiRevokeResult
import com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClient
import com.danielealbano.androidremotecontrolmcp.services.transport.TransportStatus
import com.danielealbano.androidremotecontrolmcp.wireprotocol.AiConnection
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

/** The AI-connections half of [AccountViewModelTest] — list/revoke/rename over the phone
 *  connection — split out to keep that class under detekt's `LargeClass` threshold. */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AccountViewModel AI connections")
class AccountViewModelAiConnectionsTest {
    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val googleSignInClient = mockk<GoogleSignInClient>()
    private val accountApiClient = mockk<AccountApiClient>()
    private val transportClient = mockk<DeviceTransportClient>()
    private val pauseSettings = mockk<PauseSettings>()
    private val deviceIdentityKeyStore = mockk<DeviceIdentityKeyStore>(relaxed = true)
    private val deviceInfoProvider = mockk<DeviceInfoProvider>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val accountIdFlow = MutableStateFlow<String?>(null)
    private val transportConfig = TransportConfig(host = "h", port = 1, tls = false)
    private val transportStatusFlow =
        MutableStateFlow<TransportStatus>(TransportStatus.Connected(2, accounts = true, accountId = null))

    private lateinit var viewModel: AccountViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.accountId } returns accountIdFlow
        coEvery { settingsRepository.getTransportConfig() } returns transportConfig
        coEvery { pauseSettings.getPauseState() } returns PauseState()
        every { transportClient.status } returns transportStatusFlow
        viewModel =
            AccountViewModel(
                settingsRepository,
                googleSignInClient,
                accountApiClient,
                transportClient,
                pauseSettings,
                deviceIdentityKeyStore,
                deviceInfoProvider,
                testDispatcher,
            )
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadConnections lists over the phone connection and never touches Google interactively`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { transportClient.listAiConnections() } returns
                AiConnectionsResult.Connections(
                    listOf(
                        AiConnection(
                            clientId = "c1",
                            clientName = "Claude",
                            displayName = "Claude",
                            imageUrl = null,
                            connectedAt = "2026-10-01",
                            lastUsedAt = null,
                        ),
                    ),
                )

            viewModel.loadConnections(context)
            advanceUntilIdle()

            val expected =
                ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01")))
            assertEquals(expected, viewModel.connectionsState.value)
            coVerify(exactly = 0) { googleSignInClient.signInInteractively(context) }
        }

    @Test
    fun `loadConnections still lists when Google has no credential - the list needs no token`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.NoCredential
            coEvery { transportClient.listAiConnections() } returns
                AiConnectionsResult.Connections(emptyList())

            viewModel.loadConnections(context)
            advanceUntilIdle()

            assertTrue(viewModel.connectionsState.value is ConnectionsState.Loaded)
            coVerify(exactly = 0) { googleSignInClient.signInInteractively(context) }
        }

    @Test
    fun `loadConnections shows the server's not_signed_in error plainly`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { transportClient.listAiConnections() } returns
                AiConnectionsResult.Error("not_signed_in", null)

            viewModel.loadConnections(context)
            advanceUntilIdle()

            assertEquals(ConnectionsState.Failed("not_signed_in"), viewModel.connectionsState.value)
        }

    @Test
    fun `revokeConnection removes the row from the loaded list on success`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { transportClient.listAiConnections() } returns
                AiConnectionsResult.Connections(
                    listOf(
                        AiConnection(
                            clientId = "c1",
                            clientName = "Claude",
                            displayName = "Claude",
                            imageUrl = null,
                            connectedAt = "2026-10-01",
                            lastUsedAt = null,
                        ),
                    ),
                )
            viewModel.loadConnections(context)
            advanceUntilIdle()

            coEvery { transportClient.revokeAiConnection("c1") } returns AiRevokeResult.Revoked

            viewModel.revokeConnection("c1")
            advanceUntilIdle()

            assertEquals(ConnectionsState.Loaded(emptyList()), viewModel.connectionsState.value)
        }

    @Test
    fun `revokeConnection removes the row on not_found - it is already gone elsewhere`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { transportClient.listAiConnections() } returns
                AiConnectionsResult.Connections(
                    listOf(
                        AiConnection(
                            clientId = "c1",
                            clientName = "Claude",
                            displayName = "Claude",
                            imageUrl = null,
                            connectedAt = "2026-10-01",
                            lastUsedAt = null,
                        ),
                    ),
                )
            viewModel.loadConnections(context)
            advanceUntilIdle()

            coEvery { transportClient.revokeAiConnection("c1") } returns
                AiRevokeResult.Error("not_found", null)

            viewModel.revokeConnection("c1")
            advanceUntilIdle()

            assertEquals(ConnectionsState.Loaded(emptyList()), viewModel.connectionsState.value)
        }

    @Test
    fun `revokeConnection leaves the list unchanged on not_signed_in`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Success("silent-token")
            coEvery { transportClient.listAiConnections() } returns
                AiConnectionsResult.Connections(
                    listOf(
                        AiConnection(
                            clientId = "c1",
                            clientName = "Claude",
                            displayName = "Claude",
                            imageUrl = null,
                            connectedAt = "2026-10-01",
                            lastUsedAt = null,
                        ),
                    ),
                )
            viewModel.loadConnections(context)
            advanceUntilIdle()

            coEvery { transportClient.revokeAiConnection("c1") } returns
                AiRevokeResult.Error("not_signed_in", null)

            viewModel.revokeConnection("c1")
            advanceUntilIdle()

            assertEquals(
                ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01"))),
                viewModel.connectionsState.value,
            )
        }

    @Test
    fun `renameConnection sends the new display name and updates connectionsState`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Success("token")
            coEvery { transportClient.listAiConnections() } returns
                AiConnectionsResult.Connections(
                    listOf(
                        AiConnection(
                            clientId = "c1",
                            clientName = "Claude",
                            displayName = "Claude",
                            imageUrl = null,
                            connectedAt = "2026-10-01",
                            lastUsedAt = null,
                        ),
                    ),
                )
            viewModel.loadConnections(context)
            advanceUntilIdle()

            coEvery { accountApiClient.renameConnection("h", 1, false, "token", "c1", "My Claude") } returns
                RenameResult.Updated

            viewModel.renameConnection(context, "c1", "My Claude")
            advanceUntilIdle()

            assertEquals(
                ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "My Claude", null, "2026-10-01"))),
                viewModel.connectionsState.value,
            )
        }

    @Test
    fun `renameConnection on a failed server response leaves connectionsState unchanged`() =
        runTest {
            coEvery { googleSignInClient.signInSilently(context) } returns
                GoogleSignInResult.Success("token")
            coEvery { transportClient.listAiConnections() } returns
                AiConnectionsResult.Connections(
                    listOf(
                        AiConnection(
                            clientId = "c1",
                            clientName = "Claude",
                            displayName = "Claude",
                            imageUrl = null,
                            connectedAt = "2026-10-01",
                            lastUsedAt = null,
                        ),
                    ),
                )
            viewModel.loadConnections(context)
            advanceUntilIdle()

            coEvery { accountApiClient.renameConnection("h", 1, false, "token", "c1", "My Claude") } returns
                RenameResult.Failed("HTTP 500")

            viewModel.renameConnection(context, "c1", "My Claude")
            advanceUntilIdle()

            assertEquals(
                ConnectionsState.Loaded(listOf(AccountConnection("c1", "Claude", "Claude", null, "2026-10-01"))),
                viewModel.connectionsState.value,
            )
        }
}
