package me.phie.tawc.install.pkgbootstrap

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Streaming parse of an apt `Packages` index into
 * `name -> (Filename, Size, SHA256)` — and nothing else. No Depends
 * parsing, no stanza object graph: dependency resolution is
 * debootstrap's job (design principle 1 in notes/installation.md
 * "Bootstrap flavors"); Kotlin only needs to be able to download and
 * hash-verify the files debootstrap will ask for.
 *
 * sid's arm64 index is ~75k stanzas / 63 MB decompressed; this keeps
 * only the three fields per package (~10 MB of strings), streamed line
 * by line.
 */
internal object PackagesIndex {

    data class Pkg(val name: String, val filename: String, val sizeBytes: Long, val sha256Hex: String)

    /**
     * Parse [input] (already decompressed). First stanza wins on
     * duplicate names, matching apt's own preference order within one
     * index. A stanza that ends (blank line or EOF) with only some of
     * the required fields present is a truncated/corrupt index —
     * throws rather than silently dropping packages.
     */
    fun parse(input: InputStream): Map<String, Pkg> {
        val out = HashMap<String, Pkg>(90_000)
        val reader = BufferedReader(input.reader(Charsets.UTF_8), 1 shl 16)
        var name: String? = null
        var filename: String? = null
        var size: Long? = null
        var sha256: String? = null
        var lineNo = 0L

        fun endStanza() {
            val n = name ?: return  // stanza without Package: — ignore
            if (filename == null || size == null || sha256 == null) {
                throw IOException(
                    "Packages index stanza for '$n' is missing " +
                        "Filename/Size/SHA256 — truncated or corrupt index (near line $lineNo)",
                )
            }
            out.putIfAbsent(n, Pkg(n, filename!!, size!!, sha256!!))
            name = null; filename = null; size = null; sha256 = null
        }

        while (true) {
            if (Thread.interrupted()) throw InterruptedIOException("index parse cancelled")
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isEmpty()) {
                endStanza()
                continue
            }
            // Continuation lines and fields we don't track are skipped
            // by the prefix checks below.
            when {
                line.startsWith("Package:") -> name = line.substring(8).trim()
                line.startsWith("Filename:") -> filename = line.substring(9).trim()
                line.startsWith("Size:") -> size = line.substring(5).trim().toLongOrNull()
                    ?: throw IOException("Packages index: bad Size at line $lineNo")
                line.startsWith("SHA256:") -> {
                    val hex = line.substring(7).trim().lowercase()
                    if (hex.length != 64 || !hex.all { it.isDigit() || it in 'a'..'f' }) {
                        throw IOException("Packages index: bad SHA256 at line $lineNo")
                    }
                    sha256 = hex
                }
            }
        }
        endStanza()
        if (out.isEmpty()) throw IOException("Packages index parsed to zero packages")
        return out
    }
}
