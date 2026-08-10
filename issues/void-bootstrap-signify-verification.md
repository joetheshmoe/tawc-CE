# Void bootstrap: verify the signify signature on sha256sum.txt

Void's bootstrap verification is a same-origin SHA-256: both
`sha256sum.txt` and the ROOTFS tarball come from
`repo-default.voidlinux.org/live/current/`, so a compromised origin (or
mis-issued cert) serves a matching pair and the install proceeds. See
notes/installation.md "Same-origin SHA-256 bootstraps".

A real upgrade is available: upstream publishes `sha256sum.sig` next to
`sha256sum.txt` — an OpenBSD-signify (Ed25519) signature. Verified
2026-08-10; the sig's untrusted comment reads "This key is only valid
for images with date 20250202", i.e. keys are per-image-date. The
pubkeys are published in the `void-release-keys` package
(`srcpkgs/void-release-keys/files/void-release-<date>.pub` in
void-linux/void-packages on GitHub).

Verifying the signature makes the attack require compromising both the
Void repo host and the void-packages GitHub repo — a genuine second
origin, roughly the ALARM cross-mirror tier.

Sketch:
- signify verification is small: Ed25519 over the raw file, key/sig are
  base64 lines after the comment line. BouncyCastle (already shipped)
  has Ed25519.
- Key handling: ship a small keyring of known `.pub`s and/or fetch the
  matching-date `.pub` from raw.githubusercontent.com at resolve time
  (the cross-origin property comes from GitHub being independent of
  voidlinux.org). Fail closed if the image date has no obtainable key.
- Wire in as either a new `BootstrapVerification` variant or inside
  `VoidSha256Resolver` before it trusts `sha256sum.txt`.
- Route resolve-time fetches through `mirrorProxy` like the existing
  resolver does.

Found in the 2026-08 app-side security sweep (follow-up from the
resolved bootstrap-integrity-same-origin-digests issue; the fail-open
`None` default and doc/UI honesty parts were fixed, Debian was
commit-pinned, and Debian/Manjaro have no out-of-band digest to
upgrade to).
