<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Demolition — reduce droidthumb-android to what an LLM needs to drive the phone

**Status: APPROVED 2026-09-25** with J2 changed (below). J1, J3–J10 approved as recommended. Redroid volume reset approved. §7 wording is approved in principle; the owner will flag changes after the pass.

**J2 as approved:** delete the location/geofencing event source, its map screen, `LocationProvider`, Play Services location and background location. Keep notification events (the demo trigger) and keep Wi-Fi events only if they cost nothing. **They don't:** SSID matching needs `ACCESS_FINE_LOCATION` (Android returns `<unknown ssid>` without it), plus `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, and they're the other reason `EventChannelService` is a *location*-type foreground service. So Wi-Fi events are deleted too, and the Event Channel keeps **notification events only**. Consequence: `EventChannelService`'s foreground-service type changes from `location` to `specialUse` (the permission `FOREGROUND_SERVICE_SPECIAL_USE` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest pattern already exist from the deleted `McpServerService`). This is an implementer's choice, flagged for review; the transport plan will need to choose a type for its own service anyway. **Flavours after J2:** they differ in exactly one thing, the battery-exemption flow (`gms`: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission + one-tap system dialog; `foss`: opens the settings list, because F-Droid flags that permission). Per the owner's instruction they are **not** collapsed in this pass; the recommendation goes in the report.

**Supersedes** `docs/plans/66_d19-remove-oauth-and-on-device-server_20260925070212.md` (its inventory and §1 facts are
reused here). File name `demolition.md` is the owner's choice; it deliberately does not follow the `ID_name_timestamp`
convention.

**Rollback point:** tag `pre-demolition` → `0d82c7a` (pushed).

**Owner decisions (2026-09-25):** D1 everything in one pass, no interim listener (D4–D7 moot) · D2 accept losing
real-Android coverage · D3 remove the share target · D8 `edge-release` manual-only, first commit, until the app can be
driven end to end again · D9 CLAUDE.md / agent wording proposed in §7, approved here.

Paths: `M` = `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp`, `T` = `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp`,
`G`/`F` = the `gms`/`foss` flavour source roots (same package layout).

---

## 1. Commit sequence

1. **`ci: make edge-release manual-only`**: `.github/workflows/edge-release.yml` `on:` becomes `workflow_dispatch:` only (push trigger removed; comment states why and the re-enable condition). First commit, before anything is deleted.
2. **`chore: demolition — delete everything not needed to drive the phone`**: **one commit, `git rm` of whole files and directories only (§3), no edits.** The build is expected to be broken at this commit.
3. **Fix commits** (separate, each coherent; §5): build/modules → manifests/resources → the MCP-SDK replacement → handler pruning → privacy unwrap (if J1 = delete) → settings/DI/UI pruning → test rewrites → lint. Repeat until `assemble*` and the unit tests pass.
4. **`docs: …`**: §6 doc changes and the §7 D9 wording.
5. Verify on redroid (§8), report, push `main` (edge-release is manual by then, so the push publishes nothing).

---

## 2. KEEP

Only what an LLM needs to drive the phone, plus the owner-listed extras (Event Channel, minimum UI).

### 2.1 Accessibility core (the reason for the fork)
- `M/services/accessibility/`: all files **except** `AccessibilityToolCallIndicator.kt`. That's `McpAccessibilityService`, `AccessibilityServiceProvider(+Impl)`, `AccessibilityTreeParser`, `CompactTreeFormatter`, `WebViewNodeMerger`, `ElementFinder`, `ActionExecutor(+Impl)`, `AccessibilityNodeCache(+Impl)`, `CacheInvalidationDebouncer`, `AccessibilityTreeLock`, `TypeInputController(+Impl)`, `ScreenInfo`, `ScreenStateSnapshotCache(+Impl)`.
- `app/src/main/res/xml/accessibility_service_config.xml`.

### 2.2 Screenshot capture (exists)
- `M/services/screencapture/`: `ApiLevelProvider`, `ScreenCaptureProvider(+Impl)`, `ScreenshotAnnotator`, `ScreenshotEncoder`. (`ScreenshotRedactor` → J1.)
- `M/data/model/ScreenshotData.kt`.

### 2.3 Tool handler logic (logic only, no MCP binding)

Package stays `mcp.tools`, and `McpToolException` stays; rename later, not now.

| File | Keep (handler → tool-surface class) | Remove from the file (fix phase) |
|---|---|---|
| `TouchActionTools.kt` | `TapTool` (MAPS), `LongPressTool` (MAPS), `DoubleTapTool`, `SwipeTool`, `ScrollTool` (NEW-OP, J3) | every `register()`, `registerTouchActionTools` |
| `NodeActionTools.kt` | `FindNodesTool`, `ClickNodeTool`, `LongClickNodeTool`, `ScrollToNodeTool` (MAPS), `TapNodeTool` (NEW-OP) | `register*` |
| `SystemActionTools.kt` | `PressBackHandler`, `PressHomeHandler`, `PressRecentsHandler` (MAPS), `DismissKeyboardHandler` (NEW-OP) | `OpenNotificationsHandler`, `OpenQuickSettingsHandler` (LATER), `register*` |
| `TextInputTools.kt` | `TypeAppendTextTool`, `TypeClearTextTool` (MAPS), `TypeInsertTextTool`, `TypeReplaceTextTool`, `PressKeyTool` (NEW-OP) | `register*` |
| `UtilityTools.kt` | `WaitForNodeTool` (MAPS), `GetClipboardTool`, `SetClipboardTool`, `WaitForIdleTool` (NEW-OP) | `GetNodeDetailsTool` (LATER), `register*` |
| `ScreenIntrospectionTools.kt` | `GetScreenStateHandler` (MAPS) | `register*` |
| `AppManagementTools.kt` | `OpenAppHandler` (MAPS), `CloseAppHandler` (NEW-OP) | `ListAppsHandler` (LATER), `register*` |
| `IntentTools.kt` | `OpenUriHandler` (NEW-OP) | `SendIntentHandler` (DROP), `register*` |
| `TreeFingerprint.kt` | all (used by `wait_for_idle`) | — |
| `McpToolUtils.kt` | argument parsing/validation, result builders, `untrusted*` helpers | SDK types (§5.2), `buildToolNamePrefix`/`buildServerName` |
| `M/mcp/McpToolException.kt` | all (27 users in kept code) | — |

That's 14 MAPS + 13 NEW-OP handlers kept. `take_camera_photo` is the 14th NEW-OP; see J4.

### 2.4 Services the kept handlers need
- `M/services/apps/`: `AppManager(+Impl)`, `AppIconCache` (also used by the Event Channel's app filter UI).
- `M/services/intents/`: `IntentDispatcher(+Impl)`. The send-intent path goes in the fix phase if nothing kept uses it.
- `M/services/power/`: `BatteryOptimizationManager`, `BatteryOptimizationState`, and `G`/`F` `…BatteryOptimizationManagerImpl`, `di/…BatteryModule`.
- `M/data/model/`: `AppFilter`, `AppInfo`.

### 2.5 Event Channel (D-20; owner-listed)
- `M/services/channel/`: `EventChannelService`, `EventDispatcher(+Impl)`, `listeners/NotificationEventListener`. (Geofencing and Wi-Fi: deleted, J2.)
- What it drags in, all kept:
  - `M/services/notifications/McpNotificationListenerService.kt`, `NotificationDataExtractor.kt`, **and, through the deletion commit only,** `NotificationProvider.kt` + `NotificationProviderImpl.kt`: the former declares `NotificationData`/`NotificationActionData` (used by the channel, the listener service and the extractor), and the extractor calls `NotificationProviderImpl.computeActionHash`/`computeNotificationHash`. The fix phase moves those into kept files and then removes the rest (§5.3).
  - `M/data/model/`: `ChannelConnectionStatus`, `ChannelEvent`, `ChannelEventFactory`, `EventChannelConfig`, `NotificationChangeEvent`, `ServerLogEntry`
  - `M/data/repository/`: `EventChannelSettings(+Impl)`, `ServerLogRepository(+Impl)`, `ServerLogSegmentedStore`, `SettingsChangeLogger`, `SettingsJsonCodec`, `SettingsRepository(+Impl)` (pruned, §5.5)
  - UI: `ChannelSettingsScreen`, `NotificationFilterScreen`, `ChannelViewModel`
  - libraries: Ktor **client** (`okhttp`, `content-negotiation`, `serialization-kotlinx-json`), `slf4j-android` (Ktor client's logging binding)
- Flavour-specific, kept: `G`/`F` `services/power/…BatteryOptimizationManagerImpl.kt`, `di/…BatteryModule.kt`, `G/AndroidManifest.xml` (battery permission only after J2).

### 2.6 Minimum UI
- `M/McpApplication.kt` (pruned), `M/ui/MainActivity.kt`, `M/ui/theme/*`, `M/ui/navigation/Routes.kt` (pruned).
- Screens: `MainScreen` (tabs Home / Settings / About), `ServerScreen` → pruned to the accessibility-required callout + `BatteryOptimizationCard` + channel callouts, `ServerTabScreen` + `LogsScreen` (J8), `SettingsScreen` (nav graph), `SettingsIndexScreen`, `PermissionsSettingsScreen` (pruned to accessibility, notification listener, notifications), `AboutScreen` (licence and upstream attribution, which MIT requires; DB-IP line removed).
- Components: `BatteryOptimizationCard`, `CalloutCard`, `ServerLogsSection` (J8).
- ViewModels: `MainViewModel` (pruned), `ChannelViewModel`, `LogsViewModel` (J8).
- `M/utils/`: `Logger`, `PermissionUtils`.
- `M/di/AppModule.kt` (pruned).

### 2.7 Tests kept
Every test of a kept class: `T/services/accessibility/*` (except `ToolCallIndicatorFormattingTest`), `T/services/screencapture/*` (`ScreenshotRedactorTest` → J1), `T/services/apps/AppManagerTest`, `T/services/intents/IntentDispatcherImplTest`, `T/services/power/*`, `T/services/channel/**`, `T/services/notifications/NotificationDataExtractorTest`, `T/data/model/{ChannelEventFactoryTest, EventChannelConfigTest, ServerLogEntryTypeTest}`, `T/data/repository/{EventChannelSettingsTest, ServerLogRepositoryImplTest, ServerLogSegmentedStoreTest, SettingsChangeLoggerTest}` + pruned `SettingsRepository*Test`, `T/mcp/tools/{TouchActionToolsTest, NodeActionToolsTest, SystemActionToolsTest, TextInputToolsTest, UtilityToolsTest, ScreenIntrospectionToolsTest, IntentToolsTest, McpToolUtilsTest, TreeFingerprintTest, UntrustedWarningTestHelper}` (pruned to kept handlers), `T/ui/viewmodels/{ChannelViewModelTest, LogsViewModelTest, MainViewModelTest}`, `T/utils/{LoggerTest, PermissionUtilsTest}`, `T/manifest/*` (updated), `T/testutil/RecordingServerLogRepository`, (no `testGms`/`testFoss` tests remain after J2).

**Rewritten, not deleted:** integration tests that covered kept handlers through `McpIntegrationTestHelper` over HTTP (`TouchAction`, `NodeAction`, `SystemAction`, `TextInput`, `Utility`, `ScreenIntrospection`, `AppManagementTools`, `IntentTools`, `ErrorHandling`) become direct `execute()` tests. Cases the existing `T/mcp/tools/*Test` already covers are merged, not duplicated; cases unique to the integration layer (multi-step sequences, error propagation, argument edge cases) are ported. Registration-only assertions (tool names, schemas, prefixes, counts, permission gating) have no subject left and are dropped.

---

## 3. DELETE (the single deletion commit — whole files and directories)

### 3.1 Modules and top-level
- `e2e-tests/` (module); its CI job goes in the fix phase.
- `compose-test-app/` (module): **J6**, an e2e-only fixture.
- `channel-plugin/`, `.claude-plugin/` (its only content is the marketplace registration of `channel-plugin`).
- `scripts/location-db/`, `.dbip-cache/` (local cache, gitignored; the directory itself).
- **J1 = delete:** `privacy/`, `privacy-benchmark/`, `scripts/privacy/`.

### 3.2 `M` (app main)
- `mcp/`: `McpServer.kt`, `McpStatelessTransport.kt`, `McpApplicationPlugins.kt`, `Cors.kt`, `ContentTypeUtil.kt`, `RequestBaseUrl.kt`, `CertificateManager.kt`, `auth/` (1), `oauth/` (19).
- `mcp/tools/`: `CameraTools.kt`, `FileTools.kt`, `GestureTools.kt` (pinch LATER, custom_gesture DROP), `LocationTools.kt`, `NotificationTools.kt`, `SharingTools.kt`, `LoggedToolRegistration.kt`, `ToolCallIndicator.kt`, `ReferenceCountedToolCallIndicator.kt`.
- `geo/` (4).
- `services/mcp/` (8: `McpServerService`, `BootCompletedReceiver`, `PackageReplacedReceiver`, `McpServerRestart`, `AdbConfigReceiver`, `AdbConfigHandler`, `AdbServiceTrampolineActivity`, `OAuthApprovalNotifier`): **J7**.
- `services/sharing/` (7), `services/storage/` (13), `services/camera/` (3, **J4**), `services/update/` (7; it queries **upstream's** GitHub releases, `danielealbano/android-remote-control-mcp`).
- `services/accessibility/AccessibilityToolCallIndicator.kt`.
- **J1 = delete:** `privacy/` (5 + `model/PrivacyModelDownloader.kt`), `services/screencapture/ScreenshotRedactor.kt`.
- `data/model/`: `AvailableUpdate`, `BindingAddress`, `BuiltinAccessLevel`, `BuiltinPermissions`, `BuiltinStorageLocation`, `CameraInfo`, `CameraResolution`, `CertificateSource`, `FileInfo`, `OptionalToolPermission`, `ServerConfig`, `ServerStatus`, `StorageBackend`, `StorageLocation`, `ToolPermissionsConfig`. `PrivacyModeConfig` wherever it lives (J1).
- `data/repository/OAuthClientRepository.kt`, `OAuthClientRepositoryImpl.kt`.
- `ui/ApprovalActivity.kt`, `ui/ShareReceiverActivity.kt`.
- `ui/components/`: `ConnectionInfoCard`, `ServerStatusCard`, `UpdateAvailableBanner`, `PrivacyModeCard` (J1).
- `ui/screens/ApprovalScreen.kt`.
- `ui/screens/settings/`: `AccessSettingsScreen`, `GeneralSettingsScreen` (every setting on it served deleted code: port, binding, auto-start, device slug, hide-from-recents, tool-call indicator), `McpToolsSettingsScreen`, `OAuthClientsScreen`, `SecuritySettingsScreen`, `StorageSettingsScreen`, `PrivacySettingsScreen` (J1).
- `ui/viewmodels/`: `AccessViewModel`, `ApprovalViewModel`, `OAuthClientsViewModel`, `UpdateViewModel`, `PrivacyViewModel` (J1).
- `utils/NetworkUtils.kt`, `utils/RecentsUtils.kt`.
- **J2:** `services/channel/GeofenceChannelController.kt`, `services/channel/listeners/WifiEventListener.kt`, `services/location/` (2), `data/model/LocationData.kt`, `ui/screens/settings/WifiMonitorScreen.kt`.
- **J2, `G` (gms):** `di/GmsGeofenceConfigModule.kt`, `di/GmsGeofenceModule.kt`, `di/GmsLocationModule.kt`, `startup/FlavorStartup.kt`, `data/model/GeofenceChannelEventFactory.kt`, `data/model/GeofenceConfig.kt`, `data/repository/GeofenceConfigRepository.kt`, `GeofenceConfigRepositoryImpl.kt`, `services/channel/GeofenceChannelControllerImpl.kt`, `services/channel/geofence/` (3), `services/channel/listeners/GeofenceEventListener.kt`, `services/location/LocationProviderImpl.kt`, `ui/navigation/GeofenceRoutes.kt`, `ui/viewmodels/GeofenceSettingsViewModel.kt`, `ui/screens/settings/{BackgroundLocationPermissionRow, GeofenceDestinations, GeofenceEventSourceItem, GeofenceListScreen, GeofenceMapScreen}.kt`, `res/values/strings.xml` (all geofence/background-location overrides).
- **J2, `F` (foss):** `di/FossGeofenceModule.kt`, `di/FossLocationModule.kt`, `startup/FlavorStartup.kt`, `services/channel/NoOpGeofenceChannelController.kt`, `services/location/FossLocationProviderImpl.kt`, `ui/screens/settings/{BackgroundLocationPermissionRow, GeofenceDestinations, GeofenceEventSourceItem}.kt`.

### 3.3 Other source sets
- `app/src/debug/`: `E2EConfigReceiver.kt`, `OAuthApprovalTestReceiver.kt` (+ their manifest entries in the fix phase).
- `app/src/testDebug/…/E2EConfigReceiverTest.kt`.
- **J2:** `app/src/testGms/` (7 files) and `app/src/testFoss/` (2 files): all geofence/location tests.

### 3.4 Tests (`T`) whose subject is deleted
- `geo/` (3), `mcp/` (3), `mcp/auth/` (2), `mcp/oauth/` (12), `services/mcp/` (4), `services/sharing/` (4), `services/storage/` (8), `services/camera/` (2), `services/update/` (3), `services/accessibility/ToolCallIndicatorFormattingTest`.
- `mcp/tools/`: `CameraToolsTest`, `GestureToolsTest`, `GetNodeDetailsToolTest`, `LocationToolsTest`, `LoggedToolHandlerTest`, `NotificationToolsTest`.
- `integration/`: `McpIntegrationTestHelper`, `McpProtocolIntegrationTest`, `AuthIntegrationTest`, `AuthFailureLoggingIntegrationTest`, `CorsIntegrationTest`, `OAuthFlowIntegrationTest`, `OAuthLoggingIntegrationTest`, `SharingIntegrationTest`, `CameraToolsIntegrationTest`, `FileToolsIntegrationTest`, `LocationToolsIntegrationTest`, `NotificationToolsIntegrationTest`, `GestureIntegrationTest`, `ToolCallLoggingIntegrationTest`, `ToolPermissionsIntegrationTest`, `PermissionGatedToolsIntegrationTest`, `PrivacyModeIntegrationTest` (J1). **Exception:** the nine files listed under "Rewritten" in §2.7 are **not** deleted in this commit; they're rewritten in the fix phase.
- `data/model/`: `BuiltinAccessLevelTest`, `BuiltinStorageLocationTest`, `OptionalToolPermissionTest`, `ServerConfigTest`, `ServerStatusTest`, `ToolPermissionsConfigTest`, `PrivacyModeConfigTest` (J1).
- `data/repository/`: `SettingsRepositoryServerRunningTest`, `SettingsRepositoryUpdateCheckTest`.
- `ui/components/` (2), `ui/screens/settings/StorageSettingsHelpersTest`, `ui/viewmodels/`: `AccessViewModelTest`, `ApprovalViewModelTest`, `OAuthClientsViewModelTest`, `PrivacyViewModelTest` (J1), `UpdateViewModelTest`.
- `utils/`: `NetworkUtilsTest`, `RecentsUtilsTest`.
- **J2:** `services/channel/listeners/WifiEventListenerTest`.
- `privacy/` (2) + `privacy/model/` (1) + `testutil/PrivacyToolTestDoubles` (J1).
- `app/src/test/resources/geo/` (fixture).

---

## 4. Judgement calls: recommendations, owner decides

| # | Question | Recommendation | Reasoning |
|---|---|---|---|
| **J1** | The privacy module (`:privacy`, `:privacy-benchmark`, app `privacy/`, ONNX Runtime, model downloader, Privacy UI, `ScreenshotRedactor`, the `CLAUDE.md` benchmark rule) | **Delete.** | Not needed to drive the phone and absent from the design doc. It is large: its own module, a benchmark module, ONNX Runtime, a model download, a settings screen, and a standing `CLAUDE.md` obligation. It isn't deletion-only: `PrivacyToolGate`/`PlaceholderSubstitutor` are woven into five kept handler files and into the Event Channel's `NotificationEventListener`, so the fix phase unwraps them (§5.4). **One design reason beyond size:** the model sees pseudonym placeholders and types them back, and the device substitutes the real values from `PseudonymStore`. A flow saved from such an authoring session (D-10: "the tool-call log *is* the flow") would carry placeholders whose mapping lives in device-side session state, which conflicts with whole-flow replay (D-18) on a later run or another device. Privacy on the device needs redesigning for flows, not carrying forward. **Counter-argument:** it is the only place PII can be removed *before* it leaves the phone (§5.2: "the server receives only what the device sends"). Restorable from `pre-demolition`. If kept, §2/§3 lose every "(J1)" deletion and §5.4 doesn't happen. |
| **J2** | Collapse `gms`/`foss`? | **Superseded by the owner's J2** (see Status). Geofencing, location and (by cost) Wi-Fi are deleted; the flavours still differ in the battery-exemption flow only, so they're not collapsed here; recommendation in the report. |
| **J3** | NEW-OP: keep only the services underneath, or the handlers too? | **Keep the 13 NEW-OP handlers' logic** (listed in §2.3). | For several the logic *is* the handler, not the service: `type_replace_text`'s find-and-replace, `wait_for_idle`'s fingerprint loop, `scroll`'s amount/variance, `tap_node`'s inset gesture, `press_key`'s key mapping, clipboard access. Deleting the handlers would delete exactly what the NEW-OP ops need underneath. |
| **J4** | `take_camera_photo`, the 14th NEW-OP, as `camera_capture` | **Delete all camera code** (`CameraTools`, `services/camera`, CameraX, `CAMERA`/`RECORD_AUDIO` permissions). | Design doc: it "cannot ship before D-21 does", and D-21's code "is not in v0" (OPEN (b)). The other 5 camera tools are LATER. Keeping ~1,100 lines plus CameraX for an op that can't run is the opposite of this pass. |
| **J5** | Tools that don't map cleanly (tool-surface.md) | **Keep as-is and note:** `find_nodes` (subsumed by selector resolution; its search logic is what other ops need), `get_screen_state` (front-door `read_screen`, not a step op; stays because authoring needs it), `scroll_to_node` (auto-direction vs `scroll_find`'s required `direction`: unresolved, tool-surface #5), `press_key` (mixed shapes; DEL→`delete_char`), `open_uri` (shares `IntentDispatcher` with DROP `send_intent`; only the send path goes), `close_app` (keep `KILL_BACKGROUND_PROCESSES`). **Delete** `get_node_details` as listed LATER, even though it's an authoring aid. | Deciding their final op shapes is the executor plan's job; keeping the logic keeps that choice open. |
| **J6** | `compose-test-app` | **Delete.** | Its only consumer is `e2e-tests` (module-map §1). |
| **J7** | `services/mcp/` foreground service + boot/package-replaced receivers | **Delete.** | They start and host the MCP server. The transport will need a foreground service, and module-map §4.4 already notes its *shape* (5 s `startForeground`, `START_STICKY`, `onTaskRemoved`, boot restart) is reusable; take it from `pre-demolition` then. The Event Channel has its own foreground service (`EventChannelService`) and never used these receivers. |
| **J8** | Logs (`ServerLogRepository`, `LogsScreen`, `ServerLogsSection`) | **Keep.** | The Event Channel writes its errors and recoveries there; it's the only on-device diagnostics for the one kept feature that talks to the network. |
| **J9** | Docs beyond the three named | **`docs/ARCHITECTURE.md`**: rewrite short (CLAUDE.md makes it mandatory reading and it's entirely server-based). **`docs/MCP_TOOLS.md`**: delete (documents 57 MCP tools; `tool-surface.md` is the current reference). **`docs/PERMISSIONS.md`, `CONTRIBUTING.md`**: edit. `docs/plans/*`, `build-notes.md`, `module-map.md`, `tool-surface.md`: untouched. | Otherwise the mandatory-reading docs describe an app that no longer exists. |
| **J10** | `SettingsRepository` is almost entirely server settings | **Keep the class, prune it** to the Event Channel accessors it delegates to, plus the privacy-card flag if J1 = keep. | The Event Channel reads `getEventChannelConfig()`/`eventChannelConfig` through it; collapsing it into `EventChannelSettings` is a refactor, not a deletion. |

---

## 5. Fix phase (edits after the deletion commit)

### 5.1 Build and modules
- `settings.gradle.kts`: remove `:e2e-tests`, `:compose-test-app` and (J1) `:privacy`, `:privacy-benchmark`.
- `app/build.gradle.kts`, **verify each against remaining imports before removing**. Expected to go: `ktor-server-*` (4), `ktor-network-tls-certificates`, the Netty `constraints {}` block, Bouncy Castle (main + test), `mcp-kotlin-sdk-server`, `mcp-kotlin-sdk-client` (test), `ktor-server-test-host`, `ktor-sse`, Jackson BOM, `java-jwt`, Coil ×2, CameraX ×4 (J4), `androidx-documentfile`, osmdroid + `play-services-location` (J2), WorkManager + `androidx-hilt-work` + its `ksp` (update checker only), `accompanist-permissions` (already unused), `project(":privacy")` + `onnxruntime-android` (J1); the `generateLocationDb` task + `GenerateLocationDbTask`. Expected to stay: Compose, Hilt, DataStore, kotlinx-serialization/coroutines, Ktor **client** + `serialization-kotlinx-json`, `slf4j-android`, `ktor-client-mock` (test, if Event Channel tests use it).
- `gradle/libs.versions.toml`: drop the unused entries. Root `build.gradle.kts`: stale Bouncy Castle comment (`:55`).
- `Makefile`: delete targets `test-e2e`, `privacy-benchmark` (J1), `start-server`, `forward-port`, `setup-emulator`, `start-emulator`, `stop-emulator`, `check-so-alignment` if only native libs used it, and fix `test`, `all`, `ci`, `check-deps`.
- CI: `ci.yml` remove the `test-e2e` job, the geolocation Python setup + DB-IP cache steps (two jobs) and any privacy-benchmark step; `edge-release.yml` + `release.yml` remove the Python/DB-IP steps.

### 5.2 Replace the MCP SDK types (the one new piece of code)
Handlers currently return `io.modelcontextprotocol.kotlin.sdk.types.CallToolResult` (content = `TextContent`/`ImageContent`). With the SDK gone, add a minimal internal type that mirrors that shape, so handler bodies and test assertions change mechanically:
```kotlin
// M/mcp/tools/ToolResult.kt
package com.danielealbano.androidremotecontrolmcp.mcp.tools

/** Result of a tool handler: what an LLM-facing caller receives. Mirrors the shape the MCP SDK used. */
data class ToolResult(
    val content: List<ToolContent>,
    val isError: Boolean = false,
)

sealed interface ToolContent {
    data class Text(val text: String) : ToolContent

    /** [data] is base64-encoded image bytes. */
    data class Image(val data: String, val mimeType: String) : ToolContent
}
```
- `McpToolUtils`: result builders (`textResult`, `untrustedTextResult`, `untrustedTextAndImageResult`, `untrustedImageResult`, `handleActionResult`, error builders) return `ToolResult`. Keep the untrusted-content warning: it's about anything device-derived reaching a model, not about MCP.
- Handlers: `execute(arguments: JsonObject?): ToolResult` (argument parsing stays on `kotlinx.serialization.json`, a direct dependency independent of the SDK); delete each `register()`/`register*Tools` and their `ToolSchema`/`Server`/`LoggedToolRegistrar`/`ToolPermissionsConfig` imports.
- `McpJson` (SDK) users: replace with the app's own `Json` instance.

### 5.3 Handler-level removals inside kept files
`OpenNotificationsHandler`, `OpenQuickSettingsHandler`, `GetNodeDetailsTool`, `ListAppsHandler`, `SendIntentHandler`. Notifications: move `NotificationData`/`NotificationActionData` to `M/data/model/NotificationData.kt` and the two hash functions into `NotificationDataExtractor` (moving their cases from `NotificationProviderImplTest` into `NotificationDataExtractorTest`), then `git rm` `NotificationProvider.kt`, `NotificationProviderImpl.kt` and `NotificationProviderImplTest.kt`. Then service methods left with no caller: `ActionExecutor` pinch/custom-gesture methods, `IntentDispatcher` send-intent path, `AppManager` list (**only if** the channel's app filter doesn't use it; check). Their unit tests go with them.

### 5.4 Privacy unwrap (only if J1 = delete)
**J2 edits (kept files):** `EventChannelService` (drop Wi-Fi/geofence listeners and controller; foreground type `location` → `specialUse` + manifest `<property>`), `EventChannelConfig.kt` (drop `WifiChannelConfig`, geofence config fields), `ChannelEventFactory` (drop `wifi(...)`), `EventChannelSettings(Impl)`, `ChannelSettingsScreen`/`ChannelViewModel` (drop Wi-Fi and geofence sections), `SettingsScreen` nav (drop Wi-Fi monitor + flavour geofence destinations), `McpApplication` (drop osmdroid config and `runFlavorStartupMigrations`), `res/values/strings.xml` `event_channel_subtitle`.

In `NodeActionTools`, `TextInputTools`, `UtilityTools`, `ScreenIntrospectionTools`, `AppManagementTools`, `services/channel/EventChannelService` (injected `privacyToolGate`, passed to the listener) and `services/channel/listeners/NotificationEventListener`: remove `PrivacyToolGate`/`PlaceholderSubstitutor` parameters and calls, passing text through unchanged (typed text is typed literally; notification text is sent as observed). `GetScreenStateHandler`: drop the screenshot-redaction step.

### 5.5 Wiring and UI
- `di/AppModule.kt`: remove every binding for a deleted class (OAuth, geo, sharing, storage, camera, notifications provider, update, privacy (J1), certificate).
- `AndroidManifest.xml` (main + debug + gms): remove the entries for deleted components (`McpServerService`, boot/package receivers, `AdbConfigReceiver`, `AdbServiceTrampolineActivity`, `ApprovalActivity`, `ShareReceiverActivity`, debug receivers), the WorkManager-initializer `<provider>` removal block, and permissions nothing uses any more: `CAMERA`, `RECORD_AUDIO` (J4), `READ_MEDIA_*` (storage), `RECEIVE_BOOT_COMPLETED` (only the deleted boot receiver), **J2:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (gms), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES`, `FOREGROUND_SERVICE_LOCATION`, and the gms `GeofenceTransitionReceiver`. `FOREGROUND_SERVICE_SPECIAL_USE` **stays** (J2's `specialUse`). Keep `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`. **Verify each** against remaining code.
- `McpApplication.kt`: drop the update scheduler, the OAuth-approval / MCP-server notification channels, osmdroid config and `runFlavorStartupMigrations` (J2).
- `SettingsRepository(Impl)`, `SettingsJsonCodec` (its `BuiltinPermissions` storage codec): remove every server/OAuth/HTTPS/storage/tool-permission/privacy/update/auto-start/device-slug/hide-from-recents member and key; keep event-channel delegation (J10).
- Navigation, `MainScreen` (update banner), `SettingsIndexScreen`, `SettingsScreen` (7 deleted destinations), `ServerScreen` (7 deleted refs), `PermissionsSettingsScreen`, `AboutScreen` (`UpdateViewModel`, DB-IP line), `MainViewModel` (9 deleted types), `MainActivity` (`RecentsUtils`, update scheduler): remove references to deleted screens, settings and state.
- `res/values/strings.xml` (+ gms strings): remove strings no longer referenced (lint `UnusedResources` as the check).

### 5.6 Tests
Rewrite per §2.7; update the manifest tests to the new component and permission set; prune kept test files of cases for deleted handlers. Then `./gradlew ktlintCheck detekt` clean.

---

## 6. Documentation (docs commit)
- `README.md`: rewrite to what the app is now (accessibility service + Event Channel; remote control arrives with the transport); remove server/OAuth/tunnel/HTTPS/connector setup.
- `docs/PROJECT.md`: remove server, OAuth, HTTPS, tunnel, MCP transport, sharing, storage, camera, notification-tool and location-tool sections; the MCP tool specification becomes a pointer to `tool-surface.md`; fix the stale "Folder Structure" (module-map §1 already flagged it).
- J9: `docs/ARCHITECTURE.md` rewrite; `docs/MCP_TOOLS.md` delete; `docs/PERMISSIONS.md`, `CONTRIBUTING.md` edit.
- `docs/debug-device.md`: port 8080 no longer used; the device is UI-only until the transport lands.
- Untouched: `docs/plans/*`, `docs/build-notes.md`, `docs/module-map.md`, `docs/tool-surface.md`.

---

## 7. D9: proposed wording (approve here)

### 7.1 `CLAUDE.md`
- **§1**, bullet "All operations that may be retried… (MCP tool calls, accessibility actions, service lifecycle)" → "(tool handler calls, accessibility actions, service lifecycle)".
- **§1**, Definition of Done bullet "MCP protocol compliance verified (if MCP tools are modified)" → **delete**.
- **§5 Service-based architecture**: replace the three bullets with:
  - **AccessibilityService** (`McpAccessibilityService`): UI introspection, action execution, screenshot capture via `takeScreenshot()`.
  - **EventChannelService**: foreground service that observes notification events and POSTs them outbound (D-20).
  - **MainActivity**: lightweight UI for permissions and Event Channel configuration; no business logic.
  - *(The outbound transport service is added by the transport plan; there is no on-device server — design doc D-19/SEC-19.)*
- **§5 Service lifecycle rules**: unchanged. **§5 Repository pattern**: unchanged.
- **§6 Data Storage Rules**: replace "All settings (port, binding address, bearer token, auto-start, HTTPS enabled toggle, HTTPS certificate config) MUST be stored in DataStore." with "All settings MUST be stored in DataStore." Delete the paragraph beginning "**HTTPS is optional and disabled by default**…". In "Data types", replace the port/`BindingAddress`/bearer examples with "Use appropriate types (`Int`, `Boolean`, enums/sealed classes for fixed options)." Delete the binding-address enum example. Settings validation: replace the port/IP examples with "(e.g. URLs must parse, numeric ranges enforced)". Delete "(but don't log bearer token in production)" → "Never log secrets (tokens, credentials) at any level."
- **§7 Backend Rules**: title → "Backend Rules (Kotlin + Android)". Structure: drop "`McpServerService`: Orchestrate MCP protocol, HTTP server lifecycle"; "**MCP Tool Implementations** are isolated" → "**Tool handlers** (`mcp/tools/`) are isolated: pure logic returning `ToolResult`, one category per file, dependencies by constructor injection; no transport code." **Validation**: "Validate all incoming tool parameters (type, range, required fields); invalid parameters raise `McpToolException.InvalidParams`." Delete the MCP error-code sentence and "Keep validation aligned with MCP tool schemas". **Authorization**: delete the whole subsection. **Permission handling**: "Return MCP error `-32001`" → "raise the permission-denied `McpToolException`". **Logging**: drop "MCP server start/stop" and the bearer-token example; keep "Never log … sensitive data". **Anti-prompt-injection**: keep, with "MCP tool" → "tool handler" and the helper names as in `McpToolUtils` after §5.2. **Privacy detection effectiveness table**: delete the whole rule (if J1 = delete).
- **§8 Frontend**: Forms example "(port, token)" → "(e.g. endpoint URL)", "Port must be between 1 and 65535" → "Endpoint must be an http(s) URL"; `Switch` example "(auto-start, HTTPS)" → "(e.g. notification forwarding)"; binding-address `RadioButton` example → delete.
- **§9 Testing**: replace "Integration testing (JVM-based, Ktor testApplication)" and its whole subsection with: "**Handler tests (JVM)**: tool handlers are tested by calling `execute()` directly with MockK doubles for Android service interfaces (`ActionExecutor`, `AccessibilityServiceProvider`, `ScreenCaptureProvider`, `AccessibilityTreeParser`, `ElementFinder`)." Replace the whole "E2E testing (Redroid + Podman + Testcontainers)" subsection with: "**E2E testing**: none at present. The previous redroid/Testcontainers MCP-over-HTTP suite was removed with the on-device server (demolition plan); its replacement is a fake-relay harness built with the transport (plan 66, US-9)." "Environment variables for tests": delete the `NgrokTunnelIntegrationTest` example (the `.env` mechanism stays).
- **§10**: "Podman: Required for E2E tests…" → "Podman: required only for the persistent redroid debug device (`docs/debug-device.md`)." "Gradle: Version 8.x" → "Gradle: wrapper-managed (`./gradlew`)." Emulator: unchanged. Device setup: delete "Port forwarding: `make forward-port`…".
- **§11**: delete "Health check endpoint (MCP server)" and "Graceful shutdown": "McpServerService" bullets (keep the AccessibilityService shutdown bullets under a "Graceful shutdown" heading). CI jobs line → "lint → test-unit → build-release."

### 7.2 `.claude/agents/plan-reviewer.md` and `.claude/agents/code-reviewer.md` (same edits in both where the line exists)
- "integration tests use Ktor `testApplication` with MockK for Android service interfaces" → "handler tests call `execute()` directly with MockK doubles for Android service interfaces."
- plan-reviewer `:83` (E2E via Testcontainers/redroid) → delete.
- "Ktor server resources properly managed…" → "Network client resources (Ktor client, sockets) properly closed; timeouts set."
- "Bearer token stored in DataStore…", "Bearer token NEVER logged", "constant-time comparison" → "Secrets (e.g. Event Channel auth token) stored in DataStore (app-private) and NEVER logged at any level (CRITICAL)."
- "Network binding defaults to localhost…", "HTTPS certificate handling…", "Health check endpoint…" → replace all three with "**No listening sockets and no exported components that change app behaviour** (SEC-19). Flag any `ServerSocket`, embedded server engine, or newly exported component as CRITICAL."
- Summary lines "…permission checks, network binding" → "…permission checks, no inbound network surface".

---

## 8. Closure check (done while writing this plan)

A script parsed every `.kt` file in `main`/`gms`/`foss`/`debug`, marked the §3 deletions (J1 = delete), and listed every kept file that references a declaration in a deleted file (by import or same-package name). Every hit is covered by a §5 edit: `AppModule` (bindings), the eight kept `mcp/tools` files (registration/`ToolPermissionsConfig`/privacy), `SettingsRepository(Impl)`, `SettingsJsonCodec`, `MainViewModel`, `MainActivity`, `MainScreen`, `SettingsScreen`, `ServerScreen`, `AboutScreen`, `McpApplication`, `EventChannelService` and `NotificationEventListener`. It found two things the first draft had wrong, both now fixed above: the `NotificationProvider*` files can't be deleted outright, and `EventChannelService` also holds `PrivacyToolGate`. Tests weren't included; the compiler finds those in the fix phase.

## 9. Verification after the fix phase
- [ ] `./gradlew assembleGmsDebug assembleFossDebug assembleGmsRelease assembleFossRelease`, no warnings
- [ ] `./gradlew :app:test` green (and `testGms`/`testFoss` variants)
- [ ] `./gradlew ktlintCheck detekt` clean
- [ ] `./gradlew :app:dependencies --configuration gmsReleaseRuntimeClasspath`: no `io.ktor:ktor-server-*`, `io.netty`, `io.modelcontextprotocol`, `org.bouncycastle`, `com.auth0`, `coil`, CameraX, ONNX (J1)
- [ ] Install `gmsDebug` on the redroid debug device (volume reset first, since the app's stored settings shape changes), launch, enable the accessibility service (`settings put secure enabled_accessibility_services …` and the in-app Settings path), and confirm `McpAccessibilityService` connects (logcat) and the home screen shows accessibility as enabled. Remote control: none. That is expected.
- [ ] `code-reviewer` subagent over the demolition diff
- [ ] Report: removed, what broke during the fix and how it was fixed, anything kept that was expected to go. Push.
