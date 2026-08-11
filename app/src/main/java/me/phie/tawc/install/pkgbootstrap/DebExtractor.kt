package me.phie.tawc.install.pkgbootstrap

import com.github.luben.zstd.ZstdInputStream
import me.phie.tawc.install.ProotArchiveExtractor
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Extract a `.deb`'s `data.tar.*` member into a directory, in-process:
 * `ar` via commons-compress, decompression via xz-java / zstd-jni /
 * java.util.zip, tar via the same deferred-dir-mode extractor the
 * tarball install path uses ([ProotArchiveExtractor.extractStream]).
 *
 * Used only to seed the bootstrap *workspace* (busybox + the perl the
 * vendored debootstrap needs) from archive-verified debs — the rootfs
 * itself is unpacked by debootstrap, never by this code. Keeping it
 * dumb (member find + stream) is deliberate; anything smarter would be
 * the start of a dpkg reimplementation.
 */
internal object DebExtractor {

    /**
     * Stream [deb]'s `data.tar[.xz|.gz|.zst]` member into [destDir].
     * Throws on a missing member or an unknown compression suffix —
     * loudly, per the "fail on unknown compressor" rule.
     */
    fun extractDataTar(deb: File, destDir: File, onLine: (String) -> Unit) {
        var found = false
        ArArchiveInputStream(BufferedInputStream(deb.inputStream(), 1 shl 16)).use { ar ->
            while (true) {
                val entry = ar.nextEntry ?: break
                if (!entry.name.startsWith("data.tar")) continue
                found = true
                val tar: InputStream = when (entry.name) {
                    "data.tar" -> ar
                    "data.tar.xz" -> XZInputStream(ar)
                    "data.tar.gz" -> java.util.zip.GZIPInputStream(ar)
                    "data.tar.zst" -> ZstdInputStream(ar)
                    else -> throw IOException(
                        "${deb.name}: unknown data member compression '${entry.name}'",
                    )
                }
                ProotArchiveExtractor.extractStream(
                    TarArchiveInputStream(tar), destDir.absolutePath, null, onLine,
                )
                break
            }
        }
        if (!found) throw IOException("${deb.name}: no data.tar member (not a .deb?)")
    }
}
