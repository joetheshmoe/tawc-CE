package me.phie.tawc.install.pkgbootstrap

import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * OpenPGP cleartext-signature verification (RFC 4880 §7) for
 * `InRelease`-style documents.
 *
 * This is deliberately NOT the detached-signature code path
 * [me.phie.tawc.install.SignatureVerifier] uses: a clearsigned body
 * must be canonicalized before hashing — trailing whitespace stripped
 * per line, lines joined with CRLF, no line break after the last line —
 * and dash-escaped lines (`- -----…`) un-escaped. BouncyCastle's
 * [ArmoredInputStream] handles the dash-unescaping while reading the
 * cleartext region; the canonicalization is ported from BC's own
 * `ClearSignedFileProcessor` example and pinned by the vectors in
 * `app/src/test/resources/debian-archive/` (a real `InRelease`, a
 * tampered body, a dash-escaped case, a smuggling case).
 *
 * The verified, canonicalized body is what [verify] returns. **Parse
 * only that** — never the raw fetched bytes. Content outside the
 * signed region (before the `BEGIN PGP SIGNED MESSAGE` header, after
 * the signature block) is invisible to callers by construction.
 */
internal object Clearsign {

    /**
     * Verify the clearsigned [document] against [keys] and return the
     * canonicalized signed body (`\n`-separated lines, per-line
     * trailing whitespace stripped).
     *
     * Signature policy: at least one signature must verify under a key
     * in [keys]. Signatures by keys we don't ship are skipped — Debian
     * co-signs each `InRelease` with the current and previous suite
     * keys, and a future key we don't carry must not break the ones we
     * do. A signature by a *shipped* key that fails to verify is a
     * hard error: that pattern is tampering, not rotation.
     *
     * @throws IOException on malformed input, no known key, or bad
     *   signature.
     */
    fun verify(document: ByteArray, keys: PGPPublicKeyRingCollection, label: String): ByteArray {
        val ain = ArmoredInputStream(ByteArrayInputStream(document))

        // Phase 1: collect the canonicalized cleartext region. Lines
        // arrive dash-unescaped from ArmoredInputStream; trailing
        // whitespace (and the line separator itself) is stripped here.
        val lines = mutableListOf<ByteArray>()
        val lineOut = ByteArrayOutputStream()
        var lookAhead = readInputLine(lineOut, ain)
        if (lookAhead == -1 || !ain.isClearText) {
            throw IOException("$label is not an OpenPGP clearsigned document")
        }
        lines.add(withoutTrailingWhitespace(lineOut.toByteArray()))
        while (lookAhead != -1 && ain.isClearText) {
            lookAhead = readInputLine(lineOut, lookAhead, ain)
            lines.add(withoutTrailingWhitespace(lineOut.toByteArray()))
        }
        // The read that crossed into the signature armor flips
        // isClearText; the final cleartext line has already been
        // consumed and appended by then. The line separator preceding
        // the armor header belongs to the armor, not the text (RFC
        // 4880 §7), which the canonical no-trailing-CRLF hashing below
        // encodes.

        // Phase 2: read the signature list from the armor tail.
        val objects = BcPGPObjectFactory(ain)
        val sigList = objects.nextObject() as? PGPSignatureList
            ?: throw IOException("$label: no signature list after clearsigned body")
        if (sigList.isEmpty) throw IOException("$label: empty signature list")

        // Phase 3: verify. Hash = line1 CRLF line2 CRLF … lineN, each
        // line already stripped of trailing whitespace.
        var verified = 0
        var known = 0
        for (i in 0 until sigList.size()) {
            val sig: PGPSignature = sigList[i]
            val key = keys.getPublicKey(sig.keyID) ?: continue
            known++
            sig.init(BcPGPContentVerifierBuilderProvider(), key)
            for ((idx, line) in lines.withIndex()) {
                if (idx > 0) {
                    sig.update('\r'.code.toByte())
                    sig.update('\n'.code.toByte())
                }
                sig.update(line, 0, line.size)
            }
            if (sig.verify()) {
                verified++
            } else {
                throw IOException(
                    "$label: signature by shipped key 0x${java.lang.Long.toHexString(sig.keyID)} " +
                        "FAILED to verify. The file is corrupt or tampered with.",
                )
            }
        }
        if (known == 0) {
            throw IOException(
                "$label: none of the ${sigList.size()} signatures were made by a key in the " +
                    "shipped keyring. Either the archive rotated all its signing keys " +
                    "(update the app) or this is a forged file.",
            )
        }
        check(verified > 0)

        // Reconstruct the body for parsing: same canonical lines, \n
        // separated. (The CRLF form above exists only inside the hash.)
        val body = ByteArrayOutputStream()
        for ((idx, line) in lines.withIndex()) {
            if (idx > 0) body.write('\n'.code)
            body.write(line, 0, line.size)
        }
        return body.toByteArray()
    }

    // ---- line reading, ported from BC's ClearSignedFileProcessor ----

    private fun withoutTrailingWhitespace(line: ByteArray): ByteArray {
        var end = line.size
        while (end > 0 && isWhiteSpace(line[end - 1])) end--
        return line.copyOf(end)
    }

    private fun isWhiteSpace(b: Byte): Boolean =
        b == '\r'.code.toByte() || b == '\n'.code.toByte() ||
            b == '\t'.code.toByte() || b == ' '.code.toByte()

    /** Read one line (including its separator) into [bOut]; returns the
     *  first byte of the next line (already consumed look-ahead) or -1. */
    private fun readInputLine(bOut: ByteArrayOutputStream, fIn: InputStream): Int {
        bOut.reset()
        var lookAhead = -1
        var ch: Int
        while (fIn.read().also { ch = it } >= 0) {
            bOut.write(ch)
            if (ch == '\r'.code || ch == '\n'.code) {
                lookAhead = readPassedEol(bOut, ch, fIn)
                break
            }
        }
        return lookAhead
    }

    private fun readInputLine(bOut: ByteArrayOutputStream, lookAhead0: Int, fIn: InputStream): Int {
        bOut.reset()
        var ch = lookAhead0
        do {
            bOut.write(ch)
            if (ch == '\r'.code || ch == '\n'.code) {
                return readPassedEol(bOut, ch, fIn)
            }
        } while (fIn.read().also { ch = it } >= 0)
        if (ch < 0) return -1
        return ch
    }

    private fun readPassedEol(bOut: ByteArrayOutputStream, lastCh: Int, fIn: InputStream): Int {
        var lookAhead = fIn.read()
        if (lastCh == '\r'.code && lookAhead == '\n'.code) {
            bOut.write(lookAhead)
            lookAhead = fIn.read()
        }
        return lookAhead
    }
}
