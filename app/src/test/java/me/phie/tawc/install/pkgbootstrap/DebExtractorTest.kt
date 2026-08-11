package me.phie.tawc.install.pkgbootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class DebExtractorTest {

    private fun fixtureFile(name: String): File {
        val bytes = javaClass.getResourceAsStream("/debian-archive/$name")?.readBytes()
            ?: error("missing test fixture debian-archive/$name")
        return File.createTempFile("deb-test", ".deb").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
    }

    @Test
    fun extractsDataTarMember() {
        // tiny.deb: ar(debian-binary, control.tar.gz, data.tar.xz),
        // data contains usr/bin/hello (+ a symlink, which the JVM's
        // stubbed android.system.Os cannot materialise — asserted on
        // the regular file only).
        val dest = kotlin.io.path.createTempDirectory("deb-extract").toFile()
        try {
            DebExtractor.extractDataTar(fixtureFile("tiny.deb"), dest) { }
            val hello = File(dest, "usr/bin/hello")
            assertTrue(hello.isFile)
            assertEquals("hello from tawc\n", hello.readText())
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun malformedArThrows() {
        val dest = kotlin.io.path.createTempDirectory("deb-extract").toFile()
        try {
            assertThrows(IOException::class.java) {
                DebExtractor.extractDataTar(fixtureFile("Packages.fragment"), dest) { }
            }
        } finally {
            dest.deleteRecursively()
        }
    }
}
