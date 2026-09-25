package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsRepositoryImpl settings-change logging")
class SettingsRepositoryLoggingTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val serverLog = RecordingServerLogRepository()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepositoryImpl

    private var fileCounter = 0

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        fileCounter++
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "logging_settings_$fileCounter.preferences_pb") },
            )
        val changeLogger = SettingsChangeLogger(serverLog, testDispatcher, WINDOW)
        repository =
            SettingsRepositoryImpl(
                EventChannelSettingsImpl(dataStore, changeLogger),
                TransportSettingsImpl(dataStore, changeLogger),
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun settingsMessages() = serverLog.ofType(ServerLogEntry.Type.SETTINGS).map { it.message }

    @Test
    fun `no-op write logs nothing`() =
        testScope.runTest {
            repository.updateEventChannelEndpointUrl("http://same:1")
            advanceUntilIdle()
            serverLog.clear()

            repository.updateEventChannelEndpointUrl("http://same:1")
            advanceUntilIdle()
            assertTrue(settingsMessages().isEmpty())
        }

    @Test
    fun `count-preserving set swap still logs`() =
        testScope.runTest {
            repository.updateNotificationFilterApps(setOf("com.a"))
            advanceUntilIdle()
            serverLog.clear()

            repository.updateNotificationFilterApps(setOf("com.b"))
            advanceUntilIdle()
            assertEquals("Notification filter apps changed 1 → 1", settingsMessages().single())
        }

    @Test
    fun `event channel endpoint logs old to new`() =
        testScope.runTest {
            repository.updateEventChannelEndpointUrl("http://old:1")
            advanceUntilIdle()
            serverLog.clear()

            repository.updateEventChannelEndpointUrl("http://new:2")
            advanceUntilIdle()
            assertEquals(
                "Event channel endpoint changed http://old:1 → http://new:2",
                settingsMessages().single(),
            )
        }

    private companion object {
        const val WINDOW = 2_000L
    }
}
