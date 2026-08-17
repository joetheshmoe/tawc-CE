package me.phie.tawc.install

import android.content.Context
import java.io.File

/**
 * Ships `/usr/local/bin/tawc-flatpak-run` (the unsandboxed Flatpak
 * launcher — see notes/flatpak.md) into every rootfs. Content lives in
 * `assets/tawc-flatpak-run`; entries() copies it under filesDir so the
 * generic COPY machinery in [TawcInstaller] applies and the exec bit
 * survives (`TawcInstaller.applyToRootfs` preserves the source's
 * execute permission).
 *
 * Method-independent: a single file in a distro-managed dir, copied
 * under every method. Shipping the script is cheap and harmless even
 * when Flatpak is never used; it only matters at launch time and the
 * GL-shim symlinks it creates degrade to software rendering if libhybris
 * isn't installed (CPU / gfxstream backends).
 */
internal object FlatpakInstallProvider : TawcInstallProvider {
    override val name: String = "flatpak"

    const val GUEST_BIN_PATH = "/usr/local/bin/tawc-flatpak-run"

    override fun entries(context: Context, methodKey: String): List<TawcInstall> {
        val src = File(context.filesDir, "flatpak/tawc-flatpak-run")
        src.parentFile?.mkdirs()
        context.assets.open("tawc-flatpak-run").use { input ->
            src.outputStream().use { out -> input.copyTo(out) }
        }
        src.setExecutable(true, false)
        return listOf(
            TawcInstall(
                src = src.absolutePath,
                dest = GUEST_BIN_PATH,
                type = TawcInstall.Type.COPY,
            ),
        )
    }
}
