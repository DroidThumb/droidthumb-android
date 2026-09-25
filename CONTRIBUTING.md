# Contributing

Thank you for your interest in contributing to DroidThumb!

## Getting Started

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/your-feature`
3. Make your changes following the project conventions
4. Ensure all checks pass: `make lint && make test-unit && make build`
5. Commit with descriptive messages (e.g., `feat: add ...`, `fix: ...`)
6. Open a pull request

## Development Conventions

- **Language**: Kotlin with Android (Jetpack Compose)
- **Architecture**: Service-based with SOLID principles; no on-device server (the phone dials out)
- **Testing**: JUnit 5 + MockK + Turbine (JVM unit tests; tool handlers called directly)
- **Linting**: ktlint + detekt
- **DI**: Hilt (Dagger-based)

See [docs/PROJECT.md](docs/PROJECT.md) for the complete project conventions and
[docs/plans/demolition.md](docs/plans/demolition.md) for the current state of the rebuild.

---

## Requirements

- **JDK 17** (e.g., [Eclipse Temurin](https://adoptium.net/))
- **Android SDK** with `platforms;android-34`, `platforms;android-37.x`, `build-tools;34.0.0`, `build-tools;37.0.0`
  and `platform-tools` (see `docs/debug-device.md` → "Host setup" → "Toolchain" for a user-local install)
- `local.properties` with `sdk.dir=<path to the Android SDK>` (gitignored)
- A device, emulator or the redroid debug device (`docs/debug-device.md`) for manual checks

---

## Building

### Debug Build

```bash
make build
```

Or, to also install it, re-grant the special-access permissions a reinstall clears, and launch it
in one step: `make redeploy` (wraps `scripts/install-debug.sh`; pass `SERIAL=<adb-serial>` for a
specific device, e.g. `make redeploy SERIAL=localhost:5555` for the redroid debug device).

### Release Build

```bash
make build-release
# APK: app/build/outputs/apk/release/
```

For signed release builds, create `keystore.properties` in the project root:
```properties
storeFile=path/to/your.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

### Clean Build

```bash
make clean
```

---

## Testing

### Unit Tests

```bash
make test-unit
```

Runs the JVM unit tests (JUnit 5, MockK): accessibility tree parsing and encoding, element finding, the action
executor, screenshot encoding, the tool handlers (called directly; `integration/HandlerTestHarness` for
multi-handler scenarios), the Event Channel and settings.

### Coverage

```bash
make coverage
```

Generates a Jacoco HTML report at `app/build/reports/jacoco/jacocoTestReport/html/index.html`.

### E2E Tests

None at present: the previous suite drove the removed on-device MCP server. A fake-relay harness arrives with
the outbound transport.

---

## Linting

```bash
# Check for issues
make lint

# Auto-fix issues
make lint-fix
```

Uses ktlint for code style and detekt for static analysis.

---

## Architecture

- **McpAccessibilityService** — UI introspection, actions and screenshot capture via the Android
  Accessibility APIs
- **Tool handlers** (`mcp/tools/`) — the operation logic an LLM drives the phone with; invoked by the future
  outbound transport
- **EventChannelService** — forwards notification events to a configured endpoint
- **MainActivity** — permissions, battery exemption, Event Channel configuration

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation.
