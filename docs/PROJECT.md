# DroidThumb Android app — Project Bible

This document is the source of truth for the DroidThumb Android app: architecture, technical decisions,
conventions and implementation guidelines. The product and system design (relay, control plane, wire
protocol, flow format) lives in the design doc in the private `droidthumb-server` repository.

**Goal**: an open-source Android app that lets an LLM agent drive the phone through its accessibility
service, connected to a hosted relay by an **outbound** connection. The phone dials out; nothing dials in
(design doc D-19, SEC-19).

**Status**: the fork has been reduced to what an LLM needs to drive the phone (`docs/plans/demolition.md`).
The on-device MCP/HTTP server, OAuth server and tunnels are removed. The outbound transport, flow executor
and flow cache that replace them are not built yet, so **nothing invokes the tool handlers today**.

---

## Architecture

### Components

1. **AccessibilityService** (`services/accessibility/McpAccessibilityService`): system-managed; multi-window
   UI-tree capture (`AccessibilityTreeParser`, `CompactTreeFormatter`, `WebViewNodeMerger`), element
   matching (`ElementFinder`), actions (`ActionExecutor`: node actions, gestures, global actions), typing
   (`TypeInputController`) and screenshot capture (`takeScreenshot()`, via `ScreenCaptureProvider`).
   Singleton `instance` in its companion object. See `docs/ARCHITECTURE.md` for the multi-window design.
2. **Tool handlers** (`mcp/tools/`): pure logic, one class per operation, each exposing
   `suspend fun execute(arguments: JsonObject?): ToolResult`. They depend on service interfaces by
   constructor injection and contain no transport code. The kept set is the 14 MAPS and 13 NEW-OP tools of
   `docs/tool-surface.md` (see "Tool handlers" below). Package and class names still carry the `Mcp` prefix
   from the fork; renaming is deferred.
3. **EventChannelService** (`services/channel/`): foreground service (`specialUse`) that observes
   notifications (via `McpNotificationListenerService`) and POSTs them to a user-configured HTTP endpoint
   (design doc D-20). `EventChannelBootReceiver` restarts it on boot when enabled.
4. **MainActivity** (Compose): permissions (accessibility, notifications, notification listener), battery
   exemption, Event Channel configuration and control, logs, about. No business logic.

There is **no listening socket** and **no exported component other than the launcher activity**
(`ExportedComponentsManifestTest` enforces the latter).

### Settings

All settings go through `SettingsRepository` (DataStore-backed). After the demolition the only settings are
the Event Channel's, held in the `EventChannelSettings` slice that `SettingsRepository` extends.

---

## Tech Stack

Versions as pinned in `gradle/libs.versions.toml` and verified in `docs/build-notes.md`.

- **Language**: Kotlin 2.4.10; **KSP** 2.3.11
- **Android Gradle Plugin**: 9.3.2; **Gradle** 9.7.1 (wrapper)
- **Android SDK**: `compileSdk` 37, `targetSdk` 34, `minSdk` 33 (design doc D-05)
- **JDK**: 17
- **Jetpack Compose** (Material 3), **Lifecycle**, **DataStore**, **Hilt** (2.60.1)
- **Kotlinx Serialization** (tool arguments are `JsonObject`), **Kotlinx Coroutines**
- **Ktor client** (OkHttp engine) for the Event Channel's outbound HTTP; **SLF4J-Android** as its log binding
- **Testing**: JUnit 5, MockK, Turbine; `ktor-server-netty` on the **test classpath only** (a host-side fake
  endpoint in `EventDispatcherImplTest`)
- **Lint**: ktlint, detekt

---

## Folder Structure

```
app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/
├── McpApplication.kt
├── data/model/            # ChannelEvent*, EventChannelConfig, ServerLogEntry, ScreenshotData, AppInfo, …
├── data/repository/       # SettingsRepository(+Impl), EventChannelSettings(+Impl), ServerLog*, SettingsChangeLogger
├── di/                    # AppModule (bindings), IoDispatcher qualifier
├── mcp/                   # McpToolException
├── mcp/tools/             # tool handlers, ToolResult, McpToolUtils, TreeFingerprint
├── services/accessibility/  # accessibility service, tree parsing/encoding, executor, AccessibilityData
├── services/apps/         # AppManager (open/close app), AppIconCache
├── services/channel/      # EventChannelService, EventDispatcher, listeners, boot receiver
├── services/intents/      # IntentDispatcher (open URI)
├── services/notifications/  # McpNotificationListenerService, NotificationDataExtractor, NotificationData
├── services/power/        # BatteryOptimizationManager
├── services/screencapture/  # ScreenCaptureProvider, ScreenshotEncoder, ScreenshotAnnotator
├── ui/                    # MainActivity, screens, components, viewmodels, navigation, theme
└── utils/                 # Logger, PermissionUtils
```

---

## Tool handlers

- **Contract**: `execute(arguments: JsonObject?): ToolResult`. `ToolResult(content: List<ToolContent>,
  isError)` with `ToolContent.Text` / `ToolContent.Image` (base64). Invalid arguments and failures are thrown
  as `McpToolException` subclasses (`InvalidParams`, `PermissionDenied`, `NodeNotFound`, `ActionFailed`,
  `Timeout`, `InternalError`); the caller maps them to an error result.
- **Kept operations**: tap, long_press, double_tap, swipe, scroll; find_nodes, click_node, long_click_node,
  tap_node, scroll_to_node; type_append_text, type_insert_text, type_replace_text, type_clear_text, press_key;
  press_back, press_home, press_recents, dismiss_keyboard; get_clipboard, set_clipboard, wait_for_node,
  wait_for_idle; open_app, close_app; open_uri; get_screen_state. Their mapping to flow step ops is in
  `docs/tool-surface.md`.
- **Validation**: validate every argument (type, range, required) with the `McpToolUtils` helpers before
  acting.

### Anti-Prompt-Injection (Tool Response Safety)

Handlers return data originating from the device (UI text, content descriptions, clipboard, app metadata).
This data is untrusted — a malicious app could embed adversarial instructions in UI text.

- Every handler that returns device-derived content prepends `McpToolUtils.UNTRUSTED_CONTENT_WARNING` as the
  first line of its text, via `untrustedTextResult()`, `untrustedTextAndImageResult()` or
  `untrustedImageResult()`.
- Pure action confirmations (tap, click, swipe, …) that return only handler-generated text are exempt.
- **Limitation**: an image cannot carry an inline warning; the warning is a separate text item before the
  image, but a multimodal model reading the image directly could still be influenced by text rendered on
  screen.

---

## Android-Specific Conventions

### Service Lifecycle Management

- Long-running services (EventChannelService, and the future transport service) MUST run as foreground
  services with a persistent notification
- Call `startForeground()` within 5 seconds of service start, `stopForeground()` before destruction
- Service-to-UI communication via Flow/StateFlow (LocalBroadcastManager is deprecated and NOT used)

### AccessibilityService Best Practices

- Register only for needed event types (TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED)
- Keep `onAccessibilityEvent()` fast, offload heavy work to coroutines
- Cache accessibility tree when possible; call `node.recycle()` after use
- Check `node.refresh()` before using cached nodes (stale detection)
- All node operations MUST happen on main thread
- Use `performAction()` for element actions, `performGlobalAction()` for system actions, `dispatchGesture()` for complex touch sequences (API 24+)

### `isAccessibilityTool` and `accessibilityDataSensitive` (Android 14+ / API 34)

- `accessibility_service_config.xml` declares `android:isAccessibilityTool="true"`. On API 34+, apps can mark UI subtrees `accessibilityDataSensitive`; those nodes are delivered **only** to services that declare `isAccessibilityTool="true"` (and to the system `UiAutomation`). Without the flag, such subtrees are silently filtered out of the tree we receive — e.g. the GitHub app renders as an empty `main_activity_container` in `get_screen_state`/`find_nodes` even though the content is fully present in the framework tree (confirmed via `uiautomator dump`). The flag is what lets this tool introspect and control apps that mark their content sensitive.
- **Security implication**: this intentionally bypasses the `accessibilityDataSensitive` anti-scraping protection that security-conscious apps (banking, password managers) rely on. This is acceptable for a user-installed, user-enabled remote-control tool, but is a deliberate trade-off that MUST stay documented.
- **Google Play implication (deferred)**: Play considers only genuine assistive tools eligible to declare `isAccessibilityTool`; declaring it on a remote-control app risks rejection (design doc B-11). Current distribution is sideload / GitHub Releases / F-Droid. If Play distribution is pursued later, the Play build must not declare the flag.

### Permission Handling

- **Accessibility**: user must enable manually in Settings (the app deep-links there). Also provides screenshot capture via `takeScreenshot()` (Android 11+)
- **Notification listener**: user enables in Settings → Notification access; required for Event Channel notification events
- **POST_NOTIFICATIONS** (runtime, Android 13+): the Event Channel's foreground-service notification
- **INTERNET**: Event Channel outbound HTTP
- **QUERY_ALL_PACKAGES**: resolving and launching arbitrary apps (`open_app`, `close_app`) and the Event Channel's app filter
- **KILL_BACKGROUND_PROCESSES**: `close_app` via `ActivityManager.killBackgroundProcesses()`
- **FOREGROUND_SERVICE**, **FOREGROUND_SERVICE_SPECIAL_USE**: EventChannelService
- **RECEIVE_BOOT_COMPLETED**: EventChannelBootReceiver
- No battery-optimization permission is declared: the app opens the settings list
  (`BatteryOptimizationManagerImpl`) rather than requesting the one-tap exemption dialog, which
  needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — Play restricts it and F-Droid flags it
- Always check permission state before operations; throw `McpToolException.PermissionDenied` if missing

### Background Restrictions & Memory Management

- Foreground services are exempt from Doze restrictions
- Never store Activity context in long-lived objects — use ApplicationContext
- Cancel coroutine scopes in `onDestroy()`; recycle large bitmaps after encoding; use `use {}` for automatic stream closure

### Threading Rules

- All AccessibilityService operations and UI operations MUST run on main thread
- Network operations on the IO dispatcher; screenshot encoding and tree parsing on Default

---

## Kotlin Coding Standards

### Naming Conventions

- **Classes/Interfaces**: PascalCase, no "I" prefix (e.g., `SettingsRepository`, not `ISettingsRepository`)
- **Functions/Variables**: camelCase (e.g., `captureScreenshot()`, `endpointUrl`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `DEFAULT_ENDPOINT_URL`)
- **Backing fields**: underscore prefix (e.g., `_serviceStatus`)
- **Packages**: All lowercase, no underscores

### Null Safety

- Prefer non-null types by default; use nullable types only when null is a valid state
- Avoid `!!` operator — use safe calls `?.`, `let {}`, or elvis operator `?:` instead
- Use `require()` or `check()` for preconditions

### Coroutines Best Practices

- Always use `CoroutineScope` (never `GlobalScope`); cancel scope in lifecycle cleanup
- Use `viewModelScope` for ViewModels, `lifecycleScope` for Activities
- Dispatchers: `Main` for UI/AccessibilityService, `IO` for network/file I/O/DataStore, `Default` for CPU-intensive work

### Code Organization

- File structure: package declaration → imports → class declaration → companion object → properties → init blocks → public methods → private methods → inner classes
- Keep classes focused (single responsibility), prefer files under 300 lines
- Prefer functions under 20 lines with meaningful names
- Prefer `val` over `var`; use `data class` for immutable data with `copy()` for modifications
- Use extension functions for utility operations; don't overuse — prefer member functions for core logic

---

## UI Design Principles

### Design System

- **Material Design 3** components (Compose Material3 library) with theme tokens for consistent styling
- Define primary/secondary/tertiary colors in `Color.kt`, type scale in `Type.kt`
- Support both light and dark themes; ensure sufficient contrast (WCAG AA minimum)
- Use semantic color names (e.g., `surfaceVariant`, not `grey200`)

### Visual Style

- Clean (minimal clutter, ample whitespace), modern (rounded corners, subtle shadows, smooth animations)
- Elevated cards for grouped content, filled buttons for primary actions, outlined for secondary
- Material Icons, switches for toggles, outlined text fields for input
- Consistent spacing scale (4dp, 8dp, 16dp, 24dp, 32dp), 16dp padding inside components

### Dark Mode

- Mandatory dark theme support; use dynamic colors (Material You) if appropriate
- Avoid pure white/black — use surface colors; test contrast in both modes

### Screen Structure

Three tabs. **Home**: accessibility-required callout (when disabled), battery-optimisation card (when not
exempt), `EventChannelStatusCard` (status, start/stop) and recent logs, with a full Logs screen.
**Settings**: Permissions (accessibility, notifications, notification listener) and Event Channel (endpoint,
token, notification events and app filter). **About**: version, links, licence.

### Accessibility (UI)

- All interactive elements have minimum 48dp touch target
- Use `contentDescription` for icons/images; ensure logical focus order; support TalkBack; test with large text sizes

### Compose Best Practices

- PascalCase for composables, suffix with noun (e.g., `EventChannelStatusCard`, not `ShowChannelStatus`)
- Hoist state to parent composables; use `remember` for UI state, `rememberSaveable` for surviving config changes
- Extract reusable components; use modifiers for customization; keep composables small and focused

---

## Testing Strategy

- **Unit tests (JVM)**: JUnit 5 + MockK + Turbine under `app/src/test/`. Every kept class has tests.
- **Handler tests**: handlers are called directly (`execute()`) with MockK doubles for the Android service
  interfaces. `integration/HandlerTestHarness` builds all kept handlers and dispatches by tool name, mapping a
  thrown exception to an error `ToolResult`, for multi-handler scenarios.
- **E2E**: none at present. The redroid/Testcontainers MCP-over-HTTP suite was removed with the on-device
  server; its replacement is a fake-relay harness built with the transport (plan 66, US-9).
- Manual checks run on the persistent redroid debug device (`docs/debug-device.md`).

---

## Build & Deployment

### Build System

- Gradle (wrapper) with Kotlin DSL and version catalog (`gradle/libs.versions.toml`)

### Build Variants

Single flavour since 2026-09-25 (previously two, `gms` and `foss`/F-Droid, differing only in the
battery-optimisation flow; merged using the `foss` behaviour — see "Permission Handling" above).

| Variant | Application ID | Debuggable | Minify |
|---------|---------------|-----------|--------|
| debug | `uk.co.drhconsulting.droidthumb.debug` | true | false |
| release | `uk.co.drhconsulting.droidthumb` | false | false (open source) |

### Versioning

- **Semantic versioning** (MAJOR.MINOR.PATCH)
- `VERSION_NAME` is derived from git tags (with a `gradle.properties` fallback for git-less builds); the `versionCode` is derived from git history by Gradle, never hardcoded (see [TOOLS.md](TOOLS.md) → VERSION_CODE Derivation)
- Bump the version name via Makefile: `make version-bump-patch`, `make version-bump-minor`, `make version-bump-major`

### APK Signing

- **Debug**: Default debug keystore (automatic)
- **Release**: Custom keystore via `keystore.properties` (gitignored), loaded in `app/build.gradle.kts`

### CI/CD (GitHub Actions)

- `ci.yml` on push to main and pull requests: lint, unit tests (with coverage), release build — in parallel
- `edge-release.yml` is **manual-only** until the app can be driven end to end again (demolition plan, D8)
- `release.yml` on `v*` tags

---

## Makefile Targets

`build`, `build-release`, `build-release-bundle`, `clean`, `test-unit`, `test`, `coverage`,
`lint`, `lint-fix`, `install`, `install-release`, `uninstall`, `grant-permissions`, `launch-app`, `redeploy`,
`setup-emulator`, `start-emulator`, `stop-emulator`, `logs`, `logs-clear`, `version-bump-*`,
`check-so-alignment`, `all`, `ci`. Run `make help` for descriptions.

---

## Related Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — runtime architecture and multi-window accessibility
- [tool-surface.md](tool-surface.md) — the fork's 57 tools mapped to the flow step vocabulary
- [plans/demolition.md](plans/demolition.md) — what was removed and kept, and why
- [debug-device.md](debug-device.md) — redroid debug device and host setup
- [TOOLS.md](TOOLS.md) — git, GitHub CLI and local CI conventions
