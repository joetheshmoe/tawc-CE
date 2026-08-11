package me.phie.tawc.install

import me.phie.tawc.install.distro.DistroRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The PGP keys this app bakes into the APK are the trust root for both
 * Arch bootstraps: whatever they say is a valid signature gets unpacked
 * into a rootfs and run. So assert against the shipped bytes on disk,
 * not a copy — a truncated paste, a key swapped for the wrong one, or a
 * `res/raw` file added without a matching line in
 * [SignatureVerifier.rawKeyResourceId] fails here rather than at
 * install time on a user's phone.
 *
 * See notes/installation.md "Bootstrap integrity".
 */
class ShippedPgpKeysTest {

    /**
     * Master-key fingerprints we expect to ship, uppercase and
     * unspaced. Both were cross-checked against two independent origins
     * before being committed (Arch: archlinux.org's developer page +
     * keyserver; ALARM: keyserver.ubuntu.com + the copy inside
     * upstream's own `archlinuxarm-keyring` package).
     */
    private val expected = mapOf(
        "arch_signing_key" to listOf("3E80CA1A8B89F69CBA57D98A76A5EF9054449A5C"),
        "archlinuxarm_signing_key" to listOf("68B3537F39A313B3E574D06777193F152BDBE6A6"),
        // Debian Archive Automatic Signing Keys 12/bookworm +
        // 13/trixie (both sign sid's InRelease via their signing
        // subkeys 6ED0E7B82643E131 / 78DBA3BC47EF2265). Cross-checked
        // 2026-08-10 against the debian-archive-keyring 2025.1 package
        // from deb.debian.org and ftp-master.debian.org/keys/ —
        // byte-identical key material from both origins.
        "debian_archive_keyring" to listOf(
            "B8B80B5B623EAB6AD8775C45B7C5D7D6350947F8",
            "04B54C3CDCA79751B16BC6B5225629DF75B188BD",
        ),
    )

    /**
     * `res/raw` relative to whatever directory Gradle runs the test
     * from (the `app/` project dir today, the repo root if invoked
     * from elsewhere).
     */
    private val rawDir: File = sequenceOf(
        "src/main/res/raw",
        "app/src/main/res/raw",
        "../app/src/main/res/raw",
    ).map(::File).firstOrNull { it.isDirectory }
        ?: error("cannot locate res/raw from ${File(".").absolutePath}")

    private fun ring(name: String) =
        File(rawDir, "$name.asc").inputStream().use {
            SignatureVerifier.parseKeyRing(it, "res/raw/$name")
        }

    private fun masterFingerprints(name: String): List<String> = with(SignatureVerifier) {
        ring(name).keyRings.asSequence().map { it.publicKey.fingerprintHex() }.toList()
    }

    @Test
    fun everyShippedKeyParsesWithTheExpectedFingerprint() {
        for ((name, fingerprints) in expected) {
            assertTrue("res/raw/$name.asc is missing", File(rawDir, "$name.asc").isFile)
            assertEquals(name, fingerprints, masterFingerprints(name))
        }
    }

    @Test
    fun everyAscInResRawIsCovered() {
        // Guards the map above from going stale: a newly shipped key
        // with no expected fingerprint here is untested, which is the
        // situation this test exists to prevent.
        val shipped = rawDir.listFiles { f: File -> f.name.endsWith(".asc") }
            .orEmpty().map { it.name.removeSuffix(".asc") }.toSet()
        assertEquals(expected.keys, shipped)
    }

    @Test
    fun everyDeclaredKeyResourceIsRegisteredAndShipped() {
        val declared = (
            DistroRegistry.all
                .map { it.bootstrap.verification }
                .filterIsInstance<BootstrapVerification.Pgp>()
                .map { it.keyResource } +
            DistroRegistry.all
                .flatMap { it.bootstrapFlavors.values }
                .filterIsInstance<me.phie.tawc.install.distro.PackageBootstrap>()
                .map { it.keyResource }
        ).toSet()
        assertTrue("no distro declares a Pgp keyResource", declared.isNotEmpty())
        for (name in declared) {
            assertNotEquals(
                "$name is declared by a distro but missing from " +
                    "SignatureVerifier.rawKeyResourceId — loadKeyRing would throw at install time",
                0, SignatureVerifier.rawKeyResourceId(name),
            )
            assertTrue("res/raw/$name.asc is missing", File(rawDir, "$name.asc").isFile)
        }
    }

    @Test
    fun unknownKeyResourceFailsClosed() {
        assertEquals(0, SignatureVerifier.rawKeyResourceId("no_such_key"))
    }

    /**
     * The real ALARM `.sig` (fetched 2026-08-10) names the build-system
     * key as its issuer, and only the ALARM ring holds it. Verifying a
     * signature against a ring that lacks its issuer must throw — the
     * failure mode if upstream rotates keys, or if a distro is wired to
     * the wrong `keyResource`.
     */
    @Test
    fun signatureIssuerMustBeInTheDeclaredRing() {
        val sig = SignatureVerifier.parseDetachedSignature(
            javaClass.getResourceAsStream("/pgp/ArchLinuxARM-aarch64-latest.tar.gz.sig")
                ?.readBytes() ?: error("missing test fixture pgp/ArchLinuxARM-…tar.gz.sig"),
        )
        assertEquals(0x77193F152BDBE6A6uL.toLong(), sig.keyID)

        val key = SignatureVerifier.resolveSigningKey(
            ring("archlinuxarm_signing_key"), sig, "archlinuxarm_signing_key",
        )
        with(SignatureVerifier) {
            assertEquals(expected.getValue("archlinuxarm_signing_key").single(), key.fingerprintHex())
        }

        val e = assertThrows(IOException::class.java) {
            SignatureVerifier.resolveSigningKey(
                ring("arch_signing_key"), sig, "arch_signing_key",
            )
        }
        assertTrue(e.message!!.contains("not present in shipped keyring"))
    }
}
