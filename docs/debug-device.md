# Persistent debug device (redroid)

A always-on Android device for manual debugging and quick checks, running as a rootful Podman
container (`redroid/redroid:14.0.0-latest`) managed by systemd. Distinct from `e2e-tests`, which
creates and tears down its own short-lived container per test run via Testcontainers — this one
is meant to just sit there, reachable over adb, so you don't pay a multi-minute boot cost every
time you want to poke at the app.

**Read this whole doc before touching anything here.** It was written by a session that set this
up but never got to see it run — several claims below are marked `UNVERIFIED` with the exact
command that would confirm them. Don't treat an unverified claim as true; run the check first.

## One command to get a live device from cold

```bash
sudo systemctl start redroid.service
```

That's it, if the host setup below (kernel modules, binderfs, the quadlet unit) is in place —
which after a reboot it should be, automatically, with no manual step. `UNVERIFIED`: this exact
chain (reboot → `dev-binderfs.mount` succeeds → `redroid.service` starts on its own, no manual
`systemctl start` needed at all) has not been observed. See "Host setup" below for exactly what's
confirmed vs. still pending on this machine as of this writing.

If it's already running (check with `systemctl status redroid.service`), you don't need to do
anything.

## The adb address, and how to confirm it's up

Fixed port, chosen so the address never changes between container recreations:

```
localhost:5555
```

```bash
adb connect localhost:5555
adb -s localhost:5555 shell getprop sys.boot_completed   # expect: 1
```

`UNVERIFIED`: no instance of this specific quadlet-managed container has been started yet, so
this hasn't been exercised end-to-end. The underlying redroid image/boot config is the same one
`e2e-tests` exercises successfully dozens of times per run (see `docs/build-notes.md`) — what's
untested here specifically is the systemd/quadlet wiring around it, not redroid itself.

If `adb connect` fails, check the container is actually running first:

```bash
DOCKER_HOST=unix:///run/podman/podman.sock podman ps --filter name=redroid
```

## Build, install, launch the debug APK

Variant: **`gmsDebug`** (matches what `e2e-tests` builds against; see `build.gradle.kts` for the
`gms`/`foss` flavour split).

```bash
./gradlew assembleGmsDebug
```

Confirmed working on this machine just now: `BUILD SUCCESSFUL in 32s`. Real resolved values, read
from the built APK with `aapt dump badging` (not asserted from the Gradle config):

- **Artifact**: `app/build/outputs/apk/gms/debug/app-gms-debug.apk`
- **applicationId**: `uk.co.drhconsulting.droidthumb.gms.debug`
- **Main activity**: `com.danielealbano.androidremotecontrolmcp.ui.MainActivity` (namespace
  unchanged from the applicationId — see `docs/module-map.md` and `docs/build-notes.md` for why
  those two deliberately diverge)

```bash
adb -s localhost:5555 install -r app/build/outputs/apk/gms/debug/app-gms-debug.apk
adb -s localhost:5555 shell am start -n uk.co.drhconsulting.droidthumb.gms.debug/com.danielealbano.androidremotecontrolmcp.ui.MainActivity
```

`UNVERIFIED` against this persistent container specifically — no live instance to install onto
yet. The install/launch commands themselves (same applicationId, same activity) were confirmed
working against a separate AVD-based device in `docs/build-notes.md`, so the commands are right;
what's unverified is running them against *this* redroid container.

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
e2e suite's container are independent — you can leave this one running and `e2e-tests` will still
boot a fresh one of its own alongside it.

The AVD and this container both want real memory (this container caps at 8GB), so kill the AVD
first regardless of which device you're using:

```bash
adb -s emulator-5554 emu kill
```

Then run the suite as documented in `docs/build-notes.md`:

```bash
DOCKER_HOST=unix:///run/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :e2e-tests:test
```

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

## Host setup

Everything below lives outside the repo (system config, root-owned), except the quadlet unit
itself, which is checked in at `scripts/redroid/redroid.container` and gets copied into place.
Recorded here so a future session doesn't try to recreate any of it from scratch — check
what's actually live on the machine (commands given per-item below) before assuming this
description is still accurate.

### Kernel module persistence

`/etc/modules-load.d/binder.conf`:
```
binder_linux
```

`/etc/modprobe.d/binder.conf`:
```
options binder_linux devices=binder,hwbinder,vndbinder
```

Confirmed on this machine: `lsmod | grep binder` shows `binder_linux` loaded after reboot, and
`/sys/module/binder_linux/parameters/devices` correctly reads back `binder,hwbinder,vndbinder`.
This part of the persistence setup is working.

### binderfs mount

`/etc/fstab`:
```
none /dev/binderfs binder defaults,nofail,x-systemd.after=systemd-modules-load.service,x-systemd.mkdir 0 0
```

**History, so this isn't rediscovered as a surprise**: the first version of this fstab entry
(`none /dev/binderfs binder defaults 0 0`, no ordering constraint) was applied and the host
rebooted. `/dev/binderfs` came back **empty** — not even `binder-control` — because the
fstab-generated mount unit raced the kernel module load and lost:

```
Sep 20 21:20:42 ... systemd[1]: Mounting dev-binderfs.mount - /dev/binderfs...
Sep 20 21:20:42 ... mount[407]: mount: /dev/binderfs: unknown filesystem type 'binder'.
Sep 20 21:20:42 ... systemd-modules-load[428]: Inserted module 'binder_linux'
```

`local-fs.target` (which pulls in fstab mounts) isn't ordered relative to `systemd-modules-load.service`
by default — a plain `defaults`-only fstab entry gives systemd no reason to wait. The fix adds
`x-systemd.after=systemd-modules-load.service` to force that ordering.

`UNVERIFIED`: the fixed fstab line above has been written but **not yet proven across an actual
reboot** — the machine this was diagnosed on was unavailable (remote, no sudo) at the time this
doc was written. Confirm with:

```bash
mount | grep binderfs
ls /dev/binderfs   # expect: binder-control at minimum
```

If `/dev/binderfs` is still empty after a reboot with this fstab line in place, the ordering fix
didn't work — don't paper over it with a manual `mount -t binder binder /dev/binderfs`; that
defeats the entire point of this setup (nothing manual after boot). Go back to
`journalctl -b -u dev-binderfs.mount` and `journalctl -b -u systemd-modules-load.service` and
re-diagnose the ordering.

### Podman socket permissions

`/etc/systemd/system/podman.socket.d/override.conf`:
```
[Socket]
SocketGroup=danny-harris
```

Replaces a manual `chmod 755 /run/podman && chmod 666 /run/podman/podman.sock` (needed every
boot) with group-based access set once, declaratively. Confirmed working: `podman info` succeeds
without any manual chmod after the reboot that applied this.

This only matters for tools that talk to the Docker-compatible REST API over the socket (e.g.
`e2e-tests`' Testcontainers usage via `DOCKER_HOST=unix:///run/podman/podman.sock`). The quadlet
unit below does **not** go through the socket — Quadlet-generated services invoke the local
`podman` binary directly, so `redroid.service` has no dependency on `podman.socket` being up.

### The quadlet unit

Source of truth: `scripts/redroid/redroid.container` (checked into this repo, see the file for
the full annotated content — what each option replicates from `AndroidContainerSetup.kt`, and why
a wildcard device-cgroup rule replaces that file's dynamically-detected major/minor numbers).

Install:
```bash
sudo cp scripts/redroid/redroid.container /etc/containers/systemd/redroid.container
sudo systemctl daemon-reload
```

That `daemon-reload` both generates `redroid.service` from the quadlet file **and** applies its
`[Install]` section (`WantedBy=multi-user.target`) — Quadlet-generated units don't need a separate
`systemctl enable`; that's a real difference from a hand-written unit, not an oversight here.

Fixed name: **`redroid`**. Fixed ports: **5555** (adb), **8080** (MCP server, once started
on-device).

`UNVERIFIED`: the unit has never been installed or started. `systemctl status redroid.service`
and `podman ps --filter name=redroid` are the first things to check once it has been.

### Design choice: quadlet over `podman generate systemd`

The task allowed either. Chose Quadlet because:

1. It's Podman's current recommended mechanism since 4.4 (this host runs 4.9.3) — `podman generate
   systemd` is the older, more manual path it superseded.
2. It's declarative — the `.container` file *is* the source of truth, versionable in this repo,
   rather than a unit file generated once from a running container's already-baked config (which
   is how `podman generate systemd --new` works, and which would have baked in this machine's
   *current* binder/fuse major:minor numbers rather than staying correct if those ever change).

The one place this pushed back: Quadlet has no native directive for a dynamic device-cgroup rule
(nothing can shell out to detect a number at unit-generation time), which is exactly what
`AndroidContainerSetup.kt` does per-run. Resolved with a static wildcard rule instead — see the
comment in `scripts/redroid/redroid.container` for the full reasoning.

## What to do once the reboot verification passes

Once `ls /dev/binderfs` shows real devices post-reboot and `systemctl status redroid.service`
shows it running:

1. `adb connect localhost:5555` and confirm boot completes.
2. Install + launch the debug APK (commands above) and confirm it runs.
3. Update every `UNVERIFIED` marker in this doc that the above confirms — this doc should not
   keep asserting something is unverified once it demonstrably isn't.
