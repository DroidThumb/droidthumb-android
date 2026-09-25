.PHONY: help check-deps check-deps-updates update-deps build build-release clean \
        test-unit test coverage \
        lint lint-fix \
        install install-release uninstall grant-permissions launch-app redeploy \
        setup-emulator start-emulator stop-emulator \
        logs logs-clear \
        build-release-bundle \
        version-bump-patch version-bump-minor version-bump-major \
        check-so-alignment \
        all ci

# Variables
ANDROID_HOME ?= $(HOME)/Android/Sdk
GRADLE := ./gradlew
ADB := adb
APP_ID := uk.co.drhconsulting.droidthumb
APP_ID_DEBUG := $(APP_ID).debug
# Kotlin source package (AGP `namespace`) — unchanged by the applicationId rename in commit
# 800198a, so component class names (below) still resolve under this prefix, not APP_ID.
PKG := com.danielealbano.androidremotecontrolmcp
EMULATOR_NAME := mcp_test_emulator
EMULATOR_DEVICE := pixel_6
EMULATOR_API := 34
EMULATOR_IMAGE := system-images;android-$(EMULATOR_API);google_apis;x86_64

# ─────────────────────────────────────────────────────────────────────────────
# Help
# ─────────────────────────────────────────────────────────────────────────────

help: ## Show this help message
	@echo "Android Remote Control MCP - Development Targets"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ─────────────────────────────────────────────────────────────────────────────
# Environment & Dependencies
# ─────────────────────────────────────────────────────────────────────────────

check-deps: ## Check for required development tools
	@echo "Checking required tools..."
	@echo ""
	@MISSING=0; \
	echo "  [OK] ANDROID_HOME = $(ANDROID_HOME)"; \
	if [ ! -d "$(ANDROID_HOME)" ]; then \
		echo "  [WARN] ANDROID_HOME directory does not exist: $(ANDROID_HOME)"; \
		echo "         Install Android SDK or set: export ANDROID_HOME=/path/to/sdk"; \
	fi; \
	if command -v java >/dev/null 2>&1; then \
		JAVA_VER=$$(java -version 2>&1 | head -1 | awk -F'"' '{print $$2}'); \
		echo "  [OK] Java $$JAVA_VER"; \
	else \
		echo "  [MISSING] Java (JDK 17 required)"; \
		echo "           Install: https://adoptium.net/"; \
		MISSING=1; \
	fi; \
	if [ -f "$(GRADLE)" ]; then \
		echo "  [OK] Gradle wrapper found"; \
	else \
		echo "  [MISSING] Gradle wrapper (gradlew)"; \
		echo "           Run: gradle wrapper --gradle-version 8.14.4"; \
		MISSING=1; \
	fi; \
	if command -v $(ADB) >/dev/null 2>&1; then \
		ADB_VER=$$($(ADB) version | head -1); \
		echo "  [OK] $$ADB_VER"; \
	else \
		echo "  [MISSING] adb (Android Debug Bridge)"; \
		echo "           Install Android SDK platform-tools"; \
		MISSING=1; \
	fi; \
	echo ""; \
	if [ $$MISSING -eq 1 ]; then \
		echo "Some dependencies are missing. Please install them."; \
		exit 1; \
	else \
		echo "All dependencies are present."; \
	fi

check-deps-updates: ## Check for outdated dependencies
	$(GRADLE) dependencyUpdates --no-parallel

update-deps: ## Update version catalog with latest stable versions (interactive)
	$(GRADLE) versionCatalogUpdate --interactive

# ─────────────────────────────────────────────────────────────────────────────
# Build
# ─────────────────────────────────────────────────────────────────────────────

build: ## Build debug APK
	$(GRADLE) assembleDebug

build-release: ## Build release APK
	$(GRADLE) assembleRelease

build-release-bundle: ## Build signed release AAB for Google Play upload
	@test -f keystore.properties || { \
		echo "ERROR: keystore.properties not found — the AAB would be UNSIGNED and rejected by Google Play."; \
		echo "Create it from keystore.properties.example first."; \
		exit 1; \
	}
	$(GRADLE) bundleRelease
	@echo "AAB: app/build/outputs/bundle/release/app-release.aab"

clean: ## Clean build artifacts
	$(GRADLE) clean

# ─────────────────────────────────────────────────────────────────────────────
# Testing
# ─────────────────────────────────────────────────────────────────────────────

test-unit: ## Run unit tests (JVM)
	$(if $(wildcard .env),set -a && . ./.env && set +a &&,) $(GRADLE) :app:test

test: test-unit ## Run all tests

coverage: ## Generate code coverage report (Jacoco)
	$(GRADLE) jacocoTestReport
	@echo "Coverage report: app/build/reports/jacoco/jacocoTestReport/html/index.html"

# ─────────────────────────────────────────────────────────────────────────────
# Linting
# ─────────────────────────────────────────────────────────────────────────────

lint: ## Run all linters (ktlint + detekt)
	$(GRADLE) ktlintCheck detekt

lint-fix: ## Auto-fix linting issues
	$(GRADLE) ktlintFormat

# ─────────────────────────────────────────────────────────────────────────────
# Device Management
# ─────────────────────────────────────────────────────────────────────────────

install: ## Install debug APK on connected device/emulator
	$(GRADLE) installDebug

install-release: ## Install release APK on connected device/emulator
	$(GRADLE) installRelease

uninstall: ## Uninstall app from connected device/emulator
	$(ADB) uninstall $(APP_ID) 2>/dev/null || true
	$(ADB) uninstall $(APP_ID_DEBUG) 2>/dev/null || true

grant-permissions: ## Grant permissions via adb (accessibility + notification listener + notifications)
	@echo "=== Granting permissions via adb ==="
	@echo ""
	@echo "1. Enabling Accessibility Service..."
	$(ADB) shell settings put secure enabled_accessibility_services \
		$(APP_ID_DEBUG)/$(PKG).services.accessibility.McpAccessibilityService
	@echo "   Done."
	@echo ""
	@echo "2. Enabling Notification Listener Service..."
	$(ADB) shell cmd notification allow_listener \
		$(APP_ID_DEBUG)/$(PKG).services.notifications.McpNotificationListenerService
	@echo "   Done."
	@echo ""
	@echo "3. Granting POST_NOTIFICATIONS permission..."
	$(ADB) shell pm grant $(APP_ID_DEBUG) android.permission.POST_NOTIFICATIONS
	@echo "   Done."
	@echo ""

# Note: launch-app defaults to the debug application ID (APP_ID_DEBUG).
# To launch the release build, use: make launch-app APP_ID_TARGET=$(APP_ID)
APP_ID_TARGET ?= $(APP_ID_DEBUG)

launch-app: ## Launch MainActivity on device (debug build by default)
	$(ADB) shell am start -n $(APP_ID_TARGET)/$(PKG).ui.MainActivity

redeploy: ## Build, install, re-enable accessibility, and launch the debug build (see scripts/install-debug.sh)
	scripts/install-debug.sh $(if $(SERIAL),-s $(SERIAL),)

# ─────────────────────────────────────────────────────────────────────────────
# Emulator Management
# ─────────────────────────────────────────────────────────────────────────────

setup-emulator: ## Create AVD for testing
	@echo "Creating AVD '$(EMULATOR_NAME)'..."
	@echo "Ensure system image is installed: sdkmanager '$(EMULATOR_IMAGE)'"
	avdmanager create avd \
		-n $(EMULATOR_NAME) \
		-k "$(EMULATOR_IMAGE)" \
		--device "$(EMULATOR_DEVICE)" \
		--force
	@echo "AVD '$(EMULATOR_NAME)' created."

start-emulator: ## Start emulator in background (headless)
	@echo "Starting emulator '$(EMULATOR_NAME)'..."
	emulator -avd $(EMULATOR_NAME) -no-snapshot -no-window -no-audio -no-metrics &
	@echo "Waiting for emulator to boot..."
	$(ADB) wait-for-device
	$(ADB) shell getprop sys.boot_completed | grep -q 1 || \
		(echo "Waiting for boot..."; while [ "$$($(ADB) shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 2; done)
	@echo "Emulator is ready."

stop-emulator: ## Stop running emulator
	$(ADB) -s emulator-5554 emu kill 2>/dev/null || true
	@echo "Emulator stopped."

# ─────────────────────────────────────────────────────────────────────────────
# Logging & Debugging
# ─────────────────────────────────────────────────────────────────────────────

logs: ## Show app logs (filtered by MCP tags)
	$(ADB) logcat -s "MCP:*" "AndroidRemoteControl:*"

logs-clear: ## Clear logcat buffer
	$(ADB) logcat -c
	@echo "Logcat buffer cleared."

# ─────────────────────────────────────────────────────────────────────────────
# Versioning
# ─────────────────────────────────────────────────────────────────────────────

version-bump-patch: ## Bump patch version (1.0.0 -> 1.0.1)
	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
	MINOR=$$(echo $$CURRENT | cut -d. -f2); \
	PATCH=$$(echo $$CURRENT | cut -d. -f3); \
	NEW_PATCH=$$((PATCH + 1)); \
	NEW_VERSION="$$MAJOR.$$MINOR.$$NEW_PATCH"; \
	sed -i.bak "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
	rm -f gradle.properties.bak; \
	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (versionCode is derived from git, not bumped here)"

version-bump-minor: ## Bump minor version (1.0.0 -> 1.1.0)
	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
	MINOR=$$(echo $$CURRENT | cut -d. -f2); \
	NEW_MINOR=$$((MINOR + 1)); \
	NEW_VERSION="$$MAJOR.$$NEW_MINOR.0"; \
	sed -i.bak "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
	rm -f gradle.properties.bak; \
	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (versionCode is derived from git, not bumped here)"

version-bump-major: ## Bump major version (1.0.0 -> 2.0.0)
	@CURRENT=$$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2); \
	MAJOR=$$(echo $$CURRENT | cut -d. -f1); \
	NEW_MAJOR=$$((MAJOR + 1)); \
	NEW_VERSION="$$NEW_MAJOR.0.0"; \
	sed -i.bak "s/^VERSION_NAME=.*/VERSION_NAME=$$NEW_VERSION/" gradle.properties; \
	rm -f gradle.properties.bak; \
	echo "Version bumped: $$CURRENT -> $$NEW_VERSION (versionCode is derived from git, not bumped here)"

check-so-alignment: ## Check 16KB page alignment of native .so libraries in debug APK
	@if ! command -v llvm-objdump >/dev/null 2>&1; then \
		echo "ERROR: llvm-objdump not found. Install LLVM toolchain."; \
		exit 1; \
	fi; \
	APK="app/build/outputs/apk/debug/app-debug.apk"; \
	if [ ! -f "$$APK" ]; then \
		echo "Debug APK not found. Run 'make build' first."; \
		exit 1; \
	fi; \
	TMPDIR=$$(mktemp -d); \
	unzip -q -o "$$APK" "lib/*" -d "$$TMPDIR" 2>/dev/null; \
	FAIL=0; \
	for so in $$(find "$$TMPDIR/lib" -name "*.so" 2>/dev/null); do \
		MIN_EXP=$$(llvm-objdump -p "$$so" 2>/dev/null | grep 'LOAD.*align' | sed 's/.*align 2\*\*//' | sort -n | head -1); \
		NAME=$$(basename "$$so"); \
		ABI=$$(basename $$(dirname "$$so")); \
		if [ -z "$$MIN_EXP" ]; then \
			echo "  [WARN] $$ABI/$$NAME — no LOAD segments found, skipping"; \
			continue; \
		fi; \
		if [ "$$MIN_EXP" -ge 14 ] 2>/dev/null; then \
			echo "  [OK]   $$ABI/$$NAME — 16KB aligned (2**$$MIN_EXP)"; \
		else \
			echo "  [FAIL] $$ABI/$$NAME — not 16KB aligned (2**$$MIN_EXP)"; \
			FAIL=1; \
		fi; \
	done; \
	rm -rf "$$TMPDIR"; \
	if [ $$FAIL -eq 1 ]; then \
		echo ""; \
		echo "Some .so files are not 16KB aligned."; \
		exit 1; \
	else \
		echo ""; \
		echo "All .so files are 16KB aligned."; \
	fi

# ─────────────────────────────────────────────────────────────────────────────
# All-in-One
# ─────────────────────────────────────────────────────────────────────────────

all: clean build lint test-unit ## Run full workflow (clean, build, lint, test-unit)

ci: check-deps lint test-unit coverage build-release ## Run CI workflow
