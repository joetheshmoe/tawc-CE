package me.phie.tawc.install

import me.phie.tawc.install.distro.voidlinux.VoidReleaseKeys
import me.phie.tawc.install.distro.voidlinux.VoidSha256Resolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The Void resolve path must not hand back a digest from a manifest it
 * hasn't authenticated. Exercised against the real upstream 20250202
 * `sha256sum.txt` / `sha256sum.sig` fixtures — see notes/installation.md
 * "Void: signed checksum manifest".
 */
class VoidSha256ResolverTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/minisign/$name")?.readBytes()
            ?: error("missing test fixture minisign/$name")

    private val manifest: ByteArray get() = fixture("sha256sum.txt")
    private val sigText: String get() = String(fixture("sha256sum.sig"), Charsets.UTF_8)

    private fun bundledKey(date: String) = Minisign.parsePublicKey(
        VoidReleaseKeys.bundled(date) ?: throw IOException("no bundled key for image date $date"),
        "bundled void-release-$date.pub",
    )

    private fun resolve(
        arch: String = "x86_64",
        manifestBytes: ByteArray = manifest,
        sig: String = sigText,
        keyForImageDate: (String) -> Minisign.PublicKey = ::bundledKey,
    ) = VoidSha256Resolver.resolveFromManifest(
        linuxArch = arch,
        manifestBytes = manifestBytes,
        sigText = sig,
        sigOrigin = "test://sha256sum.sig",
        keyForImageDate = keyForImageDate,
    )

    @Test
    fun resolvesGlibcRootfsForBothArches() {
        val x = resolve("x86_64")
        assertEquals("void-x86_64-ROOTFS-20250202.tar.xz", x.filename)
        assertTrue(x.downloadUrl.endsWith("/live/current/${x.filename}"))
        assertEquals(64, x.sha256Hex.length)

        val a = resolve("aarch64")
        assertEquals("void-aarch64-ROOTFS-20250202.tar.xz", a.filename)
        // The musl variant sits in the same manifest and must never win.
        assertTrue(!a.filename.contains("musl"))
    }

    @Test
    fun keyIsRequestedForTheImageDateOfTheSelectedEntry() {
        val asked = mutableListOf<String>()
        resolve(keyForImageDate = { asked += it; bundledKey(it) })
        assertEquals(listOf("20250202"), asked)
    }

    @Test
    fun tamperedDigestIsRejected() {
        // Flip one hex char of the x86_64 line's digest: a same-origin
        // attacker's edit, which the signature must catch.
        val text = String(manifest, Charsets.UTF_8)
        val line = text.lineSequence().first {
            it.startsWith("SHA256 (void-x86_64-ROOTFS-")
        }
        val forged = text.replace(line, line.dropLast(1) + if (line.last() == '0') '1' else '0')
        val e = assertThrows(IOException::class.java) {
            resolve(manifestBytes = forged.toByteArray(Charsets.UTF_8))
        }
        assertTrue(e.message!!.contains("FAILED"))
    }

    @Test
    fun unknownImageDateWithNoObtainableKeyFailsClosed() {
        val text = String(manifest, Charsets.UTF_8).replace("20250202", "20991231")
        val e = assertThrows(IOException::class.java) {
            resolve(manifestBytes = text.toByteArray(Charsets.UTF_8))
        }
        assertTrue(e.message!!.contains("20991231"))
    }

    @Test
    fun missingSignatureIsNotSilentlyAccepted() {
        assertThrows(IOException::class.java) { resolve(sig = "") }
    }

    @Test
    fun archWithNoEntryThrows() {
        val e = assertThrows(IOException::class.java) { resolve(arch = "riscv64") }
        assertTrue(e.message!!.contains("riscv64"))
    }
}
