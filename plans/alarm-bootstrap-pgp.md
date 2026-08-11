# Verify the ALARM bootstrap with upstream's PGP signature

`ArchLinuxArm` is the last bootstrap whose trust root is mirror
infrastructure rather than a key we ship, and the last MD5 in the
install path. Its class doc and `notes/installation.md` both say to fix
this "when ALARM upstream starts signing". They have.

## Why now

Verified 2026-08-10 — `https://fl.us.mirror.archlinuxarm.org/os/`
carries three files, not two:

    ArchLinuxARM-aarch64-latest.tar.gz          (829367415 bytes)
    ArchLinuxARM-aarch64-latest.tar.gz.md5
    ArchLinuxARM-aarch64-latest.tar.gz.sig      (566 bytes)

The `.sig` is a real OpenPGP detached binary signature — `gpg
--list-packets` reports tag 2, sigclass `0x00`, RSA (algo 1),
SHA-512 (digest algo 10), issuer fingerprint
`68B3537F39A313B3E574D06777193F152BDBE6A6`. That key is
`Arch Linux ARM Build System <builder@archlinuxarm.org>`, rsa4096,
created 2014-01-18, no expiry — the same key that signs ALARM
packages, i.e. the one `pacman-key --populate archlinuxarm` already
trusts inside the rootfs after extract. Signature timestamp
2026-08-05 matches the tarball's `Last-Modified`, so it is re-signed
per rebuild rather than being a stale one-off. `ca.us` and `de3` serve
a byte-identical-length `.sig` too, so it is part of the publish
pipeline, not one mirror's local addition.

Not yet done, and **step 0 of this plan**: an end-to-end `gpg --verify`
of the signature against the actual 829 MB tarball. Attempted
2026-08-10; fl.us, ca.us and de3 were all under ~200 KB/s and the
download was abandoned. If that check fails, this whole plan is void.

## Current state

- `app/src/main/java/me/phie/tawc/install/distro/arch/ArchLinuxArm.kt:37`
  — `DistroBootstrap` declares `BootstrapVerification.CrossMirrorMd5`
  with `.md5` URLs on `fl.us` and `ca.us`. The class doc at :15-25
  explains the compromise and names the upgrade trigger.
- `app/src/main/java/me/phie/tawc/install/SignatureVerifier.kt:185`
  — `verifyCrossMirrorMd5`: fetch each `.md5` over HTTPS, require
  byte-for-byte agreement, then MD5 the tarball. ALARM is its **only**
  caller. Includes an offline fallback at :222-235 that accepts a
  locally written `<tarball>.md5.verified` sidecar when every mirror
  fetch fails, duplicating it to satisfy the two-source check.
- `SignatureVerifier.kt:123` — `verifyPgp`, already used by
  `ArchLinuxX86_64`: download `.sig`, parse, look the issuer key id up
  in the shipped keyring, stream the tarball through
  `signature.update`, throw on mismatch. Nothing new is needed here.
- `SignatureVerifier.kt:339-344` — `loadKeyRing` maps a resource *name*
  to an `R.raw` id through a hand-written `when`. Today the only entry
  is `arch_signing_key`; anything else throws "Missing PGP key
  resource".
- `app/src/main/res/raw/arch_signing_key.asc` — the one shipped key.

## Target

ALARM uses `BootstrapVerification.Pgp` against an app-shipped ALARM
build-system key. `CrossMirrorMd5`, its sidecar, and MD5 leave the
codebase.

## Steps

1. **Confirm the signature end-to-end** (blocking). Download the
   tarball from whichever mirror is not crawling that day, import the
   key, `gpg --verify ArchLinuxARM-aarch64-latest.tar.gz.sig
   ArchLinuxARM-aarch64-latest.tar.gz`. Do not proceed on failure —
   investigate instead, because a `.sig` that doesn't cover the
   published bytes is a worse signal than no `.sig` at all.

2. **Obtain the key from two independent origins and compare
   fingerprints.** Keyserver alone is not a good enough provenance
   story for a key we bake into the APK. Use
   `keyserver.ubuntu.com` (already confirmed to carry it) *and* the
   copy inside upstream's own `archlinuxarm-keyring` package
   (`/usr/share/pacman/keyrings/archlinuxarm.gpg` in an installed ALARM
   rootfs, or the package pulled from a mirror). Both must yield
   `68B3537F39A313B3E574D06777193F152BDBE6A6`. Export ASCII-armored.

3. **Ship it** as `app/src/main/res/raw/archlinuxarm_signing_key.asc`,
   mirroring how `arch_signing_key.asc` is stored (armored, key only,
   no extra uids/subkey cruft beyond what upstream publishes).

4. **Register the resource** — add
   `"archlinuxarm_signing_key" -> R.raw.archlinuxarm_signing_key` to
   the `when` in `SignatureVerifier.loadKeyRing`. Consider replacing
   the hand-written map with a `resources.getIdentifier` lookup only if
   it stays fail-closed; the explicit map is fine and greppable, so the
   default is to just add the line.

5. **Flip the distro** — in `ArchLinuxArm.kt`, hoist the tarball URL to
   a `BOOTSTRAP_URL` const the way `ArchLinuxX86_64` does, replace
   `CrossMirrorMd5` with:

       verification = BootstrapVerification.Pgp(
           signatureUrl = "$BOOTSTRAP_URL.sig",
           keyResource = "archlinuxarm_signing_key",
       ),

   and rewrite the class doc (:15-25) — it currently asserts upstream
   doesn't sign, which is the claim that made this stale for so long.
   `SECONDARY_MIRROR` becomes unused; drop it.

6. **Delete `CrossMirrorMd5`.** Once ALARM is off it, the variant, its
   `verify` dispatch arm, `verifyCrossMirrorMd5`, the
   `.md5.verified` sidecar read/write, and the MD5 rationale comment
   at :163-184 are all dead. Removing it is the point — leaving a
   "weaker verification available" variant sitting in a sealed class is
   how it comes back. Grep for `md5` across `app/` afterwards; the
   `MirrorProxy` and `SignatureVerifier.verify` KDoc both mention the
   ALARM `.md5` fetches and need updating.

7. **Tests.**
   - Add a unit test asserting each shipped `res/raw` key parses and
     has its expected fingerprint — covers both the new ALARM key and
     `arch_signing_key`, which has no such test today. Model it on
     `MinisignTest`'s "every bundled key parses" check. Note
     `loadKeyRing` needs a real `Context` for `res/raw`, so this likely
     belongs in `androidTest`, or the parse half can be factored to
     take an `InputStream` and unit-tested off a test-resources copy.
   - `BootstrapVerificationFailClosedTest` — drop any `CrossMirrorMd5`
     cases, keep the registry-wide placeholder check at :60 passing.
   - Keep a negative test: a `.sig` signed by a key not in the shipped
     ring must throw, not warn.

8. **Notes.** `notes/installation.md`: replace the ALARM row in the
   "What's verified today" table (:750), and delete the whole "Known
   weaker spot: ALARM bootstrap" section (:856-888) — its premise is
   gone. The "Verifier code" list (:890-902) loses `CrossMirrorMd5`.
   Mention in the hard-rules block that MD5 is no longer in the install
   path.

9. **Verify on device.** `.tawctarget` permitting, run a real ALARM
   install through the dev cache proxy
   (`--arg mirrorProxy=http://127.0.0.1:8080/proxy/`) and confirm the
   `Bootstrap PGP signature verified:` log line from
   `SignatureVerifier.kt:156` in `adb logcat -s tawc`. The proxy path
   already wraps `.sig` fetches for the Arch x86_64 flow, so this needs
   no proxy change — but do confirm the proxy actually caches the
   `.sig` rather than 404ing it.

## Risks and things that will bite

- **`latest` is a mutable URL.** A rebuild between our tarball fetch
  and our `.sig` fetch yields a legitimate mismatch. This is not new —
  the MD5 path has the same race — and `Installer.kt:220-235` already
  handles it with one automatic cache-evict-and-retry on verify
  failure. Confirm that retry still fires for the `Pgp` arm (it is
  keyed on the verify throwing, so it should) and don't remove it.
- **`Downloader` caches on Content-Length match**
  (`Downloader.kt:52-56`). A rebuilt tarball of identical size would be
  served from cache and fail verification; the evict-and-retry above is
  what saves us. Same as today.
- **Key rotation.** If ALARM rotates the build-system key, installs
  break with "not present in shipped keyring" until we ship an APK.
  That is the correct failure mode and matches Arch x86_64, but it does
  couple installs to release cadence. Do not add a runtime key fetch to
  soften it — that hands the trust decision back to the network.
- **Signature availability across mirrors.** Confirmed on fl.us, ca.us,
  de3; `nl` did not respond at all during testing. We only fetch from
  the primary, so this only matters if we ever add mirror failover.
- **Cost.** PGP streams the whole 829 MB through SHA-512 instead of
  MD5. Comparable, one pass either way, no extra download beyond the
  566-byte `.sig`.

## Out of scope

Manjaro ARM and Debian sid remain single-origin SHA-256. Upstream
publishes no detached signature for either (checked 2026-08-10: the
`manjaro-arm/rootfs` latest release has exactly one asset; Debian ships
no `.sign` next to sid's cloud-image `SHA512SUMS`). Fixing those means
bootstrapping from signed repo metadata instead of a prebuilt tarball —
a different, much larger plan.
