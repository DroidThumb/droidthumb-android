# Build notes — first real build, applicationId verification, D-19 tunnel removal

**Status:** this repo had never been compiled before this pass. This documents what a from-scratch build actually required, what broke, what I fixed vs. what I flagged instead of fixing, and what's usable as a regression baseline before more D-19 work starts. Read `droidthumb-android/docs/module-map.md` first for the architecture context.

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

### Gotcha #3 — Podman is not installed, e2e-tests cannot run here

`podman` is not on this machine at all, there's no rootful podman socket, and the `binder_linux` kernel module redroid needs isn't loaded. None of this is fixable without root. This is **not** a consequence of anything removed under D-19 — it's a pure infrastructure gap that would have blocked `e2e-tests` on this machine regardless. See the e2e-tests section below.

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
| `:e2e-tests` | **not run — cannot run on this machine** | No Podman, no rootful socket, no `binder_linux` kernel module. `:e2e-tests:testClasses` (compile-only) succeeds, confirming the tunnel removal didn't break the e2e source, but the actual containerized suite (calculator, camera, storage, screenshots, WebView, and — significantly — `OAuthFlowE2ETest.kt`, the full OAuth DCR-through-authenticated-call flow) has never been exercised in this pass |

### Which suites are a usable regression baseline before D-19 work starts

**`:app:test` + `:privacy:test` + `:privacy-benchmark:test` (i.e. `make test-unit`) are a real, currently-green baseline** — 4330 tests total, 0 failures, run in a few minutes, no emulator or containers required. Use this before and after each D-19 step to catch regressions in the executor, tree parser, selector engine, settings, OAuth logic, and privacy pipeline.

**`e2e-tests` is not currently a usable baseline on this machine** — not because of D-19, but because the container stack was never available here to begin with. Whoever has a working Podman+redroid setup should run it once against the current `main` (post tunnel-removal) to get a real pre-D-19 baseline, because two things about it are directly relevant to the D-19 work still to come:

1. **`OAuthFlowE2ETest.kt`** exercises the full on-device OAuth server end-to-end. D-19's eventual removal of the on-device HTTP/OAuth server (not done in this pass — only tunnels were removed) will delete this test's entire subject, not just require edits to it.
2. **`AndroidContainerSetup`/`SharedAndroidContainer`/`McpClient`** all assume the device *listens* and the test *dials in* over HTTP — exactly the architecture D-19 replaces with a device that dials *out*. This harness will need a fundamental redesign (a fake-relay-in-the-loop, per the design doc's own `tools/fake-device` concept), not incremental fixes, once the on-device server itself is removed. Flagged in `module-map.md` already; repeating it here because it's directly relevant to "what's a usable baseline."

Also worth noting: **Play Integrity fails on emulators** (per the design doc's Appendix B), so any real device-facing behaviour that depends on Play Integrity cannot be validated on this AVD — only on a real device, which is explicitly the founder's own responsibility per B-15/§11.5 of the design doc.
