# Persistent debug device (redroid)

A always-on Android device for manual debugging and quick checks, running as a rootful Podman
container (`docker.io/redroid/redroid:14.0.0-latest`) managed by systemd. Distinct from
`e2e-tests`, which creates and tears down its own short-lived container per test run via
Testcontainers — this one is meant to just sit there, reachable over adb, so you don't pay a
multi-minute boot cost every time you want to poke at the app.

**Read this whole doc before touching anything here.** Claims are marked with when and how they
were verified; anything still `UNVERIFIED` carries the exact command that would confirm it. Don't
treat an unverified claim as true; run the check first.

## This host, as of the last verification (2026-09-24)

| Item | Value |
|---|---|
| OS | Ubuntu 26.04.1 LTS, kernel `7.0.0-34-generic` |
| User | `dan` (uid 1000, primary group `dan`). **Was `danny-harris` before the 2026-09-22 reinstall** — any path or group name containing `danny-harris` (here, in `docs/build-notes.md`, or in a stale `local.properties`) is dead. |
| Repo checkout | `/run/media/dan/Shared/Projects/DroidThumb/droidthumb-android` (NTFS partition — see "Git on the NTFS partition" below) |
| Podman | 5.7.0 (apt, installed by the owner). `docs/build-notes.md` records 4.9.3 from the previous install — that number is historical. |
| git | 2.53.0 (apt) |
| JDK | Temurin 17.0.20.1+1 at `/home/dan/toolchain/jdk-17.0.20.1+1` (user-local, no sudo) |
| Android SDK | `/home/dan/toolchain/android-sdk` (user-local, no sudo) — cmdline-tools build 15859902, `platform-tools` (adb 37.0.1), `platforms;android-34`, `platforms;android-37.2`, `build-tools;34.0.0`, `build-tools;37.0.0` |
| adb | `/home/dan/toolchain/android-sdk/platform-tools/adb` — not on `PATH` by default |

The emulator and AVD system image listed in `docs/build-notes.md` were **not** reinstalled — this
redroid container replaces the AVD for manual debugging. Install them only if you actually need
an AVD (`sdkmanager emulator "system-images;android-34;google_apis;x86_64"`).

## One command to get a live device from cold

None. After a boot, `redroid.service` starts on its own.

**Verified 2026-09-24** across a real reboot: `dev-binderfs.mount` succeeded,
`/dev/binderfs` held `binder binder-control features hwbinder vndbinder`, and `redroid.service`
went `active (running)` at boot (18:57:17 +07) with no manual `systemctl start`. Android booted
fully inside it (zygote, system_server, launcher, adbd all running).

If it isn't running (`systemctl status redroid.service`), `sudo systemctl start redroid.service`.

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
`adb connect`. Confirmed by both serials returning the same `/proc/sys/kernel/random/boot_id`.
Consequences:

- Always pass `-s localhost:5555` (or set `ANDROID_SERIAL=localhost:5555`); a bare `adb shell`
  fails with "more than one device".
- `emulator-5554` does **not** mean an AVD is running. There is no AVD on this host.
  `make stop-emulator` (`adb -s emulator-5554 emu kill`) talks to the emulator console port 5554,
  which nothing listens on, so it fails harmlessly (the Makefile swallows the error) — it will not
  stop this container. Use `sudo systemctl stop redroid.service` for that.

If `adb connect` fails, check the container is actually running first:

```bash
sudo podman ps --filter name=redroid
```

(`DOCKER_HOST=unix:///run/podman/podman.sock podman ps` does **not** currently work as `dan` —
see "Podman socket permissions" below.)

## Build, install, launch the debug APK

Variant: **`gmsDebug`** (matches what `e2e-tests` builds against; see `build.gradle.kts` for the
`gms`/`foss` flavour split).

```bash
export JAVA_HOME=$HOME/toolchain/jdk-17.0.20.1+1
./gradlew assembleGmsDebug
```

**Verified 2026-09-24** on this host: `BUILD SUCCESSFUL in 16m 48s` — a cold build (empty
`~/.gradle` after the reinstall, so the Gradle 9.7.1 distribution and every dependency were
downloaded). Expect a warm rebuild to be far quicker. Real resolved values, read from the built
APK with `aapt dump badging` (not asserted from the Gradle config):

- **Variant**: `gmsDebug` (Gradle task `assembleGmsDebug`)
- **Artifact**: `app/build/outputs/apk/gms/debug/app-gms-debug.apk` (≈273MB)
- **applicationId**: `uk.co.drhconsulting.droidthumb.gms.debug`
- **versionName**: `1.12.0-dev.55+ab45f12` (git-derived, so it changes per commit)
- **Main activity**: `com.danielealbano.androidremotecontrolmcp.ui.MainActivity` (namespace
  unchanged from the applicationId — see `docs/module-map.md` and `docs/build-notes.md` for why
  those two deliberately diverge)

```bash
adb -s localhost:5555 install -r app/build/outputs/apk/gms/debug/app-gms-debug.apk
adb -s localhost:5555 shell am start -W -n uk.co.drhconsulting.droidthumb.gms.debug/com.danielealbano.androidremotecontrolmcp.ui.MainActivity
```

**Verified 2026-09-24** against this container: install → `Success`; launch →
`Status: ok`, `LaunchState: COLD`, `TotalTime: 1002` ms; `dumpsys activity activities` shows
`MainActivity` as `topResumedActivity`; the process was still alive afterwards with the crash
buffer (`adb logcat -d -b crash`) empty. The screenshot showed the Server screen as expected on a
fresh install: "Accessibility permission required", MCP Server and Event Channel both "Stopped".

## Logcat filtered to the app's package

```bash
adb -s localhost:5555 logcat --pid=$(adb -s localhost:5555 shell pidof -s uk.co.drhconsulting.droidthumb.gms.debug)
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

**Important distinction**: `e2e-tests` does **not** use this persistent container. It creates its
own separate, short-lived redroid container via Testcontainers every run (see
`AndroidContainerSetup.kt` and `docs/build-notes.md`) and tears it down after. This device and the
e2e suite's container are independent.

```bash
DOCKER_HOST=unix:///run/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :e2e-tests:test
```

**Currently blocked on this host**: Testcontainers talks to the rootful podman socket, which `dan`
cannot reach after the reinstall — see "Podman socket permissions" below. Not attempted since the
reinstall.

`UNVERIFIED`: running `e2e-tests` while this persistent container is also up hasn't been tried.
Two redroid containers (8GB cap each) should fit this machine's 30GB RAM, but CPU contention and
podman/network port allocation with both running simultaneously is untested. If you hit problems,
stop this container first (`sudo systemctl stop redroid.service`) and retry.

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
Ubuntu replaces that filesystem wholesale. What survives is only what is on the separate NTFS
`Shared` partition: this repo (including `scripts/redroid/redroid.container` and this doc) and
its `build/` outputs. What is lost, every time:

- apt packages: `git`, `podman` (and any container images pulled into rootful storage)
- `/etc/modules-load.d/binder.conf`, `/etc/modprobe.d/binder.conf`
- the binderfs line in `/etc/fstab` (the installer writes a fresh fstab)
- `/etc/systemd/system/podman.socket.d/override.conf`
- `/etc/containers/systemd/redroid.container`
- everything under `/home/dan`: the JDK, the Android SDK, `~/.gradle` (≈1GB of dependency
  cache — the first build afterwards is a cold one and takes well over 10 minutes), `~/.gitconfig`
  (git identity), and any GitHub credentials
- the username itself, if a different one is chosen at install time (it was: `danny-harris` →
  `dan`)

Don't trust a feeling that "the /etc files are still there" — check. After the 2026-09-22
reinstall they had all gone. The quickest audit:

```bash
grep binder /etc/fstab; ls /etc/modules-load.d/binder.conf /etc/modprobe.d/binder.conf \
  /etc/containers/systemd/redroid.container /etc/systemd/system/podman.socket.d/override.conf
command -v git podman; ls ~/toolchain
```

Recovery, in order. Steps 1–5 need sudo; 6–9 don't.

1. `sudo apt install git podman`
2. Recreate the two binder module files (contents under "Kernel module persistence").
3. Append the binderfs line to `/etc/fstab` (under "binderfs mount") — the **fixed** one with
   `x-systemd.after=`, not the original.
4. Recreate the podman socket override (under "Podman socket permissions"), with the **current**
   username as the group, plus the tmpfiles override described there.
5. Pull the image fully qualified, install the quadlet, reload:
   ```bash
   sudo podman pull docker.io/redroid/redroid:14.0.0-latest
   sudo mkdir -p /etc/containers/systemd
   sudo cp scripts/redroid/redroid.container /etc/containers/systemd/redroid.container
   sudo systemctl daemon-reload
   ```
   The explicit pull matters — see "Registry prefix" below.
6. Reboot. This is the real test: after it, `ls /dev/binderfs` must show `binder-control` et al.
   and `systemctl status redroid.service` must be active with nothing started by hand.
7. Toolchain, user-local (see "Toolchain" below).
8. Point `local.properties` at the new SDK path (it's gitignored, so it survives on the NTFS
   partition with the **old** path in it):
   `echo "sdk.dir=/home/dan/toolchain/android-sdk" > local.properties`
9. Git: `git config core.fileMode false` (see below), and set `user.name`/`user.email` and
   GitHub credentials again before committing/pushing.

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

### Git on the NTFS partition

The checkout lives on an `ntfs3` mount with no `fmask`, so every file reads back as mode 755. With
git's default `core.fileMode=true`, a fresh `git status` after a reinstall shows **every tracked
file as modified** (≈659 files, all `old mode 100644 / new mode 100755`, zero content changes).
That's not real work — set `git config core.fileMode false` (repo-local, lives in `.git/config`,
which is on NTFS and so survives the next reinstall too). Never commit those mode flips.

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

**Not working on this host as of 2026-09-24.** The socket itself is group-accessible, but its
parent directory is not:

```
$ ls -ld /run/podman
drwx------ 2 root root 100 Sep 24 18:57 /run/podman
$ ls -l /run/podman/podman.sock
ls: cannot open file '/run/podman/podman.sock': Permission denied
```

Root cause: podman's own packaged `/usr/lib/tmpfiles.d/podman.conf` contains
`D! /run/podman 0700 root root`, which systemd-tmpfiles applies at boot. No `SocketGroup=` on the
socket can get past a 0700 root-owned directory. (The pre-reinstall doc recorded this override as
sufficient on podman 4.9.3; on 5.7.0 as packaged by Ubuntu 26.04 it is not.)

Proposed fix — `UNVERIFIED`, needs sudo, not yet applied. Override the packaged tmpfiles file by
creating one with the same name in `/etc/tmpfiles.d/` (a same-named file there replaces the
`/usr/lib` one entirely, so copy it and change only the `/run/podman` line):

```bash
sudo cp /usr/lib/tmpfiles.d/podman.conf /etc/tmpfiles.d/podman.conf
sudo sed -i 's|^D! /run/podman 0700 root root$|D! /run/podman 0750 root dan|' /etc/tmpfiles.d/podman.conf
```

Then reboot, and confirm as `dan` with no sudo:
`curl -s --unix-socket /run/podman/podman.sock http://d/v4.0.0/libpod/_ping` → `OK`.

This only matters for tools that talk to the Docker-compatible REST API over the socket (e.g.
`e2e-tests`' Testcontainers usage via `DOCKER_HOST=unix:///run/podman/podman.sock`). The quadlet
unit below does **not** go through the socket — Quadlet-generated services invoke the local
`podman` binary directly, so `redroid.service` has no dependency on `podman.socket` being up
(which is why redroid works today despite the socket being unreachable).

### Registry prefix: why the image must be pulled fully qualified

`scripts/redroid/redroid.container` says `Image=redroid/redroid:14.0.0-latest` — a **short
name**, no registry. On this host that works only by accident of what's already in local storage:

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

Consequence: on a fresh host, installing the quadlet **without** first running
`sudo podman pull docker.io/redroid/redroid:14.0.0-latest` leaves `redroid.service` failing at
start with the error above. Hence the explicit pull in the recovery sequence.

Proposed, not applied (it's a change to the checked-in unit, and changing the installed copy needs
sudo): make the quadlet say `Image=docker.io/redroid/redroid:14.0.0-latest`, which removes the
dependency on a pre-pull entirely. The running container would be unaffected — same image ID.

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

Fixed name: **`redroid`**. Fixed ports: **5555** (adb), **8080** (MCP server, once started
on-device). `podman ps` on 2026-09-24: `0.0.0.0:5555->5555/tcp, 0.0.0.0:8080->8080/tcp`.

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
