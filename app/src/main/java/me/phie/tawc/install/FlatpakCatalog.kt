package me.phie.tawc.install

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One Flatpak app as shown in the store. Metadata comes from Flathub's
 * API at browse/search time (nothing bundled in the APK — see
 * notes/flatpak.md); [iconUrl] is fetched + cached on device by
 * [FlatpakIconLoader].
 */
data class FlatpakApp(
    val appId: String,
    val name: String,
    val summary: String,
    val iconUrl: String?,
    val developer: String?,
)

/**
 * Thin client for Flathub's JSON API (`flathub.org/api/v2`):
 *  - [fetchFeatured] — metadata for the curated [FEATURED_IDS] shelf.
 *  - [search] — server-side search, filtered to glibc arches we can run
 *    (aarch64 / x86_64).
 *
 * Plain `HttpURLConnection` + `org.json`, matching the rest of the app
 * (no HTTP library dependency). All failures degrade to empty/null so a
 * network hiccup renders as "nothing loaded" rather than a crash.
 */
object FlatpakCatalog {

    private const val API = "https://flathub.org/api/v2"

    /** Curated shelf, shown before the user searches. All aarch64-verified. */
    val FEATURED_IDS: List<String> = listOf(
        "org.mozilla.firefox",
        "org.libreoffice.LibreOffice",
        "org.gimp.GIMP",
        "org.inkscape.Inkscape",
        "org.kde.krita",
        "org.videolan.VLC",
        "com.visualstudio.code",
        "org.audacityteam.Audacity",
        "org.gnome.gedit",
        "com.github.tchx84.Flatseal",
    )

    fun fetchFeatured(): List<FlatpakApp> = FEATURED_IDS.mapNotNull { fetchApp(it) }

    /** Fetch one app's metadata from `/api/v2/appstream/<id>`. */
    fun fetchApp(appId: String): FlatpakApp? {
        val obj = http("$API/appstream/$appId", "GET", null) ?: return null
        val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null
        return FlatpakApp(
            appId = obj.optString("id").ifEmpty { appId },
            name = name,
            summary = obj.optString("summary"),
            iconUrl = obj.optString("icon").takeIf { it.isNotEmpty() },
            developer = obj.optString("developer_name").takeIf { it.isNotEmpty() },
        )
    }

    /** Search Flathub, dropping apps without an aarch64/x86_64 build. */
    fun search(query: String): List<FlatpakApp> {
        val body = JSONObject().put("query", query).toString()
        val obj = http("$API/search", "POST", body) ?: return emptyList()
        val hits = obj.optJSONArray("hits") ?: return emptyList()
        val out = mutableListOf<FlatpakApp>()
        for (i in 0 until hits.length()) {
            val h = hits.optJSONObject(i) ?: continue
            val arches = h.optJSONArray("arches")
            if (arches != null && !archSupported(arches)) continue
            out += FlatpakApp(
                appId = h.optString("app_id"),
                name = h.optString("name"),
                summary = h.optString("summary"),
                iconUrl = h.optString("icon").takeIf { it.isNotEmpty() },
                developer = h.optString("developer_name").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private fun archSupported(arches: JSONArray): Boolean {
        for (i in 0 until arches.length()) {
            when (arches.optString(i)) {
                "aarch64", "x86_64" -> return true
            }
        }
        return false
    }

    private fun http(url: String, method: String, body: String?): JSONObject? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                if (conn.responseCode !in 200..299) null
                else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
