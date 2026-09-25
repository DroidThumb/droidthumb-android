# Persistent debug device (redroid)

A always-on Android device for manual debugging and quick checks, running as a rootful Podman
container (`docker.io/redroid/redroid:14.0.0-latest`) managed by systemd. It is meant to just sit
there, reachable over adb, so you don't pay a multi-minute boot cost every time you want to poke at
the app.

> **Since the demolition pass (2026-09-25, `docs/plans/demolition.md`)** the app has no on-device
> server, so this device can install, launch and inspect the app, and nothing more: there is no
> remote control until the outbound transport exists. The `e2e-tests` module and port 8080 (the old
> on-device MCP server) are gone; references to them below are history. The quadlet still publishes
> 8080; dropping `PublishPort=8080` needs a quadlet reinstall (sudo) and is left for when the
> transport defines what, if anything, this device should expose.

**Read this whole doc before touching anything here.** Claims are marked with when and how they
were verified; anything still `UNVERIFIED` carries the exact command that would confirm it. Don't
treat an unverified claim as true; run the check first.

## This host, as of the last verification (2026-09-25)

| Item | Value |
|---|---|
| OS | Ubuntu 26.04.1 LTS, kernel `7.0.0-34-generic` |
| User | `dan` (uid 1000, primary group `dan`). **Was `danny-harris` before the 2026-09-22 reinstall** — any path or group name containing `danny-harris` (e.g. in a stale `local.properties`) is dead. |
| Repo checkout | `/home/dan/Projects/DroidThumb/droidthumb-android` (ext4, moved from the NTFS `Shared` partition 2026-09-25 — see "A full OS reinstall wipes all of this" below for what that changes) |
| Podman | 5.7.0 (apt, installed by the owner). The previous install ran 4.9.3. |
| git | 2.53.0 (apt) |
| gh | installed (apt) |
| make | **not installed** — needs `sudo apt install make`. Every `make` target fails until then; the `./gradlew` commands they wrap work without it. `act` and `mmdc` (node) are also absent. |
| JDK | Temurin 17.0.20.1+1 at `/home/dan/toolchain/jdk-17.0.20.1+1` (user-local, no sudo) |
| Android SDK | `/home/dan/toolchain/android-sdk` (user-local, no sudo) — cmdline-tools build 15859902, `platform-tools` (adb 37.0.1), `platforms;android-34`, `platforms;android-37.2`, `build-tools;34.0.0`, `build-tools;37.0.0` |
| adb | `/home/dan/toolchain/android-sdk/platform-tools/adb` — not on `PATH` by default |

The emulator and AVD system image were **not** reinstalled — this
redroid container replaces the AVD for manual debugging. Install them only if you actually need
an AVD (`sdkmanager emulator "system-images;android-34;google_apis;x86_64"`).

## One command to get a live device from cold

None. After a boot, `redroid.service` starts on its own.

**Verified 2026-09-24** across a real reboot: `dev-binderfs.mount` succeeded,
`/dev/binderfs` held `binder binder-control features hwbinder vndbinder`, and `redroid.service`
went `active (running)` at boot (18:57:17 +07) with no manual `systemctl start`. Android booted
fully inside it (zygote, system_server, launcher, adbd all running).

If it isn't running (`systemctl status redroid.service`), `sudo systemctl start redroid.service`.

## What persists across restarts and reboots

Android's `/data` is a **named podman volume, `redroid-data`** (`Volume=redroid-data:/data` in
the quadlet). Everything that lives there survives `systemctl restart`, a host reboot, and the
container being deleted and recreated: installed APKs, granted permissions (including the
accessibility-service enablement the app needs), battery-optimisation exemptions, the app's
DataStore settings, `settings` values, and files under `/data/local/tmp`. Build → `adb install
-r` once, and the device keeps it — you reinstall only when you have a new build.

Why it's needed: the Quadlet-generated unit runs the container with `--rm` and stops it with
`podman rm -v -f`, so the container object is thrown away on **every** stop. Before the volume
(until 2026-09-25) `/data` lived in that container layer and a reboot returned a factory-fresh
Android — no APK, no permissions, no app settings. That's also why this, and not "reinstall the
APK as part of bring-up", is the fix: the APK is the smallest part of what was lost, and a device
that forgets its state on restart can't show whether the app restores its own state after one
(e.g. `autoStartOnBoot`). Clean-slate testing is `e2e-tests`' job — its containers are ephemeral
by design.

**Verified 2026-09-25**, first on a throwaway container (same flags as the unit, host port
15555, volume `redroid-persist-test-data`, since deleted), then on the real unit (below). The
throwaway run: installed the APK, set
`settings put global droidthumb_persist_test 42`, wrote `/data/local/tmp/persist-marker`; then
`podman rm -v -f` (exactly the unit's `ExecStop`), confirmed the container was gone and the named
volume was not, and started a new container on the same volume. After boot: the package was
still installed with the same `firstInstallTime`, the setting read `42`, the marker was intact,
and the app launched (`Status: ok`). Inside the container, `/data` is the host's ext4
(`/dev/mapper/ubuntu--vg-ubuntu--lv on /data type ext4`).

**Verified 2026-09-25 on `redroid.service` itself** (by the owner, after installing the updated
quadlet): installed the APK, `sudo systemctl restart redroid.service`, and after boot
`adb -s localhost:5555 shell pm list packages | grep droidthumb` still listed
`uk.co.drhconsulting.droidthumb.gms.debug`.

### Resetting to a factory-fresh device

Deliberately destructive — wipes every app, permission and setting on the device:

```bash
sudo systemctl stop redroid.service
sudo podman volume rm redroid-data
sudo systemctl start redroid.service     # podman recreates the volume empty; Android does a first boot
```

Do this if the device gets into a bad state, **and after pulling a newer
`redroid/redroid:14.0.0-latest`**: the tag is mutable, and a `/data` written by one Android
build isn't guaranteed to boot cleanly under another. (The unit never pulls a newer image on its
own — podman only pulls when the image is missing — so this only arises when someone pulls
deliberately.)

### Known noise: the simulated Bluetooth HAL aborts at boot

Every boot logs 4 `Fatal signal 6 (SIGABRT)` entries in `adb logcat -b crash`, all Bluetooth:
`android.hardware.bluetooth@1.1-service.sim` aborting with `Invalid address: 3C:5A:B4:01:02:03`,
and `com.android.bluetooth`'s `bt_stack_manage` with it. The service is then restarted and stays
`running` (`getprop init.svc.vendor.bluetooth-1-1`). This is the stock redroid 14 image, **not**
the `/data` volume: checked 2026-09-25 by booting a fresh container with no volume beside one
with a persisted volume — both logged the same 4 aborts. Filter them out before concluding the
app crashed; the app's own crashes carry its package name.

## The adb address, and how to confirm it's up

Fixed port, chosen so the address never changes between container recreations:

```
localhost:5555
```

```bash
export PATH=$HOME/toolchain/android-sdk/platform-tools:$PATH
adb connect localhost:5555
adb -s localhost:5555 shell getprop sys.boot_completed   # expect: 1
```

**Verified 2026-09-24**: `connected to localhost:5555`, `sys.boot_completed` = `1`,
`ro.build.version.release` = `14`, `ro.product.model` = `Pixel_6`.

### The same device shows up twice in `adb devices`

```
emulator-5554   device product:redroid_x86_64 model:Pixel_6 ...
localhost:5555  device product:redroid_x86_64 model:Pixel_6 ...
```

These are **one device**, not two. The adb server scans local ports 5555–5585 for emulators and
registers anything answering on 5555 as `emulator-5554`, independent of the explicit
`adb connect`. Confirmed 2026-09-25 by writing a unique marker file via `localhost:5555` and
reading it back via `emulator-5554` (and not from a different container). Don't use
`/proc/sys/kernel/random/boot_id` to tell containers apart — it's the **host's** boot ID, the
same in every container on this kernel (an earlier version of this doc made that mistake).
Consequences:

- Always pass `-s localhost:5555` (or set `ANDROID_SERIAL=localhost:5555`); a bare `adb shell`
  fails with "more than one device".
- `emulator-5554` does **not** mean an AVD is running. There is no AVD on this host.
  `make stop-emulator` (`adb -s emulator-5554 emu kill`) talks to the emulator console port 5554,
  which nothing listens on, so it fails harmlessly (the Makefile swallows the error) — it will not
  stop this container. Use `sudo systemctl stop redroid.service` for that.

If `adb connect` fails, check the container is actually running first:

```bash
CONTAINER_HOST=unix:///run/podman/podman.sock podman ps --filter name=redroid   # as dan, via the socket
sudo podman ps --filter name=redroid                                          # or directly as root
```

Use `CONTAINER_HOST` (or `podman --remote --url unix:///run/podman/podman.sock`), **not**
`DOCKER_HOST` — the `podman` CLI ignores `DOCKER_HOST` and silently lists your *rootless*
storage instead, which is empty, so it looks as if the container isn't running. Checked
2026-09-25: `DOCKER_HOST=... podman ps` → nothing; `CONTAINER_HOST=... podman ps` → `redroid`.
(`DOCKER_HOST` is right for Testcontainers/docker-java in `e2e-tests`, which speak the Docker API.)

## Build, install, launch the debug APK

**Since 2026-09-25 the app is single-flavour** (the `gms`/`foss` split was merged into one build
using the `foss` battery-optimization behaviour — Play restricts the `gms` one-tap dialog anyway).
Gradle tasks and output paths below have no `gms`/`foss` component any more; the applicationId
changes from `uk.co.drhconsulting.droidthumb.gms.debug` to `uk.co.drhconsulting.droidthumb.debug`.
The two "Verified 2026-09-24/25" blocks below predate that merge and describe the old, two-flavour
paths accurately for their date — left as history, not to be copy-pasted today.

**One command** (recommended): `scripts/install-debug.sh -s localhost:5555` builds, installs,
re-enables the accessibility service and the other special-access/runtime permissions (a plain
reinstall clears `enabled_accessibility_services` for the app), and launches. See the script's
`--help` and `docs/PERMISSIONS.md` for what it grants and why. From the repo root:

```bash
export JAVA_HOME=$HOME/toolchain/jdk-17.0.20.1+1
scripts/install-debug.sh -s localhost:5555
```

Or step by step:

```bash
export JAVA_HOME=$HOME/toolchain/jdk-17.0.20.1+1
./gradlew assembleDebug
```

**Verified 2026-09-24** on this host (pre-merge, `gms` flavour): `BUILD SUCCESSFUL in 16m 48s` — a
cold build (empty `~/.gradle` after the reinstall, so the Gradle 9.7.1 distribution and every
dependency were downloaded). Expect a warm rebuild to be far quicker. Real resolved values, read
from the built APK with `aapt dump badging` (not asserted from the Gradle config), as of that date:

- **Variant** (pre-merge): `gmsDebug` (Gradle task `assembleGmsDebug`); now just `assembleDebug`.
- **Artifact**: `app/build/outputs/apk/debug/app-debug.apk` (pre-merge: `apk/gms/debug/app-gms-debug.apk`, ≈273MB)
- **applicationId**: `uk.co.drhconsulting.droidthumb.debug` (pre-merge: `…droidthumb.gms.debug`)
- **versionName**: `1.12.0-dev.55+ab45f12` (git-derived, so it changes per commit)
- **Main activity**: `com.danielealbano.androidremotecontrolmcp.ui.MainActivity` (namespace
  unchanged from the applicationId — see `docs/module-map.md` and `docs/build-notes.md` for why
  those two deliberately diverge)

```bash
adb -s localhost:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s localhost:5555 shell am start -W -n uk.co.drhconsulting.droidthumb.debug/com.danielealbano.androidremotecontrolmcp.ui.MainActivity
```

**Verified 2026-09-24** against this container (pre-merge): install → `Success`; launch →
`Status: ok`, `LaunchState: COLD`, `TotalTime: 1002` ms; `dumpsys activity activities` shows
`MainActivity` as `topResumedActivity`; the process was still alive afterwards with the crash
buffer (`adb logcat -d -b crash`) empty. The screenshot showed the Server screen as expected on a
fresh install: "Accessibility permission required", MCP Server and Event Channel both "Stopped".

**Re-verified 2026-09-25 after the demolition pass** (pre-merge, still `gmsDebug`): the previous
install was removed with `adb uninstall` (its stored settings no longer match the app), the new
APK installed and launched cold with an empty crash buffer. Enabling the accessibility service with
`settings put secure enabled_accessibility_services …` bound it (`dumpsys accessibility`: bound and
enabled; logcat: `Accessibility service connected`), and after the activity resumed the home screen
dropped the accessibility callout; Settings → Permissions shows Accessibility Service as Enabled.

Install once per new build — the APK and anything you grant it persist across restarts and
reboots (see "What persists across restarts and reboots").

**Verified 2026-09-25 after the flavour merge**, via `scripts/install-debug.sh -s localhost:5555`:
build → install (`Success`) → grant accessibility, notification listener and
`POST_NOTIFICATIONS` → launch (`Status: ok`, `LaunchState: WARM`). `dumpsys accessibility` showed
`McpAccessibilityService` bound and enabled under the new `uk.co.drhconsulting.droidthumb.debug`
id, with the foreground window titled "DroidThumb" (confirms the `app_name` rename reached the
device). `settings get secure enabled_notification_listeners` included the notification listener
component. The stale pre-merge `uk.co.drhconsulting.droidthumb.gms.debug` install left on this
container from the 09-24 runs above was then removed with `adb uninstall`.

## Logcat filtered to the app's package

```bash
adb -s localhost:5555 logcat --pid=$(adb -s localhost:5555 shell pidof -s uk.co.drhconsulting.droidthumb.debug)
```

If the app isn't running yet, `pidof` returns nothing and this will just hang waiting for a PID —
launch the app first.

## Screenshot and view-hierarchy dump

```bash
# Screenshot
adb -s localhost:5555 exec-out screencap -p > screenshot.png

# View hierarchy (uiautomator)
adb -s localhost:5555 shell uiautomator dump /sdcard/uidump.xml
adb -s localhost:5555 pull /sdcard/uidump.xml
```

## Running the e2e suite

There is no e2e suite any more: `e2e-tests` drove the removed on-device MCP server and was deleted in
the demolition pass. Its replacement, a fake-relay harness, comes with the outbound transport
(plan 66, US-9). For the record, its last run on this host (2026-09-25, before the demolition) was
92 tests, 78 passed, 0 failed, 14 skipped, with this persistent container running alongside.

## Limits — what this device cannot tell you

- **No Doze, no OEM power management.** redroid doesn't implement Android's power-management
  stack the way a real device (or even most emulators) does. Background-execution and
  battery-optimisation behaviour cannot be validated here.
- **No carrier NAT.** Networking is whatever the container's network namespace gives it — nothing
  about real-world carrier NAT/CGNAT behaviour is represented.
- **Play Integrity fails on redroid**, same as it fails on the AVD emulator (see
  `docs/build-notes.md`, Appendix B of the design doc). Anything depending on Play Integrity needs
  a real device.
- **This device cannot answer whether a socket survives overnight.** It's one container, one
  process tree, evaluated per-session. Long-running connection-survival questions need a real
  device or a purpose-built longevity test, not this.
- More generally: this is a container running Android userspace on the host kernel, not a full
  virtualized phone. Anything that depends on real hardware behaviour (radios, sensors, thermal
  throttling, actual battery) is out of scope for what this device can validate.

## A full OS reinstall wipes all of this — recovery sequence

**This has happened twice.** Everything in "Host setup" below lives on the root filesystem
(`/etc`, `/home/dan`, podman's image store under `/var/lib/containers`), and a reinstall of
Ubuntu replaces that filesystem wholesale. `/home` is **not** a separate partition on this host
(`df -h /home/dan` and `/` both resolve to the same `ubuntu-vg/ubuntu-lv` volume) — so as of the
2026-09-25 move off the NTFS `Shared` partition, **this repo checkout no longer survives a
reinstall.** Before, the checkout (including `scripts/redroid/redroid.container` and this doc)
lived on the separate NTFS partition and came back for free; now recovery step 0 below is
re-cloning it from `origin`. Everything lost, every time (this item is new since the move):

- apt packages: `git`, `podman`, `make` (and any container images pulled into rootful storage)
- `/etc/modules-load.d/binder.conf`, `/etc/modprobe.d/binder.conf`
- the binderfs line in `/etc/fstab` (the installer writes a fresh fstab)
- `/etc/systemd/system/podman.socket.d/override.conf` and `/etc/tmpfiles.d/podman.conf`
- `/etc/containers/systemd/redroid.container`
- the `redroid-data` volume, i.e. everything installed or configured on the debug device
  (it lives under `/var/lib/containers`)
- everything under `/home/dan`: the JDK, the Android SDK, `~/.gradle` (≈1GB of dependency
  cache — the first build afterwards is a cold one and takes well over 10 minutes), `~/.gitconfig`
  (git identity), and any GitHub credentials
- the username itself, if a different one is chosen at install time (it was: `danny-harris` →
  `dan`)
- **new since 2026-09-25:** the repo checkout itself (`/home/dan/Projects/DroidThumb/`). Committed
  history is safe on `origin` — re-clone it — but anything uncommitted, stashed, or in
  `.git/config` (e.g. `user.name`/`user.email`) is gone

Don't trust a feeling that "the /etc files are still there" — check. After the 2026-09-22
reinstall they had all gone. The quickest audit:

```bash
grep binder /etc/fstab; ls /etc/modules-load.d/binder.conf /etc/modprobe.d/binder.conf \
  /etc/containers/systemd/redroid.container /etc/systemd/system/podman.socket.d/override.conf \
  /etc/tmpfiles.d/podman.conf
command -v git podman make; ls ~/toolchain
```

Recovery, in order. Steps 1–5 need sudo; 0, 6–9 don't.

0. Re-clone the repo (it no longer survives a reinstall — see above):
   `git clone https://github.com/DroidThumb/droidthumb-android.git ~/Projects/DroidThumb/droidthumb-android`.
1. `sudo apt install git podman make`
2. Recreate the two binder module files (contents under "Kernel module persistence").
3. Append the binderfs line to `/etc/fstab` (under "binderfs mount") — the **fixed** one with
   `x-systemd.after=`, not the original.
4. Recreate the podman socket override (under "Podman socket permissions"), with the **current**
   username as the group, plus the tmpfiles override described there.
5. Pull the image, install the quadlet, reload:
   ```bash
   sudo podman pull docker.io/redroid/redroid:14.0.0-latest
   sudo mkdir -p /etc/containers/systemd
   sudo cp scripts/redroid/redroid.container /etc/containers/systemd/redroid.container
   sudo systemctl daemon-reload
   ```
   With the fully qualified `Image=` now in the quadlet (see "Registry prefix" below), the
   service should pull on its own at first start, so the explicit pull is belt-and-braces: it
   keeps an ≈828MB download out of the unit's 300s start timeout. `UNVERIFIED`: a first start
   with **no** local image has not been exercised — the image was already present when the
   qualified name went in.
6. Reboot. This is the real test: after it, `ls /dev/binderfs` must show `binder-control` et al.
   and `systemctl status redroid.service` must be active with nothing started by hand.
7. Toolchain, user-local (see "Toolchain" below).
8. Point `local.properties` at the new SDK path (it's gitignored, and the checkout itself is
   gone per step 0, so this is a fresh write, not an edit of a stale one):
   `echo "sdk.dir=/home/dan/toolchain/android-sdk" > local.properties`
9. Git: `core.fileMode` should stay at its default `true` (see "Git and file modes" below — this
   repo is on ext4 now, not NTFS); set `user.name`/`user.email` and GitHub credentials again
   before committing/pushing.

Then work through "The adb address…" and "Build, install, launch…" above.

## Host setup

Everything below lives outside the repo (system config, root-owned), except the quadlet unit
itself, which is checked in at `scripts/redroid/redroid.container` and gets copied into place.
Check what's actually live on the machine (commands given per-item below) before assuming this
description is still accurate.

### Toolchain (user-local, no sudo)

Same method as `docs/build-notes.md` records for the first install, re-run 2026-09-24:

```bash
mkdir -p ~/toolchain && cd ~/toolchain
curl -fsSL -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk17.tar.gz && rm jdk17.tar.gz          # -> ~/toolchain/jdk-17.<version>
export JAVA_HOME=$(echo ~/toolchain/jdk-17*)

# cmdline-tools: resolve the current zip name, don't hardcode the build number
curl -fsSL https://developer.android.com/studio | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | sort -u
SDK=~/toolchain/android-sdk; mkdir -p $SDK/cmdline-tools && cd $SDK/cmdline-tools
curl -fsSLO https://dl.google.com/android/repository/<zip name from above>
unzip <zip> && mv cmdline-tools latest && rm <zip>   # the zip unpacks as cmdline-tools/, sdkmanager wants latest/

SM=$SDK/cmdline-tools/latest/bin/sdkmanager
yes | $SM --sdk_root=$SDK --licenses
$SM --sdk_root=$SDK platform-tools "platforms;android-34" "platforms;android-37.2" "build-tools;34.0.0" "build-tools;37.0.0"
```

`compileSdk = 37` needs `platforms;android-37.<n>` — there is no bare `platforms;android-37`
package (gotcha #1 in `docs/build-notes.md`). If `unzip` is missing,
`python3 -c "import zipfile; zipfile.ZipFile('<zip>').extractall('.')"` works without sudo, but
then `chmod +x latest/bin/*` because Python's extractor drops the exec bits.

### Git and file modes

**History, for anyone who finds a `core.fileMode false` note stale:** the checkout used to live on
an `ntfs3` mount with no `fmask`, where every file read back as mode 755 — with git's default
`core.fileMode=true`, a fresh `git status` there showed **every tracked file as modified** (≈659
files, all `old mode 100644 / new mode 100755`, zero content changes), and `core.fileMode false`
was the workaround. As of the 2026-09-25 move to `~/Projects/DroidThumb/droidthumb-android` (ext4),
file modes are real again and `core.fileMode` should stay at its **default, `true`** — do not set
it to `false` here. If a fresh checkout on this host ever shows every file modified again, the
first suspect is a mode bit that didn't restore correctly (e.g. copied from an NTFS source rather
than cloned), not the filesystem itself.

### Kernel module persistence

`/etc/modules-load.d/binder.conf`:
```
binder_linux
```

`/etc/modprobe.d/binder.conf`:
```
options binder_linux devices=binder,hwbinder,vndbinder
```

**Verified 2026-09-24**: `binder_linux` loaded at boot and
`/sys/module/binder_linux/parameters/devices` reads back `binder,hwbinder,vndbinder`. The stock
Ubuntu kernel ships the module (`drivers/android/binder_linux.ko.zst`); no DKMS needed.

### binderfs mount

`/etc/fstab`:
```
none /dev/binderfs binder defaults,nofail,x-systemd.after=systemd-modules-load.service,x-systemd.mkdir 0 0
```

**Verified 2026-09-24** across a reboot:

```
$ mount | grep binderfs
none on /dev/binderfs type binder (rw,relatime,max=1048576,x-systemd.after=systemd-modules-load.service,x-systemd.mkdir)
$ ls /dev/binderfs
binder  binder-control  features  hwbinder  vndbinder
```

**History, so this isn't rediscovered as a surprise**: the first version of this fstab entry
(`none /dev/binderfs binder defaults 0 0`, no ordering constraint) came back **empty** after a
reboot — not even `binder-control` — because the fstab-generated mount unit raced the kernel
module load and lost:

```
Sep 20 21:20:42 ... systemd[1]: Mounting dev-binderfs.mount - /dev/binderfs...
Sep 20 21:20:42 ... mount[407]: mount: /dev/binderfs: unknown filesystem type 'binder'.
Sep 20 21:20:42 ... systemd-modules-load[428]: Inserted module 'binder_linux'
```

`local-fs.target` (which pulls in fstab mounts) isn't ordered relative to
`systemd-modules-load.service` by default — a plain `defaults`-only fstab entry gives systemd no
reason to wait. `x-systemd.after=systemd-modules-load.service` forces that ordering, and that is
what fixed it.

If `/dev/binderfs` is ever empty after a reboot, don't paper over it with a manual
`mount -t binder binder /dev/binderfs`; that defeats the entire point of this setup (nothing
manual after boot). Go to `journalctl -b -u dev-binderfs.mount` and
`journalctl -b -u systemd-modules-load.service` and re-diagnose the ordering.

### Podman socket permissions

`/etc/systemd/system/podman.socket.d/override.conf` as installed 2026-09-24:
```
[Socket]
SocketGroup=dan
SocketMode=0660
```

The group must be the **current** username's group — the pre-reinstall version said
`danny-harris`, which no longer exists.

On its own, that override is **not enough** on this host. The socket is group-accessible, but
its parent directory is not — as found on 2026-09-24:

```
$ ls -ld /run/podman
drwx------ 2 root root 100 Sep 24 18:57 /run/podman
$ ls -l /run/podman/podman.sock
ls: cannot open file '/run/podman/podman.sock': Permission denied
```

Root cause: podman's own packaged `/usr/lib/tmpfiles.d/podman.conf` contains
`D! /run/podman 0700 root root`, which systemd-tmpfiles applies at boot. No `SocketGroup=` on the
socket can get past a 0700 root-owned directory. (The pre-reinstall doc recorded the socket
override as sufficient on podman 4.9.3; on 5.7.0 as packaged by Ubuntu 26.04 it is not.)

The fix: override the packaged tmpfiles file with a same-named one in `/etc/tmpfiles.d/` (which
replaces the `/usr/lib` one entirely, so copy it and change only the `/run/podman` line):

```bash
sudo cp /usr/lib/tmpfiles.d/podman.conf /etc/tmpfiles.d/podman.conf
sudo sed -i 's|^D! /run/podman 0700 root root$|D! /run/podman 0750 root dan|' /etc/tmpfiles.d/podman.conf
```

Applied by the owner on 2026-09-24; `diff /usr/lib/tmpfiles.d/podman.conf /etc/tmpfiles.d/podman.conf`
shows that single line changed. **The `!` means boot-only**: `systemd-tmpfiles --create` without
`--boot` skips `D!` lines, so it did nothing until the next boot. For the current boot the owner
applied the same result by hand (`chgrp dan /run/podman && chmod 0750 /run/podman`).

**Verified 2026-09-25, current boot (hand-applied permissions)**, as `dan` with no sudo:

```
$ ls -ld /run/podman; ls -l /run/podman/podman.sock
drwxr-x--- 2 root dan 100 Sep 24 18:57 /run/podman
srw-rw---- 1 root dan 0 Sep 24 18:57 /run/podman/podman.sock
$ curl -s --unix-socket /run/podman/podman.sock http://d/v4.0.0/libpod/_ping
OK
$ podman --remote --url unix:///run/podman/podman.sock ps
CONTAINER ID  IMAGE                                    ...  PORTS                                           NAMES
7171edb251f9  docker.io/redroid/redroid:14.0.0-latest  ...  0.0.0.0:5555->5555/tcp, 0.0.0.0:8080->8080/tcp  redroid
```

`UNVERIFIED`: that the `/etc/tmpfiles.d/podman.conf` line produces the same result **by itself
at boot**, with nothing applied by hand. Confirm after the next reboot with the same `ls -ld` and
`_ping` commands above. If `/run/podman` is back to `drwx------ root root`, the tmpfiles override
didn't take — check `systemd-tmpfiles --cat-config | grep run/podman` to see which line won.

This only matters for tools that talk to the Docker-compatible REST API over the socket (e.g.
`e2e-tests`' Testcontainers usage via `DOCKER_HOST=unix:///run/podman/podman.sock`). The quadlet
unit below does **not** go through the socket — Quadlet-generated services invoke the local
`podman` binary directly, so `redroid.service` has no dependency on `podman.socket` being up
(which is why redroid works today despite the socket being unreachable).

### Registry prefix: why the image must be pulled fully qualified

`scripts/redroid/redroid.container` now says `Image=docker.io/redroid/redroid:14.0.0-latest`.
Until 2026-09-25 it said `Image=redroid/redroid:14.0.0-latest` — a **short name**, no registry —
and on this host that worked only by accident of what was already in local storage:

- Ubuntu 26.04's `/etc/containers/registries.conf` defines **no** `unqualified-search-registries`,
  and `/etc/containers/registries.conf.d/shortnames.conf` has **no** alias for `redroid/*`.
- So podman cannot resolve the short name to a registry to pull from. Verified 2026-09-24 with a
  tag that doesn't exist locally:
  ```
  $ podman pull redroid/redroid:no-such-tag-xyz
  Error: short-name "redroid/redroid:no-such-tag-xyz" did not resolve to an alias and no unqualified-search registries are defined in "/etc/containers/registries.conf"
  ```
- It does work when the image is **already present locally**: podman normalises the short name to
  `docker.io/redroid/redroid:14.0.0-latest` for the local-storage lookup, finds the image the
  owner pulled with its full name, and never needs to pull. That's why `podman ps` shows the
  running container's image as `docker.io/redroid/redroid:14.0.0-latest` even though the quadlet
  doesn't say `docker.io/`.

Consequence: on a fresh host, the short-name quadlet would have left `redroid.service` failing
at start with the error above unless the image had been pulled by its full name first. Hence the
fully qualified `Image=`, which removes that dependency. The running container is unaffected —
same image, same image ID.

Don't shorten it again. `e2e-tests` still asks for `redroid/redroid:14.0.0-latest` by short name
(`AndroidContainerSetup.kt`), but that goes through the Docker-compatible API, which always
resolves bare names against `docker.io` like Docker does, so it's not affected by the above.

### The quadlet unit

Source of truth: `scripts/redroid/redroid.container` (checked into this repo, see the file for
the full annotated content — what each option replicates from `AndroidContainerSetup.kt`, and why
a wildcard device-cgroup rule replaces that file's dynamically-detected major/minor numbers).

Install:
```bash
sudo mkdir -p /etc/containers/systemd
sudo cp scripts/redroid/redroid.container /etc/containers/systemd/redroid.container
sudo systemctl daemon-reload
```

That `daemon-reload` both generates `redroid.service` from the quadlet file **and** applies its
`[Install]` section (`WantedBy=multi-user.target`) — Quadlet-generated units don't need a separate
`systemctl enable`; that's a real difference from a hand-written unit, not an oversight here.
**Verified 2026-09-24**: the service came up at boot with no `enable` ever run.

The installed copy was byte-identical to the repo file on 2026-09-24
(`diff /etc/containers/systemd/redroid.container scripts/redroid/redroid.container` → no output).
Re-run that diff after editing either one.

Fixed name: **`redroid`**. Fixed ports: **5555** (adb) and **8080** (was the on-device MCP server;
unused since the demolition pass, still published by the quadlet). `podman ps` on 2026-09-24: `0.0.0.0:5555->5555/tcp, 0.0.0.0:8080->8080/tcp`.

### Design choice: quadlet over `podman generate systemd`

The task allowed either. Chose Quadlet because:

1. It's Podman's current recommended mechanism since 4.4 (this host now runs 5.7.0, where
   `podman generate systemd` is deprecated outright) — the older, more manual path it superseded.
2. It's declarative — the `.container` file *is* the source of truth, versionable in this repo,
   rather than a unit file generated once from a running container's already-baked config (which
   is how `podman generate systemd --new` works, and which would have baked in this machine's
   *current* binder/fuse major:minor numbers rather than staying correct if those ever change).

The one place this pushed back: Quadlet has no native directive for a dynamic device-cgroup rule
(nothing can shell out to detect a number at unit-generation time), which is exactly what
`AndroidContainerSetup.kt` does per-run. Resolved with a static wildcard rule instead — see the
comment in `scripts/redroid/redroid.container` for the full reasoning.
