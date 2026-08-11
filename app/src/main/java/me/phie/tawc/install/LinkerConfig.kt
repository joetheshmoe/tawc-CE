package me.phie.tawc.install

import android.util.Log
import java.io.File

/**
 * Per-spawn copy of Android's boot-generated bionic linker config into
 * the rootfs, for libhybris's vendored linker to read.
 *
 * We used to bind Android's `/linkerconfig` into every rootfs. That
 * directory is `linkerconfig_file` on tmpfs and AOSP's `domain.te`
 * grants app domains only `file r_file_perms` + `dir search` — no
 * `getattr` on the dir — so anything that stats the entries of `/`
 * (any interactive `ls -l /`, or plain `ls /` with color + `LS_COLORS`)
 * got `Permission denied` on that one entry. Files *inside* read fine,
 * and one file is all libhybris ever wanted, so we copy it in and drop
 * the bind; `/linkerconfig` no longer exists in the guest at all.
 *
 * Not `/usr/lib/hybris/` with the rest of the hybris runtime:
 * [TawcrootMethod.assetBinds] binds `<filesDir>/libhybris` there
 * read-only, and a bind shadows anything the rootfs has at that path.
 * [GUEST_DIR] is a plain rootfs directory, so one code path serves all
 * three methods.
 *
 * Freshness: `linkerconfig` regenerates only during boot and is static
 * for the rest of uptime, a reboot kills the app and every guest, and
 * the linker reads the file once per process start — so a per-spawn
 * copy is never staler than the bind was.
 *
 * Unconditional: no ABI or emulator gate, so the emulator exercises the
 * same path phones do (the bind bug was only ever caught because it
 * did). On a host without `/linkerconfig` (pre-Android 11) there is
 * nothing to copy and hybris falls back to
 * `init_default_namespace_no_config`, exactly as it does today.
 *
 * Unlike the old `ro` bind the copy is writable by in-rootfs root. Not
 * a new boundary: the guest already owns its rootfs and
 * `LD_LIBRARY_PATH`, and the config only shapes that guest's own
 * bionic namespaces.
 */
object LinkerConfig {
    private const val TAG = "tawc-install"

    /** Android's boot-generated linker config. */
    private const val HOST_PATH = "/linkerconfig/ld.config.txt"

    /** Guest dir for the copy — see KDoc for why it isn't
     *  [LibhybrisInstallProvider.GUEST_LIB_DIR]. Must match
     *  `kLdGeneratedConfigFilePath` in the libhybris fork
     *  (`hybris/common/q/linker.cpp`). */
    const val GUEST_DIR = "/usr/lib/hybris-config"

    /** Guest path of the copy. */
    const val GUEST_PATH = "$GUEST_DIR/ld.config.txt"

    /**
     * Copy `/linkerconfig/ld.config.txt` into [rootfs] at [GUEST_PATH],
     * and clean up any `<rootfs>/linkerconfig` mountpoint an older
     * build left behind.
     *
     * Written via a temp file + rename so a concurrently-spawning guest
     * never parses a torn file. Skipped when the destination already
     * matches the source's size and mtime — the source is fixed for the
     * whole boot, so the steady-state cost is two stats.
     *
     * Best effort: a failure costs the guest its namespace config (same
     * as a pre-Android-11 host), never the spawn.
     */
    fun install(rootfs: String) {
        // Empty mountpoint from a build that still bound /linkerconfig.
        // delete() on a directory only succeeds when it is empty.
        File(rootfs, "linkerconfig").delete()

        val src = File(HOST_PATH)
        if (!src.isFile) return
        val srcLen = src.length()
        val srcMtime = src.lastModified()
        if (srcLen == 0L) return

        val destDir = File(rootfs, GUEST_DIR.removePrefix("/"))
        val dest = File(rootfs, GUEST_PATH.removePrefix("/"))
        if (dest.length() == srcLen && dest.lastModified() == srcMtime) {
            // Cheap and idempotent: re-assert readability rather than
            // stat the mode, so a copy left 0600 by an older build (or
            // chmod'd by the guest) still heals.
            dest.setReadable(true, false)
            return
        }

        try {
            destDir.mkdirs()
            val tmp = File.createTempFile("ld.config", ".tmp", destDir)
            try {
                src.copyTo(tmp, overwrite = true)
                // createTempFile is 0600; the source is 0644 and a guest
                // process that dropped privileges still has to read it.
                tmp.setReadable(true, false)
                tmp.setLastModified(srcMtime)
                if (!tmp.renameTo(dest)) throw java.io.IOException("rename $tmp -> $dest failed")
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy $HOST_PATH to $dest; guest hybris falls back to no config", e)
        }
    }
}
