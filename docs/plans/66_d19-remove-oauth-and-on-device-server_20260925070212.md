<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 66 — D-19: remove the on-device OAuth server now; retire the Ktor `/mcp` listener together with the outbound transport

**Scope.** `docs/module-map.md` §4.1 (on-device HTTP/MCP server) and §4.2 (OAuth 2.1 server). Numbering note: the
design doc's own §4.1/§4.2 are *Personas* and *Core journeys*; the removal work is module-map §4.1/§4.2, read
against design doc §5 (D-19, trust boundaries), §6.6 (fake device), §7.2 (D-23), §7.4 (wire protocol), §10 (SEC-19)
and §11.4 (build order).

**Status: SUPERSEDED by `docs/plans/demolition.md` (2026-09-25).** The owner chose D1 = remove everything in one pass (no staging, no interim listener), which makes D4–D7 moot; D2, D3, D8 accepted; D9 wording is proposed in the demolition plan. Kept unchanged below as research: the inventory, §1 facts and the review findings are reused by the demolition plan. Original status line: **NOT APPROVED FOR IMPLEMENTATION.** §0 lists decisions only the owner can make. Stage A (US-1 … US-7) is
executable once they are answered. Stage B (US-8) and Stage C (US-9) are gated on work that does not exist yet and are
scoped inventories, not executable tasks.

---

## 0. Decisions required before any implementation

Each has a recommendation; none is decided here.

| # | Decision | Recommendation | Why |
|---|---|---|---|
| **D1** | Land all of §4.1+§4.2 now, or stage it? | **Stage it.** **Stage A (now):** delete OAuth and everything that exists only for OAuth, public exposure or tunnels (HTTPS/self-signed certs, CORS, forwarded-header trust, public-URL override, capability links + the two sharing tools). Keep a plain-HTTP, bearer-only `/mcp` listener as the dev/test channel. **Stage B:** delete that listener in the same PR that switches the device to the outbound transport and e2e to the fake relay. | (1) Design doc §11.4 step 4 bundles "remove server/OAuth/tunnels" with "add outbound transport": removal was never meant to land alone. (2) e2e is the only real-Android regression net for the executor and tree code; the §4.4 tool→op re-plumbing is where module-map §4.5 and D-04 put the drift risk. Removing the net just before that work is the worst timing. (3) SEC-19 is a **v0-release** control, and v0 can't ship without the transport, **provided no public build ships the interim listener (D8)**. |
| **D2** | Real-Android coverage of the 75 e2e tests (after Stage A) that exercise LATER/DROP tools or the listener itself (§1.3) once `/mcp` goes in Stage B | **Accept the loss** and delete them in Stage B, keeping JVM integration coverage of the tool handlers (mocked Android). | Under D-23 those tools have no v0 flow op, so no protocol-based harness can reach them. The only way to keep them on real Android is a debug-only non-listening invocation path (e.g. a DUMP-gated broadcast that runs a tool and writes the result to a file). That's throwaway work in tension with SEC-19 ("no exported components that reconfigure it") and D-23 ("not addressable"). Revisit when a LATER tool is promoted. |
| **D3** | The two sharing tools die with the `/s/{token}` route (both DROP under D-23). Also remove the share target (`ShareReceiverActivity`, exported, `SEND`/`SEND_MULTIPLE`) and `SharedContentInbox`? | **Yes, remove them in Stage A.** | With both tools gone nothing drains the inbox. Sharing to the app would silently fill a queue nobody reads, through an exported component we no longer need (SEC-19/R-08 review surface). D-21 keeps the *protocol* slot; device code can be rebuilt if the capability returns. |
| **D4** | DNS-rebinding protection on the interim `/mcp` (currently off because "requests arrive via a tunnel") | **Enable it with the SDK defaults.** | The assumption stopped being true with tunnels gone. **Scope, stated plainly:** this defends a *browser on the user's machine* from being rebound onto the device's loopback port. It is **not** a LAN control: `Host` is set by the client, so a LAN attacker simply sends `Host: localhost`. LAN exposure is D7's question. SDK behaviour (verified from the 0.15.0 jar): `extractHostname()` strips the port and lowercases; default allowed hosts `localhost`, `127.0.0.1`, `[::1]`; `Origin`, when present, checked against localhost origins. adb-forward and the e2e harness (`Host: localhost:<mapped port>`) pass. |
| **D5** | Keep a bearer on/off toggle? ("Open mode" was the both-off case of bearer+OAuth dual-accept.) | **Remove the toggle: the bearer token is always required.** An empty token is regenerated rather than failing closed forever. | With `0.0.0.0` binding, bearer-off leaves the tool surface (screen reading, typing) open to the LAN. Its only legitimate use (browser/OAuth experiments) goes away with OAuth and CORS. |
| **D6** | The adb-shell config surface (`AdbConfigReceiver`, `AdbConfigHandler`, `AdbServiceTrampolineActivity`: exported, DUMP-gated) | **Stage A:** prune the extras for deleted settings, inside the story that deletes each setting. **Stage B:** delete, unless the transport design wants it for setting the relay URL. | It reconfigures a server that Stage B removes. |
| **D7** | After US-4 removes HTTPS, the bearer token travels in **cleartext HTTP** whenever NETWORK (`0.0.0.0`) binding is chosen. The e2e harness needs `0.0.0.0` (container port mapping lands on the container's `eth0`, not loopback). | **Restrict NETWORK binding to debug builds.** Release builds force `LOCALHOST` and hide the option; debug builds keep it for e2e/redroid. | Keeps the e2e path working and ensures no release build ever sends the bearer in cleartext over a LAN. The only cost is that a release user can't drive the device over Wi-Fi without `adb forward`, and after Stage B nobody can drive it that way anyway. Alternative: accept cleartext-on-LAN for the interim and record it. |
| **D8** | `.github/workflows/edge-release.yml` publishes a public `edge` prerelease APK on **every push to `main`**, and the repo is **public**. Merging Stage A would ship the first downloadable build under `uk.co.drhconsulting.droidthumb`, interim listener included. | **Disable the `edge-release` workflow (manual `workflow_dispatch` only) until Stage B has landed**, and cut no `v*` release before then. | This is what makes D1's reason (3) true. Without it, the first public DroidThumb build would contain a listening server, against SEC-19's intent, and give the interim listener real users. |
| **D9** | `CLAUDE.md` and `.claude/agents/plan-reviewer.md` describe HTTPS/certificate settings, the HTTPS `Switch`, and "empty `expectedToken` skips authentication", all of which contradict this plan once it lands. | **Owner edits (or approves edits to) these files** in US-6. | `CLAUDE.md` is the owner's instruction file; agents must not change it unilaterally. |

---

## 1. Verified facts this plan rests on (checked 2026-09-25 against code and jars, not docs)

### 1.1 Repos and build order
- `droidthumb-protocol` is an initial commit with a README; `droidthumb-server` contains only `docs/`; `droidthumb-android/tools/` does not exist. Design doc §11.4 steps 1–3 (protocol, fake-device + relay, front door) come **before** step 4 (this fork's removal + transport). None has started.
- **§6.6 `tools/fake-device` is a fake *device*** (TypeScript: connects, heartbeats, accepts `run`/`step`, returns canned trees), the target for **server** tests (§11.3 layer 2). It cannot drive the real APK, so it cannot replace this repo's e2e harness. The APK needs the mirror image, a **fake relay** it dials out to. See US-9.

### 1.2 Dependency facts
- `io.modelcontextprotocol:kotlin-sdk-server:0.15.0` brings in `ktor-server-core`, `-sse`, `-websockets` and `-content-negotiation` at runtime, but **no engine**. `ktor-server-netty` (app `build.gradle.kts`) is the only engine; removing it removes the ability to listen even while the SDK stays for the tool handlers.
- Only `mcp/CertificateManager.kt` uses Bouncy Castle; only `mcp/oauth/JwtTokenServiceImpl.kt` uses `com.auth0`; only `ui/screens/ApprovalScreen.kt` and `ui/screens/settings/OAuthClientsScreen.kt` use Coil. `HttpsMaterial` is declared in `mcp/McpServer.kt:30-34`.
- `geo/` is used only by the OAuth consent path and the service's warm-up call, so `generateLocationDb`, `scripts/location-db/`, `.dbip-cache`, the DB-IP CI steps and the About-screen DB-IP attribution are **OAuth-only**. This contradicts module-map §1 (`scripts`: "D-19 touches it: No").
- `McpToolException` is **not** server code (27 users across accessibility, storage, camera and privacy). It stays.
- `ContentTypeUtil.contentTypeOrOctetStream` is used only by the `/s/{token}` route.
- `RequestBaseUrl.kt` users: OAuth, `McpAuthPlugin` (`canonicalResource`, `baseUrlOf`), `McpStatelessTransport`, `SharingTools`.
- `fileSizeLimitMb` is also used by the file tools (`FileOperationProviderImpl`, `MediaStoreFileOperationsImpl`, `MediaStoreDownloader`); it stays.
- `ServerLogEntry.Type.fromId` returns `null` for an unknown id and `ServerLogSegmentedStore.kt:239` skips it (`?: continue`), so removing `Type.OAUTH` is safe for persisted logs.

### 1.3 The e2e suite (92 tests; 78 pass / 14 skip, 2026-09-25) against D-23

| Class | Tests | Tools / surface | Portable to a v0-protocol harness? |
|---|---|---|---|
| `E2EStorageToolsTest` | 31 | file tools (LATER), `download_from_url` (DROP) | No |
| `E2EStorageEdgeCasesTest` | 20 | file tools (LATER) | No |
| `E2EStoragePartialAccessTest` | 8 | file tools (LATER) | No |
| `E2ECameraTest` | 16 (14 skip) | camera listing (LATER), `take_camera_photo` (NEW-OP blocked on D-21) | No (not in v0) |
| `OAuthFlowE2ETest` | 1 | OAuth | No (deleted in US-2) |
| `E2EErrorHandlingTest` | 6 | 3× `/mcp` bearer HTTP checks (`:58`, `:80`, `:102`), 1× unknown MCP tool (`:121`), 2× tool errors | 2 yes; 4 listener/MCP-specific |
| `E2ECalculatorTest` | 3 | MAPS / NEW-OP ops | Yes |
| `E2EComposeRefreshTest` | 2 | MAPS | Yes |
| `E2EScreenshotTest` | 1 | MAPS | Yes |
| `E2EWebViewNodeReductionTest` | 1 | MAPS | Yes |
| `E2EWebViewRefreshTest` | 3 | MAPS | Yes |

**About 12 tests are portable.** After Stage A, **91** remain (77 pass / 14 skip); in Stage B, **79** of those have no path through the v0 protocol (59 storage + 16 camera + 4 listener/MCP-specific).

### 1.4 The four security leftovers from tunnel removal

| File | Assumption it encodes | Still true? | Disposition |
|---|---|---|---|
| `mcp/McpStatelessTransport.kt`: `enableDnsRebindingProtection = false` | "Requests arrive via a tunnel, so `Host` is the tunnel hostname" | **No** | US-5: **enable** with SDK defaults (D4). Stage B: file deleted. |
| `mcp/Cors.kt`: wildcard origin | "Browser MCP clients must do OAuth discovery/DCR and `/mcp` cross-origin" | **No**: no OAuth; no browser client is a DroidThumb use case | US-5: **delete** (and `ktor-server-cors`). Browsers then block cross-origin `/mcp`, which is strictly safer. |
| `mcp/oauth/OAuthRouteSupport.kt` `clientIp()`: trusts `CF-Connecting-IP`/`X-Forwarded-For` | "A tunnel's forwarded header is the real client IP" | **No** | US-2: deleted with OAuth. US-3: `McpAuthPlugin`'s failure log stops reading `X-Forwarded-For`. |
| `mcp/RequestBaseUrl.kt`: trusts `X-Forwarded-Host/Proto`, tunnel-aware base URL, `publicUrlOverride` | "Clients reach the device on a public URL that differs from the socket" | **No** | US-5: **delete**, with the `public_url_override` setting. |

**Carried forward on the device: nothing.** The same class of question comes back **server-side**: the front door (§6.4) sits behind Caddy, where trusting `X-Forwarded-*` from that one proxy is correct and CORS for browser MCP clients is a real decision. Record it in `droidthumb-server` as an input; out of scope here.

### 1.5 Installed base and publication
- `uk.co.drhconsulting.droidthumb*` has never been released: `gh release list` for `DroidThumb/droidthumb-android` is empty, and every tag (up to `v1.12.0`) is upstream's, built under the old applicationId. Removed DataStore keys, the `oauth_clients` DataStore and any keystore file therefore need **no migration or cleanup code**. A developer device can be reinstalled; the redroid `redroid-data` volume can be reset per `docs/debug-device.md`.
- **But `main` is published** (repo public; `edge-release.yml` on every push). See D8.

---

## 2. What the app can do: now, after Stage A, after Stage B (before the transport exists)

| Capability | Now | After Stage A | After Stage B, before transport |
|---|---|---|---|
| Driven by an MCP client (Claude Desktop/Code, `mcp-remote`) | Yes: HTTP(S), bearer or OAuth, loopback or LAN | **Less:** plain HTTP, bearer only. Release: loopback via `adb forward` only (D7). Debug: loopback or LAN | **No. Nothing can drive the device.** |
| Claude.ai remote custom connector | Needs a public URL; tunnels already gone, so in practice **already no** | No | No |
| Tool surface | 57 tools | 55 (sharing tools gone) | 55 handlers compiled and JVM-tested, **zero reachable** |
| e2e on real Android (redroid) | 92 (78 pass / 14 skip) | 91 (77 / 14) | **0** until Stage C; ~12 portable |
| Persistent debug device (`docs/debug-device.md`) | Full MCP via port 8080 | MCP via port 8080 + bearer (debug build, NETWORK binding) | UI only (install, open, settings) |
| Event Channel (outbound POST of notification/Wi-Fi events) | Yes | Yes (D-20, unchanged) | Yes: the only outbound network function left besides the update check |
| Accessibility service, executor, tree encoding | Running; used by tools | Same | Enabled but idle |
| Privacy mode | Applied to tool output | Same | Nothing to apply it to |
| Share-to-app | Yes | **No** (D3) | No |

**Stage A leaves the app somewhat less capable than now but usable for development. Stage B on its own would leave an app that installs, shows settings and does nothing else.** That is why D1 says Stage B never lands without the transport.

---

## 3. Order of work

1. **Stage A: US-1 → US-7 (this plan).** Est. **4–5 days.** Needs only the §0 answers.
2. **Outside this repo** (§11.4 steps 1–2): `droidthumb-protocol` v0 wire messages (at least `hello`, `step`, `report`, `run`/`ack`) with example documents; enough `server/relay` to validate them against `tools/fake-device`.
3. **Transport in this repo** (module-map §4.4): WSS client, reconnect/backoff, step execution for the MAPS ops. **1.5–3 weeks** (module-map's estimate, not re-derived). Separate plan.
4. **Stage C: US-9 fake-relay harness.** **3–5 days** after 2 and 3. Separate plan.
5. **Stage B: US-8**, in the **same PR** as the cut-over of 3 and 4. **2–3 days** of removal on top.

## 4. Where I expect to get stuck (cannot resolve alone)

- **S1: product decisions D1–D9.**
- **S2: Stage B's `McpServerService`** depends on the transport design, which doesn't exist. US-8 is an inventory until it does.
- **S3: Container → host reachability for Stage C.** The fake relay listens on the host and the APK in the Testcontainers redroid must dial it (`host.containers.internal` under rootful podman 5.7, or a published port). Unverified.
- **S4: The unknown-tool e2e case** needs `droidthumb-protocol` to define an error for an unknown `op`. That's a protocol-repo decision.
- **S5: `mmdc` is not installed on this host.** `CLAUDE.md` requires Mermaid validation for US-6's diagram edits; installing it needs the owner (node/npm).

~~Previously S1 (SDK host matching): resolved from the 0.15.0 jar, see D4.~~

---

## Stage A — executable after §0 is answered

Branch `feat/d19-remove-oauth-server` from latest `main`. **One commit per user story; each story is self-contained and leaves `main` compiling with its tests updated.** Stories run in the order below (it is dependency-driven: sharing first because it is the last `RequestBaseUrl` consumer besides OAuth/auth/transport; OAuth before bearer-only because the auth plugin's OAuth branch needs OAuth's config gone; CORS/base-URL last because the auth plugin's OPTIONS bypass and `baseUrlOf` depend on them). Per `CLAUDE.md`, lint/tests/build run **only** in US-7. Line numbers are as of `main` @ `109cc83` and are locators only — the named symbol is authoritative; re-locate by symbol if they drift.

## US-1 — Delete capability links, both sharing tools and the share target (D3)

The `/s/{token}` route dies with the server; both tools that use it are DROP under D-23.

**Acceptance criteria**
- [ ] No `/s/{token}` route; `android_get_shared_content` / `android_share_file_via_web` not registered (tool count 57 → 55).
- [ ] No exported `SEND`/`SEND_MULTIPLE` activity in the merged manifest.

### Task 1.1 — Sources
- delete `services/sharing/` (7 files), `mcp/tools/SharingTools.kt`, `mcp/ContentTypeUtil.kt`, `ui/ShareReceiverActivity.kt`.
- modify `mcp/McpServer.kt`: remove the `ephemeralFileLinkService` param, the `/s/{token}` route, and `EphemeralFileLinkService.PATH_PREFIX` from `excludedPathPrefixes`.
- modify `services/mcp/McpServerService.kt`: remove `ephemeralFileLinkService`, `sharedContentInbox`, `currentBaseUrl`, `registerSharingBundle` and its call.
- modify `di/AppModule.kt`: remove the `EphemeralFileLinkService` and `SharedContentInbox` bindings.
- modify `app/src/main/AndroidManifest.xml`: remove `.ui.ShareReceiverActivity`.
- modify `ui/screens/settings/McpToolsSettingsScreen.kt` (and `ToolPermissionsConfig` if it enumerates tools): remove the sharing entries.
- modify `res/values/strings.xml`: remove `share_target*` (verify each is unreferenced).
- **DoD:** [ ] no reference to any deleted class under `app/src`

### Task 1.2 — Tests
- delete `test/.../services/sharing/*Test.kt` (`EphemeralFileLinkServiceImplTest`, `SharedContentClassifierTest`, `SharedContentInboxImplTest`, `SharedStreamReaderTest`), `mcp/ContentTypeUtilTest.kt`, `integration/SharingIntegrationTest.kt`.
- modify `integration/McpIntegrationTestHelper.kt`: drop sharing registration/deps.
- modify `integration/McpProtocolIntegrationTest.kt`: `EXPECTED_TOOL_COUNT` (`:138`, 57 → 55); remove the two sharing names (`:211-212`).
- modify `integration/ToolPermissionsIntegrationTest.kt` (`:323-324`): remove the sharing cases.

| Test | Verifies |
|---|---|
| `McpProtocolIntegrationTest: tool list has 55 tools, no sharing tools` | registration |
| `ExportedComponentsManifestTest: no activity handles SEND/SEND_MULTIPLE` | D3 |

- **DoD:** [ ] tests updated

## US-2 — Delete the on-device OAuth 2.1 authorization server

Design doc D-19 / §5.2: tenant auth is server-side (SEC-01); nothing on the device consumes OAuth.

**Acceptance criteria**
- [ ] No reference under `app/src/**`, `e2e-tests/**` to `mcp.oauth`, `OAuthClientRepository`, `JwtTokenService`, `OAuthApprovalCoordinator`, `AuthorizationCodeStore`, `GeoIpResolver`, `ApprovalActivity`, `oauthEnabled`, `ServerLogEntry.Type.OAUTH`.
- [ ] `java-jwt`, Jackson BOM, Coil, `generateLocationDb`, and the DB-IP download are gone from build and CI.
- [ ] No OAuth UI, route, notification (channel included), log-type label or string remains.

### Task 2.1 — Delete sources
- delete `mcp/oauth/` (19 files), `data/repository/OAuthClientRepository.kt`, `OAuthClientRepositoryImpl.kt`, `ui/ApprovalActivity.kt`, `ui/screens/ApprovalScreen.kt`, `ui/viewmodels/ApprovalViewModel.kt`, `ui/screens/settings/OAuthClientsScreen.kt`, `ui/viewmodels/OAuthClientsViewModel.kt`, `services/mcp/OAuthApprovalNotifier.kt`, `geo/` (4 files).
- delete `app/src/debug/.../debug/OAuthApprovalTestReceiver.kt` and its `<receiver>` in `app/src/debug/AndroidManifest.xml`.
- **DoD:** [ ] files gone

### Task 2.2 — Unwire
- `di/AppModule.kt`: remove the `@OAuthClientsDataStore` qualifier, the `oauth_clients` `preferencesDataStore` extension and provider, and the bindings for `OAuthClientRepository`, `JwtTokenService`, `OAuthApprovalCoordinator`, `AuthorizationCodeStore`, `GeoIpResolver`.
- `mcp/McpServer.kt`: remove the `oauth: OAuthServerDeps` param, `accessValidator`, the `installOAuthRoutes` block, `oauthEnabled = …` and `validateOAuthToken = …` in `installMcpBasePlugins` (`:166`, `:168`), and `/register`, `/token`, `/authorize`, `/authorize/status` from `excludedPaths` and `/.well-known/` from `excludedPathPrefixes`. (`McpAuthConfig.oauthEnabled` defaults to `false`, so the plugin compiles unchanged until US-3.)
- `services/mcp/McpServerService.kt`: remove the injected OAuth deps and `geoIpResolver`, the `OAuthServerDeps(...)` argument, the geo warm-up launch, `approvalObserverJob` and its cancellation.
- `McpApplication.kt` (`:77-103`): remove creation of the OAuth-approval notification channel.
- `ui/viewmodels/MainViewModel.kt`: remove `approvalCoordinator`, `pendingApprovalCount`.
- `ui/screens/ServerScreen.kt`: remove the pending-approvals callout (`server_pending_approvals_*`) and the OAuth usage at `:112`.
- `ui/screens/settings/AccessSettingsScreen.kt`: remove the OAuth toggle and the dual-auth condition (`:105`, `:116-118`), the connected-clients entry and the `onNavigateClients` param (`:59`). `ui/viewmodels/AccessViewModel.kt`: remove `requestSetOauthEnabled` (`:55-65`).
- `ui/navigation/Routes.kt`, `ui/screens/SettingsScreen.kt` (nav graph, `:66`): remove `SettingsRoute.OAuthClients` and its destination.
- `ui/screens/LogsScreen.kt` (`:58`, `:222`), `ui/components/ServerLogsSection.kt` (`:129`): remove the OAUTH log-type filter/label.
- `data/model/ServerLogEntry.kt`: remove `OAUTH(TYPE_ID_OAUTH)` and replace the constant with a reservation comment:
  ```kotlin
  // Byte id 3 was OAUTH (removed with the on-device OAuth server, plan 66). Never reuse it: persisted
  // logs may still contain it, and Type.fromId maps it to null so those entries are skipped.
  ```
- `ui/screens/AboutScreen.kt` (`:62`, `:248-253`): remove the DB-IP attribution.
- `app/src/main/AndroidManifest.xml`: remove `.ui.ApprovalActivity`.
- `services/mcp/AdbConfigHandler.kt` (`:88-90`, `EXTRA_OAUTH_ENABLED`) and `app/src/debug/.../E2EConfigReceiver.kt` (`:151-154`): remove the `oauth_enabled` extra.
- **DoD:** [ ] AC grep clean for main/debug

### Task 2.3 — Settings
- `data/model/ServerConfig.kt`: remove `oauthEnabled`.
- `data/repository/SettingsRepository.kt` + `SettingsRepositoryImpl.kt`: remove `updateOauthEnabled` (incl. its `logToggle` call), `getOrCreateJwtSigningSecret`, and the `oauth_enabled` and `jwt_signing_secret` keys and mappings.
- **DoD:** [ ] no `oauth`/`jwt` keys remain

### Task 2.4 — Build, CI, resources
- `app/build.gradle.kts`: remove `platform(libs.jackson.bom)`, `libs.java.jwt`, `libs.coil.compose`, `libs.coil.network`; the `generateLocationDb` registration and its `addGeneratedSourceDirectory` hookup (≈`:451-471`); `abstract class GenerateLocationDbTask` (≈`:588-640`).
- `gradle/libs.versions.toml`: remove the `java-jwt`, `jackson` and `coil` versions and libraries.
- `.github/workflows/ci.yml` (two jobs: "Set up Python (geolocation DB generator)" ≈`:221-224`/`:377-380`, DB-IP cache ≈`:227-234`/`:383-390`), `edge-release.yml` (≈`:228-241`), `release.yml` (≈`:150-164`): remove the Python setup and DB-IP cache steps.
- `.gitignore`: remove `.dbip-cache/`.
- delete `scripts/location-db/` — **directory removal: ask the owner at implementation time** (`CLAUDE.md` §3).
- `res/values/strings.xml`: remove `approval_*`, `oauth_clients_*`, `access_oauth_*`, `access_connected_clients`, `server_pending_approvals_*`, `server_logs_type_oauth`, `notification_channel_oauth_approval_name`, `notification_oauth_approval_title`, `notification_oauth_approval_body`, `about_attribution_dbip` (verify each is unreferenced).
- **DoD:** [ ] no `com.auth0`/`coil3`/`dbip` in build files or CI

### Task 2.5 — Tests
- delete: `test/.../mcp/oauth/*Test.kt` (12 files), `integration/OAuthFlowIntegrationTest.kt`, `integration/OAuthLoggingIntegrationTest.kt`, `ui/viewmodels/ApprovalViewModelTest.kt`, `ui/viewmodels/OAuthClientsViewModelTest.kt`, the geo tests (`CountryDisplayTest`, `DbIpGeoResolverTest`, `LocationDbTest`), `app/src/test/resources/geo/location-db-fixture.bin` (single file).
- modify: `integration/McpIntegrationTestHelper.kt` (drop OAuth deps/routes), `ui/viewmodels/MainViewModelTest.kt`, `ui/viewmodels/AccessViewModelTest.kt`, `data/model/ServerConfigTest.kt`, `data/repository/SettingsRepositoryImplTest.kt`, `data/model/ServerLogEntryTypeTest.kt` (`:14`), `data/repository/ServerLogSegmentedStoreTest.kt` (`:34`), `services/mcp/AdbConfigHandlerTest.kt`, `manifest/ExportedComponentsManifestTest.kt`.
- e2e: delete `OAuthFlowE2ETest.kt`; remove the `OAUTH_APPROVE` helper and `OAuthApprovalTestReceiver` constants from `AndroidContainerSetup.kt`.

| Test | Verifies |
|---|---|
| `ServerLogEntryTypeTest: fromId(3) returns null` | reserved id decodes to "skip" |
| `ServerLogSegmentedStoreTest: segment containing a type-3 entry skips it and reads the rest` | persisted-log compatibility |
| `AdbConfigHandlerTest: oauth_enabled extra is ignored` | extra removed |
| `ExportedComponentsManifestTest: no ApprovalActivity, no OAuthApprovalTestReceiver` | manifest |

- **DoD:** [ ] tests updated

## US-3 — Bearer-only authentication, always required (D5)

**Acceptance criteria**
- [ ] `/mcp` without a valid bearer → 401, no `WWW-Authenticate … resource_metadata`.
- [ ] No setting, UI or ADB extra can disable authentication; an empty token is regenerated on read.

### Task 3.1 — `McpAuthPlugin`
- modify `mcp/auth/BearerTokenAuth.kt`. `McpAuthConfig` becomes:
  ```kotlin
  class McpAuthConfig {
      var expectedToken: String = ""
      var excludedPaths: Set<String> = emptySet()
      var onAuthFailure: ((remoteInfo: String) -> Unit)? = null
  }
  ```
  The interceptor body becomes the single path below (the OPTIONS bypass stays until US-5 removes CORS; `excludedPathPrefixes` is gone because its last users, `/s/` and `/.well-known/`, went in US-1/US-2):
  ```kotlin
  application.intercept(authPhase) {
      val call = context
      if (call.request.httpMethod == HttpMethod.Options) return@intercept // removed in US-5 with CORS
      if (excludedPaths.any { call.request.path() == it }) return@intercept
      val authHeader = call.request.headers["Authorization"]
      val providedToken =
          if (authHeader != null && authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
              authHeader.substring(BEARER_PREFIX.length).trim()
          } else {
              ""
          }
      // Empty expectedToken never matches: fail closed.
      if (expectedToken.isNotEmpty() && constantTimeEquals(expectedToken, providedToken)) return@intercept
      val remoteAddr = call.request.local.remoteAddress // socket peer only; no forwarded-header trust
      Log.w(TAG, "Authentication failed from $remoteAddr")
      onAuthFailure?.invoke(remoteAddr)
      call.respondText(
          McpJson.encodeToString(AuthErrorResponse.serializer(), AuthErrorResponse("unauthorized", "Authentication required")),
          ContentType.Application.Json,
          HttpStatusCode.Unauthorized,
      )
      finish()
  }
  ```
  Remove the `canonicalResource`/`deriveBaseUrl` imports.
- modify `mcp/McpServer.kt` (`installMcpBasePlugins { … }`, `:164-171`): keep only `expectedToken`, `excludedPaths = setOf("/health")` and `onAuthFailure`; remove `bearerTokenEnabled` (`:164`) and `baseUrlOf` (`:167`).
- **DoD:** [ ] plugin has one path

### Task 3.2 — Settings, UI, ADB
- `ServerConfig.kt`: remove `bearerTokenEnabled`.
- `SettingsRepository(Impl).kt`: remove `updateBearerTokenEnabled` (incl. `logToggle`), the `bearer_token_enabled`, `bearer_token_enabled_initialized` and `bearer_token_initialized` keys and their mapping. Replace `ensureAuthModelMigrated` with:
  ```kotlin
  /** Guarantees a non-empty bearer token: authentication is always required, so an empty token is regenerated. */
  override suspend fun ensureBearerTokenInitialized() {
      dataStore.edit { prefs ->
          if (prefs[BEARER_TOKEN_KEY].isNullOrEmpty()) {
              prefs[BEARER_TOKEN_KEY] = generateTokenString()
          }
      }
  }
  ```
  `getServerConfig()` calls it; rename the interface member and update every caller (grep `ensureAuthModelMigrated`, incl. the `McpServerService` comment).
- `ui/screens/settings/AccessSettingsScreen.kt` + `AccessViewModel.kt`: keep token show/copy/regenerate; remove the bearer toggle, the disable-auth dialog and the no-auth warning (`access_disable_auth_*`, `access_no_auth_warning_*`, `access_bearer_label` if only the toggle uses it).
- `ui/screens/ServerScreen.kt`: remove the no-auth warning callout.
- `AdbConfigHandler.kt` (`:95-97`) and `E2EConfigReceiver.kt` (`:156-158`): remove the `bearer_token_enabled` extra.
- **DoD:** [ ] no code path yields unauthenticated `/mcp`

### Task 3.3 — Tests
- modify `mcp/auth/BearerTokenAuthTest.kt`, `BearerTokenAuthPrefixTest.kt`, `integration/AuthIntegrationTest.kt`, `integration/AuthFailureLoggingIntegrationTest.kt`, `integration/McpIntegrationTestHelper.kt`, `SettingsRepositoryImplTest.kt`, `AccessViewModelTest.kt`, `AdbConfigHandlerTest.kt`, `ServerConfigTest.kt`.

| Test | Verifies |
|---|---|
| `BearerTokenAuthTest: missing token → 401 without WWW-Authenticate` | OAuth discovery gone |
| `BearerTokenAuthTest: empty expected token → 401 for any token incl. empty` | fail closed |
| `BearerTokenAuthTest: failure log uses socket peer, ignores X-Forwarded-For` | no forwarded trust |
| `SettingsRepositoryImplTest: empty token regenerated on getServerConfig, non-empty kept` | D5 |
| `SettingsRepositoryImplTest: updateBearerToken("") then getServerConfig yields non-empty` | no lock-out |
| `AccessViewModelTest: no action disables authentication` | D5 (UI) |
| `AdbConfigHandlerTest: bearer_token_enabled extra is ignored` | extra removed |

- **DoD:** [ ] tests updated

## US-4 — Delete HTTPS / self-signed certificates; NETWORK binding debug-only (D7)

**Acceptance criteria**
- [ ] No Bouncy Castle or `ktor-network-tls-certificates` on the runtime classpath.
- [ ] `McpServer` configures exactly one HTTP connector.
- [ ] Release builds cannot select NETWORK binding (UI hidden; repository and ADB coerce to `LOCALHOST`).

### Task 4.1 — HTTPS
- delete `mcp/CertificateManager.kt`, `data/model/CertificateSource.kt`, `ui/screens/settings/SecuritySettingsScreen.kt`.
- `mcp/McpServer.kt`: delete `HttpsMaterial` (`:30-34`), the `httpsMaterial` param and the HTTPS connector branch.
- `services/mcp/McpServerService.kt`: remove `certificateManager`, keystore handling, `buildHttpsMaterial`.
- `ServerConfig.kt`, `SettingsRepository(Impl).kt`: remove `httpsEnabled`, `certificateSource`, `certificateHostname`, their updaters (incl. `logToggle` calls), `validateCertificateHostname`, and the `https_enabled`, `certificate_source` and `certificate_hostname` keys.
- `data/model/ServerStatus.kt` (`:26`): remove `httpsEnabled`; update constructors.
- `ui/viewmodels/MainViewModel.kt`: remove the import (`:11`), `hostnameInput`/`hostnameError` (`:63-67`, `:128`), `updateHttpsEnabled` (`:199`), `updateCertificateSource` (`:205`), `updateCertificateHostname` (`:211-222`).
- `ui/screens/ServerScreen.kt` (`:173`), `ui/components/ConnectionInfoCard.kt` (`:63`, `:76`, `:216`): URL scheme is always `http`.
- `Routes.kt`, `ui/screens/SettingsScreen.kt`, `ui/screens/settings/SettingsIndexScreen.kt`: remove `SettingsRoute.Security`.
- `AdbConfigHandler.kt` (`:181-208`) and `E2EConfigReceiver.kt` (if present): remove the `https_enabled`, `certificate_source` and `certificate_hostname` extras.
- `app/build.gradle.kts` / `libs.versions.toml`: remove `libs.ktor.network.tls.certificates` and `libs.bouncy.castle.*` (main **and** `testImplementation`). Root `build.gradle.kts:55`: fix the now-stale Bouncy Castle comment.
- `e2e-tests/.../McpClient.kt` (`:36-38`): remove the trust-all-HTTPS client setup (dead).
- `strings.xml`: remove `config_cert*`, `config_certificate_title`, `config_hostname_label`, `config_https_enabled_label`, `settings_security_title`, `settings_security_subtitle`.

### Task 4.2 — NETWORK binding debug-only (D7)
- `SettingsRepositoryImpl.updateBindingAddress` and the `binding_address` read mapping: when `!BuildConfig.DEBUG`, persist/return `BindingAddress.LOCALHOST` regardless of input.
- `ui/screens/settings/GeneralSettingsScreen.kt`: render the binding selector only when `BuildConfig.DEBUG`; the network-warning dialog stays for debug.
- `ui/screens/ServerScreen.kt`: hide the "enable Wi-Fi access" suggestion when `!BuildConfig.DEBUG`.
- `AdbConfigHandler.kt`: the `binding_address` extra is coerced by the repository (no separate code).

### Task 4.3 — Tests
- delete `mcp/CertificateManagerTest.kt`. Modify `ServerStatusTest.kt` (`:53-55`), `ServerConfigTest.kt`, `SettingsRepositoryImplTest.kt`, `MainViewModelTest.kt`, `ui/components/ConnectionInfoCardTest.kt`, `AdbConfigHandlerTest.kt`.

| Test | Verifies |
|---|---|
| `McpServerTest (new, JVM): engine config has exactly one connector, type HTTP` | AC 2. **Setup:** inspect the Netty `applicationEnvironment`/connector list built by `McpServer` without starting it; if the connector list isn't observable without starting, start on port 0 on loopback and assert the scheme via a plain HTTP request |
| `SettingsRepositoryImplTest: release build coerces NETWORK to LOCALHOST` | D7. **Setup:** inject a `isDebugBuild: () -> Boolean` seam into `SettingsRepositoryImpl` (default `{ BuildConfig.DEBUG }`) so both branches are testable |
| `SettingsRepositoryImplTest: debug build keeps NETWORK` | D7 |
| `AdbConfigHandlerTest: https_* extras are ignored` | extras removed |

## US-5 — Delete public-exposure plumbing and CORS; enable DNS-rebinding protection (D4)

**Acceptance criteria**
- [ ] `RequestBaseUrl.kt`, `Cors.kt`, `ktor-server-cors`, the `public_url_override` setting and the auth OPTIONS bypass are gone.
- [ ] A `/mcp` request with `Host: evil.example` is rejected; `Host: localhost:<any port>` with a valid bearer is served; a non-loopback `Origin` is rejected.

### Task 5.1
- delete `mcp/RequestBaseUrl.kt`, `mcp/Cors.kt`.
- `mcp/McpApplicationPlugins.kt`: remove `configureCors()` and update the order comment (ContentNegotiation → auth).
- `mcp/auth/BearerTokenAuth.kt`: remove the OPTIONS bypass line and fix the phase comment (no CORS any more).
- `mcp/McpStatelessTransport.kt` becomes:
  ```kotlin
  /**
   * Installs the MCP stateless Streamable HTTP transport at `/mcp`.
   *
   * DNS-rebinding protection is ON with the SDK defaults (hosts `localhost`/`127.0.0.1`/`[::1]`, port ignored;
   * `Origin`, when present, must be a localhost origin). It stops a web page in the user's browser from being
   * rebound onto the device's loopback listener. It is NOT a LAN control — `Host` is client-set — the bearer
   * token is; see plan 66 D4/D7.
   *
   * Shared by [McpServer] and the integration tests so production and test wiring cannot drift.
   */
  fun Application.installMcpStatelessTransport(block: () -> Server) {
      mcpStatelessStreamableHttp(
          path = "/mcp",
          enableDnsRebindingProtection = true,
      ) {
          block()
      }
  }
  ```
- `mcp/McpServer.kt` (`:213`): `installMcpStatelessTransport { mcpSdkServer }`.
- `ServerConfig.kt`, `SettingsRepository(Impl).kt`: remove `publicUrlOverride`, `updatePublicUrlOverride` (incl. its `logToggle`/log call), `validatePublicUrlOverride`, the `public_url_override` key.
- `AccessSettingsScreen.kt` + `AccessViewModel.kt`: remove the public-URL field.
- `AdbConfigHandler.kt` (`:109-113`) and `E2EConfigReceiver.kt` (if present): remove the `public_url_override` extra.
- `app/build.gradle.kts` (`:357`) / `libs.versions.toml` (`:124`): remove `ktor-server-cors`.
- `strings.xml`: remove `access_public_url_*`.

### Task 5.2 — Tests
- delete `mcp/RequestBaseUrlTest.kt`, `integration/CorsIntegrationTest.kt`. Modify `McpIntegrationTestHelper.kt` (no `publicUrlOverride`, no base-URL element), `BearerTokenAuthTest.kt` (OPTIONS case), `SettingsRepositoryImplTest.kt`, `AccessViewModelTest.kt`, `AdbConfigHandlerTest.kt`.
- e2e `AndroidContainerSetup.kt`: add a precondition that the MCP base URL host (`container.host`, `:649`) resolves to a loopback address. Fail fast with a clear message otherwise, since D4 would reject it.

| Test | Verifies |
|---|---|
| `McpProtocolIntegrationTest: Host evil.example → rejected (403/421 per SDK)` | D4 |
| `McpProtocolIntegrationTest: Host localhost:54321 + valid bearer → 200` | port ignored |
| `McpProtocolIntegrationTest: Origin http://evil.example → rejected` | SDK origin check |
| `BearerTokenAuthTest: OPTIONS without token → 401` | preflight bypass gone |
| `AdbConfigHandlerTest: public_url_override extra is ignored` | extra removed |

## US-6 — Documentation

Why: every removal leaves docs describing subsystems that no longer exist; `CLAUDE.md` makes `PROJECT.md`/`ARCHITECTURE.md` mandatory reading for agents, so stale text there misleads the next implementer.

**Acceptance criteria**
- [ ] `grep -riE "oauth|certificate|https toggle|public.url|tunnel|share_file_via_web|get_shared_content"` over `README.md CONTRIBUTING.md docs/PROJECT.md docs/ARCHITECTURE.md docs/MCP_TOOLS.md docs/PERMISSIONS.md` returns only historical notes that say "removed (plan 66)".

### Task 6.1
- `docs/PROJECT.md`, `docs/ARCHITECTURE.md`: remove OAuth, HTTPS, CORS, public-URL, capability-link and dual-accept sections and diagrams; describe the interim bearer-only `/mcp` (DNS-rebinding on, NETWORK binding debug-only) and that Stage B removes it. Mermaid edits validated with `mmdc` (S5).
- `docs/MCP_TOOLS.md`: remove the two sharing tools; 57 → 55.
- `docs/PERMISSIONS.md`: remove anything about the share target, if present.
- `README.md`, `CONTRIBUTING.md` (`:149-163`, HTTP/HTTPS + tunnel diagram): remove OAuth/Claude.ai-connector/HTTPS/tunnel setup.
- `docs/module-map.md`: correct §1 `scripts` (location-db was OAuth-only; removed in plan 66).
- `docs/build-notes.md`: record the tunnel-era security-file follow-up as resolved (§1.4).
- `docs/debug-device.md`: note the debug-build requirement for NETWORK binding (D7).
- **Owner (D9):** `CLAUDE.md` §6 (HTTPS toggle/cert in DataStore; "Use `Switch` for toggles (auto-start, HTTPS)"), §7 ("When `expectedToken` is empty, authentication is skipped entirely"), and `.claude/agents/plan-reviewer.md` ("HTTPS certificate handling"). The implementer proposes exact wording; the owner applies or approves it.

## US-7 — Quality gates, review, PR

Why: `CLAUDE.md` §4 — gates run once, after all stories.

**Acceptance criteria / tasks**
- [ ] `./gradlew ktlintCheck detekt` clean (`make` not installed on this host; Gradle directly)
- [ ] `./gradlew :app:test :privacy:test :privacy-benchmark:test` green
- [ ] `./gradlew assembleGmsDebug assembleFossDebug assembleGmsRelease assembleFossRelease` with no warnings
- [ ] `./gradlew :app:dependencies --configuration gmsReleaseRuntimeClasspath` shows no `com.auth0`, `coil3`, `org.bouncycastle`, `ktor-network-tls-certificates`, `ktor-server-cors`
- [ ] e2e on the redroid host: **91 tests, 77 passed, 0 failed, 14 skipped**
- [ ] `edge-release` disabled per D8 **before** the PR merges (if D8 is accepted)
- [ ] `code-reviewer` subagent, plan-compliance mode, until clean
- [ ] PR via `gh pr create` per `docs/TOOLS.md`; no AI attribution

---

## Stage B — gated inventory (NOT executable until the transport and US-9 exist)

## US-8 — Remove the `/mcp` listener (lands in the transport cut-over PR)

**Gate:** device holds an outbound WSS session and executes MAPS ops (module-map §4.4); US-9 harness green. SEC-19 then holds on `main`; D8's `edge-release` can be re-enabled.

Inventory (state after Stage A):
- delete `mcp/McpServer.kt`, `mcp/McpStatelessTransport.kt`, `mcp/McpApplicationPlugins.kt`, `mcp/auth/BearerTokenAuth.kt`.
- `services/mcp/McpServerService.kt`: server content replaced by the transport host (module-map §4.4). Keep as scaffolding: foreground shape, `START_STICKY`, `onTaskRemoved`, `BootCompletedReceiver`, `PackageReplacedReceiver`, `McpServerRestart`. Tool registration onto the SDK `Server` stays only until the executor replaces it (separate plan).
- delete settings `port`, `binding_address`, `bearer_token`, `server_running`, `data/model/BindingAddress.kt`, and their UI: `ConnectionInfoCard`, port/binding in `GeneralSettingsScreen`, `AccessSettingsScreen` (whole screen, route), `ServerStatusCard` (replaced by connection status), the network-access callout in `ServerScreen`.
- delete `AdbConfigReceiver`, `AdbConfigHandler`, `AdbServiceTrampolineActivity` (D6) unless repurposed.
- `app/build.gradle.kts`: remove `ktor-server-netty`, `ktor-server-content-negotiation` (if unused), the Netty `constraints {}` block. Add a build check failing if any `io.ktor:ktor-server-{netty,cio,jetty,tomcat}` artifact is on a release runtime classpath (SEC-19 guard; the SDK still brings `ktor-server-core`).
- `McpIntegrationTestHelper`: re-host on a **test-only** SDK transport installed into Ktor `testApplication`, so the integration tests keep handler coverage with no production listener.
- e2e: delete the 79 non-portable tests (D2, §1.3); unknown-tool case per S4.
- `.github/workflows/ci.yml` `test-e2e`: switch to the US-9 harness.
- `scripts/redroid/redroid.container` + `docs/debug-device.md`: drop `PublishPort=8080`.
- docs: remove the interim-listener notes from US-6.

**Estimate:** 2–3 days of removal on top of the transport work.

## Stage C — separate plan, sized here

## US-9 — Fake-relay e2e harness (replaces `McpClient`-over-HTTP)

**What:** a Kotlin WebSocket server inside the `e2e-tests` JVM (host side; a listening socket there is fine) that speaks the `droidthumb-protocol` messages as the relay would: accepts `hello`, sends `step`/`run`, collects `report`/`snapshot`. It pairs with, and does not replace, §6.6's TypeScript `tools/fake-device`, which exercises the relay from the device side. Both should consume one shared set of example messages from `droidthumb-protocol` so they can't drift apart.

**Prerequisites:** `droidthumb-protocol` v0 messages; the APK transport; a debug-only way to point the APK at the harness URL (DUMP-gated, same pattern as `E2EConfigReceiver`); container → host reachability (S3).

**Work:** WS server + codec (≈1 day); config injection + reachability (≈0.5–1 day); step/report client DSL replacing `McpClient` (≈0.5–1 day); port the ~12 portable tests (≈1–2 days).

**Estimate: 3–5 days after prerequisites.** Cannot start before them; nothing in this repo today can stand in for it.

---

## Review findings

`plan-reviewer` audit of the first draft (2026-09-25). All findings addressed in this revision:

| Finding | Resolution |
|---|---|
| C1 US-1 (OAuth) left OAuth UI/VM/ADB/E2E users compiling against removed settings | Moved into US-2 Task 2.2 with line refs |
| C2 `Type.OAUTH` still referenced in `LogsScreen`, `ServerLogsSection`, string | Added to Task 2.2/2.4 |
| C3 US-2 pointed at `McpApplicationPlugins.kt`; fields are set in `McpServer.kt`; ADB/E2E bearer extras | Task 3.1/3.2 corrected |
| C4 US-3 missed `MainViewModel`, `ServerScreen`, `ConnectionInfoCard`, `ServerStatus`, `AdbConfigHandler`; `HttpsMaterial` location | Task 4.1 corrected |
| C5 Story order broke the build | Reordered: sharing → OAuth → bearer → HTTPS → CORS/base-URL; per-story ADB/E2E pruning; old US-6 folded in |
| C6 New logic without code | Code added for `McpAuthPlugin`, `ensureBearerTokenInitialized`, transport, `TYPE_ID_OAUTH` comment |
| W1 Tests deferred to one story | Tests moved into each story |
| W2 Test lists incomplete | Named `McpProtocolIntegrationTest`, `ToolPermissionsIntegrationTest`, `ServerLogEntryTypeTest`, `ServerLogSegmentedStoreTest`, `ServerStatusTest`, sharing/geo tests, geo fixture |
| W3 OAuth notification channel, strings, DB-IP attribution, `settings_security_subtitle` | Added |
| W4 `release.yml` DB-IP steps, Python setup steps | Added to Task 2.4 |
| W5 `ktor-server-cors` left behind | Removed in US-5 |
| W6 D4 overstated as LAN control; cleartext bearer on LAN | D4 rescoped; new D7 (NETWORK binding debug-only) |
| W7 `edge-release` publishes `main` publicly; tag range wrong | New D8; §1.5 rewritten; tag range corrected |
| W8 Portability numbers | §1.3 corrected: ~12 portable, 79 non-portable after Stage A |
| W9 Gates mid-plan | Removed from story DoDs; only US-7 |
| W10 AC without tests; US structure | Tests added (connector, D5 UI/repo, `fromId(3)`, ignored extras, Origin); US-6/US-7 given why + AC |
| W11 `CLAUDE.md`, `CONTRIBUTING.md`, `plan-reviewer.md` contradict | `CONTRIBUTING.md` in US-6; `CLAUDE.md`/agent file → owner (D9) |
| I1 SDK host matching answerable from jar | Verified; S1 resolved; D4 uses defaults; e2e loopback precondition added |
| I2 `SettingsChangeLogger` has no such entries | Removed; logging lives in `SettingsRepositoryImpl` `logToggle`, handled per story |
| I3 Empty token lock-out | `ensureBearerTokenInitialized` regenerates whenever empty |
| I4 Stale root `build.gradle.kts:55` comment | Task 4.1 |
| I5 Dead trust-all HTTPS in `McpClient` | Task 4.1 |
| I6 Verified claims | No change |
