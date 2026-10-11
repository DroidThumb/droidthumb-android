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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [ControlBarSettingsImpl] - the floating control bar kill-switch (plan 71 round 3). Uses a real,
 * temp-file-backed Preferences DataStore (same fixture as [SettingsRepositoryLoggingTest]) rather
 * than a mock, since the behavior under test - including an existing install with NO saved value
 * yet - is exactly what a real DataStore does on first read, not something a mock could fake
 * meaningfully.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ControlBarSettingsImpl")
class ControlBarSettingsImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val serverLog = RecordingServerLogRepository()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: ControlBarSettingsImpl

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
                produceFile = { File(tempDir, "control_bar_settings.preferences_pb") },
            )
        settings = ControlBarSettingsImpl(dataStore, SettingsChangeLogger(serverLog, testDispatcher, WINDOW))
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    @DisplayName("a fresh install (no saved value) defaults to disabled")
    fun `defaults to disabled with no saved value`() =
        testScope.runTest {
            assertFalse(settings.getControlBarEnabled())
        }

    @Test
    fun `setControlBarEnabled persists and is read back`() =
        testScope.runTest {
            settings.setControlBarEnabled(true)
            advanceUntilIdle()

            assertTrue(settings.getControlBarEnabled())
        }

    @Test
    fun `setControlBarEnabled back to false persists`() =
        testScope.runTest {
            settings.setControlBarEnabled(true)
            advanceUntilIdle()
            settings.setControlBarEnabled(false)
            advanceUntilIdle()

            assertFalse(settings.getControlBarEnabled())
        }

    @Test
    fun `enabling logs a settings-change entry`() =
        testScope.runTest {
            settings.setControlBarEnabled(true)
            advanceUntilIdle()

            assertEquals(
                "Floating control bar enabled",
                serverLog.ofType(ServerLogEntry.Type.SETTINGS).map { it.message }.single(),
            )
        }

    private companion object {
        const val WINDOW = 2_000L
    }
}
