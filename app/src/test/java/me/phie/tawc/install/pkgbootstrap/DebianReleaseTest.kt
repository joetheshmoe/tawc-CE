package me.phie.tawc.install.pkgbootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

class DebianReleaseTest {

    private val skew = Duration.ofHours(3)

    private val body = """
        Origin: Debian
        Suite: unstable
        Codename: sid
        Date: Mon, 10 Aug 2026 02:08:06 UTC
        Valid-Until: Mon, 17 Aug 2026 02:08:06 UTC
        Acquire-By-Hash: yes
        SHA256:
         aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 123 main/binary-arm64/Packages.xz
         bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 456 main/binary-arm64/Packages
    """.trimIndent()

    @Test
    fun parsesFieldsAndFileList() {
        val r = DebianRelease.parse(body)
        assertTrue(r.acquireByHash)
        assertEquals(Instant.parse("2026-08-10T02:08:06Z"), r.date)
        assertEquals(Instant.parse("2026-08-17T02:08:06Z"), r.validUntil)
        val e = r.fileEntry("main/binary-arm64/Packages.xz")
        assertEquals("a".repeat(64), e.sha256Hex)
        assertEquals(123L, e.sizeBytes)
    }

    @Test
    fun missingEntryThrows() {
        val r = DebianRelease.parse(body)
        assertThrows(IOException::class.java) { r.fileEntry("main/binary-riscv64/Packages.xz") }
    }

    @Test
    fun freshIndexPasses() {
        val r = DebianRelease.parse(body)
        r.requireFresh(Instant.parse("2026-08-10T12:00:00Z"), skew)
        // Inside the skew allowance just past expiry is still accepted.
        r.requireFresh(Instant.parse("2026-08-17T04:00:00Z"), skew)
    }

    @Test
    fun expiredIndexIsRejectedAndNamesTheClock() {
        val r = DebianRelease.parse(body)
        val e = assertThrows(IOException::class.java) {
            r.requireFresh(Instant.parse("2026-08-18T00:00:00Z"), skew)
        }
        assertTrue(e.message!!.contains("expired"))
        assertTrue(e.message!!.contains("clock"))
    }

    @Test
    fun missingValidUntilIsRejected() {
        val noVu = body.lineSequence().filterNot { it.startsWith("Valid-Until") }
            .joinToString("\n")
        val r = DebianRelease.parse(noVu)
        val e = assertThrows(IOException::class.java) {
            r.requireFresh(Instant.parse("2026-08-10T12:00:00Z"), skew)
        }
        assertTrue(e.message!!.contains("Valid-Until"))
    }

    @Test
    fun unparseableDateThrows() {
        assertThrows(IOException::class.java) {
            DebianRelease.parse(body.replace("Mon, 17 Aug 2026", "someday soon"))
        }
    }

    @Test
    fun emptyFileListThrows() {
        assertThrows(IOException::class.java) {
            DebianRelease.parse("Origin: Debian\nSuite: unstable")
        }
    }
}
