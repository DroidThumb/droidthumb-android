# Build notes — first real build, applicationId verification, D-19 tunnel removal, e2e-tests

**Status:** this repo had never been compiled before this pass. This documents what a from-scratch build actually required, what broke, what I fixed vs. what I flagged instead of fixing, how `e2e-tests` was gotten to a green baseline once Podman was available, and what's usable as a regression baseline before more D-19 work starts. Read `droidthumb-android/docs/module-map.md` first for the architecture context.

**Host and toolchain setup live in `docs/debug-device.md`, not here.** That doc is the single source of truth for: the host as it currently is (user, OS, podman version), installing the JDK and Android SDK user-local, the kernel-module/binderfs/podman-socket persistence, the persistent redroid debug device, and the recovery sequence after an OS reinstall (which wipes all of it — this has happened twice). This doc covers what building and testing the code needs and has found: toolchain versions, build gotchas, the D-19 tunnel removal, applicationId verification, the e2e harness fixes, and the test baselines. If the two ever disagree about the host, `debug-device.md` wins and this one is the bug.

## Toolchain versions

Current host as of 2026-09-24/25 (Ubuntu 26.04.1, user `dan`, reinstalled 2026-09-22 — the previous install was user `danny-harris`; any path containing that name is dead). Paths are under `/home/dan/toolchain/`.

| Component | Version | Source |
|---|---|---|
| JDK | Temurin 17.0.20.1+1 | `api.adoptium.net` binary API, installed user-local (no sudo) at `~/toolchain/jdk-17.0.20.1+1` |
| Android SDK cmdline-tools | build 15859902 | resolved from `developer.android.com/studio` at install time — this number changes over time, don't hardcode it |
| Android platform (compileSdk) | `platforms;android-37.2` | **not** `platforms;android-37` — see gotcha #1 below |
| Android platform (targetSdk/minSdk) | `platforms;android-34` | |
| Build-tools | `34.0.0` and `37.0.0` | both installed; AGP picks per-variant |
| platform-tools (adb) | 37.0.1 | `~/toolchain/android-sdk/platform-tools/adb` — not on `PATH` by default; `e2e-tests` shells out to a bare `adb`, so it must be on `PATH` for that suite |
| NDK | not installed, not needed | only used to have been required by the now-removed tunnel native libs |
| Emulator + system image | **not installed on the current host** | the redroid debug device (`docs/debug-device.md`) replaced the AVD after the reinstall. Previous install had emulator 37.1.11 and `system-images;android-34;google_apis;x86_64` (matches `EMULATOR_IMAGE` in the Makefile) — reinstall with sdkmanager only if you need an AVD |
| Gradle | 9.7.1 | via `./gradlew`, wrapper-managed, no local Gradle install needed |
| AGP | 9.3.2 | pinned in `gradle/libs.versions.toml`, untouched |
| Kotlin | 2.4.10 | pinned in `gradle/libs.versions.toml`, untouched |
| KSP | 2.3.11 | pinned, untouched |
| Hilt | 2.60.1 | pinned, untouched |
| Podman | 5.7.0 | apt, installed by the owner; rootful socket at `/run/podman/podman.sock` — needed for `:e2e-tests` and the debug device. The previous install ran 4.9.3. |
| make | **not installed on the current host** | apt (needs sudo). Every `make` target in this repo fails until it is; the underlying `./gradlew` commands work without it |
| act, mmdc (node) | **not installed on the current host** | `docs/TOOLS.md` uses `act` for local CI; `CLAUDE.md` requires `mmdc` to validate Mermaid diagrams |

**No pinned version in the repo (Gradle, AGP, JDK target, SDK versions) needed to change.** Everything in the existing config built cleanly once the SDK was populated correctly. I did not touch `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `compileSdk`/`minSdk`/`targetSdk`, or `sourceCompatibility`/`jvmTarget` — none of the environmental failures required it, so nothing here was a "stop and ask" case.

## Setting up the build environment

The JDK and Android SDK are installed **user-local, no root**, under `~/toolchain/`. The exact commands are in `docs/debug-device.md` → "Host setup" → "Toolchain", and were last re-run from there on 2026-09-24. Two build-specific points on top of that:

- `local.properties` at the repo root needs `sdk.dir=/home/dan/toolchain/android-sdk` (or `ANDROID_HOME` exported) before Gradle can find the SDK at all. It is gitignored, so it doesn't exist in a fresh checkout. The checkout now lives on ext4 under `/home/dan` (moved off the NTFS `Shared` partition 2026-09-25), which is wiped on an OS reinstall same as everything else under `/home/dan` — see `docs/debug-device.md`'s recovery sequence — so this file no longer survives a reinstall with a dead path in it; a reinstall now means writing it fresh, not editing a stale one.
- The first build after an empty `~/.gradle` is cold: `assembleGmsDebug` took 16m 48s on 2026-09-24, nearly all of it downloading the Gradle distribution and dependencies.

Only if you need an AVD (the redroid debug device usually replaces it): `sdkmanager emulator "system-images;android-34;google_apis;x86_64"`, then `avdmanager create avd -n mcp_test_emulator -k "system-images;android-34;google_apis;x86_64" --device "pixel_6" --force` (matches `EMULATOR_NAME`/`EMULATOR_DEVICE`/`EMULATOR_IMAGE` in the Makefile, so `make setup-emulator` produces the same AVD), and start it headless with `emulator -avd mcp_test_emulator -no-snapshot -no-window -no-audio -no-metrics` (what `make start-emulator` runs).

### Gotcha #1 — `compileSdk = 37` does not mean `platforms;android-37`

`app/build.gradle.kts` pins `compileSdk = 37`. The real, currently-downloadable SDK platform is versioned `platforms;android-37.2` (with `37.0`/`37.1`/`37.2` and several `37.2-beta*` all separately listed by `sdkmanager --list`) — there is no bare `platforms;android-37` package. AGP resolved this without complaint once `platforms;android-37.2` was installed, but a naive `sdkmanager "platforms;android-37"` fails with no such package, and this is exactly the kind of thing a second person will hit and not immediately understand. Install whichever `platforms;android-37.*` is current, not the bare integer.

### Gotcha #2 — KVM access here is a personal ACL grant, not group membership

Only matters for an AVD — redroid doesn't use KVM. `groups` for this user does **not** list `kvm`, and `getent group kvm` shows no members — a naive check (`groups | grep kvm`) says "no access." But `/dev/kvm` carries an explicit POSIX ACL that grants the logged-in desktop user read/write outside the normal group mechanism. On the current host `getfacl /dev/kvm` shows `user:dan:rw-` (checked 2026-09-25; on the previous install it was `user:danny-harris:rw-` and the emulator used it successfully). It follows whoever is logged in at the seat, so it is not something to configure — but **a second person on a different machine should not assume it exists.** Check with `ls -l /dev/kvm` (should show `crw-rw----+`, note the `+`) and `getfacl /dev/kvm`; if there's no ACL and the user isn't in the `kvm` group, they need `sudo usermod -aG kvm $USER` (then log out/in) or an equivalent ACL grant.

### Gotcha #3 — Podman and the binder kernel setup are host config, and a reinstall removes them

Podman, its rootful socket permissions, and the `binder_linux`/binderfs setup redroid needs are all root-owned host config, installed by the owner, and all of it was wiped by the 2026-09-22 OS reinstall (and had to be rebuilt once before that). None of it is a consequence of anything in this repo. What it is, how to check it, and how to rebuild it: `docs/debug-device.md` → "Host setup" and "A full OS reinstall wipes all of this".

## What broke, and what I did about it

### `./gradlew assembleFossDebug` — first attempt failed

Exact error: `IllegalArgumentException: File/directory does not exist: .../vendor/ngrok-java/ngrok-java-native/target/ngrok-java-native-classes.jar`. `app/build.gradle.kts` depends on prebuilt jars from `vendor/ngrok-java` and native `.so`s from `vendor/cloudflared` via the Makefile's `compile-cloudflared`/`compile-ngrok-native` targets — but both are uninitialized git submodules (never checked out, never built), and building them for real would have meant standing up Go, Rust/cargo, Maven, and the Android NDK, and cross-compiling two upstream projects, all for the tunnel subsystem D-19 already removes.

**Decision (made with the user, not unilaterally):** remove the tunnel subsystem instead of building it. Full detail in the "Remove tunnel subsystem (D-19)" commit — summary: deleted `services/tunnel/*`, the three tunnel-only data models, `TunnelSettingsScreen`, every UI/DI/ADB-config/settings touchpoint that referenced them, the vendor submodules, the ngrok-java jar dependencies, `useLegacyPackaging` in `packaging { jniLibs { ... } }` (only needed for the now-gone native libs), and the `compile-cloudflared`/`compile-ngrok-native` Makefile targets. Confirmed via `module-map.md` that `services/tunnel/*` has zero import coupling to `services/accessibility/*`, so this could not have damaged the executor or tree encoding.

**Deliberately left untouched** (flagged for the owner, not decided here): four files whose comments justify real server behaviour — DNS-rebinding protection disabled, CORS wildcard origin, X-Forwarded-* IP trust, tunnel-aware base-URL scheme detection — on the assumption that traffic always arrives via a tunnel. That assumption no longer holds now the tunnel subsystem is gone, but whether to change that behaviour is a decision about the on-device server itself (module-map.md's §4.1), not about tunnel removal, so I did not widen the change into it:

- `app/src/main/kotlin/.../mcp/McpStatelessTransport.kt` — DNS-rebinding protection disabled
- `app/src/main/kotlin/.../mcp/Cors.kt` — CORS wildcard origin justified by dynamic tunnel hosts
- `app/src/main/kotlin/.../mcp/oauth/OAuthRouteSupport.kt` — trusts `CF-Connecting-IP`/`X-Forwarded-For`
- `app/src/main/kotlin/.../mcp/RequestBaseUrl.kt` — tunnel-aware scheme/host derivation

Also left untouched as stale copy, not code: `access_oauth_supporting` string ("Requires an active tunnel…") and a comment in `AccessViewModel.kt`.

After the removal, `./gradlew assembleFossDebug` built clean on the next attempt. No further environmental issues appeared — no missing SDK components, no license prompts, no JDK mismatch, nothing else NDK-related.

### Unit tests — first attempt failed to compile

8 test files referenced now-deleted tunnel symbols and failed to compile: `ServerConfigTest.kt`, `ServerLogEntryTypeTest.kt`, `ServerLogSegmentedStoreTest.kt`, `SettingsRepositoryImplTest.kt`, `AdbConfigHandlerTest.kt`, `ServerStatusLogMessageTest.kt`, `ConnectionInfoCardTest.kt`, `MainViewModelTest.kt`. Fixed by removing the tunnel-specific test cases/classes and updating call sites (e.g. `MainViewModel`'s constructor lost its `TunnelManager` parameter, so every test instantiation needed the argument dropped). Four other files that mention "tunnel" only in comments or unrelated strings (`RequestBaseUrlTest.kt`, `OAuthFlowIntegrationTest.kt`, `SharingIntegrationTest.kt`, `ExportedComponentsManifestTest.kt`) did **not** need changes — confirmed by the fact they compiled and passed without touching them.

## Step 3 — applicationId verification (from real build artifacts)

Built all four flavour/build-type combinations and inspected the actual resolved package name with `aapt dump badging` and each variant's `output-metadata.json`, not by reading the Gradle config and assuming:

| Variant | Resolved applicationId |
|---|---|
| `assembleFossDebug` | `uk.co.drhconsulting.droidthumb.foss.debug` |
| `assembleGmsDebug` | `uk.co.drhconsulting.droidthumb.gms.debug` |
| `assembleFossRelease` | `uk.co.drhconsulting.droidthumb` |
| `assembleGmsRelease` | `uk.co.drhconsulting.droidthumb` |

Confirmed: release id identical across flavours; debug ids get the per-flavour suffix the comments at `app/build.gradle.kts:254` and `:477-480` describe; `com.danielealbano.androidremotecontrolmcp` does not appear as an `applicationId` anywhere in any built output (it correctly remains only as the `namespace`/Kotlin source package, per commit 800198a's explicit design).

## Step 4 — install and launch (foss debug)

`adb install -r app-foss-debug.apk` succeeded. `adb shell am start -n uk.co.drhconsulting.droidthumb.foss.debug/com.danielealbano.androidremotecontrolmcp.ui.MainActivity` launched cleanly — confirmed via logcat (`ActivityTaskManager: Displayed ... MainActivity for user 0: +1s569ms`, no `FATAL`/`AndroidRuntime` crash lines) and the process staying alive. Did not grant accessibility or interact further, per instruction — only launched and screenshotted.

**What it shows:** the Server tab, with four dismissable/actionable cards — "Accessibility permission required" (Go to permissions), "Keep the server running" (Disable battery optimization), "Reachable from this device only" (Enable Wi-Fi access — **no "Set up tunnel" option**, confirming the `NetworkAccessSuggestionCard` edit rendered correctly), "Worried about personal data?" (Set up Privacy Mode / Dismiss) — and a Services Status section showing MCP Server and Event Channel both Stopped, each with a Start button. Bottom nav: Server / Settings / About.

**What it asks for:** accessibility permission (required for the core function), battery-optimisation exemption (optional, for background survival), Wi-Fi/network binding (optional, for LAN reachability), Privacy Mode setup (optional). Nothing auto-starts by default (`autoStartOnBoot = false`), consistent with `ServerConfig`'s defaults.

## Step 5 — test suites

| Suite | Result | Notes |
|---|---|---|
| `:app:test` (foss) | **2083 tests, 0 failures, 0 errors** | |
| `:app:test` (gms) | **2123 tests, 0 failures, 0 errors** | 40 more tests than foss — the gms-only geofencing/Fused-location tests |
| `:privacy:test` | **79 tests, 0 failures, 0 errors, 4 skipped** | Skips are the gated real-model tests (`OrtPiiModelRunnerRealModelTest` etc.) that need `PRIVACY_MODEL_DIR` set — pre-existing, not related to today's changes |
| `:privacy-benchmark:test` | **45 tests, 0 failures, 0 errors, 1 skipped** | |
| `test-integration` (`:app:testGmsDebugUnitTest --tests "...integration.*"`) | **included in the above** | Makefile's own comment says this is a subset of `test-unit` since both are JVM-based; no separate run needed |
| `:e2e-tests` | **92 tests, 78 passed, 0 failed, 14 skipped** | Now runs end-to-end on this machine. See "e2e-tests — working setup" below for how, and for two real applicationId-propagation bugs the setup process found and fixed. |

## e2e-tests — working setup

Once the owner installed Podman and set up the rootful socket, getting `e2e-tests` fully green took three fix passes. Each is its own commit; summarized here so a future session knows what "green" looks like and doesn't have to rediscover any of this.

### Running it

```bash
DOCKER_HOST=unix:///run/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :e2e-tests:test
```

`make test-e2e` wraps the same command (plus `:e2e-tests:cleanTest` and sourcing `.env`), but `make` is not installed on the current host. `adb` must be on `PATH` (`export PATH=$HOME/toolchain/android-sdk/platform-tools:$PATH`) because the harness shells out to a bare `adb`. No `sudo` is required for a normal run on this machine — see "the sudo-free path" below. Kill any stray Gradle/Kotlin daemons first (`./gradlew --stop`) if a previous run didn't exit cleanly; a leftover daemon can hold the podman socket or stale compiled test classes.

### Kernel-module detection fix (fuse is built into this kernel)

First attempt failed in 56s, before the container ever got created: `ensureKernelModules()` in `AndroidContainerSetup.kt` checked `/proc/modules` for both `binder_linux` and `fuse`, and only ran `sudo modprobe ...` if either was missing. `binder_linux` was loaded, but this kernel has `fuse` compiled in rather than shipped as a loadable module, so it never appears in `/proc/modules` — the check always read as "not satisfied," so it always fell through to a non-interactive `sudo modprobe binder_linux ...`, which failed immediately (no TTY, no cached credentials): `sudo: a password is required`.

Fixed by accepting two alternative satisfaction signals, narrowly scoped to the detection logic only (nothing about the container config or the sudo invocation itself changed):

- binder: `/proc/modules` **or** `/dev/binderfs` already mounted
- fuse: `/proc/modules` **or** `/dev/fuse` device node existing

This is inherited upstream test infrastructure, not project-specific logic — worth sending upstream if this kernel-packaging variance (fuse built-in vs. loadable) affects other users of the same harness.

### The sudo-free path

With the fix above, `ensureKernelModules()` logs `Kernel modules already loaded` and returns immediately whenever `binder_linux` is loaded and `/dev/binderfs` is mounted — `sudo` is never invoked in that case. Both are now set up at boot by the host persistence in `docs/debug-device.md` ("Kernel module persistence", "binderfs mount"), verified across a reboot on 2026-09-24. So a normal `e2e-tests` run needs no `sudo` at all — provided `dan` can reach the podman socket (see "Podman socket permissions" in that doc).

### Redroid image pull

`redroid/redroid:14.0.0-latest` (asked for by short name; the Docker-compatible API resolves it to `docker.io/redroid/redroid:14.0.0-latest` — confirmed 2026-09-25 from the running container's image) is 828MB, pulled once via the rootful Podman socket (~28s at ~29MB/s on this connection) and cached by Podman thereafter — every run after the first skips straight to container creation. Boot itself (container start → ADB-reachable) is fast once the image is local: consistently 8-9 seconds across four separate runs.

### Two applicationId-propagation bugs found and fixed

Getting `e2e-tests` running for the first time since the `800198a` applicationId rename surfaced two real bugs — the rename only touched `app/build.gradle.kts` and `compose-test-app/build.gradle.kts`, and nothing in `e2e-tests/` was in scope at the time:

1. **`APP_PACKAGE`** — hardcoded in two independent places (`AndroidContainerSetup.kt`, `StorageE2E.kt`) to the pre-rename applicationId. Every `adb -n`/`pm`/`pidof` command addressed a package that no longer exists on the device, so the app never launched and the MCP server never started (`MCP server did not become ready within 60000ms`). Fixed by updating both constants to `uk.co.drhconsulting.droidthumb.gms.debug`.
2. **`COMPOSE_TEST_PACKAGE`** — same root problem, one level deeper. `compose-test-app`'s applicationId was renamed but its namespace deliberately wasn't (same pattern as the main app), so this single constant was being used two incompatible ways: as a bare package name (`force-stop`, where the applicationId is correct) and as the prefix for `-n pkg/.MainActivity`-style relative component references, which Android resolves by literally concatenating the relative class name onto whatever precedes the slash — so *that* usage needed the namespace, not the applicationId. No single string value could satisfy both. Split into `COMPOSE_TEST_APPLICATION_ID` (bare uses) and `COMPOSE_TEST_MAIN_ACTIVITY_CLASS`/`COMPOSE_TEST_WEBVIEW_ACTIVITY_CLASS` (fully-qualified, used with `-n`), matching the pattern `AndroidContainerSetup.kt` already used correctly for the main app. Also deleted a dead, unused duplicate of the old constant in `E2EComposeRefreshTest.kt`.

Worth checking for the same applicationId/namespace-divergence trap anywhere else a future rename touches an app whose namespace stays fixed.

### Pass/fail/skip baseline (post-fix, current `main`)

**92 tests, 78 passed, 0 failed, 14 skipped — this is green.**

**Re-verified 2026-09-25 on the reinstalled host** (Ubuntu 26.04.1, podman 5.7.0, user `dan`, no sudo, with the persistent debug device from `docs/debug-device.md` running alongside): same 92/78/0/14, identical per class, `BUILD SUCCESSFUL in 4m 58s` including the APK build.

| Test class | Tests | Skipped |
|---|---|---|
| `E2ECalculatorTest` | 3 | 0 |
| `E2ECameraTest` | 16 | 14 |
| `E2EComposeRefreshTest` | 2 | 0 |
| `E2EErrorHandlingTest` | 6 | 0 |
| `E2EScreenshotTest` | 1 | 0 |
| `E2EStorageEdgeCasesTest` | 20 | 0 |
| `E2EStoragePartialAccessTest` | 8 | 0 |
| `E2EStorageToolsTest` | 31 | 0 |
| `E2EWebViewNodeReductionTest` | 1 | 0 |
| `E2EWebViewRefreshTest` | 3 | 0 |
| `OAuthFlowE2ETest` | 1 | 0 |

All 14 skips are `E2ECameraTest`, gated by `Assumptions.assumeTrue(...)` in the test source — redroid has no real camera hardware, so these skip gracefully by design rather than failing. Not an environmental gap worth chasing.

## Which suites are a usable regression baseline before D-19 work starts

**`:app:test` + `:privacy:test` + `:privacy-benchmark:test` (i.e. `make test-unit`) are a real, currently-green baseline** — 4330 tests total, 0 failures, run in a few minutes, no emulator or containers required. Use this before and after each D-19 step to catch regressions in the executor, tree parser, selector engine, settings, OAuth logic, and privacy pipeline.

**`e2e-tests` is now also a real, green baseline on this machine** (92 tests, 78 passed, 0 failed, 14 skipped — see above), but its usable lifespan is short relative to the D-19 work still ahead:

1. **`OAuthFlowE2ETest.kt`** currently passes — it exercises the full on-device OAuth server end-to-end (DCR through authenticated tool call) and that server still exists (only tunnels were removed in this pass). D-19's eventual removal of the on-device HTTP/OAuth server will delete this test's entire subject, not just require edits to it.
2. **`AndroidContainerSetup`/`SharedAndroidContainer`/`McpClient`** all assume the device *listens* and the test *dials in* over HTTP — exactly the architecture D-19 replaces with a device that dials *out*. This harness will need a fundamental redesign (a fake-relay-in-the-loop, per the design doc's own `tools/fake-device` concept), not incremental fixes, once the on-device server itself is removed. Flagged in `module-map.md` already; repeating it here because it's directly relevant to "what's a usable baseline."

So: run it now, while it's green, to catch any regression from tunnel removal or the applicationId-propagation fixes above — but expect it to need a real redesign, not incremental patching, once D-19's on-device-server removal lands.

Also worth noting: **Play Integrity fails on emulators** (per the design doc's Appendix B), so any real device-facing behaviour that depends on Play Integrity cannot be validated on this AVD — only on a real device, which is explicitly the founder's own responsibility per B-15/§11.5 of the design doc.
