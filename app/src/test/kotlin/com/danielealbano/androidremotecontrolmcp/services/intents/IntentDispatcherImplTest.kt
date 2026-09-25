package com.danielealbano.androidremotecontrolmcp.services.intents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
@DisplayName("IntentDispatcherImpl")
class IntentDispatcherImplTest {
    @MockK
    private lateinit var mockContext: Context

    private lateinit var dispatcher: IntentDispatcherImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkStatic(BuildConfig::class)

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setPackage(any()) } returns mockk()
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk()
        every { anyConstructed<Intent>().setDataAndType(any(), any()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Float>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Double>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } returns mockk()
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<ArrayList<String>>())
        } returns mockk()

        every { mockContext.startActivity(any()) } just Runs
        every { mockContext.sendBroadcast(any()) } just Runs
        every { mockContext.startService(any()) } returns mockk()

        dispatcher = IntentDispatcherImpl(mockContext)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ─── sendIntent extras tests ─────────────────────────────────────────

    // ─── sendIntent extras_types override tests ──────────────────────────

    // ─── sendIntent flags tests ──────────────────────────────────────────

    // ─── sendIntent dispatch mode tests ──────────────────────────────────

    // ─── sendIntent component tests ──────────────────────────────────────

    // ─── sendIntent package tests ────────────────────────────────────────

    // ─── sendIntent exception handling tests ─────────────────────────────

    // ─── sendIntent data and type tests ──────────────────────────────────

    // ─── openUri tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("openUri")
    inner class OpenUri {
        @Test
        fun `openUri calls startActivity with ACTION_VIEW`() =
            runTest {
                val result = dispatcher.openUri("https://example.com")

                assertTrue(result.isSuccess)
                verify(exactly = 1) { mockContext.startActivity(any()) }
            }

        @Test
        fun `openUri with package_name sets package on intent`() =
            runTest {
                val result =
                    dispatcher.openUri(
                        uri = "https://example.com",
                        packageName = "com.android.chrome",
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().setPackage("com.android.chrome") }
            }

        @Test
        fun `openUri with mime_type uses setDataAndType`() =
            runTest {
                val result =
                    dispatcher.openUri(
                        uri = "content://media/external/images/1",
                        mimeType = "image/jpeg",
                    )

                assertTrue(result.isSuccess)
                verify { anyConstructed<Intent>().setDataAndType(any(), "image/jpeg") }
            }

        @Test
        fun `openUri with uri only sets data`() =
            runTest {
                val result = dispatcher.openUri("https://example.com")

                assertTrue(result.isSuccess)
                verify(exactly = 1) { mockContext.startActivity(any()) }
            }

        @Test
        fun `openUri wraps ActivityNotFoundException in Result failure`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    ActivityNotFoundException("No handler")

                val result = dispatcher.openUri("custom://unknown")

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("No app found to handle URI") == true,
                )
            }

        @Test
        fun `openUri wraps SecurityException in Result failure with sanitized message`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    SecurityException("Internal details here")

                val result = dispatcher.openUri("https://restricted.com")

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertEquals(
                    "Permission denied: not allowed to open URI",
                    result.exceptionOrNull()?.message,
                )
            }

        @Test
        fun `openUri wraps unexpected exception in Result failure with sanitized message`() =
            runTest {
                every { mockContext.startActivity(any()) } throws
                    RuntimeException("Internal crash detail")

                val result = dispatcher.openUri("https://example.com")

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertEquals(
                    "Failed to open URI unexpectedly",
                    result.exceptionOrNull()?.message,
                )
            }
    }
}
