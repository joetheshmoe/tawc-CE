package me.phie.tawc.install

import android.content.Context
import android.content.ContextWrapper
import me.phie.tawc.install.distro.DistroRegistry
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The bootstrap verify gate must fail closed: if a distro's static
 * [BootstrapVerification.ResolvedAtInstallTime] placeholder ever
 * survives to the verify stage (a `resolveBootstrap` override dropped
 * or forgotten), the install must abort, not proceed unverified.
 * See notes/installation.md "Bootstrap integrity".
 */
class BootstrapVerificationFailClosedTest {

    // Context is unused by the branches under test; android.jar is
    // stubbed (returnDefaultValues) so this is a plain dummy.
    private val context: Context = ContextWrapper(null)

    private fun tempTarball(bytes: ByteArray = "payload".toByteArray()): File =
        File.createTempFile("bootstrap-test", ".tar").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    @Test
    fun placeholderVerificationThrowsInsteadOfPassing() {
        val e = assertThrows(IOException::class.java) {
            SignatureVerifier.verify(
                context, tempTarball(), BootstrapVerification.ResolvedAtInstallTime,
            )
        }
        assertTrue(e.message!!.contains("ResolvedAtInstallTime"))
    }

    @Test
    fun sha256MismatchThrows() {
        assertThrows(IOException::class.java) {
            SignatureVerifier.verify(
                context, tempTarball(), BootstrapVerification.Sha256("0".repeat(64)),
            )
        }
    }

    @Test
    fun sha256MatchPasses() {
        val data = "tawc test payload".toByteArray()
        val hex = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        SignatureVerifier.verify(context, tempTarball(data), BootstrapVerification.Sha256(hex))
    }

    @Test
    fun onlyResolveAtInstallTimeDistrosDeclareThePlaceholder() {
        for (distro in DistroRegistry.all) {
            val isPlaceholder =
                distro.bootstrap.verification is BootstrapVerification.ResolvedAtInstallTime
            val resolvesLive = distro.key in setOf(
                Installation.DISTRO_VOID,
                Installation.DISTRO_MANJARO,
                Installation.DISTRO_DEBIAN_SID,
            )
            assertTrue(
                "${distro.displayName} (${distro.linuxArch}): static verification " +
                    "${distro.bootstrap.verification::class.simpleName} does not match " +
                    "its resolveBootstrap strategy",
                isPlaceholder == resolvesLive,
            )
        }
    }
}
