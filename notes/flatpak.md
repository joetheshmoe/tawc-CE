# Flatpak apps

Spike-proven on physical (Debian sid, 2026-08): `flatpak install` works
natively and apps run **unsandboxed, hardware-accelerated** through the
normal launcher pipeline. Not wired into the store UI yet — see
`notes/launcher.md` and the store plans.

## Model

Flatpak's sandbox needs user namespaces, which Android blocks, so
`flatpak run` dies in `bwrap` (`Creating new namespace failed:
Operation not permitted`). We bypass it: install with the real `flatpak`
tool, then run the app directly — same trust model as every other tawc
app (the guest is already fake root in an unsandboxed chroot). A naive
`/usr/bin/bwrap` shim that `exec`s the command past `--` covers
`flatpak run`'s default path; `flatpak run --no-sandbox`-style manual
launchers are the fallback for apps that need a curated env.

Install lands under `~/.local/share/flatpak/` (guest `$HOME=/root`):
`app/<id>/…/active` and `runtime/org.gnome.Platform/…/active` are
symlinks to the current ref; append `/files` for the tree. Flatpak's
export `.desktop` goes to `var/lib/flatpak/exports/share/applications`
(already scanned by `launcher.rs`'s `APPS_SUBDIRS`) — user installs
export to `~/.local/share/flatpak/exports/…`, which is *not* scanned.

## Running (the two non-obvious tricks)

The runtime is self-contained but built against its own glibc; the host
rootfs has a *different* glibc. The two problems and their fixes:

1. **glibc mismatch → SIGBUS.** The GNOME 50 runtime's glibc is **2.42**,
   Debian sid's is **2.43**. Running the app on the runtime's own
   `ld-linux` works, but then the app sees the runtime glibc and the
   host-built libhybris shims (needing 2.43) can't load. Reverse it:
   run on the **host loader + host glibc** (2.43), which is *newer*, so
   the runtime's libs (built for 2.42) still load. Do that by prepending
   a "shadow" dir to `LD_LIBRARY_PATH` with symlinks to the host's
   glibc-family libs (`libc.so.6`, `libm.so.6`, `libpthread`, `libdl`,
   `librt`, `libstdc++`, `libgcc_s`, …) so they out-rank the runtime's
   copies of the same names.

2. **GL must go through libhybris, not the runtime's Mesa.** The
   runtime ships its own Mesa whose `swrast_dri.so` isn't usable here.
   Point the app at tawc's libhybris GL instead: shadow `libEGL.so.1` →
   `/usr/lib/hybris/libEGL.so.1` (note: EGL lives at the *top* of
   `/usr/lib/hybris/`, only `libGL`/`libGLESv2` shims are in
   `gl-shims/`), `libGLESv2.so.2`/`libGL.so.1` → `gl-shims/`, and set
   `HYBRIS_EGLPLATFORM=wayland` + `GDK_GL=gles:always` (the same env
   `RootfsEnv` already sets for the LIBHYBRIS backend — keep
   `/usr/lib/hybris` on `LD_LIBRARY_PATH` for the shims' internal deps).

Full launcher: shadow dir (glibc family + GL shims) first, then
`$APP/lib/aarch64-linux-gnu:$RT/lib/aarch64-linux-gnu:$RT/lib`, then the
inherited `$LD_LIBRARY_PATH` (which carries `/usr/lib/hybris`), plus
`XDG_DATA_DIRS`, `GSETTINGS_SCHEMA_DIR`, `GIO_EXTRA_MODULES` pointing at
the app+runtime trees, and `exec "$APP/bin/<binary>"`.

## Caveats

- **Session bus**: GTK warns "Unable to acquire session bus" because
  `dbus-launch --autolaunch` spawns a *host* binary against the
  runtime's `LD_LIBRARY_PATH` and crashes. Harmless for most apps; a
  proper fix is a session bus started host-side with
  `DBUS_SESSION_BUS_ADDRESS` passed through, not autolaunched inside.
- **Arch test pending** — mechanism is distro-agnostic but unverified on
  Arch ARM.
- The working spike launcher hardcodes the Calculator app id; the store
  step generalizes it into a per-app `tawc-flatpak-run <appid>` wrapper.
