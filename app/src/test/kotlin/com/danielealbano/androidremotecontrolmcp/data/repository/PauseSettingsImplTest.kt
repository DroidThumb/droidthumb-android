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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [PauseSettingsImpl]'s `pauseHoldCapMs` slice only (round 4 phone-feedback redesign - the
 * configurable cap a held step waits for Resume before giving up) - `pauseState`/`pauseUntil`/
 * `resume` are already exercised indirectly via [SettingsRepositoryLoggingTest] and
 * [com.danielealbano.androidremotecontrolmcp.services.transport.DeviceTransportClientPauseTest].
 * Uses a real, temp-file-backed Preferences DataStore (same fixture as
 * [SettingsRepositoryLoggingTest]/[ControlBarSettingsImplTest]) rather than a mock, since "a fresh
 * install with no saved value yet" is exactly what a real DataStore does on first read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("PauseSettingsImpl - pauseHoldCapMs")
class PauseSettingsImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val serverLog = RecordingServerLogRepository()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: PauseSettingsImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "pause_settings.preferences_pb") },
            )
        settings = PauseSettingsImpl(dataStore, SettingsChangeLogger(serverLog, testDispatcher, WINDOW))
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    @DisplayName("a fresh install (no saved value) defaults to 60s")
    fun `defaults to 60 seconds with no saved value`() =
        testScope.runTest {
            assertEquals(60_000L, settings.getPauseHoldCapMs())
        }

    @Test
    fun `setPauseHoldCapMs persists and is read back`() =
        testScope.runTest {
            settings.setPauseHoldCapMs(90_000L)
            advanceUntilIdle()

            assertEquals(90_000L, settings.getPauseHoldCapMs())
        }

    @Test
    @DisplayName("a zero or negative cap is rejected, not silently persisted")
    fun `setPauseHoldCapMs rejects a non-positive value`() =
        testScope.runTest {
            assertThrows<IllegalArgumentException> { settings.setPauseHoldCapMs(0L) }
            assertThrows<IllegalArgumentException> { settings.setPauseHoldCapMs(-1L) }
        }

    @Test
    fun `changing it logs a settings-change entry`() =
        testScope.runTest {
            settings.setPauseHoldCapMs(120_000L)
            advanceUntilIdle()

            assertEquals(
                "Pause hold cap set to 120000ms",
                serverLog.ofType(ServerLogEntry.Type.SETTINGS).map { it.message }.single(),
            )
        }

    private companion object {
        const val WINDOW = 2_000L
    }
}
