package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Metadata parsing for [Installation.desktopOrientations] (per-entry
 * orientation force, notes/launcher.md): legacy-record default (absent
 * → empty), round-trip, omit-when-empty JSON shape, and the
 * [Installation.withEntryOrientation] copy helper.
 */
class InstallationDesktopOrientationsTest {

    private fun minimalRecord(extra: String = ""): String = """
        {
          "id": "arch",
          "arch": "arm64-v8a"
          $extra
        }
    """.trimIndent()

    @Test
    fun legacyRecordDefaultsToEmpty() {
        assertTrue(Installation.fromJson(minimalRecord()).desktopOrientations.isEmpty())
    }

    @Test
    fun roundTripsThroughJson() {
        val inst = Installation.fromJson(minimalRecord())
            .copy(desktopOrientations = mapOf("xfce.desktop" to "landscape"))
        assertEquals(
            mapOf("xfce.desktop" to "landscape"),
            Installation.fromJson(inst.toJson()).desktopOrientations,
        )
    }

    @Test
    fun explicitObjectParses() {
        val inst = Installation.fromJson(
            minimalRecord(""", "desktopOrientations": {"a.desktop": "landscape", "b.desktop": "portrait"}"""),
        )
        assertEquals(
            mapOf("a.desktop" to "landscape", "b.desktop" to "portrait"),
            inst.desktopOrientations,
        )
    }

    @Test
    fun unknownValuesDroppedOnParse() {
        val inst = Installation.fromJson(
            minimalRecord(""", "desktopOrientations": {"a.desktop": "sideways", "b.desktop": "portrait"}"""),
        )
        assertEquals(mapOf("b.desktop" to "portrait"), inst.desktopOrientations)
    }

    @Test
    fun emptyOmittedFromJson() {
        // Additive field is only serialized when non-empty, so legacy
        // records stay byte-identical after a load/save cycle.
        assertFalse(Installation.fromJson(minimalRecord()).toJson().contains("desktopOrientations"))
    }

    @Test
    fun withEntryOrientationSetsAndClears() {
        val base = Installation.fromJson(minimalRecord())
        val set = base.withEntryOrientation("xfce.desktop", "landscape")
        assertEquals(mapOf("xfce.desktop" to "landscape"), set.desktopOrientations)
        // "system" (follow system) removes the override — the
        // "disable the force" path.
        assertTrue(set.withEntryOrientation("xfce.desktop", "system").desktopOrientations.isEmpty())
        // Changing an entry is a plain overwrite.
        assertEquals(
            mapOf("xfce.desktop" to "portrait"),
            set.withEntryOrientation("xfce.desktop", "portrait").desktopOrientations,
        )
        // Clearing an entry that isn't there is a no-op.
        assertEquals(set.desktopOrientations, set.withEntryOrientation("firefox", "system").desktopOrientations)
    }

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
