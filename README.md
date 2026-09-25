# DroidThumb — Android app

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

The on-device half of DroidThumb: an Android accessibility service that lets an LLM agent read the
screen and act on any app, driven by a hosted relay rather than by a server on the phone.

> **Status: mid-rebuild, not usable yet.** The app has been reduced to the parts an LLM needs to
> drive the phone (see `docs/plans/demolition.md`). The on-device MCP server, OAuth server and
> tunnels are gone by design (design doc D-19: the phone dials out, nothing dials in). The outbound
> transport that replaces them does not exist yet, so **nothing can drive the device remotely**
> today. Installing it gives you the accessibility service, the permission screens and the Event
> Channel, and nothing else.

> **Warning:** This software is provided "as-is" without warranty of any kind. Users are solely
> responsible for ensuring their use complies with all applicable laws and regulations.

---

## What is in the app today

- **Accessibility service** (`McpAccessibilityService`): multi-window UI-tree capture and compact
  encoding, node actions, gesture dispatch, global actions, typing via the input connection, and
  screenshot capture (`takeScreenshot()`).
- **Tool handler logic** (`mcp/tools/`): the handlers behind the operations an LLM uses to drive the
  phone — the 14 "MAPS" and 13 "NEW-OP" tools classified in `docs/tool-surface.md` (tap, long
  press, double tap, swipe, scroll, find/click/long-click/tap/scroll-to node, type/insert/replace/
  clear text, keys, back/home/recents, dismiss keyboard, clipboard, wait for node/idle, open/close
  app, open URI, get screen state). They return an internal `ToolResult`; nothing invokes them yet.
- **Event Channel** (`services/channel/`): forwards notification events to a configured HTTP
  endpoint. It is the trigger for the auto-reply demo (design doc D-20).
- **Minimal UI**: enable the accessibility service, notification access and the battery-optimisation
  exemption; configure and start the Event Channel; view recent logs.

Flavours: `gms` and `foss`. They currently differ only in the battery-optimisation flow (`gms` uses
the one-tap system exemption dialog, which F-Droid does not allow).

## Build

Requirements: JDK 17 and the Android SDK (`compileSdk` 37, `platforms;android-37.x`). See
`docs/debug-device.md` → "Host setup" → "Toolchain" for the exact user-local install.

```bash
./gradlew assembleGmsDebug          # or assembleFossDebug
./gradlew :app:test                 # JVM unit tests
./gradlew ktlintCheck detekt        # lint
```

`local.properties` needs `sdk.dir=<path to the Android SDK>` (it is gitignored).

## Try it on a device

`docs/debug-device.md` describes the persistent redroid (Android-in-a-container) debug device used
for development: how to reach it over adb, install the debug APK and enable the accessibility
service.

## Documentation

- `docs/plans/demolition.md` — what was removed from the fork and why, and what was kept.
- `docs/tool-surface.md` — the fork's 57 tools mapped to the flow step vocabulary.
- `docs/module-map.md` — the fork's original module map and the D-19 removal analysis.
- `docs/debug-device.md` — the redroid debug device and host setup.
- The product and technical design lives in the private `droidthumb-server` repository.

## Upstream

Forked from [Android Remote Control MCP](https://github.com/danielealbano/android-remote-control-mcp)
by Daniele Salvatore Albano (MIT). The accessibility service, tree encoding and action executor are
upstream's work.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

This project is licensed under the MIT License. See [LICENSE.md](LICENSE.md) for details.
