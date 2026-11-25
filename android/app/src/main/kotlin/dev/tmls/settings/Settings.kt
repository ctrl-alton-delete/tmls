package dev.tmls.settings

import android.content.Context
import androidx.core.content.edit

/** The app's own settings (not ssh's): one SharedPreferences file. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** KB-4: text input mode for keyboards that need it; off = Termux's char-based mode. */
    var textInputMode: Boolean
        get() = prefs.getBoolean("text_input_mode", false)
        set(value) = prefs.edit { putBoolean("text_input_mode", value) }

    /** KB-8: the composer's Send presses Enter after the paste. */
    var sendPressesEnter: Boolean
        get() = prefs.getBoolean("send_enter", true)
        set(value) = prefs.edit { putBoolean("send_enter", value) }

    /** TERM-4: terminal text size in pixels; 0 = the default for the screen density. */
    var textSizePx: Int
        get() = prefs.getInt("text_size_px", 0)
        set(value) = prefs.edit { putInt("text_size_px", value) }

    /** KB-1: the extra-keys rows in Termux's syntax. */
    var extraKeys: String
        get() = prefs.getString("extra_keys", null) ?: DEFAULT_EXTRA_KEYS
        set(value) = prefs.edit { putString("extra_keys", value) }

    /** TERM-5: OSC 52 from the host (tmux, agents) copies into the Android clipboard. */
    var osc52Copy: Boolean
        get() = prefs.getBoolean("osc52_copy", true)
        set(value) = prefs.edit { putBoolean("osc52_copy", value) }

    /** The host and session typed last in the picker (M2's temporary picker). */
    var lastHost: String
        get() = prefs.getString("last_host", "") ?: ""
        set(value) = prefs.edit { putString("last_host", value) }
    var lastSession: String
        get() = prefs.getString("last_session", "") ?: ""
        set(value) = prefs.edit { putString("last_session", value) }

    companion object {
        /** Spec KB-1, with KB-3's popups. */
        const val DEFAULT_EXTRA_KEYS =
            "[[{key:'ESC',popup:'`'},{key:'/',popup:'\\\\'},{key:'-',popup:'|'},'HOME','UP','END','PGUP','PREFIX']," +
                "['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN','KEYBOARD']]"
    }
}
