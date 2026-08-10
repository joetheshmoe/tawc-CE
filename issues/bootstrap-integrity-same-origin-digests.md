# Bootstrap integrity: same-origin digests, and `None` as the default

Two related problems in the download→verify gate
(`SignatureVerifier`, `BootstrapVerification`, `distro/*`).

## 1. Three distros verify against a digest from the tarball's own origin

Only two of the five shipping distros have a real integrity barrier:

- `ArchLinuxX86_64` — detached PGP against a pinned key in
  `res/raw/arch_signing_key.asc`. Strong.
- `ArchLinuxArm` — `CrossMirrorMd5` across two independently operated
  HTTPS mirrors. Weaker, but the cross-check is real.

The other three use `BootstrapVerification.Sha256` with a digest fetched
from **the same TLS origin that serves the tarball**:

- `VoidLinux` — `sha256sum.txt` and the rootfs both from
  `repo-default.voidlinux.org/live/current/`.
- `DebianSid` — `image-manifest.json` and `rootfs.tar.gz` from the same
  `raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-<arch>/<suite>/oci/blobs/`
  path, on a mutable branch tip.
- `ManjaroArm` — GitHub Releases API `digest` for a GitHub-hosted asset.

That detects mid-download corruption and redirect-to-a-different-host.
It is not an integrity barrier: a compromised origin, a mis-issued cert,
or (for Debian) a force-push to `dist-<arch>` serves a matching
tarball/digest pair and the install proceeds. The extracted tree is then
what the user runs desktop apps in.

`SignatureVerifier.Sha256`'s KDoc and the Void/Manjaro class docs state
this honestly. `DebianDockerResolver` carries no such note, and
`Installer`'s stage-2 comment still reads "PGP-verify the just-downloaded
tarball" generically. At minimum the docs should agree; better, the
install UI should distinguish "signed" from "checksummed by the same
host" rather than showing an undifferentiated verify step.

## 2. `BootstrapVerification.None` is the static default and fails open

`VoidLinux`, `ManjaroArm`, and `DebianSid` all declare
`verification = BootstrapVerification.None` in their static `bootstrap`
field and depend on overriding `resolveBootstrap()` to substitute the
real `Sha256`. Nothing enforces that pairing. A new distro — or a
refactor that drops an override — installs with **no verification at
all**, and the only signal is a `Log.w` in logcat plus one
`verify: None` line in the op log. No user-visible warning, no failure.

`Downloader` leans on the verify gate twice (reuse-cache-when-size-
matches, and trust-cache-when-HEAD-fails), both justified in comments by
"the integrity layer catches it" — so a silent `None` also un-gates
those.

This reads as a structural problem rather than a missing check: the type
lets a distro declare a placeholder policy that is indistinguishable at
the call site from a deliberate one, and the "real" policy lives in a
method that is optional to override. Worth looking at how
`Distro.bootstrap` / `Distro.resolveBootstrap` / `BootstrapVerification`
divide responsibility before picking a fix — the goal is that a distro
cannot end up unverified without someone writing something that
obviously says so.

Relevant: `install/distro/Distro.kt`, `install/distro/*/*.kt`,
`install/SignatureVerifier.kt`, `install/Installer.kt` (stage 1–2 loop),
`install/Downloader.kt`, notes/installation.md "Bootstrap integrity".

Found in the 2026-08 app-side security sweep.
