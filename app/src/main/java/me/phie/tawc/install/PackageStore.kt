package me.phie.tawc.install

import java.io.File

/**
 * The in-app "app store": a curated catalog of common packages the user
 * can install into a distro with one tap, instead of opening a terminal
 * and remembering apt names. Two shelves — [APPS] (everyday GUI programs)
 * and [TOOLS] (system-level tooling like Wine/emulators/toolchains).
 *
 * Package lists are apt-family (Debian) today, like [DesktopOptions]; the
 * install path is [me.phie.tawc.install.distro.Distro.installExtraPackages].
 *
 * `bin` is the detection binary: when `<rootfs>/usr/bin/<bin>` exists the
 * item renders as Installed (the launcher's .desktop scan covers GUI apps;
 * tools have no .desktop entry, so a binary check is the shared signal).
 */
data class StoreItem(
    /** Stable id (`install-packages` broker arg). */
    val id: String,
    val name: String,
    val description: String,
    val packages: List<String>,
    /** Binary under `usr/bin` whose presence marks the item installed. */
    val bin: String,
    val category: Category,
) {
    enum class Category { APPS, TOOLS }
}

object PackageStore {

    val APPS: List<StoreItem> = listOf(
        StoreItem(
            id = "firefox",
            name = "Firefox",
            description = "Web browser (ESR)",
            packages = listOf("firefox-esr"),
            bin = "firefox-esr",
            category = StoreItem.Category.APPS,
        ),
        StoreItem(
            id = "gimp",
            name = "GIMP",
            description = "Image editor",
            packages = listOf("gimp"),
            bin = "gimp",
            category = StoreItem.Category.APPS,
        ),
        StoreItem(
            id = "blender",
            name = "Blender",
            description = "3D modeling and animation",
            packages = listOf("blender"),
            bin = "blender",
            category = StoreItem.Category.APPS,
        ),
        StoreItem(
            id = "libreoffice",
            name = "LibreOffice",
            description = "Office suite (Writer, Calc, Impress)",
            packages = listOf("libreoffice-writer", "libreoffice-calc", "libreoffice-impress"),
            bin = "libreoffice",
            category = StoreItem.Category.APPS,
        ),
        StoreItem(
            id = "geany",
            name = "Geany",
            description = "Lightweight code editor",
            packages = listOf("geany"),
            bin = "geany",
            category = StoreItem.Category.APPS,
        ),
    )

    val TOOLS: List<StoreItem> = listOf(
        StoreItem(
            id = "wine",
            name = "Wine",
            description = "Run Windows programs",
            packages = listOf("wine"),
            bin = "wine",
            category = StoreItem.Category.TOOLS,
        ),
        StoreItem(
            id = "box64",
            name = "Box64",
            description = "Run x86_64 Linux programs on arm64",
            packages = listOf("box64"),
            bin = "box64",
            category = StoreItem.Category.TOOLS,
        ),
        StoreItem(
            id = "devtools",
            name = "Developer tools",
            description = "gcc, git, python, node, go, rust, clang, cmake",
            packages = listOf(
                "build-essential", "git", "python3", "python3-pip",
                "nodejs", "npm", "golang-go", "rustc", "cargo",
                "clang", "cmake", "curl", "wget", "vim",
            ),
            bin = "gcc",
            category = StoreItem.Category.TOOLS,
        ),
    )

    fun all(): List<StoreItem> = APPS + TOOLS

    fun byId(id: String): StoreItem? = all().firstOrNull { it.id == id }

    /** True when the item's detection binary exists in the rootfs. */
    fun isInstalled(item: StoreItem, rootfs: String): Boolean =
        File(rootfs, "usr/bin/${item.bin}").isFile
}
