package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityEvent
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarState
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ReturnTarget
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("McpAccessibilityService cache-invalidation wiring")
class McpAccessibilityServiceTest {
    @Nested
    @DisplayName("triggersCacheInvalidation")
    inner class TriggersCacheInvalidation {
        @Test
        @DisplayName("window state changed (rotation / activity transition) triggers invalidation")
        fun `window state changed triggers invalidation`() {
            assertTrue(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        }

        @Test
        @DisplayName("windows changed (keyboard show / hide) triggers invalidation")
        fun `windows changed triggers invalidation`() {
            assertTrue(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        }

        @Test
        @DisplayName("window content changed does NOT trigger invalidation (too frequent)")
        fun `window content changed does not trigger invalidation`() {
            assertFalse(triggersCacheInvalidation(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        }

        @ParameterizedTest
        @ValueSource(
            ints = [
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            ],
        )
        @DisplayName("unrelated event types do NOT trigger invalidation")
        fun `unrelated event types do not trigger invalidation`(eventType: Int) {
            assertFalse(triggersCacheInvalidation(eventType))
        }
    }

    @Nested
    @DisplayName("scheduleCacheInvalidationIfNeeded")
    inner class ScheduleCacheInvalidationIfNeeded {
        @Test
        @DisplayName("schedules the debouncer on a triggering event")
        fun `schedules on a triggering event`() {
            val debouncer = mockk<CacheInvalidationDebouncer>(relaxed = true)

            scheduleCacheInvalidationIfNeeded(AccessibilityEvent.TYPE_WINDOWS_CHANGED, debouncer)

            verify(exactly = 1) { debouncer.schedule() }
        }

        @Test
        @DisplayName("does NOT schedule on a non-triggering event")
        fun `does not schedule on a non-triggering event`() {
            val debouncer = mockk<CacheInvalidationDebouncer>(relaxed = true)

            scheduleCacheInvalidationIfNeeded(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                debouncer,
            )

            verify(exactly = 0) { debouncer.schedule() }
        }

        @Test
        @DisplayName("is a safe no-op when the debouncer is null")
        fun `is a safe no-op when the debouncer is null`() {
            assertDoesNotThrow {
                scheduleCacheInvalidationIfNeeded(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null)
            }
        }
    }

    @Nested
    @DisplayName("invalidateCache")
    inner class InvalidateCache {
        @Test
        @DisplayName("clears the provided cache")
        fun `clears the provided cache`() {
            val cache = mockk<AccessibilityNodeCache>(relaxed = true)

            invalidateCache(cache)

            verify(exactly = 1) { cache.clear() }
        }

        @Test
        @DisplayName("is a safe no-op when the cache is null")
        fun `is a safe no-op when the cache is null`() {
            assertDoesNotThrow { invalidateCache(null) }
        }
    }

    @Nested
    @DisplayName("isForeignWindowStateChange")
    inner class IsForeignWindowStateChange {
        @Test
        @DisplayName("a different package's window state change is foreign (tracked)")
        fun `a different package is foreign`() {
            assertTrue(isForeignWindowStateChange("com.android.contacts", "com.example.self"))
        }

        @Test
        @DisplayName("our own package's window state change is NOT foreign (ignored)")
        fun `our own package is not foreign`() {
            assertFalse(isForeignWindowStateChange("com.example.self", "com.example.self"))
        }

        @Test
        @DisplayName("a null event package is treated as foreign (tracked), matching prior behavior")
        fun `a null event package is foreign`() {
            assertTrue(isForeignWindowStateChange(null, "com.example.self"))
        }
    }

    @Nested
    @DisplayName("shouldShowOverlayWindows")
    inner class ShouldShowOverlayWindows {
        private val target = ReturnTarget(packageName = "com.app", label = "App")

        @Test
        @DisplayName("Hidden means the overlay windows should NOT be shown")
        fun `hidden means not shown`() {
            assertFalse(shouldShowOverlayWindows(ControlBarState.Hidden))
        }

        @Test
        @DisplayName("Running means the overlay windows should be shown")
        fun `running means shown`() {
            assertTrue(shouldShowOverlayWindows(ControlBarState.Running("Tapping…", target)))
        }

        @Test
        @DisplayName("Paused means the overlay windows should be shown")
        fun `paused means shown`() {
            assertTrue(shouldShowOverlayWindows(ControlBarState.Paused(target)))
        }

        @Test
        @DisplayName("Ended means the overlay windows should be shown")
        fun `ended means shown`() {
            assertTrue(shouldShowOverlayWindows(ControlBarState.Ended("summary", target)))
        }
    }
}
