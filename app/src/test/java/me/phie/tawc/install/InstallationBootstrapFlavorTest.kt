package me.phie.tawc.install

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `bootstrapFlavor` metadata round-trip: pre-flavor records (no field)
 * parse as `tarball` (the only flavor that existed when they were
 * written), and every write makes the value explicit so old records
 * converge on the next metadata write instead of leaning on the
 * default forever.
 */
class InstallationBootstrapFlavorTest {

    private fun record(flavor: String? = null): Installation = Installation(
        id = "sid",
        distro = Installation.DISTRO_DEBIAN_SID,
        arch = "arm64-v8a",
        method = "tawcroot",
        installedAtMillis = 1L,
        sourceUrl = "http://deb.debian.org/debian",
    ).let { if (flavor != null) it.copy(bootstrapFlavor = flavor) else it }

    @Test
    fun missingFieldParsesAsTarball() {
        val json = JSONObject(record().toJson())
        json.remove("bootstrapFlavor")
        val parsed = Installation.fromJson(json.toString())
        assertEquals(Installation.FLAVOR_TARBALL, parsed.bootstrapFlavor)
    }

    @Test
    fun fieldIsAlwaysWritten() {
        // Even the default value is written explicitly, so legacy
        // records converge on their next save.
        assertTrue(JSONObject(record().toJson()).has("bootstrapFlavor"))
    }

    @Test
    fun packagesRoundTrips() {
        val parsed = Installation.fromJson(record(Installation.FLAVOR_PACKAGES).toJson())
        assertEquals(Installation.FLAVOR_PACKAGES, parsed.bootstrapFlavor)
    }
}
