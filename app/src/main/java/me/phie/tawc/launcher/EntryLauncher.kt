package me.phie.tawc.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.phie.tawc.R
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationMethod
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.TawcrootMethod
import me.phie.tawc.install.UserRootfsSession
import me.phie.tawc.terminal.TerminalActivity

/**
 * Shared fire-and-forget dispatch of a launcher entry into its rootfs —
 * the single point every launch surface goes through ([LauncherActivity]
 * today; home-screen shortcuts later).
 *
 * `Terminal=true` entries on tawcroot installs open [TerminalActivity]
 * with the entry's Exec as a command tab instead of a headless spawn —
 * a CLI program run to /dev/null would be invisible. The terminal is
 * tawcroot-only (see the gate in MainActivity), so proot/chroot keep
 * the headless launch with a logcat warn; those methods are debug-only.
 *
 * For GUI entries, stdio is redirected to /dev/null so a chatty program
 * can't fill the pipe back to the JVM (which we never read).
 *
 * No `setsid -f` detach: under proot's `--kill-on-exit` the detached
 * child gets SIGKILLed when the launcher bash exits, so the app dies
 * before it ever opens a Wayland window. Letting runInside block for
 * the program's whole lifetime is the correct behaviour anyway — the
 * program needs the JVM alive for the compositor's Wayland socket, so
 * there's nothing to gain from detaching.
 *
 * Spawn failures (compositor start, Wayland socket wait, the
 * fail-closed bind IOException from startInside) surface via
 * [LaunchErrorActivity] started from the application context — the
 * launching Activity is typically finished by the time they arrive. A
 * nonzero exit of the program itself returns normally and is
 * intentionally not surfaced.
 */
object EntryLauncher {

    private const val TAG = "tawc-launcher"

    /**
     * Process-wide scope for fire-and-forget launches. Outlives the
     * launching Activity so closing it doesn't tear down the program
     * the user just started. [SupervisorJob] keeps one failed launch
     * from cancelling sibling launches. [UserRootfsSession.runInside]
     * blocks until the program exits, so each launch pins one IO
     * thread for the program's lifetime.
     */
    private val LAUNCH_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget launch of [entry] in [inst]'s rootfs. */
    fun launch(appContext: Context, inst: Installation, entry: LauncherEntry) {
        val method = InstallationMethod.forKey(appContext, inst.method)
        if (method == null) {
            // E.g. a proot install opened by a release build (which
            // ships tawcroot only). A silent return here reads as a
            // dead tap; say what's wrong instead.
            Log.w(TAG, "launch ${entry.id}: method '${inst.method}' not in this build")
            LaunchErrorActivity.start(
                appContext,
                appContext.getString(R.string.launcher_launch_failed_title, entry.name.ifEmpty { entry.id }),
                appContext.getString(R.string.launcher_method_unavailable, inst.method),
            )
            return
        }
        if (entry.terminal) {
            if (method is TawcrootMethod) {
                appContext.startActivity(
                    Intent(appContext, TerminalActivity::class.java)
                        .putExtra(TerminalActivity.EXTRA_ID, inst.id)
                        .putExtra(TerminalActivity.EXTRA_COMMAND, entry.exec)
                        .putExtra(TerminalActivity.EXTRA_LABEL, entry.name.ifEmpty { entry.id })
                        // Per-distro document URI — see the manifest
                        // comment on TerminalActivity.
                        .setData(Uri.parse("tawc://terminal/${inst.id}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            Log.w(TAG, "terminal entry ${entry.id}: native terminal is tawcroot-only, running headless")
        }
        val rootfs = InstallationStore(appContext).rootfsDir(inst.id).absolutePath
        // Keep stdin from /dev/null and stdout discarded, but leave stderr
        // on the pipe so a crashing app's "why" (missing .so, Wayland
        // connect failure, GTK fatal) is captured for the failure dialog
        // instead of lost to /dev/null.
        val cmd = "${entry.exec} </dev/null >/dev/null"
        LAUNCH_SCOPE.launch {
            // Orientation force for this launch: the per-entry override
            // wins, else the .desktop file's X-Tawc-Orientation. Stays
            // live for the whole launch (NativeBridge reads it at each
            // spawnActivity), so every window of a DE session inherits
            // the force and later launches start clean. See
            // NativeBridge.orientationSession.
            val orientation = inst.desktopOrientations[entry.id] ?: entry.orientation
            if (orientation.isNotEmpty()) {
                NativeBridge.orientationSession = orientation
            }
            // Desktop-environment sessions (tawc-de-* entries) collapse
            // onto one Android task for their lifetime — see
            // NativeBridge.desktopSession. Non-DE launches leave it
            // alone, so an app started while a DE runs joins the
            // desktop's task rather than spawning its own card.
            val desktop = entry.id.startsWith("tawc-de-")
            if (desktop) {
                NativeBridge.desktopSession = true
            }
            try {
                // Snapshot the window epoch before spawn so we can detect
                // "ran but never opened a window" on exit. Terminal and DE
                // entries are exempt — a CLI tool legitimately opens
                // nothing, and a DE opens windows lazily over its life.
                val diagnostics = !entry.terminal && !desktop
                val windowEpoch = if (diagnostics) NativeBridge.windowEpoch else 0L
                val result = UserRootfsSession.runInside(appContext, method, rootfs, cmd)
                if (diagnostics) {
                    if (result.exitCode != 0) {
                        reportLaunchFailure(
                            appContext, entry,
                            appContext.getString(R.string.launcher_exited_with_code, result.exitCode),
                            result.output,
                        )
                    } else if (NativeBridge.windowEpoch == windowEpoch) {
                        reportLaunchFailure(
                            appContext, entry,
                            appContext.getString(R.string.launcher_no_window_opened),
                            result.output,
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "launch ${entry.id}: $e")
                val title = appContext.getString(
                    R.string.launcher_launch_failed_title,
                    entry.name.ifEmpty { entry.id },
                )
                LaunchErrorActivity.start(appContext, title, e.message ?: e.javaClass.simpleName)
            } finally {
                NativeBridge.orientationSession = ""
                if (desktop) {
                    NativeBridge.desktopSession = false
                }
            }
        }
    }

    /**
     * Surface a GUI app's failed launch: the reason (exit code / no window)
     * plus the tail of the program's stderr so the user can see *why*.
     */
    private fun reportLaunchFailure(
        appContext: Context,
        entry: LauncherEntry,
        reason: String,
        output: String,
    ) {
        val title = appContext.getString(
            R.string.launcher_launch_failed_title,
            entry.name.ifEmpty { entry.id },
        )
        val stderrTail = output.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(12)
            .joinToString("\n")
        val message = if (stderrTail.isEmpty()) reason else "$reason\n\n$stderrTail"
        Log.w(TAG, "launch ${entry.id}: $reason${if (stderrTail.isNotEmpty()) "\n$stderrTail" else ""}")
        LaunchErrorActivity.start(appContext, title, message)
    }
}
