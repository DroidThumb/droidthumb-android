#!/usr/bin/env bash
# Build, install, re-enable the accessibility service, and launch the debug build.
#
# Reinstalling clears Android's `enabled_accessibility_services` setting for the app (the
# component drops out of the list on uninstall/replace), so a plain `adb install -r` + launch
# lands on the "Accessibility permission required" screen with nothing driving the phone. This
# script re-grants accessibility (and the other special-access/runtime permissions the app
# needs) after every install so the debug loop stays one command.
#
# Usage:
#   scripts/install-debug.sh [-s <adb-serial>]
#
# The adb serial is, in priority order: -s/--serial, $ANDROID_SERIAL, or adb's own default
# (only works if exactly one device/emulator is attached). For the persistent redroid debug
# device (docs/debug-device.md) pass -s localhost:5555 or export ANDROID_SERIAL=localhost:5555.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

APP_ID="uk.co.drhconsulting.droidthumb.debug"
PKG="com.danielealbano.androidremotecontrolmcp"
ACCESSIBILITY_SERVICE="$APP_ID/$PKG.services.accessibility.McpAccessibilityService"
NOTIFICATION_LISTENER="$APP_ID/$PKG.services.notifications.McpNotificationListenerService"
APK="app/build/outputs/apk/debug/app-debug.apk"

SERIAL="${ANDROID_SERIAL:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    -s | --serial)
      SERIAL="$2"
      shift 2
      ;;
    -h | --help)
      sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

echo "=== 1. Build ==="
./gradlew assembleDebug

echo ""
echo "=== 2. Install ==="
"${ADB[@]}" install -r "$APK"

echo ""
echo "=== 3. Grant permissions ==="
echo "-- Accessibility service"
"${ADB[@]}" shell settings put secure enabled_accessibility_services "$ACCESSIBILITY_SERVICE"
"${ADB[@]}" shell settings put secure accessibility_enabled 1
echo "-- Notification listener"
"${ADB[@]}" shell cmd notification allow_listener "$NOTIFICATION_LISTENER"
echo "-- POST_NOTIFICATIONS"
"${ADB[@]}" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS

echo ""
echo "=== 4. Launch ==="
"${ADB[@]}" shell am start -W -n "$APP_ID/$PKG.ui.MainActivity"

echo ""
echo "Done. $APP_ID installed and running with accessibility enabled."
