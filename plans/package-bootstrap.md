# Package bootstrap: assemble a rootfs from signed repo metadata

Supersedes `debian-signed-bootstrap.md`. That plan reimplemented debootstrap
in Kotlin and called its dpkg-database fabrication "the whole risk"; this
plan keeps its trust design but runs **real debootstrap** on-device, and
generalizes the installer so tarball-vs-packages is a per-distro bootstrap
**flavor** rather than a Debian one-off.

Shape when done:

- Every distro has one or more bootstrap flavors: `tarball` (today's path,
  unchanged) and optionally `packages`.
- Each distro names one release-supported flavor. Debian sid keeps
  `tarball` as its supported flavor until `packages` has earned it — this
  plan does **not** replace the existing Debian install method.
- Dev builds get a flavor selector (UI + `--arg bootstrap=packages`);
  release builds reject non-supported flavors, mirroring the mirrorProxy
  debug gate.
- First (and here, only) packages implementation: Debian sid via vendored
  debootstrap. Other distros are sketched at the end and out of scope.

## Verified upstream facts (2026-08-10)

The Debian archive trust chain — one clearsigned file, then hashes all the
way down:

    dists/sid/InRelease            clearsigned, ~190 KB
      └ SHA-256 of main/binary-arm64/Packages.xz
          └ SHA-256 of every .deb in the suite

- sid's `InRelease` carries signatures from `Debian Archive Automatic
  Signing Key (12/bookworm)` (`6ED0E7B82643E131`, expires ~2031) and
  `(13/trixie)` (`78DBA3BC47EF2265`, expires ~2035); both are in
  `debian-archive-keyring`.
- `InRelease` carries `Valid-Until` (7 days for sid) — replay/rollback
  protection no tarball bootstrap has, including the new Ubuntu one.
- `Acquire-By-Hash: yes`; `dists/sid/main/binary-arm64/by-hash/SHA256/<digest>`
  serves 200. sid republishes several times a day, so the by-hash path is
  the only race-free way to fetch the index.
- `No-Support-for-Architecture-all: Packages` — arch:all packages are
  folded into each arch index; one index file covers everything.
- Measured scope: index is 75,640 stanzas (10.6 MB xz / 63 MB raw);
  Essential+required+apt closure is ~80 packages, 31.5 MB download,
  ~143 MB installed. Comparable to the 49.4 MB tarball download, but adds
  an on-device `dpkg --configure -a` pass — expect this flavor to be
  *slower* than tarball for Debian; its value is trust + freshness, not
  speed.

debootstrap facts, from the upstream source (pin and re-verify at
implementation time; line numbers from master 2026-08-10):

- `--print-debs` (debootstrap:98,202) resolves and prints the exact package
  set without installing — we never reimplement dependency resolution.
- `file://` mirrors are handled natively by copying (debootstrap:462-465);
  no wget needed against a local mirror.
- `--foreign` / `--second-stage` split stage 1 (host-side resolve +
  extract, writes `$TARGET/debootstrap/`) from stage 2 (runs *inside* the
  target using the target's freshly-unpacked tools).
- `setup_devices` tries mknod then falls back to bind mounts
  (functions:1330-1349); under tawcroot mknod is emulated
  (pretend-success, `tawcroot/src/syscalls_fs.c:1101-1146`) and guest
  `/dev` is a host bind anyway, so whatever lands in `$TARGET/dev` is
  runtime-irrelevant — the tarball path already ships zero device nodes
  (`ProotArchiveExtractor.kt:198-204`).
- Extraction uses `dpkg-deb` or `ar` + tar (functions:1043); `.zst`
  members shell out to `zstdcat` (functions:1071,1094) — sid's debs are xz
  today.
- Signature checking wants `gpgv`/`sqv`/`sopv` (debootstrap:388);
  `--no-check-sig` exists for pre-verified mirrors.
- Merged-/usr setup, base-passwd/base-files ordering, and all the
  accumulated dpkg edge cases live in debootstrap itself — by running it
  we get them for free instead of porting them.

## Design principles (read before writing code)

1. **Never reimplement dpkg/apt logic.** Kotlin's jobs are trust
   (verify), transport (download), environment (a place debootstrap can
   run), and progress. Dependency resolution, unpacking order, the dpkg
   database, maintainer-script choreography: all debootstrap's.
2. **The trust boundary is Kotlin, before any downloaded code runs.**
   Everything debootstrap ever reads — index, debs, even debootstrap's own
   busybox interpreter — is hash-verified against the shipped-key-verified
   `InRelease` first. debootstrap then runs with `--no-check-sig` against
   an app-private local mirror; that flag does not weaken anything because
   verification already happened upstream of it. Write that justification
   as a loud comment at the call site *and* in `notes/installation.md`'s
   hard-rules section, so it never pattern-matches as "verification was
   turned off".
3. **Bootstrap the bootstrapper from the archive itself.** debootstrap
   needs a shell + ar + tar + xz. Do not cross-compile or vendor a
   busybox: download Debian's own `busybox-static` package (present in the
   verified index for both arches, statically linked), verify it like any
   other deb, and extract it in Kotlin — commons-compress already has
   `ArArchiveInputStream`, and the tar/xz streaming machinery exists
   (`ProotArchiveExtractor.kt`). Zero new shipped binaries, zero
   cross-compilation, arch-correct automatically.
4. **Run debootstrap as a guest of the existing method machinery** (the
   workspace-guest trick below) instead of inventing a new execution
   path. The app demonstrably executes rootfs binaries as the app uid
   under tawcroot; stage 2 runs via the same `runInside` used by
   `initPackageManager` today. apt already unpacks packages and runs
   maintainer scripts under tawcroot in every existing install
   (`dist-upgrade` in `installBasePackages`), so stage 2 is not a new
   class of operation.
5. **Fail closed, visibly.** The new bootstrap variant must carry its
   trust root in the type; there must be no verification-free variant to
   accidentally reach. Same invariant as
   `notes/installation.md:722-737`.

## Part 1 — flavor infrastructure

1. **Sealed bootstrap type.** `DistroBootstrap` (`Distro.kt:158-163`)
   becomes a sealed interface:

       sealed interface DistroBootstrap
       data class TarballBootstrap(url, format, stripPrefix, verification) : DistroBootstrap
       data class PackageBootstrap(
           archiveRoot: String,      // e.g. http://deb.debian.org/debian
           suite: String,            // "sid"
           packagesArch: String,     // dpkg arch: "arm64" / "amd64"
           keyResource: String,      // res/raw keyring, non-optional
       ) : DistroBootstrap

   Today's data class becomes `TarballBootstrap` (mechanical rename; it is
   consumed only inside `Installer.kt` and the fail-closed test). The
   point of the sealed type: the verification invariant survives visibly —
   `PackageBootstrap` has no "skip verification" shape at all, its
   `keyResource` is the policy.
2. **Flavor concept on `Distro`.** Add
   `val bootstrapFlavors: Map<BootstrapFlavor, DistroBootstrap>` (enum
   `TARBALL`, `PACKAGES`) with first/`supported` flavor designation;
   default implementation wraps the existing single `bootstrap` so the six
   other distros change nothing. `resolveBootstrap(log, mirrorProxy)`
   gains a flavor parameter (default = supported).
3. **Selection plumbing**, following the mirrorProxy trail exactly:
   broker arg `bootstrap=tarball|packages` in
   `InstallActions.InstallAction` (`InstallActions.kt:51-84`); new
   `EXTRA_BOOTSTRAP` + `startInstall` param in `InstallationService`
   (`:884-912`, `:246-254`); ctor param on `Installer` (`:53-84`);
   persisted field on `Installation` (`toJson`/`fromJson`). Metadata
   written before this field exists has no entry: `fromJson` must
   interpret a missing field as `tarball` (that's what every existing
   install is) **and** the value must be made explicit on the next
   metadata write, so old records converge instead of relying on the
   default forever — same shape as `fromJson`'s missing-distro default
   (`Installation.kt:240`), plus the write-back. New installs always
   persist the flavor explicitly. Dev-only UI: a small radio row between the
   distro picker and the label field in `InstallActivity.buildFormSection`
   (`:159-235`), shown only when the selected distro has >1 flavor —
   which in release builds is never true unless we ship one.
4. **Release gating.** In `InstallationService.startInstall` validation,
   reject a flavor other than the distro's supported one when
   `!BuildConfig.DEBUG`, exactly like the mirrorProxy gate at `:336-339`.
   Do not use the `EnabledMethods` BuildConfig-field pattern — that exists
   to prune native libs from the APK; flavors ship no per-flavor
   binaries, so a runtime gate is enough.
5. **Installer dispatch.** In `Installer.install`, branch on the sealed
   type: `TarballBootstrap` keeps lines `:160-257`
   (download/verify/extract) verbatim; `PackageBootstrap` delegates those
   stages to a new `PackageBootstrapInstaller` (Debian-family
   implementation first) and **rejoins the common path at `:264`
   (`configure`)** — configure/`TawcInstaller.installInto`/
   `initPackageManager`/`installBasePackages` are flavor-agnostic and run
   unchanged. `Installation.sourceUrl` gets `archiveRoot`.
6. **Fail-closed test restructure.**
   `BootstrapVerificationFailClosedTest` (`:60-75`) currently walks one
   bootstrap per distro. Extend it to walk every flavor of every distro:
   tarball flavors keep the placeholder-iff-resolves-live biconditional;
   package flavors must declare a non-blank `keyResource` that exists in
   `SignatureVerifier.loadKeyRing`'s map (factor the `when` into a
   testable map to make that assertable). Keep it impossible to add a
   flavor without stating its trust root.

## Part 2 — Debian sid packages flavor

### Trust phase (Kotlin, no downloaded code runs yet)

7. **Ship `debian-archive-keyring`** as
   `res/raw/debian_archive_keyring.asc` containing (at least) the
   bookworm and trixie archive signing keys. Obtain from two independent
   origins and diff (the `debian-archive-keyring` package contents vs
   keyring.debian.org / the published fingerprints). Register in
   `loadKeyRing`. Add a release-prep checklist item in `notes/release.md`
   (agent steps): confirm shipped archive keyrings aren't nearing expiry
   and new-suite keys are included. **No runtime key fetch, ever** — that
   hands the trust decision back to the network.
8. **Verify `InRelease`.** Fetch `dists/sid/InRelease` (proxy-wrapped in
   dev). Verify the clearsign with BouncyCastle. Clearsign is *not* the
   detached-signature code path `verifyPgp` uses: the signed text must be
   canonicalized (dash-unescaping, trailing-whitespace/CRLF rules) before
   hashing, and the body must be parsed **only from the canonicalized,
   verified region** — never from the raw fetched bytes. Get vectors in
   place (below) before trusting this code.
9. **Enforce `Valid-Until`** — reject, don't warn, with a small skew
   allowance (hours, not days) and an error message that names the
   device-clock possibility. This is the replay defence; without it an
   origin-controlling attacker serves last month's fully-signed index.
10. **Fetch the index by hash.** Read the SHA-256 for
    `main/binary-<arch>/Packages.xz` out of the verified body, fetch
    `by-hash/SHA256/<digest>`, verify the digest of what arrived.
11. **Parse the index into `name -> (Filename, Size, SHA256)` only.**
    Stream the 63 MB decompressed text (xz-java); no Depends parsing, no
    object graph — resolution is debootstrap's job.

### Workspace guest

12. **Vendor debootstrap** via `deps/deps.list` (`debootstrap` from
    salsa.debian.org, pinned commit) and a small Gradle task that packs
    `debootstrap`, `functions`, and `scripts/` into an asset tar (follow
    `packLibhybris`, `app/build.gradle.kts:661-686` — assets strip
    symlinks, and `scripts/sid` is a symlink). Pin means: tested against
    that exact version; bump deliberately via `scripts/update-deps.sh`.
    If a local patch ever becomes necessary, fork like libhybris rather
    than sedding at build time; start unpatched.
13. **Build the workspace** under the install dir
    (`distros/<id>/bootstrap-work/` beside the future `rootfs/`):
    - `bin/busybox` from the verified `busybox-static` deb (Kotlin
      `ArArchiveInputStream` → `data.tar.xz` → existing tar streaming),
      plus the exact paths `startInside` hardcodes
      (`TawcrootMethod.kt:109-131`): `/usr/bin/env` and `/bin/bash` as
      busybox copies/links (busybox sh invoked as `bash -lc` runs ash;
      debootstrap is POSIX sh), `/bin/sh`, plus `/tmp` and `/root` dirs.
    - `mirror/` laid out as `dists/sid/InRelease`,
      `dists/sid/main/binary-<arch>/Packages.xz` (the by-hash bytes at the
      plain path), `pool/<Filename>` for downloaded debs.
    - `debootstrap/` from the asset tar; invoked as
      `DEBOOTSTRAP_DIR=/debootstrap sh /debootstrap/debootstrap …`.
    - `rootfs/` — the bootstrap target, moved/renamed into place as the
      real rootfs only after stage 2 succeeds (or bootstrap directly into
      the final path and wipe on failure; pick one and make failure
      cleanup exact).
    The workspace *is* a rootfs as far as the method layer cares:
    `runInside(workspacePath, …)` gives sh + binds (`/proc`, `/dev`,
    `/sys` from host) + tawcroot's virtual-root semantics (mknod
    pretend-success, virtual euid 0). That's the whole trick — no new
    execution machinery.
14. **Resolve the set**: run
    `debootstrap --arch=<arch> --print-debs --variant=minbase --no-check-sig
    --include=debian-archive-keyring sid /rootfs file:///mirror` in the
    workspace guest, capture stdout (80 names, far under the 256 KiB
    `runInside` cap). Map names through the step-11 index to
    `(Filename, SHA256, Size)`. The `--include` guarantees the keyring
    package lands even if apt's dependency set ever stops pulling it —
    `DebianSid.configure` writes a `Signed-By` pointing at the keyring
    file that package installs (`DebianSid.kt:63`).
15. **Download and verify each deb** into `mirror/pool/…`: existing
    `Downloader` per file (proxy-wrapped in dev), SHA-256 checked against
    the index entry before the file is considered present. Progress:
    aggregate bytes over the set (sizes known up front from the index), so
    the one-download `InstallProgress` percent keeps working — message
    string gains an `(n/m)` counter; no new stages, no UI model change.
    Cache: keep the pool under `BootstrapCache`'s dir as
    `bootstrap-pkgs-<cacheKey>/` and teach `sweepStale` about the
    directory form; files are content-addressed by their verified hash, so
    reuse across retries is safe and eviction is per-file.
16. **Stage 1**: same debootstrap argv as step 14 minus `--print-debs`,
    plus `--foreign`, in the workspace guest. It extracts the required set
    into `/rootfs` and writes `/rootfs/debootstrap/` for stage 2.
17. **Stage 2**: `runInside(rootfsPath,
    "/debootstrap/debootstrap --second-stage")` — inside the *real*
    rootfs now, using the freshly-unpacked dpkg/bash. Needs `/proc`:
    provided by the per-spawn binds, same as every `initPackageManager`
    run today. Then delete the workspace (busybox, mirror, debootstrap
    asset copy) and hand off to the unchanged
    `configure → initPackageManager → installBasePackages` path.

### Tests

- Unit, clearsign: check in a real `InRelease` (trimmed if the checked-in
  size offends, but keep the signed region byte-exact) +
  the shipped keyring under `app/src/test/resources/debian-archive/`
  (upstream filenames, dir-per-scheme like `minisign/`). Positive verify;
  tampered-body negative; dash-escaped-line canonicalization case; expired
  `Valid-Until` negative with a fixed fake clock; body-parsed-only-from-
  verified-region assertion (e.g. content smuggled outside the signed
  region is invisible).
- Unit, index: a checked-in Packages fragment → name→(Filename,SHA256)
  extraction, unknown-compression and truncated-stanza negatives.
- Unit, deb extraction: a tiny fixture `.deb` through the
  ar→control/data→tar path, plus a malformed-ar negative.
- Unit, flavor infra: the Part-1 fail-closed restructure; Installation
  round-trip with and without the new field.
- Integration: **none that install** — `notes/testing.md` bans real
  installs in the integration suite. On-device acceptance is scripted but
  manual (below).

### Acceptance

On the `.tawctarget` device, through the cache proxy: full packages-flavor
install of Debian sid; then `dpkg --audit` (empty), `apt-get check`,
`apt-get install` of something small, and a normal app launch
(lxterminal). Optionally diff `dpkg -l` and the file list against a host
`debootstrap --variant=minbase` of the same snapshot — nice-to-have, not
the bar it was in the old plan, because the same tool produced both.
Verify the tarball flavor still installs unchanged. tawcroot is the
must-pass method; try proot/chroot opportunistically and, if one
misbehaves, reject that flavor×method combination with a clear message
rather than special-casing.

## Risks, with handling

- **busybox `ar` applet.** debootstrap's fallback extractor needs `ar`
  (functions:1043). Debian's own installer runs debootstrap under its
  busybox, so `busybox-static` almost certainly includes it — but verify
  the applet list first thing (`busybox --list` on-device or the package's
  config). If absent: extract debs' members in Kotlin is *not* the answer
  (that re-opens the reimplementation door); instead pick the smallest
  fix (Debian bug/config reality determines: another provider package, or
  a one-applet shim) and document it.
- **zstd debs.** sid is xz today; if any required deb shows a `.zst`
  member, busybox can't decompress it. Fail loudly on unknown compressor
  in stage 1 rather than mysteriously later. (This is also why an Ubuntu
  packages flavor is future work: Ubuntu debs are zstd across the board.)
- **sid moves mid-install.** by-hash makes the index fetch race-free; a
  pool 404 between index and download means the archive rotated — do one
  clean re-resolve from a fresh `InRelease` (mirroring the tarball path's
  single evict-and-retry, `Installer.kt:172-236`), never mix files from
  two index generations.
- **Clock skew vs `Valid-Until`.** A badly wrong device clock bricks
  installs with a scary message; make the error name the clock and the
  remedy. Keep the skew allowance small and constant — do not make it
  configurable, that's a downgrade knob.
- **Clearsign canonicalization bugs** verify signatures over text that
  isn't what you parse. The vectors above are the defence; write them
  before the implementation, and include one adversarial case that would
  pass a naive non-canonicalizing implementation.
- **Key rotation.** Shipped keyring couples installs to APK releases
  (expiries ~2031/~2035, but new suites bring new keys). Release-prep
  checklist item (step 7); no runtime fetch.
- **`--print-debs` behavior offline.** Verify it works against a
  `file://` mirror with an empty pool (it should — resolution needs only
  the index). If it insists on pool access, fall back to letting stage 1
  itself fail on the first missing file and iterating is *not* acceptable
  (slow, ugly); instead pre-run `--print-debs` on a host mirror in CI to
  detect drift, or reconsider. Decide at implementation time; do not
  build a Kotlin closure walker as a "temporary" fallback.
- **Exec-from-app-data.** The whole app already runs rootfs binaries as
  the app uid under tawcroot, so the workspace busybox is not a new
  capability — but it is load-bearing; if a future Android tightens W^X
  for this app's domain, tarball flavor breaks equally, so this plan adds
  no new exposure.
- **Cache proxy stress.** ~80 fetches through nginx; pool files are
  immutable and cache well, `InRelease` is proxied in dev like every
  other verification endpoint (the inverted rule,
  `notes/cache-proxy.md:174-183` — update that section to name the new
  endpoints). Watch for the known abandoned-fill hang
  (`issues/cache-proxy-abandoned-fills-block-installs.md`).
- **Progress/UX.** Many failure points vs one download, on a phone,
  screen possibly off. The aggregate-percent approach keeps the existing
  UI honest; make per-file failures name the file and leave the log
  readable (`AptCommon.filteringLog` precedent).
- **debootstrap pin drift.** sid archive-format changes that break the
  pinned debootstrap are rare and loud; the fix is a deliberate pin bump,
  tested, via `scripts/update-deps.sh`.

## Later distros (out of scope, for shape only)

- **Ubuntu packages flavor**: identical apt mechanics (noble `InRelease`
  is clearsigned the same way) — blocked only on the zstd point above.
- **Void**: xbps signs repodata; `xbps-static` is a single static binary
  that installs into an empty root — the "real tool" is even easier than
  debootstrap. Would give Void the same shipped-root-of-trust upgrade;
  no `Valid-Until` equivalent, so the documented rollback residual
  remains.
- **ALARM/Manjaro**: pacman sync DBs are unsigned (upstream gap), but
  per-package detached sigs against the shipped keyrings still beat a
  tarball for trust, and here packages-flavor genuinely *shrinks* the
  install: the ALARM tarball hauls a kernel+firmware the app never uses.
  Weaker chain — treat as its own decision.

## Decide before starting

If the underlying goal was "a signed apt rootfs", land
[ubuntu-distro.md](ubuntu-distro.md) first — a day's work with existing
verification shapes. This plan is worth its size when (a) sid's freshness
specifically matters and (b) the flavor infrastructure is wanted as a
platform for the other distros above. Part 1 is independently useful and
reviewable on its own; land it as its own change.
