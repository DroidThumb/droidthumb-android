# droidthumb-android — Module map and D-19 removal plan

**Status of this document:** read-only research output. No code, branches, or git state were changed to produce it. Where the design doc (`droidthumb-server/docs/droidthumb-design-doc.md`, v0.5) and the repository disagree, the disagreement is reported below (see "Contradictions with the design doc"); the design doc has not been edited.

**Repo state at time of writing:** `droidthumb-android` `main` is byte-for-byte identical to the `upstream-baseline` tag (`git log upstream-baseline..main` and the reverse are both empty, both point at `16f3971`, dated 2026-08-26). This is genuinely the unmodified fork the task description says it is — no applicationId work has landed yet (`app/build.gradle.kts:220` still reads `applicationId = "com.danielealbano.androidremotecontrolmcp"`). Everything below describes upstream code, read as the substrate D-19 will cut into.

---

## 1. Module map

Gradle modules are declared in `settings.gradle.kts:1-19`: `app`, `privacy`, `privacy-benchmark`, `compose-test-app`, `e2e-tests`. `channel-plugin`, `vendor`, `docs`, and `scripts` are **not** Gradle modules — they are plain directories (a Bun/TypeScript CLI plugin, two git submodules, documentation, and standalone Python scripts, respectively).

### `app` — the APK

**What it does:** The entire Android application — accessibility service, on-device MCP server (Ktor/Netty), self-contained OAuth 2.1 authorization server, Cloudflare/ngrok tunnels, Compose UI, settings, camera/file/location/notification/sharing tool surfaces, privacy-mode redaction wiring, event-channel push, update checker.

**Depends on:** `:privacy` (project dependency, `app/build.gradle.kts:434`), `onnxruntime-android` (runtime for the model `:privacy` only declares `compileOnly`), the two vendored native libraries `vendor/ngrok-java` (as prebuilt jars, `app/build.gradle.kts:396-398`) and `vendor/cloudflared` (as a bundled `libcloudflared.so`, per `docs/PROJECT.md:642`), Ktor server/client, the MCP Kotlin SDK, Hilt, java-jwt, Bouncy Castle, CameraX, osmdroid, DataStore, WorkManager.

**Depended on by:** `e2e-tests` (installs and drives the built APK inside a redroid container — not a Gradle `implementation` dependency, but a hard runtime one). `compose-test-app` is a separate installable fixture APK, not linked to `app` at build time; e2e/emulator tests install both side by side.

**D-19 touches it:** Yes — this is where nearly all of D-19's cutting and rebuilding happens. See §3 and §4.

### `privacy`

**What it does:** Pure-JVM on-device PII detection and redaction: deterministic detectors (card/email/IBAN/national-ID/phone/credential, `privacy/src/main/kotlin/.../privacy/detectors/`), a NER pipeline (ModernBERT tokenizer + ONNX Runtime inference, `privacy/src/main/kotlin/.../privacy/ner/`), and the merge/redaction engine (`RedactionEngine.kt`, `PlaceholderSubstitutor.kt`, `PseudonymStore.kt`). Consumed by `app` to redact device-derived text before it reaches an MCP client (`PrivacyToolGate`, referenced from `mcp/tools/SharingTools.kt` and others).

**Depends on:** `onnxruntime` (`compileOnly`, per `app/build.gradle.kts:435` comment), nothing else in this repo.

**Depended on by:** `app` (`implementation(project(":privacy"))`), `privacy-benchmark`.

**D-19 touches it:** No. Nothing in `privacy/` imports Ktor, OAuth, or tunnel code, and nothing in the wire-protocol/executor rework changes what needs redacting. Confirmed by grep — zero cross-imports between `privacy/` and `services/tunnel/` or `mcp/oauth/`.

### `privacy-benchmark`

**What it does:** A standalone JVM CLI (`privacy-benchmark/src/main/kotlin/.../benchmark/BenchmarkMain.kt`) that runs the `:privacy` detection pipeline against fixed corpora — a bundled adversarial corpus (`corpus_c.jsonl`), a downloaded `ai4privacy` corpus, and a generated "UI-synthetic" corpus (`UiCorpusGenerator.kt`) meant to look like real accessibility-tree text — and writes a `report.md` with per-category recall/precision (`scoring/ReportWriter.kt`, `Scorer.kt`). `make privacy-benchmark` runs it; `CLAUDE.md` makes re-running it and updating `PrivacySettingsScreen.kt`'s `MEASURED_DETECTION_RATES` mandatory after any detector change.

**Depends on:** `:privacy`.

**Depended on by:** nobody in-repo; it's a leaf, run manually/in CI.

**D-19 touches it:** No, for the same reason as `:privacy`.

### `compose-test-app`

**What it does:** A minimal fixture Compose app (`MainActivity.kt`, `WebViewActivity.kt`) with nothing but a launcher activity and a WebView activity (`compose-test-app/src/main/AndroidManifest.xml`). It exists purely as a target for e2e/emulator tests that need a controllable, source-owned UI (Compose refresh behaviour, WebView node-merging/refresh) instead of a real third-party app.

**Depends on:** nothing beyond Compose/AndroidX basics (own `build.gradle.kts`).

**Depended on by:** `e2e-tests` (`E2EComposeRefreshTest.kt`, `E2EWebViewNodeReductionTest.kt`, `E2EWebViewRefreshTest.kt` install and exercise it inside the redroid container).

**D-19 touches it:** No. It has no knowledge of the server/OAuth/tunnel layer; it's a passive fixture for the accessibility/tree-encoding tests, which D-19 explicitly must not damage.

### `e2e-tests`

**What it does:** JVM-only Gradle module that runs the *real* built APK inside a `redroid/redroid:14.0.0-latest` container via Testcontainers + rootful Podman (`e2e-tests/src/test/kotlin/.../AndroidContainerSetup.kt`, `SharedAndroidContainer.kt`), then drives it as an actual MCP client would: `McpClient.kt` wraps the MCP Kotlin SDK's `Client` + `StreamableHttpClientTransport` and talks HTTP to the container's exposed MCP port. Covers: calculator app interaction (`E2ECalculatorTest.kt`), camera (`E2ECameraTest.kt`), screenshots (`E2EScreenshotTest.kt`), storage tools incl. partial-access and edge cases (`E2EStorageToolsTest.kt`, `E2EStoragePartialAccessTest.kt`, `E2EStorageEdgeCasesTest.kt`), error handling (`E2EErrorHandlingTest.kt`), Compose/WebView tree refresh and node-reduction (`E2EComposeRefreshTest.kt`, `E2EWebViewRefreshTest.kt`, `E2EWebViewNodeReductionTest.kt`), and — critically for D-19 — **the full OAuth 2.1 dynamic-client-registration → consent → token → authenticated-`/mcp`-call flow** (`OAuthFlowE2ETest.kt`). `make test-e2e` runs it.

**Depends on:** the built `app` APK (installed into the container, not a Gradle dependency), `compose-test-app` APK, MCP Kotlin SDK client artifacts, Testcontainers.

**Depended on by:** nobody; it's the outermost test layer (`docs/TOOLS.md`/`PROJECT.md` "the slow test is the last layer", D-17).

**D-19 touches it:** Yes, and this is the sharpest edge in the whole plan — see §4, item 6. `AndroidContainerSetup`/`SharedAndroidContainer`/`McpClient` assume the device *listens* and the test *dials in*. Under D-19 the device dials *out*; there is no port to expose from the container. `OAuthFlowE2ETest.kt` tests a subsystem D-19 deletes outright.

### `channel-plugin`

**What it does:** A standalone Bun/TypeScript **Claude Code plugin** (`channel-plugin/index.ts`), not part of the Android build at all. It runs on the user's computer, exposes a local MCP stdio server plus a tiny HTTP listener (`POST http://127.0.0.1:9090/event`, bearer-token-guarded, `channel-plugin/index.ts:31-51`), and forwards events it receives as MCP notifications into a Claude Code session. It is the *receiving* end of the on-device "Event Channel" feature (see `EventChannelConfig.DEFAULT_ENDPOINT_URL = "http://localhost:9090"`, `app/src/main/kotlin/.../data/model/EventChannelConfig.kt:17`): `app`'s `EventDispatcherImpl` (`services/channel/EventDispatcherImpl.kt:109`) POSTs notification/Wi-Fi events *outbound* to this listener using a Ktor HTTP client (not the on-device server).

**Depends on:** the MCP TypeScript SDK, Bun runtime; nothing in this repo's Gradle graph.

**Depended on by:** nothing in-repo; it's a separate distributable artifact registered as a Claude Code plugin (`channel-plugin/.claude-plugin/plugin.json`, `.claude-plugin/marketplace.json` at repo root).

**D-19 touches it:** Not directly — it doesn't use the on-device listening server, OAuth, or tunnels; it's a client of `app`'s outbound HTTP push, which is a different subsystem. But it is **squarely in D-19's conceptual blast radius and the design doc never mentions it.** It is a second, parallel "phone talks to a channel on your computer" mechanism, requiring a computer — the opposite of B-03/B-13's "no computer required" pitch — and it overlaps functionally with the future `LATER` inbox/unattended-trigger loop (§8.4) that the WebSocket relay is meant to serve instead. This needs an explicit decision (delete it, or fold its notification/Wi-Fi-listener triggers into the relay's `run` dispatch), not silent inheritance. See §5(c).

### `vendor`

**What it does:** Two git submodules, both currently **uninitialized** (`git submodule status` shows a leading `-` for both — no working tree checked out): `vendor/cloudflared` (`cloudflare/cloudflared`, provides the bundled `libcloudflared.so` used by `CloudflareTunnelProvider`) and `vendor/ngrok-java` (`danielealbano/ngrok-java` fork, built from source into the jars `app/build.gradle.kts:396-398,459` references directly by path).

**Depends on:** nothing; these *are* the dependencies.

**Depended on by:** `app`'s tunnel code exclusively (`services/tunnel/CloudflareTunnelProvider.kt`, `services/tunnel/NgrokTunnelProvider.kt`) and the `build.gradle.kts` file-path jar references above.

**D-19 touches it:** Yes, entirely — D-19 removes tunnels, so both submodules and their `.gitmodules` entries (`.gitmodules:1-6`) are deleted outright. Because they're uninitialized in this checkout, there is nothing to "unbuild" locally, but CI and any contributor's build currently does check them out (the ngrok jar paths in `build.gradle.kts` are hard file-path `implementation(files(...))` references — removing the submodule without removing those lines breaks the build immediately, so this must be done as one atomic change, not two).

### `docs`

**What it does:** `PROJECT.md` (816 lines) and `ARCHITECTURE.md` (342 lines) are upstream's own "read these first" design bible for its own AI-agent workflow (per `CLAUDE.md`'s mandatory-read rule); `MCP_TOOLS.md` (3506 lines) documents all 57 tools; `PERMISSIONS.md` documents the permission bundle; `docs/plans/` holds 60+ dated implementation-plan documents that are, per `CLAUDE.md`, "PERMANENT artifacts... ZERO exceptions" on deletion.

**Depends on:** nothing; describes the code.

**Depended on by:** nobody programmatically; `CLAUDE.md` makes reading `PROJECT.md`/`ARCHITECTURE.md` mandatory before any agent work in this repo.

**D-19 touches it:** Only as documentation debt — every removal in §4 leaves `PROJECT.md`/`ARCHITECTURE.md`/`MCP_TOOLS.md` describing subsystems that no longer exist. Note also that `PROJECT.md`'s own "Folder Structure" section (lines 129-161) is **already stale relative to the current code** — e.g. it lists `ui/screens/HomeScreen.kt`, `ui/components/ConfigurationSection.kt`, `RemoteAccessSection.kt`, `PermissionsSection.kt`, `StorageLocationsSection.kt`, none of which exist; the actual files are `ui/screens/MainScreen.kt`, `ServerScreen.kt`, `ServerTabScreen.kt`, and a `ui/screens/settings/` package with a dozen files not mentioned at all. This is an upstream documentation-drift problem independent of D-19, flagged here because whoever does the D-19 rewrite will otherwise be misled by it.

### `scripts`

**What it does:** Two unrelated, standalone Python toolchains, neither invoked at runtime by the app. `scripts/location-db/` builds the gzipped offline IP-geolocation database (`build_location_db.py`) that `app/build.gradle.kts`' `GenerateLocationDbTask` (lines 598-640) invokes at **build time** from the monthly DB-IP City Lite CSV, consumed by `geo/DbIpGeoResolver.kt`/`geo/LocationDb.kt`. `scripts/privacy/generate_tokenizer_fixtures.py` generates the `:privacy` module's tokenizer test fixtures.

**Depends on:** Python 3, network access (for the DB-IP download) at build time only.

**Depended on by:** `app`'s build (`generateLocationDb` task) and `:privacy`'s test fixtures.

**D-19 touches it:** No.

---

## 2. Precise locations

**Ktor/Netty server, routes, and lifecycle**
- Server class: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpServer.kt` (223 lines). `start()`/`stop()` wrap an `embeddedServer(factory = Netty, ...)` (lines 70-151); `configureApplication()` (lines 153-216) installs plugins and routes.
- Routes registered directly on `McpServer.configureApplication()`: `GET /health` (line 177), `GET /s/{token}` — the ephemeral-file-link download route (line 188), OAuth routes mounted conditionally (line 200-208), and the MCP Streamable HTTP transport at `/mcp` via `installMcpStatelessTransport` (line 213) → `mcp/McpStatelessTransport.kt`.
- Base plugins (content negotiation, CORS, combined bearer/OAuth auth): `mcp/McpApplicationPlugins.kt` (`installMcpBasePlugins`, invoked at `McpServer.kt:163`), `mcp/Cors.kt`, `mcp/auth/BearerTokenAuth.kt`.
- Foreground-service host and lifecycle: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt` (717 lines). `onCreate()` (line 205), `onStartCommand()` (line 211, calls `startForeground()` at line 216 — within the 5s budget `CLAUDE.md` mandates), `startServer()` builds the SDK `Server`, registers all 57 tools via `registerAllTools()` (called at line 310, defined at line 442; the 12 `register*Tools` calls span lines 457-470ish across `mcp/tools/*.kt`), then starts the tunnel *after* the HTTP server (`tunnelManager.start(config.port)`, line 365). `onDestroy()` (line 554) stops the tunnel before the server (matching `ARCHITECTURE.md`'s documented shutdown order).
- Boot/restart plumbing: `services/mcp/BootCompletedReceiver.kt`, `services/mcp/PackageReplacedReceiver.kt`, `services/mcp/McpServerRestart.kt`, `services/mcp/AdbConfigReceiver.kt` + `AdbConfigHandler.kt` + `AdbServiceTrampolineActivity.kt` (headless ADB-shell start/stop/config, `DUMP`-permission-gated, `android:exported="true"` — the only two exported components in the manifest, both unreachable by ordinary apps).

**OAuth 2.1 server and token storage**
- All 20 files under `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/oauth/` (1,586 lines total): `OAuthPolicy.kt` (redirect allowlist/TTLs), `Pkce.kt` (S256), `JwtTokenServiceImpl.kt` (HS256 issue/verify), `OAuthClientRepositoryImpl.kt` (persisted client registry), `AuthorizationCodeStoreImpl.kt` (in-memory 60s single-use codes), `OAuthApprovalCoordinatorImpl.kt` (number-match pending approvals), `OAuthMetadata.kt` (RFC 9728/8414 discovery docs), `OAuthRoutes.kt` (the 8 HTTP endpoints — `.well-known/oauth-protected-resource[/…]`, `.well-known/oauth-authorization-server[/…]`, `.well-known/openid-configuration`, `POST /register`, `GET /authorize`, `GET /authorize/status`, `POST /token`; `OAuthRoutes.kt:39-48`), `OAuthAccessValidator.kt` (the `/mcp`-facing token check), `LogoUrlPolicy.kt` (SSRF guard for client-logo images).
- **Token storage:** the HS256 signing secret is a DataStore string key `jwt_signing_secret` in the *main* settings DataStore (`data/repository/SettingsRepositoryImpl.kt:47`); the OAuth client registry (client_id, redirect URIs, logo, last-used) lives in a **dedicated** Preferences DataStore keyed `oauth_clients` (`data/repository/OAuthClientRepositoryImpl.kt:33,173`, injected via the `@OAuthClientsDataStore` qualifier); authorization codes are in-memory only, never persisted (`AuthorizationCodeStoreImpl.kt`). Access/refresh JWTs themselves are not stored server-side at all (stateless, verified by signature/`aud`/registry membership on each request).
- UI surface tied to OAuth: `ui/ApprovalActivity.kt`, `ui/screens/ApprovalScreen.kt`, `ui/viewmodels/ApprovalViewModel.kt` (the on-device number-match consent flow), `ui/screens/settings/OAuthClientsScreen.kt` + `ui/viewmodels/OAuthClientsViewModel.kt` (client registry management), `services/mcp/OAuthApprovalNotifier.kt` (heads-up notification for pending approvals).

**Tunnel code**
- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/` (6 files, 727 lines): `TunnelProvider.kt` (interface), `TunnelManager.kt` (orchestrator, reads `ServerConfig.tunnelEnabled`/provider choice), `CloudflareTunnelProvider.kt` (395 lines — process-based, spawns bundled `libcloudflared.so` via `AndroidCloudflareBinaryResolver.kt`/`CloudflaredBinaryResolver.kt`), `NgrokTunnelProvider.kt` (133 lines — in-process JNI via `vendor/ngrok-java`).
- Native/vendor wiring: `.gitmodules:1-6`, `vendor/cloudflared/` and `vendor/ngrok-java/` (both submodules, uninitialized in this checkout), `app/build.gradle.kts:396-398,459` (direct jar-file dependencies on `ngrok-java`'s Maven-built artifacts), `app/build.gradle.kts:299-301` (`jniLibs { useLegacyPackaging = true }` — present specifically because of ngrok's native `.so`).
- UI: `ui/screens/settings/TunnelSettingsScreen.kt`, tunnel status display in `ui/components/ConnectionInfoCard.kt`.

**Accessibility service and action executor**
- Service: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt` (486 lines). Singleton pattern via companion `instance` (declared line 419, set in `onServiceConnected()` line 63, cleared in `onDestroy()` line 140); `isReady()` at line 206.
- Executor: `services/accessibility/ActionExecutor.kt` (154-line interface — node actions, coordinate gestures, global actions, pinch/custom multi-path gestures) and `services/accessibility/ActionExecutorImpl.kt` (985 lines, the implementation).
- Supporting: `services/accessibility/ElementFinder.kt` (210 lines, selector matching), `services/accessibility/AccessibilityNodeCache.kt`/`AccessibilityNodeCacheImpl.kt` (152 lines combined), `services/accessibility/CacheInvalidationDebouncer.kt`, `services/accessibility/TypeInputController.kt`/`TypeInputControllerImpl.kt` (192 lines combined, IME-based typing), `services/accessibility/AccessibilityTreeLock.kt` (26 lines, serializes concurrent tree access — the concrete mechanism behind `CLAUDE.md`'s "Mutex or synchronized for critical sections" rule).
- Config: `app/src/main/res/xml/accessibility_service_config.xml`.
- Confirmed zero coupling to the transport layer: no file under `services/accessibility/` imports `io.ktor.*`, `mcp.oauth.*`, or `services.tunnel.*` (verified by repo-wide grep). The tool-handler files in `mcp/tools/` are likewise clean of direct Ktor/OAuth/tunnel imports — they only reach the accessibility layer through `McpAccessibilityService.instance` / the injected `ActionExecutor` interface.

**UI-tree encoding and compaction**
- Parsing: `services/accessibility/AccessibilityTreeParser.kt` (308 lines) — walks `AccessibilityNodeInfo` into a serializable `AccessibilityNodeData` tree, with a `nodeMap`-based mode that retains live node references for later action dispatch without re-walking.
- Compaction/encoding: `services/accessibility/CompactTreeFormatter.kt` (491 lines) — the "compact tree representation" D-04 cites as a fork rationale. Produces a flat TSV (not nested JSON) with a fixed 7-line preamble, filters out purely structural nodes (kept only if they have text/description/resource-id or are clickable/long-clickable/scrollable/editable — doc comment at lines 6-33), then appends a separate indented `hierarchy:` section.
- WebView-specific reduction: `services/accessibility/WebViewNodeMerger.kt` (260 lines) — merges/collapses WebView subtrees, covered by `e2e-tests`' `E2EWebViewNodeReductionTest.kt`/`E2EWebViewRefreshTest.kt`.
- Multi-window support (all windows, not just foreground) is documented in `docs/ARCHITECTURE.md:283-337`.
- Change-detection for `wait_until`-style polling: `mcp/tools/TreeFingerprint.kt`.

**`accessibilityDataSensitive` handling**
- There is **no bespoke code** for this — it is a one-line manifest declaration: `android:isAccessibilityTool="true"` in `app/src/main/res/xml/accessibility_service_config.xml:10`. Setting that flag is what makes Android 14+ deliver `accessibilityDataSensitive`-marked subtrees (e.g. the GitHub app's UI) to this service at all; documented explicitly, including the security trade-off, in `docs/PROJECT.md:394-397`. Nothing else in the codebase references "sensitive"/"data-sensitive" accessibility handling (repo-wide grep confirms this).

**gms/foss flavour split**
- Declared in `app/build.gradle.kts:230-234` (`flavorDimensions += "distribution"`, flavours `gms`/`foss`). Release `applicationId` is identical across flavours; debug builds get a per-flavour suffix (`app/build.gradle.kts:476-483`).
- What actually differs: **only location and geofencing**, nothing else. `src/gms/` (16 files) provides Play-Services-backed `LocationProviderImpl.kt`, a full geofencing stack (`services/channel/geofence/GeofenceManagerImpl.kt`, `GeofenceTransitionReceiver.kt`, map/list UI screens, `GmsBatteryOptimizationManagerImpl.kt` using Play Services' battery-exemption check) and a `gmsImplementation(libs.play.services.location)` dependency (`app/build.gradle.kts:351`). `src/foss/` (9 files) provides the equivalents without Play Services: framework `LocationManager`-based `FossLocationProviderImpl.kt`, a `NoOpGeofenceChannelController.kt` (geofencing is simply absent on `foss`), `FossBatteryOptimizationManagerImpl.kt`.
- The on-device server, OAuth, and tunnels all live in `src/main/` and are **identical on both flavours** — the flavour split has no bearing on D-19 at all, and D-19's removal work does not need to touch `src/gms/` or `src/foss/`.

**What `e2e-tests` and `privacy-benchmark` exercise**
- `e2e-tests`: the full stack, for real, inside a redroid container — MCP-over-HTTP tool calls against the live accessibility tree of a running Android system and the fixture apps (calculator, `compose-test-app`), screenshot capture, all storage/file operations including partial MediaStore access, camera capture, error paths, WebView tree behaviour, and — the one that matters most for D-19 — the entire OAuth dynamic-client-registration-through-authenticated-call flow (`OAuthFlowE2ETest.kt`). It is explicitly the outermost, slowest layer in the test pyramid (`docs/PROJECT.md` §Testing Strategy, D-17), run nightly/on `main`, not blocking the inner loop.
- `privacy-benchmark`: purely the `:privacy` module's detection accuracy — no Android, no accessibility service, no network. It scores the deterministic detectors + NER model against three corpora (a fixed adversarial JSONL, a downloaded `ai4privacy` set, and a generated UI-synthetic corpus meant to resemble real screen text) and writes a recall/precision `report.md` that `PrivacySettingsScreen.kt`'s published `MEASURED_DETECTION_RATES` must be kept in sync with per `CLAUDE.md`. It has nothing to do with networking, transport, or D-19.

---

## 3. What D-19 leaves alone (the "stays" list, for orientation before the removal plan)

Explicitly confirmed independent of the server/OAuth/tunnel layer, by import-graph inspection, not assumption:
- `services/accessibility/*` (tree parser, compact formatter, WebView merger, element finder, node cache, executor, the service itself) — the reason the fork exists (D-04). Zero imports from Ktor/OAuth/tunnel packages.
- `mcp/tools/*` tool-handler *logic* (as opposed to their registration onto the SDK `Server`) — they call into the accessibility layer via the same clean interfaces, not through Ktor.
- `:privacy` and `:privacy-benchmark` in full.
- `compose-test-app` in full.
- The `gms`/`foss` flavour split in full (location/geofencing only).
- `scripts/` in full.

---

## 4. D-19 removal and rearchitecture plan

D-19 is not a subtraction exercise. It is: (a) delete the on-device listening server, its auth server, and its tunnels; (b) build, from nothing, a persistent outbound WebSocket client with reconnect/backoff, a flow cache, a local executor that runs whole flows and journals steps, a heartbeat, and an FCM wake handler — none of which exist upstream today. Item (b) is larger than item (a).

### 4.1 Remove: on-device HTTP/MCP server
- **Remove:** `mcp/McpServer.kt`, `mcp/McpStatelessTransport.kt`, `mcp/McpApplicationPlugins.kt`, `mcp/Cors.kt`, `mcp/ContentTypeUtil.kt`, `mcp/auth/BearerTokenAuth.kt`, `mcp/CertificateManager.kt` (self-signed HTTPS cert generation — no longer needed once there's no listening socket to secure), `mcp/RequestBaseUrl.kt` (only meaningful for a server that has a "base URL" clients hit).
- **Entangled with:** `McpServerService.kt`'s entire `startServer()`/`onDestroy()` lifecycle is written around starting/stopping this Ktor instance; that lifecycle itself is what gets replaced (§4.4), not preserved and rewired.
- **Breaks:** every settings screen that assumes an HTTP server exists — `ui/screens/ServerScreen.kt`, `ui/screens/ServerTabScreen.kt`, `ui/components/ServerStatusCard.kt`, `ui/components/ConnectionInfoCard.kt`, `ui/screens/settings/SecuritySettingsScreen.kt` (bearer token management), binding-address settings (`data/model/BindingAddress.kt`, `ui/screens/settings/AccessSettingsScreen.kt`) — all need rewriting for "connect to a hosted account" instead of "configure a listening address."
- **Also breaks:** `services/sharing/EphemeralFileLinkService(Impl).kt` and its `/s/{token}` route (`McpServer.kt:188`) — `mcp/tools/SharingTools.kt`'s `get_shared_content` currently hands MCP clients a device-hosted download URL for full-resolution shared images/files. With no listening socket, that capability has nowhere to live unless re-hosted through the relay/object storage (design doc's snapshot/report path handles screenshots, not arbitrary shared files — this is a gap, see §5(c)).
- **Effort:** 2-3 days to delete cleanly and unwind the Gradle dependencies (Ktor server/CORS/TLS-certificates artifacts, the Netty CVE-pin `constraints{}` block at `app/build.gradle.kts:373-388`, Bouncy Castle if nothing else needs it — check `services/storage/SslUtils.kt` first, it may have an independent reason to keep BC).

### 4.2 Remove: OAuth 2.1 server
- **Remove:** all 20 files in `mcp/oauth/`, `ui/ApprovalActivity.kt`, `ui/screens/ApprovalScreen.kt`, `ui/viewmodels/ApprovalViewModel.kt`, `ui/screens/settings/OAuthClientsScreen.kt`, `ui/viewmodels/OAuthClientsViewModel.kt`, `services/mcp/OAuthApprovalNotifier.kt`, the `jwt_signing_secret` DataStore key and the dedicated `oauth_clients` DataStore (`di/AppModule.kt`'s `@OAuthClientsDataStore` binding).
- **Entangled with:** `java-jwt` and the Jackson BOM pin (`app/build.gradle.kts:404-408`) exist only for this; removable together. `coil`/`coil-network` (`app/build.gradle.kts:411-412`) are used for OAuth client-logo rendering (`LogoUrlPolicy.kt`) — check nothing else uses Coil before dropping it.
- **Breaks:** nothing outside itself — OAuth is genuinely self-contained upstream (it's how Claude.ai's *remote* connector flow authenticates against the on-device server). Per the design doc's own architecture (§5.2, trust boundary 1), tenant auth moves server-side (SEC-01) as OAuth 2.1 *between the user's AI tooling and the hosted front door*, not the device — so this is a clean delete, not a port.
- **Effort:** 1-2 days delete + Gradle cleanup. Low risk.

### 4.3 Remove: tunnels
- **Remove:** `services/tunnel/` (6 files), `.gitmodules` entries for `vendor/cloudflared` and `vendor/ngrok-java`, the `vendor/` submodule directories, `app/build.gradle.kts:394-398,459` (ngrok jar file-references), `ui/screens/settings/TunnelSettingsScreen.kt`, tunnel-status branches in `ui/components/ConnectionInfoCard.kt`, `data/model/TunnelProviderType.kt`/`TunnelStatus.kt`/`CloudflareTunnelMode.kt`.
- **Entangled with:** `app/build.gradle.kts:299-301` (`jniLibs.useLegacyPackaging`) exists specifically for ngrok's native `.so` packaging — confirm nothing else needs legacy packaging before removing it (unlikely, but check).
- **Breaks:** nothing else; `McpServerService.startServer()` calls `tunnelManager.start()`/`.stop()` as isolated, already-optional steps ("Tunnel failure does NOT prevent the MCP server from running locally" — `docs/PROJECT.md:649`), so this is the single cleanest removal of the three.
- **Effort:** 1-2 days, including submodule/`.gitmodules` surgery (do this atomically with the jar-reference removal in `build.gradle.kts` — a partial removal breaks the build immediately, not gracefully).

### 4.4 Build: outbound WebSocket transport, executor, flow cache, heartbeat (net-new)
- **Nothing to remove here — this is new construction.** Confirmed: no Firebase/FCM dependency, no `google-services.json`, and no WebSocket client library anywhere in the current tree (repo-wide search). `McpServerService.kt`'s foreground-service *shape* (start within 5s, persistent notification, `START_STICKY`, `onTaskRemoved` restart logic at line 242-245, boot/package-replaced restart receivers) is reusable scaffolding, but its *content* — starting a Ktor server — is entirely replaced by: opening and holding a WSS connection, reconnect-with-backoff, sending `hello`/`heartbeat` per the design doc's wire protocol (§7.4), receiving `run` commands, running the design doc's D-09 step vocabulary against the *existing, untouched* `ActionExecutor`/`AccessibilityTreeParser`/`CompactTreeFormatter`, journaling steps (D-11's exactly-once requirement), and sending one `report`.
- **Decision required before estimating precisely:** which of the current 57 MCP tools become D-09 op equivalents. The touch/node/gesture/screen-introspection/text-input/system-action categories (~24-30 tools) map fairly directly onto `tap`/`long_press`/`swipe`/`type_text`/`key`/`wait_until`/`read`/`assert`. Camera, file, notification, location, sharing, and app-management tools (~19-27 tools) are **not** in the closed D-09 vocabulary at all (§7.2) and the design doc never says whether they're dropped, deferred, or folded into `open_intent`/`call_flow` primitives. This is a product decision, not an engineering one — flagged again in §5.
- **Effort:** this is the long pole. Transport + reconnect/backoff + journaling + exactly-once (D-11) + heartbeat + FCM wake integration + flow cache/sync (D-19's `sync`/`flow-cache` modules, §6.1) realistically runs **1.5-3 weeks** on its own, even with the executor and tree code untouched, before touching the tool-to-op mapping decision above.

### 4.5 Risk to the executor and tree encoding — the thing that must not break
- **Assessed risk: low, if the removal is scoped as above.** `services/accessibility/*` has no import-level dependency on anything being removed (§3), so a mechanical deletion of `mcp/oauth/`, `services/tunnel/`, and the Ktor-hosting parts of `mcp/`/`services/mcp/McpServerService.kt` cannot, by construction, touch `AccessibilityTreeParser.kt`, `CompactTreeFormatter.kt`, `WebViewNodeMerger.kt`, `ActionExecutorImpl.kt`, or `ElementFinder.kt`.
- **Where the risk actually lives:** not in accidental deletion, but in the *tool-handler → op* remapping in §4.4. The 24-30 tool handlers that *do* call into the executor (`mcp/tools/TouchActionTools.kt`, `NodeActionTools.kt`, `GestureTools.kt`, `ScreenIntrospectionTools.kt`, `TextInputTools.kt`, `SystemActionTools.kt`) currently format results as MCP `CallToolResult`/`TextContent` (SDK types). Rewriting these to instead produce/consume the design doc's flow-step JSON (§7.1/§7.4) is exactly the kind of mechanical-looking transformation where a rushed pass could subtly change selector-resolution order, timeout semantics, or tree-filtering behaviour. Whoever does this work should diff the *behavior* (existing JVM unit tests in `app/src/test/kotlin/.../services/accessibility/` and the recorded-tree fixtures under `e2e-tests`/emulator tests), not just the call signatures.

---

## 5. Summary

**(a) Total estimate vs. D-04's two-week revisit trigger.** D-04 says: "if unpicking the server layer costs more than two weeks, reconsider a fresh Kotlin build against the same tool surface." Unpicking alone (§4.1-4.3: delete Ktor server, OAuth server, tunnels, and the Gradle/submodule surface under them) is **4-7 days** — comfortably under two weeks, and low-risk given the confirmed lack of coupling to the accessibility/executor code. But D-19 is not just unpicking — it's unpicking *plus* building the outbound-transport/executor/flow-cache/heartbeat layer that doesn't exist yet (§4.4), which is **1.5-3 weeks** by itself, before the tool-to-op product decision is even made. Read literally, D-04's trigger ("unpicking... costs more than two weeks") is not tripped — the removal is fast. Read as "cost to get from here to a working D-19 device," the honest total is **3-5 weeks**, because most of the cost was never in removal, it's in building the thing that replaces it. This distinction is worth putting in front of whoever owns D-04/B-15 explicitly: the two-week trigger as worded measures the wrong half of the work.

**(b) Five things I am least certain about:**
1. Which of the ~19-27 non-D-09-vocabulary MCP tools (camera, file, notification, location, sharing, app-management) are meant to survive at all under the new architecture — the design doc's closed step vocabulary (§7.2) has no slot for most of them, and nothing in §6/§7 says whether they're v0-dropped, `LATER`, or folded into `open_intent`. This blocks a precise effort estimate for §4.4.
2. Whether `EphemeralFileLinkService`'s "hand back a full-resolution file via a device-hosted link" capability is meant to be preserved in some other form (relay-hosted?) or is simply an acceptable loss under D-19 — the design doc's snapshot/report model doesn't obviously cover arbitrary shared-file retrieval.
3. What happens to the Event Channel subsystem (`services/channel/*`, `channel-plugin/`) — it's unmentioned in the design doc, functionally pre-empts part of the `LATER` inbox loop, and requires a computer, which contradicts the product's core pitch. I could not find any design-doc text that resolves this either way.
4. Whether the executor/tool-handler split can really be re-plumbed onto flow-step JSON without behavioral drift (§4.5) purely from reading the code — this needs someone to actually attempt the first tool (e.g. `tap`) end to end and check it against the existing JVM tests, not just an estimate from a read-only pass.
5. Real effort for `e2e-tests`' rearchitecture (§4/e2e-tests entry) — turning "test dials into a listening container" into "test acts as a fake relay the container dials out to" is a first-of-its-kind harness for this repo, and I have no comparable prior art in the codebase to size it against. The design doc's own `tools/fake-device` (§6.6, to live in `droidthumb-android`, not yet present in this repo) is presumably meant to become part of that harness, but it doesn't exist yet either.

**(c) Contradictions with the design doc found in the repo (reported, not corrected in the doc):**
1. **D-05 says `minSdk 29`; the repo's `app/build.gradle.kts:221` sets `minSdk = 33`.** These are different Android versions (Android 10 vs. Android 13) with materially different accessibility/background-execution/permission behavior. Either D-05 is stale or the fork target changed after D-05 was written.
2. **D-04's "54-tool surface" is stale against the pinned fork point.** `docs/PROJECT.md:202` states 57 tools across 14 categories at the `upstream-baseline` tag; 54 appears to be the count as of the Appendix A snapshot (v1.10.0, 31 Jul), three tools before whatever `upstream-baseline` (26 Aug) actually pins. Not a substantive contradiction, just drift worth knowing about before citing the number again.
3. **The Event Channel subsystem (`services/channel/*` + `channel-plugin`) has no mention anywhere in the design doc**, despite functionally overlapping both the "no computer required" positioning (B-03/B-13) and the `LATER` inbox-trigger loop (§8.4). This isn't a doc-vs-code factual contradiction so much as a genuine gap — the design doc's module inventory (§6.1) doesn't account for a real, sizeable subsystem (event dispatcher, listeners, geofencing on `gms`, a whole separate Bun/TS plugin) that D-19 will have to make an explicit call about.
4. **`docs/PROJECT.md`'s own "Folder Structure" section is stale against its own repo** (see §1, `docs` entry) — not a design-doc contradiction, but worth flagging since the design doc leans on this repo's docs being trustworthy scaffolding for the fork work, and at least one of them already isn't.
