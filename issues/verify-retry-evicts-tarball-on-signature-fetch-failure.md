# A timed-out `.sig` fetch costs a full bootstrap re-download

`Installer.install`'s verify stage retries once on any `IOException`
from `SignatureVerifier.verify`, and the retry path calls
`cache.evict(...)` before re-downloading. That is right for a signature
*mismatch* (the cached bytes are by definition not what we want) but
wrong for a signature *fetch* failure, where the tarball may be
perfectly good and only the 566-byte `.sig` GET fell over.

Observed on the physical device 2026-08-10 during the ALARM PGP
switchover, with the dev cache proxy in play:

    D verify: Pgp
    D Verifying PGP signature for bootstrap-arch-aarch64.tar.gz
    D verify: failed (timeout); evicting local cache and retrying once
    D download: …/ArchLinuxARM-aarch64-latest.tar.gz (retry 1)
    I Bootstrap PGP signature verified: … signed by 0x77193F152BDBE6A6

The `.sig` fetch hit `downloadBytes`' 30s timeout (the proxy was
fetching it cold from fl.us). The retry then threw away a byte-correct
829 MB tarball and pulled all of it again — about 3.5 minutes here, but
on a phone on mobile data that is a large, silent cost for a transient
blip. It recovered, so this is waste rather than breakage.

Both PGP distros are exposed; the `Sha256` ones less so, since their
digest is resolved before the download rather than fetched at verify
time.

Fix sketch: distinguish "could not obtain the signature" from "the
signature did not verify". Either throw a distinct exception type from
the `.sig` download inside `verifyPgp`, or have `verify` signal
retry-without-evict, and only evict when the tarball's own bytes are
implicated. Retrying the `.sig` fetch a couple of times before giving
up would also help, since it is tiny.

Do not fix by removing the retry — it is what recovers from the
mutable-`latest` race where the tarball is rebuilt between our fetch
and the signature's (see notes/installation.md "ALARM bootstrap").
