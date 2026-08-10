package me.phie.tawc.install

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException
import java.util.Base64

/**
 * Minisign (Ed25519) detached-signature verification.
 *
 * Used by [me.phie.tawc.install.distro.voidlinux.VoidSha256Resolver] to
 * authenticate Void Linux's `sha256sum.txt` against a release key
 * published in a *different* origin (void-packages on GitHub) before
 * any digest from that manifest is trusted — see notes/installation.md
 * "Bootstrap integrity". Load-bearing security code: every parse error
 * and every mismatch throws, there is no lenient path.
 *
 * Minisign is signify's successor and shares its shape; upstream Void
 * calls these "signify" keys but the on-disk format is minisign's
 * (`.pub` comments literally read "minisign public key <id>"). File
 * layout — comment lines are plain text, payload lines are base64:
 *
 * ```
 * untrusted comment: <text>
 * <base64: alg(2) || key_id(8) || ed25519_sig(64)>
 * trusted comment: <text>
 * <base64: ed25519_sig(64) over (ed25519_sig || trusted_comment_text)>
 * ```
 *
 * Two signature algorithms exist and both are accepted:
 *  - `Ed` — Ed25519 over the raw file bytes.
 *  - `ED` — Ed25519 over BLAKE2b-512(file bytes). This is what Void
 *    publishes today, and what minisign uses by default since 0.6.
 *
 * The trailing trusted-comment block is optional (plain signify emits
 * only the first two lines). When present its global signature is
 * verified, matching minisign's own behaviour. Nothing in this app
 * *uses* the trusted comment, so its absence is not a downgrade — the
 * file signature is the security-relevant one.
 */
object Minisign {

    private const val PUBLIC_KEY_BYTES = 42 // alg(2) + key_id(8) + key(32)
    private const val SIGNATURE_BYTES = 74 // alg(2) + key_id(8) + sig(64)
    private const val GLOBAL_SIGNATURE_BYTES = 64
    private const val ALG_LEGACY = "Ed"
    private const val ALG_PREHASHED = "ED"
    private const val TRUSTED_COMMENT_PREFIX = "trusted comment: "

    /** Parsed `.pub`: an Ed25519 public key plus the key id it is filed under. */
    class PublicKey internal constructor(
        /** The file's `untrusted comment:` line, verbatim. Informational only. */
        val comment: String,
        /** Lowercase hex of the 8-byte key id, as it appears on the wire (little-endian). */
        val keyIdHex: String,
        internal val keyId: ByteArray,
        internal val ed25519: ByteArray,
    )

    /** Parsed `.sig`/`.minisig`. */
    class Signature internal constructor(
        internal val algorithm: String,
        /** Lowercase hex of the signer's 8-byte key id. */
        val keyIdHex: String,
        internal val keyId: ByteArray,
        internal val signature: ByteArray,
        /** Text after `trusted comment: `, or null when the block is absent. */
        val trustedComment: String?,
        internal val globalSignature: ByteArray?,
    )

    /**
     * Parse a minisign public key. [origin] names the source in error
     * messages (a URL, or "bundled void-release-<date>.pub").
     */
    fun parsePublicKey(text: String, origin: String): PublicKey {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val comment = lines.firstOrNull { it.startsWith("untrusted comment:") } ?: ""
        val blobLine = lines.firstOrNull { !it.startsWith("untrusted comment:") }
            ?: throw IOException("Minisign public key $origin has no base64 payload line")
        val blob = decode(blobLine, "public key", origin)
        if (blob.size != PUBLIC_KEY_BYTES) {
            throw IOException(
                "Minisign public key $origin is ${blob.size} bytes, expected $PUBLIC_KEY_BYTES",
            )
        }
        val alg = String(blob, 0, 2, Charsets.US_ASCII)
        if (alg != ALG_LEGACY) {
            throw IOException("Minisign public key $origin has algorithm '$alg', expected '$ALG_LEGACY'")
        }
        val keyId = blob.copyOfRange(2, 10)
        return PublicKey(comment, keyId.toHex(), keyId, blob.copyOfRange(10, PUBLIC_KEY_BYTES))
    }

    /**
     * Parse a minisign detached signature. [origin] names the source in
     * error messages.
     */
    fun parseSignature(text: String, origin: String): Signature {
        // Don't trim comment lines: the global signature covers the
        // trusted comment byte-for-byte, so trailing whitespace inside
        // it is signed data, not noise. Only `\r` (a CRLF-mangled
        // download) and trailing blank lines are dropped.
        val lines = text.split('\n').map { it.removeSuffix("\r") }
            .dropLastWhile { it.isEmpty() }
        if (lines.size < 2) {
            throw IOException("Minisign signature $origin has ${lines.size} line(s), expected at least 2")
        }
        if (!lines[0].startsWith("untrusted comment:")) {
            throw IOException("Minisign signature $origin does not start with an 'untrusted comment:' line")
        }
        val blob = decode(lines[1].trim(), "signature", origin)
        if (blob.size != SIGNATURE_BYTES) {
            throw IOException("Minisign signature $origin is ${blob.size} bytes, expected $SIGNATURE_BYTES")
        }
        val alg = String(blob, 0, 2, Charsets.US_ASCII)
        if (alg != ALG_LEGACY && alg != ALG_PREHASHED) {
            throw IOException(
                "Minisign signature $origin has unknown algorithm '$alg' " +
                    "(expected '$ALG_LEGACY' or '$ALG_PREHASHED')",
            )
        }
        val keyId = blob.copyOfRange(2, 10)
        val sig = blob.copyOfRange(10, SIGNATURE_BYTES)

        var trustedComment: String? = null
        var globalSig: ByteArray? = null
        if (lines.size >= 3) {
            if (!lines[2].startsWith(TRUSTED_COMMENT_PREFIX)) {
                throw IOException("Minisign signature $origin line 3 is not a 'trusted comment: ' line")
            }
            if (lines.size < 4) {
                throw IOException("Minisign signature $origin has a trusted comment but no global signature line")
            }
            trustedComment = lines[2].substring(TRUSTED_COMMENT_PREFIX.length)
            globalSig = decode(lines[3].trim(), "global signature", origin)
            if (globalSig.size != GLOBAL_SIGNATURE_BYTES) {
                throw IOException(
                    "Minisign global signature $origin is ${globalSig.size} bytes, " +
                        "expected $GLOBAL_SIGNATURE_BYTES",
                )
            }
        }
        return Signature(alg, keyId.toHex(), keyId, sig, trustedComment, globalSig)
    }

    /**
     * Verify [sig] over [data] with [key], throwing [IOException] on key-id
     * mismatch or bad signature. [what] names the signed artifact in
     * error messages. Returns normally only on a good signature.
     */
    fun verify(key: PublicKey, sig: Signature, data: ByteArray, what: String) {
        if (!sig.keyId.contentEquals(key.keyId)) {
            throw IOException(
                "Minisign key id mismatch for $what: signature is from ${sig.keyIdHex} " +
                    "but the key we resolved is ${key.keyIdHex}. Refusing to trust it.",
            )
        }
        val message = if (sig.algorithm == ALG_PREHASHED) blake2b512(data) else data
        if (!ed25519Verify(key.ed25519, sig.signature, message)) {
            throw IOException(
                "Minisign signature verification FAILED for $what (key ${key.keyIdHex}). " +
                    "The file is corrupt or forged.",
            )
        }
        val globalSig = sig.globalSignature
        if (globalSig != null) {
            val signed = sig.signature + sig.trustedComment!!.toByteArray(Charsets.UTF_8)
            if (!ed25519Verify(key.ed25519, globalSig, signed)) {
                throw IOException(
                    "Minisign trusted-comment (global) signature FAILED for $what (key ${key.keyIdHex}).",
                )
            }
        }
    }

    private fun ed25519Verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    private fun blake2b512(data: ByteArray): ByteArray {
        val digest = Blake2bDigest(512)
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    private fun decode(line: String, what: String, origin: String): ByteArray =
        try {
            Base64.getDecoder().decode(line)
        } catch (e: IllegalArgumentException) {
            throw IOException("Minisign $what $origin is not valid base64: ${e.message}", e)
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
