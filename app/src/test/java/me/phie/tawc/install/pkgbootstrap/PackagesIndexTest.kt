package me.phie.tawc.install.pkgbootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.IOException

class PackagesIndexTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/debian-archive/$name")?.readBytes()
            ?: error("missing test fixture debian-archive/$name")

    @Test
    fun parsesRealFragment() {
        // First stanzas of the real sid arm64 index plus the
        // busybox-static stanza the workspace build depends on.
        val idx = PackagesIndex.parse(ByteArrayInputStream(fixture("Packages.fragment")))
        assertTrue(idx.size >= 3)
        val bb = idx.getValue("busybox-static")
        assertTrue(bb.filename.startsWith("pool/main/b/busybox/"))
        assertEquals(64, bb.sha256Hex.length)
        assertTrue(bb.sizeBytes > 100_000)
    }

    @Test
    fun firstStanzaWinsOnDuplicates() {
        val text = """
            Package: dup
            Filename: pool/main/d/dup/dup_1_arm64.deb
            Size: 10
            SHA256: ${"a".repeat(64)}

            Package: dup
            Filename: pool/main/d/dup/dup_2_arm64.deb
            Size: 20
            SHA256: ${"b".repeat(64)}

        """.trimIndent()
        val idx = PackagesIndex.parse(ByteArrayInputStream(text.toByteArray()))
        assertEquals("pool/main/d/dup/dup_1_arm64.deb", idx.getValue("dup").filename)
    }

    @Test
    fun truncatedStanzaThrows() {
        val text = "Package: cut-short\nFilename: pool/main/c/cut/cut_1_arm64.deb\n"
        val e = assertThrows(IOException::class.java) {
            PackagesIndex.parse(ByteArrayInputStream(text.toByteArray()))
        }
        assertTrue(e.message!!.contains("cut-short"))
    }

    @Test
    fun badSha256Throws() {
        val text = "Package: bad\nFilename: f\nSize: 1\nSHA256: nothex\n\n"
        assertThrows(IOException::class.java) {
            PackagesIndex.parse(ByteArrayInputStream(text.toByteArray()))
        }
    }

    @Test
    fun emptyIndexThrows() {
        assertThrows(IOException::class.java) {
            PackagesIndex.parse(ByteArrayInputStream(ByteArray(0)))
        }
    }

    @Test
    fun garbageXzStreamThrows() {
        // The installer feeds the index through XZInputStream; content
        // that is not xz (mislabeled compression) must throw, not
        // parse to nonsense.
        assertThrows(IOException::class.java) {
            XZInputStream(ByteArrayInputStream("not an xz stream".toByteArray())).use {
                PackagesIndex.parse(it)
            }
        }
    }
}
