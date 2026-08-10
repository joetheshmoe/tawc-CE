# App-shipped assets: copy → RO bind

Replace the per-rootfs copy of whole app-owned asset dirs with `-b
SRC:DST:ro` binds for tawcroot installs. Follow-on to the
system-partition `:ro` work (shipped 2026-07: `BindSpec(src, dst, ro)`,
`LIBHYBRIS_BIND_DIRS` bound RO, pinned in
app/src/test/.../TawcrootBindSpecsTest.kt).

**Status: ready to implement.** Re-assessed 2026-08-10. The two design
blockers from the 2026-07 assessment have concrete resolutions (below);
what remains is a *verification* constraint: the libhybris end-to-end
check needs the physical device, and `.tawctarget` is currently
`emulator`. The gfxstream piece is fully verifiable on the emulator, so
the mechanism can be built and proven now and the libhybris result
spot-checked when the target next points at the phone.

## Scope

Only whole, app-owned guest dirs with no distro-managed siblings
qualify (bind = replacement, not merge — a bind shadows distro-shipped
siblings, and single-file binds don't appear in parent `readdir`).
There are exactly three today, all `<filesDir>` extracts with the same
`ensure*Extracted` stamp mechanism:

| host (`<filesDir>/…`) | guest | provider | ~size |
|---|---|---|---|
| `libhybris/` | `/usr/lib/hybris` | `LibhybrisInstallProvider` | 12 MB |
| `mesa-zink/` | `/usr/lib/mesa-zink` | `MesaZinkInstallProvider` | ~20 MB (with gfxstream) |
| `mesa-gfxstream/` | `/usr/lib/gfxstream` | `BridgeInstallProvider` | small |

Files that must coexist with distro siblings stay copied for every
method: the glvnd vendor JSON (`/usr/share/glvnd/egl_vendor.d/` is
libglvnd-package-owned; pacman writes `50_mesa.json` there) and the
`/usr/lib/hybris-vulkan-only/libvulkan.so.1` LINK (its own dir, but a
1-symlink dir isn't worth a bind and the symlink resolves through the
`/usr/lib/hybris` bind at runtime anyway).

Payoff: ~30 MB per arm64 install, no per-upgrade copy churn, and the
guest can no longer corrupt its GPU stack (RO). Debug methods
(proot/chroot) keep the copy path — proot has no RO bind primitive.

## Resolved design questions

- **Method-agnostic manifest** (was blocker 1). Every spawn surface
  resolves the method from install metadata (`Installation.method` via
  `InstallationMethod.forKey` — RunCommandOp, TerminalActivity, broker),
  so per-method manifests can't go stale from cross-method entry. Give
  `TawcInstallProvider.entries` the install's method key:
  `entries(context, methodKey)`. `TawcInstaller.installInto` already
  loads the `Installation`; pass `installation.method` through. For
  `methodKey == TawcrootMethod.KEY` the three providers skip their
  whole-dir `walk()`/copy entries (the bind supplies the tree); for
  proot/chroot they return today's full set. `AndoInstallProvider` /
  `ShellDefaultsInstallProvider` ignore the param.
- **Spawn-time src guarantee** (was blocker 2). tawcroot opens every
  bind src at startup (`tawcroot_path_add_bind`:
  `open(O_PATH|O_DIRECTORY)`) and `exit(93)`s if one is missing, and
  the stamp fast-path in `TawcInstaller` skips
  `ensureLibhybrisExtracted`. Resolution: gate each asset bind at spawn
  time in `TawcrootMethod` on the matching
  `CompositorService.ensure*Extracted(context)` call. Steady-state cost
  is one asset-existence probe plus one small stamp-file read per dir,
  trivially cheap next to forking a login shell — no memoization
  needed (and skipping it keeps "dir deleted out from under a running
  app" self-healing: the next spawn just re-extracts). `false` (no
  asset for this ABI / backend build-disabled) → bind simply not added.
- **`.version` leak.** The extract stamp file becomes guest-visible at
  e.g. `/usr/lib/hybris/.version` (the copy path skipped it). Read-only
  dotfile, nothing scans those dirs by glob; accept it.
- **Guest writes now EROFS.** Under copies a rootfs could overwrite its
  own GPU libs; RO is the point. These are tawc-owned namespaces
  (`/usr/lib/{hybris,mesa-zink,gfxstream}`); no distro package touches
  them.

## Work

1. `TawcInstallProvider.entries(context, methodKey: String)`; thread
   `installation.method` through `TawcInstaller.installInto`. Under
   `TawcrootMethod.KEY`:
   - `LibhybrisInstallProvider`: keep the `ensureLibhybrisExtracted`
     gate and, when the asset exists, still emit the glvnd JSON COPY and
     the vulkan-only LINK; drop the `walk()` COPY/LINK entries.
   - `MesaZinkInstallProvider`, `BridgeInstallProvider`: return
     `emptyList()` (their whole output is the bound dir). Keep the
     `EnabledGraphicsBackends` gates so no misleading "unavailable" logs.
2. `TawcrootMethod`:
   - Retain `context.applicationContext` in the constructor (currently
     only `appPaths`/`store` are kept).
   - New private `assetBinds(): List<BindSpec>`, called from
     `startInside`/`ptyShellExec` alongside `externalBindsFor`: for each
     of the three dirs, if its backend is build-enabled and
     `ensure*Extracted(appContext)` returns true, add
     `BindSpec("<filesDir>/<name>", GUEST_DIR, ro = true)`.
   - Extend the static `bindSpecs(tawcShare, libhybrisDirs, …)` with an
     `assetBinds: List<BindSpec>` param, inserted after the
     `libhybrisDirs` system binds and before the tawc share bind
     (grouped with the other RO dlopen sources; still ahead of external
     binds so user binds can't shadow them).
   - `prepareSpawn`: mkdir each asset bind's guest dst in the rootfs,
     matching the existing habit for the other binds.
3. Bookkeeping: no schema change. `tawcStamp`/refresh flow is
   untouched; the manifest for a tawcroot install just shrinks to the
   coexist files (+ ando/bashrc). The x86_64 stamp fast-path is
   unchanged (that optimization is the stamp compare, not manifest
   emptiness).
4. Tests:
   - `TawcrootBindSpecsTest`: add the new param; extend the exact-list
     pins with asset binds present (`…:/usr/lib/hybris:ro` etc. in the
     new position) and absent (`emptyList()` → argv identical to
     today's, pinning the no-asset/emulator shape).
   - The provider method-split isn't plain-JVM testable (needs Context
     + assets); it's covered by the emulator/device verification below.
     If a pure seam falls out naturally (e.g. a
     `filterForMethod(entries, methodKey)` helper), pin that instead.
5. Docs, same change: rewrite notes/installation.md §"Why copy, not
   bind" (now "copy for coexist files + debug methods, RO bind for
   whole app-owned dirs under tawcroot"), update
   `LibhybrisInstallProvider` / `TawcrootMethod` kdoc (argv sketch +
   bind-order comment), and delete this plan.

## Migration & failure handling

- **Existing installs migrate themselves.** The APK carrying this
  change bumps `currentExtractStamp`; `TawcInstaller` wipes the old
  manifest's dests (the recorded COPY/LINK files under the three dirs)
  and records the new, smaller manifest. Empty leftover subdirs in the
  rootfs are shadowed by the bind; harmless.
- **Spawn before refresh finishes** (installAll runs on a background
  thread in `TawcApplication.onCreate`): benign — the bind shadows any
  not-yet-wiped stale copies.
- **`ensure*Extracted` throws at spawn** (I/O error mid-extract): let
  it propagate as an IOException that names the dir, mirroring the
  fail-closed external-bind precedent — a silent skip would launch a
  session whose GPU stack is missing with a far more confusing failure.
  (Related: issues/tawcinstaller-refresh-failures-not-surfaced.md.)
- **tawcroot exit 93 ("bind add failed")** should now be unreachable
  for asset binds (existence assured just before spawn; the extract
  swap via `atomicReplaceDir` is rename-based, so the dir never
  transiently disappears). If it shows up in `adb logcat -s tawc`,
  something deleted the extract between the guard and exec — the next
  spawn self-heals by re-extracting.
- **Re-extract under a running session:** the session's bind holds an
  O_PATH fd to the pre-swap dir (inode pinned), new spawns open the new
  dir. In practice APK replacement force-stops the uid, killing
  sessions anyway. No action needed.
- **Rollback:** reverting the change bumps the stamp again; the refresh
  finds a manifest with no whole-dir entries to wipe and lays down full
  copies. Clean in both directions.
- **No host-side consumers break:** no script or integration test
  reads the copied files out of the rootfs tree (checked 2026-08-10);
  guest-side consumers (`RootfsEnv` LD_LIBRARY_PATH / VK_ICD_FILENAMES,
  glvnd JSON `library_path`, vulkan-only symlink) all use the guest
  paths, which the binds preserve.

## Verification

Emulator (valid now, `.tawctarget=emulator`; exercises the whole
mechanism via gfxstream, which is the emulator default):
- `./gradlew :app:testDebugUnitTest`.
- Fresh install (with `--arg mirrorProxy=http://127.0.0.1:8080/proxy/`),
  then in-rootfs: `/usr/lib/gfxstream` lists the .so + ICD JSON;
  `touch /usr/lib/gfxstream/x` → EROFS; a GL client renders under
  gfxstream.
- Host side: `distros/<id>/rootfs/usr/lib/gfxstream/` contains no
  copied files after refresh of a pre-change install (stale copies
  wiped) and is absent/empty on a fresh install.
- `adb install -r` upgrade cycle: stamp refresh runs, spawns still work
  immediately after (bind guard re-extracts as needed).
- `scripts/run-integration-tests.sh`.

Physical device (whenever `.tawctarget` next points at it — do NOT
substitute the phone while the target says emulator):
- libhybris backend boots and GPU init works with `/usr/lib/hybris`
  bound RO (e.g. `scripts/rootfs-run.sh 'lxterminal'`); write into the
  dir → EROFS; libhybris-zink smoke (`/usr/lib/mesa-zink` bound).
- Uninstall/reinstall + `adb install -r` upgrade cycles: no stale
  copies, bound dirs track the APK.
