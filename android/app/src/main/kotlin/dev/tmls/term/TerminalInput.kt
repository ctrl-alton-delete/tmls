package dev.tmls.term

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import com.termux.shared.terminal.io.TerminalExtraKeys
import com.termux.shared.terminal.io.extrakeys.ExtraKeyButton
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.shared.terminal.io.extrakeys.SpecialButton
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.tmls.settings.Settings

/**
 * The terminal's input side: the [TerminalViewClient] Termux's view asks about keys, and the
 * extra-keys row's clicks (spec KB-1…KB-5, TERM-4).
 */
class TerminalInput(
    private val context: Context,
    private val settings: Settings,
    private val currentTab: () -> Tab?,
    /** KB-8: the extra-keys row's COMPOSE key (for layouts that have one). */
    private val onCompose: () -> Unit,
) : TerminalViewClient {
    lateinit var terminalView: TerminalView
    var extraKeysView: ExtraKeysView? = null

    /** The extra-keys row's client: Termux's, plus PREFIX, KEYBOARD and COMPOSE. */
    fun extraKeysClient(): ExtraKeysView.IExtraKeysView = object : TerminalExtraKeys(terminalView) {
        override fun onTerminalExtraKeyButtonClick(view: View?, key: String?, ctrlDown: Boolean, altDown: Boolean, shiftDown: Boolean, fnDown: Boolean) {
            when (key) {
                "PREFIX" -> currentTab()?.takeIf { it.ended == null }?.let { it.session.write(it.prefix) }
                "KEYBOARD" -> toggleKeyboard()
                "COMPOSE" -> onCompose()
                else -> super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown)
            }
        }

        override fun performExtraKeyButtonHapticFeedback(view: View?, buttonInfo: ExtraKeyButton?, button: Button?) = false
    }

    fun showKeyboard() {
        terminalView.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, 0)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val shown = androidx.core.view.ViewCompat.getRootWindowInsets(terminalView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (shown) imm.hideSoftInputFromWindow(terminalView.windowToken, 0) else showKeyboard()
    }

    // TerminalViewClient

    /** TERM-4: pinch changes the text size in steps; Termux's own approach. */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeTextSize(scale > 1f)
            return 1f
        }
        return scale
    }

    fun changeTextSize(larger: Boolean) {
        val current = settings.textSizePx.takeIf { it > 0 } ?: defaultTextSizePx(context)
        val next = (current + if (larger) 2 else -2).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        settings.textSizePx = next
        terminalView.setTextSize(next)
    }

    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = !settings.textInputMode
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false

    // KB-2: sticky modifiers from the extra-keys row apply to the next key from either keyboard
    override fun readControlKey() = read(SpecialButton.CTRL)
    override fun readAltKey() = read(SpecialButton.ALT)
    override fun readShiftKey() = read(SpecialButton.SHIFT)
    override fun readFnKey() = read(SpecialButton.FN)
    private fun read(button: SpecialButton) = extraKeysView?.readSpecialButton(button, true) == true

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object {
        private const val TAG = "tmls"
        private const val MIN_TEXT_PX = 12
        private const val MAX_TEXT_PX = 96

        fun defaultTextSizePx(context: Context): Int =
            (12 * context.resources.displayMetrics.scaledDensity).toInt()

        /** KB-1: the layout in Termux's syntax; null (and the default used) when it does not parse. */
        fun parseLayout(layout: String): ExtraKeysInfo? = runCatching {
            ExtraKeysInfo(layout, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES)
        }.getOrNull()
    }
}
