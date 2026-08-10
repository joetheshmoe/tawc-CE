package me.phie.tawc.install.distro.voidlinux

/**
 * Void Linux release-signing public keys, keyed by image date.
 *
 * Void signs `live/<date>/sha256sum.txt` with a **per-image-date**
 * minisign key ("This key is only valid for images with date
 * <date>."). The keys are published in the `void-release-keys` package
 * source at
 * `srcpkgs/void-release-keys/files/void-release-<date>.pub` in
 * void-linux/void-packages on GitHub — a different origin from the
 * `repo-default.voidlinux.org` host that serves the manifest and the
 * tarball, which is exactly what makes verifying the signature worth
 * anything. See notes/installation.md "Bootstrap integrity".
 *
 * Keys below are verbatim copies of those files (fetched 2026-08-10).
 * Bundling them means the common case needs no second network fetch
 * and no runtime trust in GitHub at all. [urlFor] is the fallback for
 * an image date newer than this APK, so a Void release doesn't brick
 * installs until we ship an update; [VoidSha256Resolver] fails closed
 * when neither source yields a key.
 */
internal object VoidReleaseKeys {

    private const val GITHUB_FILES =
        "https://raw.githubusercontent.com/void-linux/void-packages/master/" +
            "srcpkgs/void-release-keys/files"

    private val BUNDLED: Map<String, String> = mapOf(
        "20191109" to
            "untrusted comment: This key is only valid for releases with date 20191109.\n" +
            "RWSFkPfJ0Jkg3EIuGjZoCn1/GSChINr/WHdJcdAh1s0d5P+C+ejdCC64\n",
        "20210218" to
            "untrusted comment: This key is only valid for images with date 20210218. public key\n" +
            "RWRSNnH5WbLx1EWUgJGeccx/Dof1MH5k5tZFytMlIOgpRJvRxHJtMqrP\n",
        "20210930" to
            "untrusted comment: minisign public key 2DDAD7D879020384\n" +
            "RWSEAwJ52NfaLd6eT11x7dDsUPRLz4Xfiz7jH/1a3bl6nfbKTDCve/lz\n",
        "20221001" to
            "untrusted comment: This key is only valid for images with date 20221001. public key\n" +
            "RWQ0DEc5FwYgp8wuGTRe3IWJGagpbeOpPqfSQbPIJie9GP8oBybejTqs\n",
        "20230628" to
            "untrusted comment: minisign public key 5D7153E025EC26B6\n" +
            "RWS2Juwl4FNxXe0NtAdYushNLM3GtJ6poGkZ0Up1P/9YLcCK4xlSWAfs\n",
        "20240314" to
            "untrusted comment: minisign public key A3FCFCCA9D356F86\n" +
            "RWSGbzWdyvz8o4nrhY1nbmHLF6QiFH/AQXs1mS/0X+t1x3WwUA16hdc/\n",
        "20250202" to
            "untrusted comment: minisign public key 4D56E70F102AF9F9\n" +
            "RWT5+SoQD+dWTeOdNuc4Q/jq2+3+jpql7+JJp4WukkxTdpsZlk2EGuPj\n",
    )

    /** Bundled `.pub` file contents for [imageDate], or null if we don't ship one. */
    fun bundled(imageDate: String): String? = BUNDLED[imageDate]

    /** Image dates this build ships a key for. */
    val bundledDates: Set<String> get() = BUNDLED.keys

    /** Upstream URL of the release key for [imageDate]. */
    fun urlFor(imageDate: String): String = "$GITHUB_FILES/void-release-$imageDate.pub"
}
