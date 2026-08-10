package me.phie.tawc.compositor

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * Test [ImeOutput]. Records every call without ever hitting the real
 * [InputMethodManager], so the system IME never sees `updateSelection`
 * (and therefore never fires defensive `finishComposingText` reactions
 * mid-test). Installed by the broker `test-init` action; process death
 * restores [RealImeOutput].
 *
 * Debug builds only — lives in `src/debug/java` alongside the broker
 * actions that install it, so it never reaches a release APK.
 *
 * Records are append-only and exposed for assertions, but the primary
 * value of this impl is what it *prevents* (IME reactivity), not what it
 * captures.
 */
class RecordingImeOutput : ImeOutput {
    sealed class Call {
        data class UpdateSelection(val selStart: Int, val selEnd: Int, val composingStart: Int, val composingEnd: Int) : Call()
        data object ShowSoftInput : Call()
        data object HideSoftInput : Call()
        data object RestartInput : Call()
        data class BindInputConnection(val inputType: Int, val imeOptions: Int) : Call()
    }

    private val _calls = mutableListOf<Call>()
    private var testInputConnection: TawcInputConnection? = null
    private var hiddenInputConnection: TawcInputConnection? = null
    val calls: List<Call> get() = synchronized(_calls) { _calls.toList() }

    private fun bindTestInputConnection(view: View) {
        hiddenInputConnection = testInputConnection ?: hiddenInputConnection
        val editorInfo = EditorInfo()
        testInputConnection = view.onCreateInputConnection(editorInfo) as? TawcInputConnection
        synchronized(_calls) {
            _calls += Call.BindInputConnection(editorInfo.inputType, editorInfo.imeOptions)
        }
    }

    internal fun clearTestInputConnection() {
        testInputConnection = null
        hiddenInputConnection = null
    }

    internal fun finishHiddenComposingTextForDev(): Boolean {
        val ic = hiddenInputConnection ?: return false
        hiddenInputConnection = null
        val ok = ic.finishComposingText()
        return ok
    }

    internal fun lastEditorInfoForDev(): Pair<Int, Int>? =
        synchronized(_calls) {
            _calls.asReversed()
                .filterIsInstance<Call.BindInputConnection>()
                .firstOrNull()
                ?.let { it.inputType to it.imeOptions }
        }

    override fun updateSelection(view: View, selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
        synchronized(_calls) { _calls += Call.UpdateSelection(selStart, selEnd, composingStart, composingEnd) }
    }

    override fun showSoftInput(view: View) {
        synchronized(_calls) { _calls += Call.ShowSoftInput }
        bindTestInputConnection(view)
    }

    override fun hideSoftInput(view: View) {
        synchronized(_calls) { _calls += Call.HideSoftInput }
        if (hiddenInputConnection == null) {
            hiddenInputConnection = testInputConnection
        }
        testInputConnection = null
        val active = NativeBridge.activeInputConnection
        if (active === hiddenInputConnection || active?.targetsView(view) == true) {
            NativeBridge.activeInputConnection = null
        }
    }

    override fun restartInput(view: View) {
        synchronized(_calls) { _calls += Call.RestartInput }
        bindTestInputConnection(view)
    }
}
