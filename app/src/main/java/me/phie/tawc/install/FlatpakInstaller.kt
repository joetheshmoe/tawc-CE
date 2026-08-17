package me.phie.tawc.install

import java.io.File

/**
 * Flatpak-app install/launch plumbing (see notes/flatpak.md).
 *
 * Install is two steps: [installScript] installs the `flatpak` tool
 * (via whatever package manager the distro ships) plus the Flathub
 * remote and the app itself, run inside the rootfs; [writeDesktopEntry]
 * then drops a `.desktop` into the launcher's managed dir
 * (`/root/.local/share/applications`) so `launcher.rs` picks the app up
 * and launches it via `/usr/local/bin/tawc-flatpak-run` (shipped by
 * [FlatpakInstallProvider]).
 */
object FlatpakInstaller {

    const val REMOTE = "flathub"
    const val REMOTE_URL = "https://dl.flathub.org/repo/flathub.flatpakrepo"

    /** Rootfs-side flatpak install dir (guest `$HOME=/root`). */
    fun appDir(rootfs: String, appId: String): File =
        File(rootfs, "root/.local/share/flatpak/app/$appId")

    /** True once the app's flatpak ref is present (or the desktop entry exists). */
    fun isInstalled(rootfs: String, appId: String): Boolean =
        File(rootfs, "root/.local/share/applications/$appId.desktop").isFile ||
            appDir(rootfs, appId).isDirectory

    /**
     * Shell script run inside the rootfs (as root) to ensure `flatpak`
     * is present, add the Flathub remote, and install [appId]. Idempotent:
     * `--if-not-exists` on the remote, and `flatpak install` no-ops on an
     * already-installed ref. The appId is a flatpak id (`[A-Za-z0-9._-]`),
     * safe to embed single-quoted.
     */
    fun installScript(appId: String): String = """
        set -e
        if ! command -v flatpak >/dev/null 2>&1; then
          if command -v apt-get >/dev/null 2>&1; then
            apt-get update && apt-get install -y flatpak
          elif command -v pacman >/dev/null 2>&1; then
            pacman -Sy --noconfirm flatpak
          elif command -v dnf >/dev/null 2>&1; then
            dnf install -y flatpak
          elif command -v xbps-install >/dev/null 2>&1; then
            xbps-install -Sy flatpak
          else
            echo "tawc: no supported package manager for flatpak" >&2
            exit 1
          fi
        fi
        flatpak remote-add --user --if-not-exists $REMOTE $REMOTE_URL
        flatpak install -y --user --noninteractive '$appId'
    """.trimIndent()

    /**
     * Write the launcher `.desktop` entry for an installed app into the
     * managed dir. Done from Kotlin (not the install script) so the
     * display name never passes through shell quoting. Idempotent.
     */
    fun writeDesktopEntry(rootfs: String, appId: String, name: String): File {
        val dest = File(rootfs, "root/.local/share/applications/$appId.desktop")
        dest.parentFile?.mkdirs()
        val safeName = name.replace('\n', ' ').trim()
        dest.writeText(
            "[Desktop Entry]\n" +
                "Name=$safeName\n" +
                "Comment=$safeName (Flatpak)\n" +
                "Exec=/usr/local/bin/tawc-flatpak-run $appId\n" +
                "Type=Application\n" +
                "Terminal=false\n",
        )
        return dest
    }
}
