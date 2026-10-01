#!/usr/bin/env bash
# Installs a list of APKs on the connected redroid device one at a time and checks each one
# completes the device-identity handshake against a target server. See docs/debug-device.md for
# the one-time setup this depends on (Remote Control already enabled and pointed at the target).
#
# Usage: scripts/redroid-compat.sh <staging|production|host:port> <apk-path> [apk-path ...]
set -euo pipefail

PKG="uk.co.drhconsulting.droidthumb.debug"
ACTIVITY_CATEGORY="android.intent.category.LAUNCHER"
CONNECT_TIMEOUT_SECONDS=15

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <staging|production|host:port> <apk-path> [apk-path ...]" >&2
  exit 1
fi

target="$1"
shift
apks=("$@")

case "$target" in
  staging) label="staging.droidthumb.com" ;;
  production) label="mcp.droidthumb.com" ;;
  *) label="$target" ;;
esac

echo "Target: $label (this script does not configure the app's host/port/TLS — see docs/debug-device.md)"
echo

pass=0
fail=0

for apk in "${apks[@]}"; do
  if [ ! -f "$apk" ]; then
    echo "SKIP: $apk (not found)"
    fail=$((fail + 1))
    continue
  fi

  # `|| true` inside the substitution (not after it) so a missing/failing aapt doesn't trip
  # `set -e` via pipefail — this is a soft "unknown" fallback, not a reason to abort the run.
  version=$(aapt dump badging "$apk" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1 || true)
  version=${version:-unknown}
  echo "=== $apk (apk_version=$version) ==="

  if ! adb install -r -d "$apk" >/tmp/redroid-compat-install.log 2>&1; then
    echo "  FAIL — install failed:"
    sed 's/^/    /' /tmp/redroid-compat-install.log
    fail=$((fail + 1))
    continue
  fi

  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell monkey -p "$PKG" -c "$ACTIVITY_CATEGORY" 1 >/dev/null 2>&1

  echo "  waiting up to ${CONNECT_TIMEOUT_SECONDS}s for the handshake..."
  connected=0
  for _ in $(seq 1 "$CONNECT_TIMEOUT_SECONDS"); do
    if adb logcat -d -s "MCP:DeviceTransport:V" 2>/dev/null | grep -q "Connected: protocol_version"; then
      connected=1
      break
    fi
    sleep 1
  done

  if [ "$connected" -eq 1 ]; then
    line=$(adb logcat -d -s "MCP:DeviceTransport:V" 2>/dev/null | grep "Connected: protocol_version" | tail -1)
    echo "  PASS — $line"
    pass=$((pass + 1))
  else
    echo "  FAIL — no handshake within ${CONNECT_TIMEOUT_SECONDS}s"
    echo "  Recent MCP:DeviceTransport log lines:"
    adb logcat -d -s "MCP:DeviceTransport:V" 2>/dev/null | tail -5 | sed 's/^/    /'
    fail=$((fail + 1))
  fi
  echo
done

echo "=== Summary: $pass passed, $fail failed (of $((pass + fail))) ==="
[ "$fail" -eq 0 ]
