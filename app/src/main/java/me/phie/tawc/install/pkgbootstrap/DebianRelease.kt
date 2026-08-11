package me.phie.tawc.install.pkgbootstrap

import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The body of a verified `InRelease` file — parsed **only** from the
 * canonicalized, signature-checked region [Clearsign.verify] returns,
 * never from raw fetched bytes.
 *
 * Holds just what the packages bootstrap needs: freshness fields,
 * `Acquire-By-Hash`, and the `SHA256` file list.
 */
internal class DebianRelease private constructor(
    val date: Instant?,
    val validUntil: Instant?,
    val acquireByHash: Boolean,
    private val sha256ByPath: Map<String, FileEntry>,
) {
    /** One `SHA256:` list row: ` <hex> <size> <path>`. */
    data class FileEntry(val path: String, val sha256Hex: String, val sizeBytes: Long)

    fun fileEntry(path: String): FileEntry =
        sha256ByPath[path] ?: throw IOException(
            "InRelease has no SHA256 entry for '$path' — archive layout changed?",
        )

    /**
     * Enforce `Valid-Until` — reject, don't warn. This is the replay
     * defence: without it an origin-controlling attacker can serve
     * last month's fully-signed index and the whole chain still
     * verifies. [skew] is a small constant clock allowance (hours, not
     * days) and deliberately not configurable — a knob would be a
     * downgrade path.
     */
    fun requireFresh(now: Instant, skew: java.time.Duration) {
        val vu = validUntil
            ?: throw IOException(
                "InRelease carries no Valid-Until field; refusing — replay protection " +
                    "depends on it for this suite.",
            )
        if (now.isAfter(vu.plus(skew))) {
            throw IOException(
                "InRelease expired: Valid-Until $vu is in the past (now: $now). " +
                    "Either the mirror is serving a stale (possibly replayed) index, " +
                    "or this device's clock is wrong — check the date and time settings.",
            )
        }
    }

    companion object {
        /** `Date:` / `Valid-Until:` format, e.g. `Sun, 17 Aug 2026 02:08:06 UTC`. */
        private val DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

        fun parse(body: String): DebianRelease {
            var date: Instant? = null
            var validUntil: Instant? = null
            var acquireByHash = false
            val entries = HashMap<String, FileEntry>()
            var inSha256 = false
            for (line in body.lineSequence()) {
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    if (!inSha256) continue
                    // ' <hex> <size> <path>'
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size != 3) continue
                    val hex = parts[0].lowercase()
                    if (hex.length != 64 || !hex.all { it.isDigit() || it in 'a'..'f' }) continue
                    val size = parts[1].toLongOrNull() ?: continue
                    entries[parts[2]] = FileEntry(parts[2], hex, size)
                    continue
                }
                inSha256 = false
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val field = line.substring(0, colon)
                val value = line.substring(colon + 1).trim()
                when {
                    field.equals("Date", ignoreCase = true) -> date = parseDate(value, "Date")
                    field.equals("Valid-Until", ignoreCase = true) ->
                        validUntil = parseDate(value, "Valid-Until")
                    field.equals("Acquire-By-Hash", ignoreCase = true) ->
                        acquireByHash = value.equals("yes", ignoreCase = true)
                    field.equals("SHA256", ignoreCase = true) -> inSha256 = true
                }
            }
            if (entries.isEmpty()) {
                throw IOException("InRelease body has no SHA256 file list")
            }
            return DebianRelease(date, validUntil, acquireByHash, entries)
        }

        private fun parseDate(value: String, field: String): Instant = try {
            ZonedDateTime.parse(value, DATE_FORMAT).toInstant()
        } catch (e: DateTimeParseException) {
            throw IOException("InRelease $field '$value' does not parse: ${e.message}")
        }
    }
}
