package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format + packaging helpers for the install-time DE picker
 * ([DesktopOptions]): selection parsing and package resolution.
 */
class DesktopOptionsTest {

    @Test
    fun parseSelectionParsesCommaSeparatedIds() {
        assertEquals(listOf("xfce", "lxqt"), DesktopOptions.parseSelection("xfce,lxqt").map { it.id })
    }

    @Test
    fun parseSelectionToleratesSpacesAndBlank() {
        assertEquals(listOf("lxqt", "xfce"), DesktopOptions.parseSelection("  lxqt , xfce ").map { it.id })
        assertTrue(DesktopOptions.parseSelection(null).isEmpty())
        assertTrue(DesktopOptions.parseSelection("").isEmpty())
        assertTrue(DesktopOptions.parseSelection(" , , ").isEmpty())
    }

    @Test
    fun parseSelectionDropsUnknownIds() {
        assertEquals(listOf("xfce"), DesktopOptions.parseSelection("xfce,mate,nope").map { it.id })
    }

    @Test
    fun packagesForDeduplicatesInSelectionOrder() {
        val opts = DesktopOptions.parseSelection("lxqt,xfce")
        val packages = DesktopOptions.packagesFor(opts)
        assertEquals(listOf("lxqt", "openbox", "xfce4"), packages)
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun optionsHaveNoSetupScripts() {
        // Both remaining DEs are plain package installs — no per-DE
        // setup scripts are needed anymore.
        for (o in DesktopOptions.ALL) {
            assertEquals("", o.setupScript)
        }
        assertEquals("", DesktopOptions.setupScriptFor(DesktopOptions.ALL))
    }
}
