# Granting Permissions Programmatically

This document describes how to grant every permission the app needs from the command line via `adb`, without opening the UI. This is useful for automated setups and headless devices such as the redroid debug device (`docs/debug-device.md`).

The commands below require a device or emulator reachable over `adb` where the app is already installed. Granting **special access** (Accessibility, Notification Listener) via the command line requires a privileged shell (e.g. an emulator, or a device that allows `settings put secure` from the adb shell). On a standard production device, grant these through the app's **Settings > Permissions** screen instead.

## Application ID

Replace `<app-id>` with the application ID for your build:

- **Debug**: `uk.co.drhconsulting.droidthumb.<flavor>.debug` (e.g. `uk.co.drhconsulting.droidthumb.gms.debug`, `uk.co.drhconsulting.droidthumb.foss.debug`)
- **Release**: `uk.co.drhconsulting.droidthumb` (identical across flavours)

> **Note**: the **class names do not change** with the application ID. The Accessibility and Notification Listener component names below always use the source package (`com.danielealbano.androidremotecontrolmcp.services.*`).

## Permission categories

- **Normal** — granted automatically at install. No command needed: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`, and (`gms` only) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **Runtime** — granted with `pm grant`.
- **Special access** — granted with `settings put secure` / `cmd notification`, **not** `pm grant`.

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

The following script grants the runtime permission and enables both special-access services. Set `APP_ID` to match your installed build.

```bash
#!/usr/bin/env bash
set -euo pipefail

# Set to uk.co.drhconsulting.droidthumb for a release build
APP_ID="uk.co.drhconsulting.droidthumb.gms.debug"

ACCESSIBILITY_SERVICE="$APP_ID/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"
NOTIFICATION_LISTENER="$APP_ID/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService"

# Runtime permissions
adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS

# Accessibility service
adb shell settings put secure enabled_accessibility_services "$ACCESSIBILITY_SERVICE"
adb shell settings put secure accessibility_enabled 1

# Notification listener
adb shell cmd notification allow_listener "$NOTIFICATION_LISTENER"

echo "Permissions granted for $APP_ID"
```

## Verifying

```bash
# Confirm which build is installed
adb shell pm list packages | grep droidthumb

# Confirm runtime permission grants
adb shell dumpsys package <app-id> | grep -A40 "runtime permissions"

# Confirm the accessibility service is connected
adb shell dumpsys accessibility | grep McpAccessibilityService
```
