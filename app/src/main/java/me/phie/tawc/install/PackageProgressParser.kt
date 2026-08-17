package me.phie.tawc.install

import android.os.SystemClock
import me.phie.tawc.install.util.HumanSize

/**
 * Line-based progress parser for package-manager output (apt/dpkg,
 * pacman, xbps, flatpak). Feed it one line at a time from a streamed
 * command; it returns a [Tick] whenever the on-screen progress should
 * change, carrying a rich human status line ("Downloading packages ·
 * 34.2 MiB · 3.1 MiB/s") and a percent for the progress bar when one
 * is derivable.
 *
 * The tools report progress inconsistently:
 *  - apt prints per-file `Get:N … [size]` during download, a
 *    `Fetched X in Ys (Z/s)` summary, then `Unpacking …` / `Setting up …`
 *    per package (no i/N on a pipe, so package counts stand in).
 *  - pacman prints `:: Phase …` banners; percentages are tty-only.
 *  - xbps prints `[NN%]` bars and `[avg rate: …]`.
 *  - flatpak prints `Downloading: NN%` / `Installing: NN%` /
 *    `Receiving objects: NN% (i/j)`.
 *
 * Not thread-safe — one instance per streamed command. Throughput is a
 * smoothed byte-delta estimate so it stays sane even when lines arrive
 * in bursts.
 */
class PackageProgressParser(
    private val downloadLabel: String = "Downloading packages",
    private val configureLabel: String = "Installing packages",
    private val syncLabel: String = "Syncing package index",
) {
    private enum class Phase { SYNC, DOWNLOAD, CONFIGURE }

    private var phase = Phase.DOWNLOAD
    private var downloadedBytes = 0L
    private var lastBytes = 0L
    private var lastSampleMs = 0L
    private var speedBps = 0L
    /** Packages seen in `Get:` lines — used as the "of N" total for the configure phase. */
    private var downloadCount = 0
    /** Packages configured so far (dpkg `Unpacking` / `Setting up` lines). */
    private var configureCount = 0
    private var lastPercent = -1
    private var lastEmitMs = 0L

    data class Tick(
        val percent: Int?,
        val message: String,
    )

    fun feed(rawLine: String): Tick? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null

        // apt "Fetched X in Ys (Z/s)" — download complete, authoritative
        // throughput + total bytes. Configure phase follows.
        FETCHED_RE.find(line)?.let { m ->
            downloadedBytes = parseSize(m.groupValues[1], m.groupValues[2])
            speedBps = parseSize(m.groupValues[3], m.groupValues[4])
            phase = Phase.CONFIGURE
            return maybeEmit(force = true)
        }

        // apt "Get:N … [size]" — one package file downloaded.
        if (line.startsWith("Get:")) {
            downloadCount++
            GET_SIZE_RE.find(line)?.let { m ->
                addBytes(parseSize(m.groupValues[1], m.groupValues[2].ifEmpty { "B" }))
            }
            setPhase(Phase.DOWNLOAD)
            return maybeEmit()
        }

        // dpkg per-package progress (no i/N on a pipe, so we count).
        if (line.startsWith("Unpacking ") || line.startsWith("Setting up ") ||
            line.startsWith("Preparing to unpack ")
        ) {
            configureCount++
            return maybeEmit(force = setPhase(Phase.CONFIGURE))
        }

        // Generic percent — flatpak "NN%", xbps "[NN%]", pacman, dpkg
        // "Reading database … NN%". Banners never carry a percent, so
        // this is safe to check before the banner branch below.
        PERCENT_RE.find(line)?.let { m ->
            m.groupValues[1].toIntOrNull()?.coerceIn(0, 100)?.let { lastPercent = it }
            when {
                line.startsWith("Installing:") -> phase = Phase.CONFIGURE
                line.startsWith("Downloading:") || line.startsWith("Receiving") -> phase = Phase.DOWNLOAD
            }
            return maybeEmit(force = line.startsWith("Installing:"))
        }

        // pacman / xbps phase banners.
        val bannerPhase: Phase? = when {
            line.startsWith(":: Synchronizing") -> Phase.SYNC
            line.startsWith(":: Retrieving") || line.startsWith(":: Starting full system upgrade") -> Phase.DOWNLOAD
            line.startsWith("resolving dependencies") -> Phase.DOWNLOAD
            line.startsWith(":: Processing package changes") -> Phase.CONFIGURE
            else -> null
        }
        if (bannerPhase != null) {
            return maybeEmit(force = setPhase(bannerPhase))
        }

        // Throughput mention without a percent (xbps "[avg rate: …]",
        // flatpak "| 3.2 MB/s").
        SPEED_RE.find(line)?.let { m ->
            speedBps = parseSize(m.groupValues[1], m.groupValues[2])
        }
        return null
    }

    /** True when the phase actually changed (first tick of a new phase). */
    private fun setPhase(new: Phase): Boolean {
        if (new == phase) return false
        phase = new
        return true
    }

    private fun addBytes(size: Long) {
        val now = SystemClock.elapsedRealtime()
        downloadedBytes += size
        if (lastSampleMs != 0L && now > lastSampleMs) {
            val dt = now - lastSampleMs
            val delta = downloadedBytes - lastBytes
            val inst = delta * 1000 / dt
            speedBps = if (speedBps == 0L) inst else (speedBps * 3 + inst) / 4
        }
        lastBytes = downloadedBytes
        lastSampleMs = now
    }

    private fun maybeEmit(force: Boolean = false): Tick? {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastEmitMs < EMIT_INTERVAL_MS) return null
        lastEmitMs = now
        return snapshot()
    }

    private fun snapshot(): Tick {
        val speed = if (speedBps > 0) HumanSize.format(speedBps) + "/s" else null
        return when (phase) {
            Phase.SYNC -> Tick(
                percent = null,
                message = syncLabel + (speed?.let { " · $it" } ?: ""),
            )
            Phase.DOWNLOAD -> Tick(
                percent = null,
                message = "$downloadLabel · ${HumanSize.format(downloadedBytes)}" +
                    (speed?.let { " · $it" } ?: ""),
            )
            Phase.CONFIGURE -> {
                val percent = if (downloadCount > 0 && configureCount > 0) {
                    (configureCount * 100L / downloadCount).toInt().coerceIn(0, 100)
                } else if (lastPercent >= 0) lastPercent else null
                val message = when {
                    downloadCount > 0 -> "$configureLabel · $configureCount/$downloadCount"
                    lastPercent >= 0 -> "$configureLabel · $lastPercent%"
                    else -> configureLabel
                }
                Tick(percent = percent, message = message)
            }
        }
    }

    private fun parseSize(number: String, unit: String): Long {
        val n = number.replace(",", "").toDoubleOrNull() ?: return 0L
        val mult = when (unit.lowercase()) {
            "gb" -> 1024.0 * 1024.0 * 1024.0
            "mb" -> 1024.0 * 1024.0
            "kb" -> 1024.0
            else -> 1.0
        }
        return (n * mult).toLong()
    }

    private companion object {
        const val EMIT_INTERVAL_MS = 250L
        val GET_SIZE_RE = Regex("\\[([\\d.,]+)\\s*(kB|MB|GB|B)?\\]")
        val FETCHED_RE = Regex(
            "Fetched\\s+([\\d.,]+)\\s*(kB|MB|GB)\\s+in\\s+\\d+s\\s*\\(([\\d.,]+)\\s*(kB|MB|GB)/s\\)",
        )
        val PERCENT_RE = Regex("(\\d+)%")
        val SPEED_RE = Regex("([\\d.,]+)\\s*(kB|MB|GB|B)/s")
    }
}
