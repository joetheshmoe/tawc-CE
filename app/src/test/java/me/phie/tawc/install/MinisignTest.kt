package me.phie.tawc.install

import me.phie.tawc.install.distro.voidlinux.VoidReleaseKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Minisign verification against the real upstream vectors: Void's
 * `live/current/sha256sum.txt` + `sha256sum.sig` as fetched on
 * 2026-08-10, checked with the release key this app bundles. Covers
 * both the crypto (Ed25519 over BLAKE2b-512, the `ED` prehashed
 * algorithm Void uses) and the bundled keyring's correctness — a typo
 * in [VoidReleaseKeys] fails here rather than at install time.
 *
 * See notes/installation.md "Bootstrap integrity".
 */
class MinisignTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/minisign/$name")?.readBytes()
            ?: error("missing test fixture minisign/$name")

    private val manifest: ByteArray get() = fixture("sha256sum.txt")
    private val sigText: String get() = String(fixture("sha256sum.sig"), Charsets.UTF_8)

    private fun key(date: String): Minisign.PublicKey = Minisign.parsePublicKey(
        VoidReleaseKeys.bundled(date) ?: error("no bundled key for $date"),
        "bundled void-release-$date.pub",
    )

    @Test
    fun verifiesVoidManifestWithBundledKey() {
        val sig = Minisign.parseSignature(sigText, "sha256sum.sig")
        val k = key("20250202")
        assertEquals("f9f92a100fe7564d", k.keyIdHex)
        assertEquals(k.keyIdHex, sig.keyIdHex)
        assertTrue(sig.trustedComment!!.contains("20250202"))
        Minisign.verify(k, sig, manifest, "test manifest")
    }

    @Test
    fun tamperedManifestFails() {
        val sig = Minisign.parseSignature(sigText, "sha256sum.sig")
        val tampered = manifest.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x01).toByte()
        val e = assertThrows(IOException::class.java) {
            Minisign.verify(key("20250202"), sig, tampered, "test manifest")
        }
        assertTrue(e.message!!.contains("FAILED"))
    }

    @Test
    fun wrongReleaseKeyIsRejectedOnKeyId() {
        val sig = Minisign.parseSignature(sigText, "sha256sum.sig")
        val e = assertThrows(IOException::class.java) {
            Minisign.verify(key("20240314"), sig, manifest, "test manifest")
        }
        assertTrue(e.message!!.contains("key id mismatch"))
    }

    @Test
    fun tamperedTrustedCommentFails() {
        val lines = sigText.trim().lines().toMutableList()
        lines[2] = "trusted comment: This key is only valid for images with date 19700101."
        val sig = Minisign.parseSignature(lines.joinToString("\n"), "sha256sum.sig")
        val e = assertThrows(IOException::class.java) {
            Minisign.verify(key("20250202"), sig, manifest, "test manifest")
        }
        assertTrue(e.message!!.contains("global"))
    }

    @Test
    fun everyBundledReleaseKeyParses() {
        for (date in VoidReleaseKeys.bundledDates) {
            val k = key(date)
            assertEquals("key $date id length", 16, k.keyIdHex.length)
            assertTrue("key $date comment", k.comment.startsWith("untrusted comment:"))
        }
    }

    @Test
    fun malformedSignatureThrows() {
        assertThrows(IOException::class.java) {
            Minisign.parseSignature("untrusted comment: x\nnot-base64!!\n", "bad.sig")
        }
        assertThrows(IOException::class.java) {
            Minisign.parseSignature("untrusted comment: x\nAAAA\n", "short.sig")
        }
        assertThrows(IOException::class.java) {
            Minisign.parseSignature("no comment line\nAAAA\n", "nocomment.sig")
        }
    }
}
