package me.phie.tawc.install

/**
 * Optional desktop environments offered at install time. The launcher
 * already auto-discovers installed DEs (see notes/de-desktops.md); these
 * entries let a first-time user pick DEs as checkboxes during distro
 * install instead of apt-installing them later. The package list is
 * apt-family (Debian); other package managers aren't wired up yet.
 */
data class DesktopOption(
    /** Stable id used on the wire (`--arg desktops=xfce,lxqt`). */
    val id: String,
    /** Short display name. */
    val label: String,
    /** One-line "what you get" description. */
    val description: String,
    /** apt packages to install (with `--no-install-recommends`). */
    val packages: List<String>,
    /** Optional shell fragment appended after apt install, run as root
     *  inside the rootfs. Used for DEs that need a one-time setup step
     *  beyond installing packages. Empty for the shipped DEs. */
    val setupScript: String = "",
)

/** The DE options offered by the install form, in display order. */
object DesktopOptions {
    val ALL: List<DesktopOption> = listOf(
        DesktopOption(
            id = "xfce",
            label = "XFCE",
            description = "Lightweight classic desktop — great on phones",
            packages = listOf("xfce4"),
        ),
        DesktopOption(
            id = "lxqt",
            label = "LXQt",
            description = "Fast Qt-based desktop",
            // openbox is only a Recommends of lxqt; the apt config here
            // installs without recommends, so pull it explicitly (the
            // session's WM).
            packages = listOf("lxqt", "openbox"),
        ),
    )

    fun byId(id: String): DesktopOption? = ALL.firstOrNull { it.id == id }

    /** Resolve a comma/space-separated selection of ids to options,
     *  dropping unknown ids silently (forward-compatible wire format). */
    fun parseSelection(ids: String?): List<DesktopOption> {
        if (ids.isNullOrBlank()) return emptyList()
        return ids.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { byId(it) }
    }

    /** All packages for [options], deduplicated in selection order. */
    fun packagesFor(options: List<DesktopOption>): List<String> = buildList {
        for (o in options) for (p in o.packages) if (p !in this) add(p)
    }

    /** Concatenated per-DE setup scripts, or empty. */
    fun setupScriptFor(options: List<DesktopOption>): String =
        options.map { it.setupScript }.filter { it.isNotEmpty() }.joinToString("\n")
}
