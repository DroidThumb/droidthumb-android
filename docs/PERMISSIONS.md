# Granting Permissions Programmatically

This document describes how to grant every permission the app needs from the command line via `adb`, without opening the UI. This is useful for automated setups and headless devices such as the redroid debug device (`docs/debug-device.md`).

The commands below require a device or emulator reachable over `adb` where the app is already installed. Granting **special access** (Accessibility, Notification Listener) via the command line requires a privileged shell (e.g. an emulator, or a device that allows `settings put secure` from the adb shell). On a standard production device, grant these through the app's **Settings > Permissions** screen instead.

## Application ID

Replace `<app-id>` with the application ID for your build:

- **Debug**: `uk.co.drhconsulting.droidthumb.debug`
- **Release**: `uk.co.drhconsulting.droidthumb`

> **Note**: the **class names do not change** with the application ID. The Accessibility and Notification Listener component names below always use the source package (`com.danielealbano.androidremotecontrolmcp.services.*`).

## Permission categories

- **Normal** — granted automatically at install. No command needed: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`.
- **Runtime** — granted with `pm grant`.
- **Special access** — granted with `settings put secure` / `cmd notification`, **not** `pm grant`.

No battery-optimization permission is needed: the app opens the settings list
(`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) rather than requesting the one-tap exemption
dialog, which would need `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

See `docs/ARCHITECTURE.md` → "Permission Model" for what each permission is used for.

## Runtime permissions

```bash
# Notifications (Android 13+) — the Event Channel's foreground-service notification
adb shell pm grant <app-id> android.permission.POST_NOTIFICATIONS
```

## Special access

### Accessibility Service

Required for UI introspection, action execution, and screenshots (core functionality).

```bash
adb shell settings put secure enabled_accessibility_services \
  <app-id>/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### Notification Listener

Required for Event Channel notification events.

```bash
adb shell cmd notification allow_listener \
  <app-id>/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
```

If `cmd notification allow_listener` is unavailable on your platform image, use the secure setting instead:

```bash
adb shell settings put secure enabled_notification_listeners \
  <app-id>/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
```

## One-shot script

`scripts/install-debug.sh` builds the debug APK, installs it, grants everything above, and
launches it — the canonical version of the sequence documented in this file. Run it directly, or
via `make redeploy [SERIAL=<adb-serial>]`. See its `--help` for options.

## Verifying

```bash
# Confirm which build is installed
adb shell pm list packages | grep droidthumb

# Confirm runtime permission grants
adb shell dumpsys package <app-id> | grep -A40 "runtime permissions"

# Confirm the accessibility service is connected
adb shell dumpsys accessibility | grep McpAccessibilityService
```
