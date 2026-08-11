package me.phie.tawc.install

import me.phie.tawc.BuildConfig
import me.phie.tawc.install.distro.BootstrapFlavor

/**
 * Build-time gate for the bootstrap flavors this APK ships (see
 * notes/installation.md "Bootstrap flavors"). Driven by the
 * `BOOTSTRAP_PACKAGES_ENABLED` BuildConfig field set per-buildType in
 * `app/build.gradle.kts` (override with
 * `-PtawcBootstrapPackages=true|false`).
 *
 * Defaults: `tarball` always — it is every distro's
 * [me.phie.tawc.install.distro.Distro.supportedFlavor] and the default
 * for new installs. `packages` (on-device debootstrap, Debian sid
 * only) is a dev-only experiment: debug ships it, release does not, so
 * a production APK has no flavor to pick and never packs the
 * debootstrap asset.
 *
 * The choke point is
 * [me.phie.tawc.install.distro.Distro.bootstrapFlavors], which filters
 * the distro's declared flavors through this — so the install form,
 * the service gate, and bootstrap resolution all see only what the
 * build ships. Pattern mirrors [EnabledMethods].
 */
object EnabledBootstrapFlavors {
    fun isEnabled(flavor: BootstrapFlavor): Boolean = when (flavor) {
        BootstrapFlavor.TARBALL -> true
        BootstrapFlavor.PACKAGES -> BuildConfig.BOOTSTRAP_PACKAGES_ENABLED
    }

    /** Flavors this APK ships, in enum declaration order. */
    val enabled: List<BootstrapFlavor> = BootstrapFlavor.entries.filter { isEnabled(it) }
}
