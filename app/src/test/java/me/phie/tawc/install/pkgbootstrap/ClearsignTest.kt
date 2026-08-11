package me.phie.tawc.install.pkgbootstrap

import me.phie.tawc.install.SignatureVerifier
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Vectors for [Clearsign] — written before the implementation was
 * trusted, per the plan's "canonicalization bugs verify signatures
 * over text that isn't what you parse" risk.
 *
 * Fixtures in `resources/debian-archive/`:
 *  - `InRelease` — the real sid InRelease (fetched 2026-08-10),
 *    signed by the bookworm + trixie archive keys shipped in
 *    `res/raw/debian_archive_keyring.asc`.
 *  - `InRelease.tampered` — same file with one hash digit flipped
 *    inside the signed region.
 *  - `dash-escapes.asc` — synthetic doc (signed by
 *    `test_key.asc`) whose body contains dash-prefixed lines,
 *    exercising the dash-escape canonicalization a naive
 *    implementation gets wrong.
 *  - `smuggled.asc` — the same signed doc with attacker content
 *    prepended and appended outside the signed region; the returned
 *    body must not contain it.
 */
class ClearsignTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/debian-archive/$name")?.readBytes()
            ?: error("missing test fixture debian-archive/$name")

    private val rawDir: File = sequenceOf(
        "src/main/res/raw",
        "app/src/main/res/raw",
        "../app/src/main/res/raw",
    ).map(::File).firstOrNull { it.isDirectory }
        ?: error("cannot locate res/raw from ${File(".").absolutePath}")

    private val debianKeys: PGPPublicKeyRingCollection =
        File(rawDir, "debian_archive_keyring.asc").inputStream().use {
            SignatureVerifier.parseKeyRing(it, "debian_archive_keyring")
        }

    private val testKeys: PGPPublicKeyRingCollection =
        fixture("test_key.asc").inputStream().use {
            SignatureVerifier.parseKeyRing(it, "test_key")
        }

    @Test
    fun realInReleaseVerifiesAgainstShippedKeyring() {
        val body = Clearsign.verify(fixture("InRelease"), debianKeys, "InRelease")
        val text = String(body, Charsets.UTF_8)
        assertTrue(text.startsWith("Origin: Debian"))
        assertTrue(text.contains("\nSuite: unstable\n") || text.contains("\nSuite: sid\n"))
        assertTrue(text.contains("\nSHA256:\n"))
        // The armor headers must not leak into the parsed body.
        assertFalse(text.contains("BEGIN PGP"))
    }

    @Test
    fun tamperedBodyIsRejected() {
        val e = assertThrows(IOException::class.java) {
            Clearsign.verify(fixture("InRelease.tampered"), debianKeys, "InRelease")
        }
        assertTrue(e.message!!.contains("FAILED"))
    }

    @Test
    fun signaturesByUnknownKeysAloneAreRejected() {
        // The dash-escape doc is signed only by the throwaway test key;
        // against the Debian keyring there is no known signer.
        val e = assertThrows(IOException::class.java) {
            Clearsign.verify(fixture("dash-escapes.asc"), debianKeys, "test-doc")
        }
        assertTrue(e.message!!.contains("shipped keyring"))
    }

    @Test
    fun dashEscapedLinesAreUnescapedInTheVerifiedBody() {
        val body = Clearsign.verify(fixture("dash-escapes.asc"), testKeys, "test-doc")
        val text = String(body, Charsets.UTF_8)
        // gpg dash-escaped these on signing; a naive implementation
        // that skips unescaping would either fail the signature or
        // return "- "-prefixed garbage for parsing.
        assertTrue(text.contains("\n-----BEGIN FAKE ARMOR-----\n"))
        assertTrue(text.contains("\n- leading dash-space line\n"))
        assertTrue(text.contains("\n--two dashes\n"))
        assertFalse(text.contains("- -----BEGIN FAKE"))
    }

    @Test
    fun contentOutsideTheSignedRegionIsInvisible() {
        val body = Clearsign.verify(fixture("smuggled.asc"), testKeys, "test-doc")
        val text = String(body, Charsets.UTF_8)
        assertFalse(text.contains("SMUGGLED"))
        // …and the genuine content is still there.
        assertTrue(text.contains("Origin: TawcTest"))
    }

    @Test
    fun trailingWhitespaceIsCanonicalizedAwayNotSignatureBreaking() {
        // Mutate the signed doc by appending spaces to a body line —
        // RFC 4880 hashes lines with trailing whitespace stripped, so
        // the signature must still verify, and the returned body must
        // carry the stripped form. (This is the adversarial case a
        // naive raw-bytes implementation gets wrong in both
        // directions.)
        val raw = String(fixture("dash-escapes.asc"), Charsets.UTF_8)
        val mutated = raw.replace("Suite: dashes", "Suite: dashes   ")
        assertTrue(mutated != raw)
        val body = Clearsign.verify(mutated.toByteArray(), testKeys, "test-doc")
        val text = String(body, Charsets.UTF_8)
        assertTrue(text.contains("\nSuite: dashes\n"))
        assertFalse(text.contains("Suite: dashes   "))
    }

    @Test
    fun nonClearsignedInputIsRejected() {
        assertThrows(IOException::class.java) {
            Clearsign.verify("Origin: Debian\nSuite: sid\n".toByteArray(), debianKeys, "plain")
        }
    }

    @Test
    fun bodyMatchesWhatDebianReleaseParses() {
        // End-to-end: the verified body must parse into the entry the
        // installer actually uses.
        val body = Clearsign.verify(fixture("InRelease"), debianKeys, "InRelease")
        val release = DebianRelease.parse(String(body, Charsets.UTF_8))
        assertTrue(release.acquireByHash)
        val entry = release.fileEntry("main/binary-arm64/Packages.xz")
        assertEquals(64, entry.sha256Hex.length)
        assertTrue(entry.sizeBytes > 1_000_000)
    }
}
