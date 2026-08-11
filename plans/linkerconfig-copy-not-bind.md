# /linkerconfig: copy the config, drop the bind

Fixes [issues/linkerconfig-bind-breaks-ls-in-rootfs.md](../issues/linkerconfig-bind-breaks-ls-in-rootfs.md).
Stop binding Android's `/linkerconfig` into rootfses. Instead, copy the
one file libhybris reads out of it into the rootfs at
`/usr/lib/hybris/ld.config.txt` at each spawn, and patch our libhybris
fork (one constant) to read it there. `/linkerconfig` disappears from
the guest filesystem entirely.

## Why this shape

- Only hybris's vendored Q linker reads the path: single constant
  `kLdGeneratedConfigFilePath`
  (deps/libhybris/hybris/common/q/linker.cpp:111), the sole
  `/linkerconfig` reference in the whole fork. Guest processes are
  glibc; Android's real linker never runs in a rootfs. So the path is
  purely our fork's implementation detail, and `/usr/lib/hybris/`
  (`LibhybrisInstallProvider.GUEST_LIB_DIR`) is where the rest of the
  hybris runtime already lives.
- The other libhybris binds (`/apex /vendor /system /system_ext`)
  cannot move: their paths are baked into `ld.config.txt` namespace
  search paths, vendor blobs' internal absolute-path dlopens, and
  libhardware's compiled-in `/vendor/lib64/hw` HAL search path. They
  also don't share the bug (world-readable SELinux labels; only the
  boot-generated `linkerconfig_file` tmpfs denies dir getattr).
- Copy freshness ≡ bind: Android regenerates `/linkerconfig` only
  during boot and it is static for the rest of uptime; a reboot kills
  the app and every guest; the hybris linker reads the file once per
  process start. A per-spawn copy is therefore never staler than the
  bind for any process that reads it.
- **No emulator gating anywhere.** The bind/copy behavior stays
  uniform across emulator and phone deliberately — this bug was only
  caught because the emulator binds the same set phones do. Do NOT add
  the `isEmulator` gate to `TawcrootMethod` that the issue originally
  suggested (rejected in review: it would hide phone-present problems
  from emulator repro). Leave `ChrootMounter`'s pre-existing gate
  alone (debug-only method).

## Changes

### 1. libhybris fork (`deps/libhybris`)

- `hybris/common/q/linker.cpp:111`: `kLdGeneratedConfigFilePath` →
  `"/usr/lib/hybris/ld.config.txt"`. Constant only; the probe order in
  `get_ld_config_file_path()` already works out — the apex path only
  applies to `/apex/*/bin` executables, and
  `/system/etc/ld.config.arm64.txt` doesn't exist on Android 11+
  phones, so lookup falls through to this constant.
- Fork workflow per TAWC_FORK.md: new patch in the stack with its own
  problem-area section in TAWC_FORK.md (doc change amended into the
  final TAWC_FORK.md commit), tag `tawc-<DD-Mon-YYYY>-<n>`, bump the
  `libhybris` pin in `deps/deps.list` in the same main-repo change.
  Fork commits only with user go-ahead (standing rule).
- Rebuild: `scripts/build-libhybris.sh` →
  `assets/libhybris/arm64-v8a.tar`. The Gradle dep-artifact
  tree-state fingerprint should force the repack; verify the built APK
  actually carries the new tar.

### 2. App: per-spawn copy

New small helper (e.g. `LinkerConfig.kt` in `install/`), called at the
top of each method's `startInside` (TawcrootMethod.kt:109,
ProotMethod.kt:141, ChrootMethod.kt:51). Rootfses are app-private
paths, so plain host-side file IO from the app works for all three
methods:

- If `/linkerconfig/ld.config.txt` exists on the host (Android 11+;
  absent pre-Q — skip silently): `mkdir -p <rootfs>/usr/lib/hybris`,
  write a temp file in that dir, `rename()` over
  `/usr/lib/hybris/ld.config.txt`. Atomic rename so a
  concurrently-starting guest never parses a torn file; also makes
  concurrent spawns safe.
- Unconditional — no ABI or emulator gate (parity, see above). ~235 KB
  per spawn, negligible.
- On copy failure: one warning log, then continue — hybris falls back
  to `init_default_namespace_no_config` +
  `--with-default-hybris-ld-library-path`, the same behavior pre-Q
  devices get today.
- Accepted behavior change (from the issue): the file is writable by
  in-rootfs root, unlike the `ro` bind. Not a new boundary — the guest
  already owns its rootfs and `LD_LIBRARY_PATH`.
- Tidy-up in the same helper: `rmdir` an empty `<rootfs>/linkerconfig`
  left behind by old chroot installs (`ChrootMounter` used to `mkdir`
  a real mountpoint; tawcroot/proot never created one).

### 3. Drop the bind

- TawcrootMethod.kt:404 — remove from `LIBHYBRIS_BIND_DIRS`.
- ProotMethod.kt:319 — same; update the comments at 264–269 and
  305–312.
- ChrootMounter.kt — drop the `mkdir`/`mount_if_needed /linkerconfig`
  lines (~77, 83) and the class doc line 21. `unmount` needs no
  change: it discovers mounts from `/proc/mounts`, so leaked binds
  from older builds still get cleaned.
- app/src/main/res/values/strings.xml:96 — chroot method description
  lists `/linkerconfig`; update.
- tawcroot/include/path.h:135 — comment lists the bind set; update.

### 4. Tests

- TawcrootBindSpecsTest.kt:19,47,78 — drop `/linkerconfig` from the
  expected specs.
- New integration test (tests/integration): run
  `LS_COLORS='or=40;31;01' ls --color=always -l /` in the rootfs and
  assert exit 0 — encodes the interactive-shell condition (color +
  LS_COLORS forces stat of every `/` entry) that the non-interactive
  broker path misses. Also assert `/usr/lib/hybris/ld.config.txt`
  exists and is non-empty when the host has `/linkerconfig` (skip
  otherwise).
- `./gradlew :app:testDebugUnitTest` and `tawcroot/test.sh` still
  green.

### 5. Docs

- Update bind lists: notes/proot.md:17, notes/chroot.md:21,
  notes/emulator.md:504, notes/tawcroot/bootstrap-and-modules.md:16,32.
- Document the copy (what/why/freshness) wherever the
  `/usr/lib/hybris` guest layout is described, and add a line on
  deliberate emulator/phone bind parity in notes/emulator.md.
- Fold the issue's diagnosis (SELinux mechanism, why only interactive
  `ls` hits it) into notes before deleting the issue.

## Validation

Standing target is `physical` — the only environment where the config
matters.

Pre-fix on the phone (settles the issue's open questions; record the
results in the issue):

1. `adb shell ls /system/etc/ld.config*` → expect no per-arch file
   (confirms `/linkerconfig` is the phone's live config source).
2. `scripts/rootfs-run.sh "LS_COLORS='or=40;31;01' ls --color=always -l /"`
   → expect the `Permission denied` row and exit 1 (confirms the
   predicted phone repro).

Post-fix on the phone:

3. `scripts/build-libhybris.sh && scripts/app-build-install.sh`.
4. Repeat check 2 → exit 0, no `/linkerconfig` row at all.
5. Config found at the new path: spawn once with
   `HYBRIS_LOGGING_LEVEL=debug` (hybris/common/logging.c honors it)
   and look for the linker's
   `Reading linker config "/usr/lib/hybris/ld.config.txt"` line.
   Verify at implementation time that linker INFO routes through
   hybris logging; if it doesn't, fall back to `stat` of the copied
   file plus check 6 as the signal.
6. GPU still up through hybris: launch a client on the default
   libhybris backend (`scripts/rootfs-run.sh 'lxterminal'` /
   gui-testing skill), confirm rendering via screenshot sub-agent.
7. `scripts/run-integration-tests.sh` on the phone, including the new
   test.
8. Emulator (the original repro env): `scripts/emulator.sh start`,
   then `TAWC_TARGET=emulator` repeat checks 2→4 with tawcroot;
   exit 0 expected. Hybris never runs there but the copy still happens
   (parity).
9. Proot spot-check of check 4 (debug method, same bind list); chroot
   needs a rooted device — cover if `su` is available, else note it.

Wrap-up: move diagnosis into notes (§5), delete the issue, delete this
plan.
