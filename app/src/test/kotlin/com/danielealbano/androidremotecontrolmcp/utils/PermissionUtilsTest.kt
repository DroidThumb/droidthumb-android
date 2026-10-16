package com.danielealbano.androidremotecontrolmcp.utils

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PermissionUtils")
class PermissionUtilsTest {
    private val mockContext: Context = mockk(relaxed = true)
    private val mockContentResolver: ContentResolver = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.packageName } returns "com.danielealbano.androidremotecontrolmcp"
        mockkStatic(Settings.Secure::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Settings.Secure::class)
    }

    @Nested
    @DisplayName("isAccessibilityServiceEnabled")
    inner class IsAccessibilityServiceEnabled {
        @Test
        fun `returns true when service is in enabled list`() {
            val serviceName =
                "com.danielealbano.androidremotecontrolmcp/" +
                    "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns serviceName

            assertTrue(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false when enabled services is null`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns null

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(mockContext, Any::class.java),
            )
        }

        @Test
        fun `returns false when service is not in enabled list`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.other.package/com.other.Service"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(mockContext, Any::class.java),
            )
        }

        @Test
        fun `returns true when service is enabled via short-form component name`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.danielealbano.androidremotecontrolmcp/" +
                ".services.accessibility.McpAccessibilityService"

            assertTrue(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns true when short-form service is among multiple entries`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.other.package/com.other.Service:" +
                "com.danielealbano.androidremotecontrolmcp/" +
                ".services.accessibility.McpAccessibilityService"

            assertTrue(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false for malformed entry without component separator`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false for entry with empty class part`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.danielealbano.androidremotecontrolmcp/"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false for entry with empty package part`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false when short-form resolves to a different class in the same package`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.danielealbano.androidremotecontrolmcp/.services.accessibility.OtherService"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false when the class name casing differs`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.danielealbano.androidremotecontrolmcp/" +
                "com.danielealbano.androidremotecontrolmcp.services.accessibility.MCPAccessibilityService"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false for entry with a duplicated component separator`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.danielealbano.androidremotecontrolmcp//" +
                "services.accessibility.McpAccessibilityService"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false for entry with a component separator inside the class part`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.danielealbano.androidremotecontrolmcp/" +
                "services/accessibility.McpAccessibilityService"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns false for short-form entry with an empty package part`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "/.services.accessibility.McpAccessibilityService"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }

        @Test
        fun `returns true when short-form service follows an empty leading entry`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns ":com.danielealbano.androidremotecontrolmcp/" +
                ".services.accessibility.McpAccessibilityService"

            assertTrue(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    McpAccessibilityService::class.java,
                ),
            )
        }
    }

    @Nested
    @DisplayName("openAccessibilitySettings")
    inner class OpenAccessibilitySettings {
        @Test
        fun `starts activity with accessibility settings intent`() {
            PermissionUtils.openAccessibilitySettings(mockContext)

            verify { mockContext.startActivity(any()) }
        }
    }

    @Nested
    @DisplayName("isNotificationPermissionGranted")
    inner class IsNotificationPermissionGranted {
        @BeforeEach
        fun setUpPermission() {
            mockkStatic(ContextCompat::class)
        }

        @AfterEach
        fun tearDownPermission() {
            unmockkStatic(ContextCompat::class)
        }

        @Test
        fun `returns true when permission is granted`() {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.POST_NOTIFICATIONS)
            } returns PackageManager.PERMISSION_GRANTED

            assertTrue(PermissionUtils.isNotificationPermissionGranted(mockContext))
        }

        @Test
        fun `returns false when permission is denied`() {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.POST_NOTIFICATIONS)
            } returns PackageManager.PERMISSION_DENIED

            assertFalse(PermissionUtils.isNotificationPermissionGranted(mockContext))
        }
    }

    @Nested
    @DisplayName("openNotificationListenerSettings")
    inner class OpenNotificationListenerSettings {
        @Test
        fun `starts activity with notification listener settings intent`() {
            PermissionUtils.openNotificationListenerSettings(mockContext)

            verify { mockContext.startActivity(any()) }
        }
    }

    @Nested
    @DisplayName("isNotificationListenerEnabled")
    inner class IsNotificationListenerEnabled {
        @Test
        fun `returns true when service is in enabled_notification_listeners`() {
            val serviceName =
                "com.danielealbano.androidremotecontrolmcp/" +
                    "com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService"
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns serviceName

            assertTrue(
                PermissionUtils.isNotificationListenerEnabled(
                    mockContext,
                    McpNotificationListenerService::class.java,
                ),
            )
        }

        @Test
        fun `returns true when service is enabled via short-form component name`() {
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns "com.danielealbano.androidremotecontrolmcp/" +
                ".services.notifications.McpNotificationListenerService"

            assertTrue(
                PermissionUtils.isNotificationListenerEnabled(
                    mockContext,
                    McpNotificationListenerService::class.java,
                ),
            )
        }

        @Test
        fun `returns true when short-form service follows a non-matching and an empty entry`() {
            // The non-matching entry and the empty entry precede the match, so `any`
            // must evaluate both (including componentMatches("")) before matching.
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns "com.other.package/com.other.Service::" +
                "com.danielealbano.androidremotecontrolmcp/" +
                ".services.notifications.McpNotificationListenerService"

            assertTrue(
                PermissionUtils.isNotificationListenerEnabled(
                    mockContext,
                    McpNotificationListenerService::class.java,
                ),
            )
        }

        @Test
        fun `returns false when the class name casing differs`() {
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns "com.danielealbano.androidremotecontrolmcp/" +
                "com.danielealbano.androidremotecontrolmcp.services.notifications.MCPNotificationListenerService"

            assertFalse(
                PermissionUtils.isNotificationListenerEnabled(
                    mockContext,
                    McpNotificationListenerService::class.java,
                ),
            )
        }

        @Test
        fun `returns false when service is not in list`() {
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns "com.other.package/com.other.Service"

            assertFalse(
                PermissionUtils.isNotificationListenerEnabled(mockContext, Any::class.java),
            )
        }

        @Test
        fun `returns false when setting is null`() {
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns null

            assertFalse(
                PermissionUtils.isNotificationListenerEnabled(mockContext, Any::class.java),
            )
        }
    }

    @Nested
    @DisplayName("buildAccessibilityServiceSettingsIntent")
    inner class BuildAccessibilityServiceSettingsIntent {
        @Test
        fun `api 31+ builds the per-service details intent with our component`() {
            mockkConstructor(Intent::class)
            mockkConstructor(ComponentName::class)
            every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
            every {
                anyConstructed<Intent>().putExtra(any<String>(), any<Parcelable>())
            } answers { self as Intent }
            val action = slot<String>()
            try {
                PermissionUtils.buildAccessibilityServiceSettingsIntent(
                    "com.danielealbano.androidremotecontrolmcp",
                    McpAccessibilityService::class.java,
                    31,
                )

                verify(exactly = 1) { anyConstructed<Intent>().setAction(capture(action)) }
                assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", action.captured)
                verify(exactly = 1) {
                    anyConstructed<Intent>()
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, capture(slot<ComponentName>()))
                }
                verify(exactly = 0) { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) }
            } finally {
                unmockkAll()
            }
        }

        @Test
        fun `below api 31 falls back to the plain accessibility settings intent with fragment args`() {
            mockkConstructor(Intent::class)
            mockkConstructor(ComponentName::class)
            mockkConstructor(Bundle::class)
            every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
            every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
            every {
                anyConstructed<Intent>().putExtra(any<String>(), any<Parcelable>())
            } answers { self as Intent }
            every { anyConstructed<ComponentName>().flattenToString() } returns "com.example/com.example.S"
            every { anyConstructed<Bundle>().putString(any(), any()) } just Runs
            val action = slot<String>()
            try {
                PermissionUtils.buildAccessibilityServiceSettingsIntent(
                    "com.danielealbano.androidremotecontrolmcp",
                    McpAccessibilityService::class.java,
                    30,
                )

                verify(exactly = 1) { anyConstructed<Intent>().setAction(capture(action)) }
                assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, action.captured)
                verify(exactly = 1) {
                    anyConstructed<Intent>()
                        .putExtra(PermissionUtils.FRAGMENT_ARGS_KEY_EXTRA, "com.example/com.example.S")
                }
                verify(exactly = 1) {
                    anyConstructed<Intent>()
                        .putExtra(PermissionUtils.SHOW_FRAGMENT_ARGS_EXTRA, capture(slot<Bundle>()))
                }
                verify(exactly = 1) {
                    anyConstructed<Bundle>()
                        .putString(PermissionUtils.FRAGMENT_ARGS_KEY_EXTRA, "com.example/com.example.S")
                }
            } finally {
                unmockkAll()
            }
        }
    }
}
