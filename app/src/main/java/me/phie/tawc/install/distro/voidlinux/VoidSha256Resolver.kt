package me.phie.tawc.install.distro.voidlinux

import me.phie.tawc.install.Minisign
import me.phie.tawc.install.MirrorProxy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolve the latest Void Linux ROOTFS tarball for a given arch by
 * fetching `sha256sum.txt` from `repo-default.voidlinux.org/live/current/`,
 * **verifying its minisign signature**, and parsing out the matching line.
 *
 * Void publishes dated rootfs tarballs (`void-x86_64-ROOTFS-20250202.tar.xz`)
 * under a `current/` channel. There's no "latest" symlink to a stable
 * filename, so install-time resolution looks at the freshest manifest.
 *
 * The manifest and the tarball share an origin, so the SHA-256 alone
 * would only be a corruption / host-swap check. Void also publishes
 * `sha256sum.sig` next to it — an Ed25519 signature under a
 * per-image-date release key whose public half lives in **void-packages
 * on GitHub**, not on voidlinux.org ([VoidReleaseKeys]). Checking it
 * before we trust a single digest out of the manifest means forging a
 * bootstrap needs both origins, roughly the cross-mirror tier. See
 * notes/installation.md "Bootstrap integrity".
 *
 * Fails closed throughout: an unfetchable signature, an unknown image
 * date with no obtainable key, or a bad signature all throw, and the
 * install aborts before anything is downloaded.
 */
internal object VoidSha256Resolver {

    private const val MIRROR = "https://repo-default.voidlinux.org/live/current"

    data class Resolved(val downloadUrl: String, val filename: String, val sha256Hex: String)

    /**
     * Look up the latest ROOTFS tarball for [linuxArch] (e.g. `"x86_64"`,
     * `"aarch64"`) — the glibc variant, never musl. Throws [IOException]
     * if the manifest can't be fetched, its signature doesn't verify, or
     * the matching line is missing.
     */
    fun resolveLatest(
        linuxArch: String,
        mirrorProxy: MirrorProxy? = null,
        log: (String) -> Unit = {},
    ): Resolved {
        val manifestUrl = "$MIRROR/sha256sum.txt"
        // Keep the raw bytes: the signature covers the file verbatim, so
        // re-encoding a decoded String could break verification.
        val manifestBytes = downloadBytes(mirrorProxy?.wrap(manifestUrl) ?: manifestUrl)
        val sigUrl = "$MIRROR/sha256sum.sig"
        val sigText = try {
            String(downloadBytes(mirrorProxy?.wrap(sigUrl) ?: sigUrl), Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException(
                "Void sha256sum.sig could not be fetched from $sigUrl (${e.message}). " +
                    "Refusing to trust an unsigned sha256sum.txt.",
                e,
            )
        }
        return resolveFromManifest(
            linuxArch = linuxArch,
            manifestBytes = manifestBytes,
            sigText = sigText,
            sigOrigin = sigUrl,
            keyForImageDate = { date -> releaseKey(date, mirrorProxy, log) },
            log = log,
        )
    }

    /**
     * The pure half of [resolveLatest]: pick the newest matching entry
     * out of [manifestBytes], authenticate the manifest with the key
     * [keyForImageDate] returns for that entry's image date, and only
     * then hand back the parsed values. Split out so the whole
     * trust-critical sequence is unit-testable against real upstream
     * vectors without network access.
     */
    internal fun resolveFromManifest(
        linuxArch: String,
        manifestBytes: ByteArray,
        sigText: String,
        sigOrigin: String,
        keyForImageDate: (String) -> Minisign.PublicKey,
        log: (String) -> Unit = {},
    ): Resolved {
        val manifest = String(manifestBytes, Charsets.UTF_8)
        // Lines look like:
        //   SHA256 (void-x86_64-ROOTFS-20250202.tar.xz) = <64 hex>
        // We want the glibc rootfs, i.e. `void-<arch>-ROOTFS-*.tar.xz`,
        // explicitly excluding the `-musl-` variant.
        val pattern = Regex(
            """^SHA256 \((void-${Regex.escape(linuxArch)}-ROOTFS-(\d{8})\.tar\.xz)\) = ([0-9a-f]{64})\s*$""",
            RegexOption.IGNORE_CASE,
        )
        // If multiple dated entries exist, pick the newest (highest
        // date prefix). In practice `current/` only has one rootfs per
        // arch, but be defensive in case Void ever ships a transition.
        val best = manifest.lineSequence()
            .mapNotNull { pattern.find(it.trim()) }
            .maxByOrNull { it.groupValues[2] }
            ?: throw IOException(
                "Void sha256sum.txt has no entry for void-$linuxArch-ROOTFS-*.tar.xz; " +
                    "manifest start: " + manifest.lineSequence().take(5).joinToString(" / "),
            )
        val filename = best.groupValues[1]
        val imageDate = best.groupValues[2]
        val sha = best.groupValues[3].lowercase()

        // Authenticate the manifest before any digest parsed out of it
        // is used. The parse above is on untrusted bytes, but it only
        // selects *which* line to trust, and the key is chosen from the
        // same line's image date — an attacker steering that choice can
        // only steer us to a genuine upstream key.
        val signature = Minisign.parseSignature(sigText, sigOrigin)
        val key = keyForImageDate(imageDate)
        Minisign.verify(key, signature, manifestBytes, "Void sha256sum.txt (image $imageDate)")
        log("void: sha256sum.txt signature verified (minisign key ${key.keyIdHex}, image $imageDate)")

        return Resolved(
            downloadUrl = "$MIRROR/$filename",
            filename = filename,
            sha256Hex = sha,
        )
    }

    /**
     * Release key for [imageDate]: the bundled copy when we ship one,
     * otherwise fetched from void-packages on GitHub. Throws if neither
     * source yields a key — a Void image we cannot authenticate must not
     * install.
     */
    private fun releaseKey(
        imageDate: String,
        mirrorProxy: MirrorProxy?,
        log: (String) -> Unit,
    ): Minisign.PublicKey {
        VoidReleaseKeys.bundled(imageDate)?.let {
            return Minisign.parsePublicKey(it, "bundled void-release-$imageDate.pub")
        }
        val url = VoidReleaseKeys.urlFor(imageDate)
        log("void: image date $imageDate is newer than the bundled keyring; fetching $url")
        val text = try {
            String(downloadBytes(mirrorProxy?.wrap(url) ?: url), Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException(
                "Void image date $imageDate has no bundled release key and $url " +
                    "could not be fetched (${e.message}). Refusing to trust sha256sum.txt. " +
                    "Bundled dates: ${VoidReleaseKeys.bundledDates.sorted().joinToString(", ")}",
                e,
            )
        }
        return Minisign.parsePublicKey(text, url)
    }

    private fun downloadBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tawc-installer")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("GET $url returned HTTP $code")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
