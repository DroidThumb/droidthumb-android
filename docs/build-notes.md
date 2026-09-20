# Build notes — first real build, applicationId verification, D-19 tunnel removal, e2e-tests

**Status:** this repo had never been compiled before this pass. This documents what a from-scratch build actually required, what broke, what I fixed vs. what I flagged instead of fixing, how `e2e-tests` was gotten to a green baseline once Podman was available, and what's usable as a regression baseline before more D-19 work starts. Read `droidthumb-android/docs/module-map.md` first for the architecture context.

**For a persistent, always-on debug device** (rather than the ephemeral, per-run containers `e2e-tests` manages itself), see `docs/debug-device.md` — it covers the redroid quadlet unit, the host-level kernel-module/binderfs/podman-socket persistence this doc used to describe as manual per-boot steps, and how to reach/rebuild/verify the device. The host-setup detail below is kept for its historical diagnosis value (the redroid image pull cost, the applicationId-propagation bugs); the "do this every time you boot" framing it used to carry is obsolete — `docs/debug-device.md` is now the source of truth for host setup.

## Toolchain versions (confirmed working)

| Component | Version | Source |
|---|---|---|
| JDK | Temurin 17.0.20.1+1 | `api.adoptium.net` binary API, installed user-local (no sudo) |
| Android SDK cmdline-tools | build 15859902 | resolved from `developer.android.com/studio` at install time — this number changes over time, don't hardcode it |
| Android platform (compileSdk) | `platforms;android-37.2` | **not** `platforms;android-37` — see gotcha #1 below |
| Android platform (targetSdk/minSdk) | `platforms;android-34` | |
| Build-tools | `34.0.0` and `37.0.0` | both installed; AGP picks per-variant |
| NDK | not installed, not needed | only used to have been required by the now-removed tunnel native libs |
| Emulator | 37.1.11 (from sdkmanager) | |
| System image | `system-images;android-34;google_apis;x86_64` | matches `EMULATOR_IMAGE` in the Makefile |
| Gradle | 9.7.1 | via `./gradlew`, wrapper-managed, no local Gradle install needed |
| AGP | 9.3.2 | pinned in `gradle/libs.versions.toml`, untouched |
| Kotlin | 2.4.10 | pinned in `gradle/libs.versions.toml`, untouched |
| KSP | 2.3.11 | pinned, untouched |
| Hilt | 2.60.1 | pinned, untouched |
| Podman | 4.9.3 | rootful socket at `/run/podman/podman.sock`, installed and configured by the owner (root required) — needed only for `:e2e-tests` |

**No pinned version in the repo (Gradle, AGP, JDK target, SDK versions) needed to change.** Everything in the existing config built cleanly once the SDK was populated correctly. I did not touch `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `compileSdk`/`minSdk`/`targetSdk`, or `sourceCompatibility`/`jvmTarget` — none of the environmental failures required it, so nothing here was a "stop and ask" case.

## Environment: what this machine actually had, and what I had to set up

Contrary to the initial assumption that JDK/SDK/emulator were already installed, this session's environment had **only** `adb` (`android-tools-adb` via apt) and nothing else — no JDK anywhere, no `sdkmanager`, no `platforms`, no `build-tools`, no NDK, no emulator binary, no AVD. `sudo` requires a password I don't have, so everything below was installed **user-local, no root**:

1. **JDK 17**: downloaded the Temurin tarball directly (`api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse`) into `~/toolchain/jdk-17.0.20.1+1`, no package manager involved.
2. **Android SDK**: downloaded `commandlinetools-linux-<build>_latest.zip` from `dl.google.com` (URL resolved from the current Android Studio download page, not hardcoded) into `~/toolchain/android-sdk/cmdline-tools/latest`.
3. Accepted all SDK licenses (`yes | sdkmanager --licenses`) — required before `sdkmanager` will install anything.
4. Installed via `sdkmanager`: `platform-tools`, `platforms;android-34`, `platforms;android-37.2`, `build-tools;34.0.0`, `build-tools;37.0.0`, `emulator`, `system-images;android-34;google_apis;x86_64`.
5. Created `local.properties` at the repo root with `sdk.dir=<the above path>` — this file is gitignored and does not exist by default; a fresh checkout needs it created (or `ANDROID_HOME` exported) before Gradle can find the SDK at all.
6. Created the AVD: `avdmanager create avd -n mcp_test_emulator -k "system-images;android-34;google_apis;x86_64" --device "pixel_6" --force` — matches the `EMULATOR_NAME`/`EMULATOR_DEVICE`/`EMULATOR_IMAGE` already defined in the Makefile, so `make setup-emulator` would produce the same AVD.
7. Started it headless: `emulator -avd mcp_test_emulator -no-snapshot -no-window -no-audio -no-metrics` (exactly what `make start-emulator` runs).

### Gotcha #1 — `compileSdk = 37` does not mean `platforms;android-37`

`app/build.gradle.kts` pins `compileSdk = 37`. The real, currently-downloadable SDK platform is versioned `platforms;android-37.2` (with `37.0`/`37.1`/`37.2` and several `37.2-beta*` all separately listed by `sdkmanager --list`) — there is no bare `platforms;android-37` package. AGP resolved this without complaint once `platforms;android-37.2` was installed, but a naive `sdkmanager "platforms;android-37"` fails with no such package, and this is exactly the kind of thing a second person will hit and not immediately understand. Install whichever `platforms;android-37.*` is current, not the bare integer.

### Gotcha #2 — KVM access here is a personal ACL grant, not group membership

`groups` for this user does **not** list `kvm`, and `getent group kvm` shows no members — a naive check (`groups | grep kvm`) says "no access." But `/dev/kvm` carries an explicit POSIX ACL (`getfacl /dev/kvm` shows `user:danny-harris:rw-`) that grants this specific user read/write outside the normal group mechanism, and the emulator used it successfully (fast boot, no software-rendering fallback warnings in the log). **A second person on a different machine should not assume this ACL exists.** Check with `ls -l /dev/kvm` (should show `crw-rw----+`, note the `+`) and `getfacl /dev/kvm`; if there's no ACL and the user isn't in the `kvm` group, they need `sudo usermod -aG kvm $USER` (then log out/in) or an equivalent ACL grant — a command I cannot run myself.

### Gotcha #3 — Podman was not installed initially; since resolved, see "e2e-tests" below

At the time this pass first ran, `podman` was not on this machine at all, there was no rootful podman socket, and the `binder_linux` kernel module redroid needs wasn't loaded. None of that was a consequence of anything removed under D-19 — it was a pure infrastructure gap. The owner has since installed Podman and set up the rootful socket themselves (root access I don't have); `e2e-tests` now runs on this machine. See "e2e-tests — working setup" below for the full story, including a real bug the setup process surfaced, and `docs/debug-device.md` for how the podman-socket permissions and kernel-module/binderfs setup were later made to survive a reboot instead of being reapplied by hand.

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

No `sudo` is required for a normal run on this machine — see "the sudo-free path" below. Kill any stray Gradle/Kotlin daemons first (`./gradlew --stop`) if a previous run didn't exit cleanly; a leftover daemon can hold the podman socket or stale compiled test classes.

### Kernel-module detection fix (fuse is built into this kernel)

First attempt failed in 56s, before the container ever got created: `ensureKernelModules()` in `AndroidContainerSetup.kt` checked `/proc/modules` for both `binder_linux` and `fuse`, and only ran `sudo modprobe ...` if either was missing. `binder_linux` was loaded, but this kernel has `fuse` compiled in rather than shipped as a loadable module, so it never appears in `/proc/modules` — the check always read as "not satisfied," so it always fell through to a non-interactive `sudo modprobe binder_linux ...`, which failed immediately (no TTY, no cached credentials): `sudo: a password is required`.

Fixed by accepting two alternative satisfaction signals, narrowly scoped to the detection logic only (nothing about the container config or the sudo invocation itself changed):

- binder: `/proc/modules` **or** `/dev/binderfs` already mounted
- fuse: `/proc/modules` **or** `/dev/fuse` device node existing

This is inherited upstream test infrastructure, not project-specific logic — worth sending upstream if this kernel-packaging variance (fuse built-in vs. loadable) affects other users of the same harness.

### The sudo-free path

With the fix above, `ensureKernelModules()` logs `Kernel modules already loaded` and returns immediately whenever `binder_linux` is loaded and `/dev/binderfs` is mounted — `sudo` is never invoked in that case. At the time this was written, that state was reached manually each boot (a `modprobe`/`mkdir`/`mount` sequence run by hand). That's since been replaced with real boot-time persistence — `/etc/modules-load.d/`, `/etc/modprobe.d/`, and an `/etc/fstab` entry for `/dev/binderfs` — documented in full, including a real boot-ordering bug the first version of that persistence hit, in `docs/debug-device.md`'s "Host setup" section. Once that's confirmed working across a reboot, `sudo` should play no role in a normal `e2e-tests` run on this machine at all, not even a one-time manual step.

### Redroid image pull

`redroid/redroid:14.0.0-latest` is 828MB, pulled once via the rootful Podman socket (~28s at ~29MB/s on this connection) and cached by Podman thereafter — every run after the first skips straight to container creation. Boot itself (container start → ADB-reachable) is fast once the image is local: consistently 8-9 seconds across four separate runs.

### Two applicationId-propagation bugs found and fixed

Getting `e2e-tests` running for the first time since the `800198a` applicationId rename surfaced two real bugs — the rename only touched `app/build.gradle.kts` and `compose-test-app/build.gradle.kts`, and nothing in `e2e-tests/` was in scope at the time:

1. **`APP_PACKAGE`** — hardcoded in two independent places (`AndroidContainerSetup.kt`, `StorageE2E.kt`) to the pre-rename applicationId. Every `adb -n`/`pm`/`pidof` command addressed a package that no longer exists on the device, so the app never launched and the MCP server never started (`MCP server did not become ready within 60000ms`). Fixed by updating both constants to `uk.co.drhconsulting.droidthumb.gms.debug`.
2. **`COMPOSE_TEST_PACKAGE`** — same root problem, one level deeper. `compose-test-app`'s applicationId was renamed but its namespace deliberately wasn't (same pattern as the main app), so this single constant was being used two incompatible ways: as a bare package name (`force-stop`, where the applicationId is correct) and as the prefix for `-n pkg/.MainActivity`-style relative component references, which Android resolves by literally concatenating the relative class name onto whatever precedes the slash — so *that* usage needed the namespace, not the applicationId. No single string value could satisfy both. Split into `COMPOSE_TEST_APPLICATION_ID` (bare uses) and `COMPOSE_TEST_MAIN_ACTIVITY_CLASS`/`COMPOSE_TEST_WEBVIEW_ACTIVITY_CLASS` (fully-qualified, used with `-n`), matching the pattern `AndroidContainerSetup.kt` already used correctly for the main app. Also deleted a dead, unused duplicate of the old constant in `E2EComposeRefreshTest.kt`.

Worth checking for the same applicationId/namespace-divergence trap anywhere else a future rename touches an app whose namespace stays fixed.

### Pass/fail/skip baseline (post-fix, current `main`)

**92 tests, 78 passed, 0 failed, 14 skipped — this is green.**

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
