package me.phie.tawc

import android.app.Application

/**
 * Release build of the process-start dev hooks: nothing.
 *
 * The real one is in `src/debug/java` next to the exec broker and its
 * actions. Keeping the whole `me.phie.tawc.dev` package out of the
 * release source set is what makes "no broker in release" a property of
 * the artifact rather than of one `if` — see notes/exec-broker.md.
 * `scripts/check-no-dev-code.sh` asserts it.
 */
internal object DevHooks {
    fun start(app: Application) {}
}
