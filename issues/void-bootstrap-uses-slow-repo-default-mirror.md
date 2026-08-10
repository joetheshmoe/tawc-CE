# Void bootstrap downloads from the slow repo-default mirror

`VoidSha256Resolver.MIRROR` is
`https://repo-default.voidlinux.org/live/current`, so the ~120 MB
ROOTFS tarball (plus `sha256sum.txt` / `sha256sum.sig`) comes from the
single Helsinki box. `VoidCommon`'s mirror comment already records the
measurement for the *package* repos: repo-default ~163 KB/s outside
Europe vs ~3 MB/s on Fastly, which is why runtime xbps repos already
point at `repo-fastly.voidlinux.org`. The bootstrap path never got the
same treatment.

Verified 2026-08-10: `https://repo-fastly.voidlinux.org/live/current/`
serves the same tree, and its `sha256sum.sig` is byte-identical to
repo-default's.

Switching is now also *safe* in a way it wasn't before: since
`VoidSha256Resolver` verifies the minisign signature on
`sha256sum.txt` against a key from void-packages on GitHub (see
notes/installation.md "Void: signed checksum manifest"), which mirror
serves the manifest and tarball no longer affects the trust story — a
bad mirror can only fail the signature or the SHA-256.

Not measured cleanly yet: the throughput comparison above was taken
while the local uplink was saturated, so re-measure before committing
to the change.

Found while implementing the Void signify/minisign verification.
