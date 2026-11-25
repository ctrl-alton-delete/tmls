package dev.tmls.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import dev.tmls.core.openArgv
import dev.tmls.core.prefixArgv
import dev.tmls.core.run
import dev.tmls.settings.Settings
import dev.tmls.ssh.BundledTools
import java.util.concurrent.Executors

/**
 * One open tab (spec TERM-1): a Termux [TerminalSession] running `ssh -t HOST tmux new -A -s NAME`.
 * Closing the tab ends ssh only; the tmux session lives on on the host.
 */
class Tab(val id: Long, val host: String, val name: String, session: TerminalSession) {
    var session: TerminalSession by mutableStateOf(session)
        internal set
    /** Why ssh ended (TERM-3), or null while it runs. */
    var ended: Ended? by mutableStateOf(null)
        internal set
    /** The composer's text for this tab (KB-8). */
    var draft: String by mutableStateOf("")
    /** The host's tmux prefix as bytes to send (KB-1 PREFIX); C-b until the host says otherwise. */
    @Volatile var prefix: String = "\u0002"
        internal set

    val label: String get() = "$host: $name"
}

/** How a tab's ssh ended, for the bar over the dead screen (TERM-3, SEC-5). */
data class Ended(val message: String, val hostKeyChanged: Boolean = false)

/**
 * Owns the tabs (spec ARCH-5): lives as long as the process, which [TerminalService] keeps in the
 * foreground while any tab is open, so tabs survive the Activity going away (rotation, Recents).
 * Everything here runs on the main thread, like Termux's sessions.
 */
class SessionManager(
    private val context: Context,
    private val tools: BundledTools,
    private val settings: Settings,
    /** Told the number of open tabs after each change: starts and stops [TerminalService]. */
    private val onTabCount: (Int) -> Unit = { TerminalService.update(context, it) },
) : TerminalSessionClient {
    val tabs = mutableStateListOf<Tab>()
    var current: Tab? by mutableStateOf(null)
    /** Composer send history, newest first, shared by all tabs (KB-8). */
    val history = mutableStateListOf<String>()

    /** The terminal view on screen, if any, showing [current]. */
    var view: TerminalView? = null
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private var nextId = 1L
    /** Size of the last laid-out view, so a new session starts at the size it will be shown at. */
    var lastSize = intArrayOf(80, 24, 12, 24)

    /** Opens session [name] on [host], or shows its tab when one is open already. */
    fun open(host: String, name: String): Tab {
        tabs.firstOrNull { it.host == host && it.name == name }?.let { show(it); return it }
        val tab = Tab(nextId++, host, name, newSession(host, name))
        tabs.add(tab)
        show(tab)
        readPrefix(tab)
        onTabCount(tabs.size)
        return tab
    }

    fun show(tab: Tab) {
        current = tab
    }

    /** TERM-3: a new ssh for an ended tab, same session. */
    fun reattach(tab: Tab) {
        tab.session.finishIfRunning()
        tab.ended = null
        tab.session = newSession(tab.host, tab.name)
        if (current == tab) view?.let { attach(it, tab) }
    }

    /** Detaches: ends ssh, closes the tab; the tmux session lives on (TERM-2). */
    fun close(tab: Tab) {
        tab.session.finishIfRunning()
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        if (current == tab) current = tabs.getOrNull(index) ?: tabs.lastOrNull()
        onTabCount(tabs.size)
    }

    fun closeAll() = tabs.toList().forEach(::close)

    fun attach(view: TerminalView, tab: Tab) {
        this.view = view
        view.attachSession(tab.session)
        view.onScreenUpdated()
    }

    /** KB-8: [text] as one bracketed paste, then Enter a moment later (so TUIs see two events). */
    fun sendText(tab: Tab, text: String, enter: Boolean) {
        if (tab.ended != null) return
        val clean = text.replace(Regex("[\u001b\u0080-\u009f]"), "").replace(Regex("\r?\n"), "\r")
        tab.session.write("\u001b[200~$clean\u001b[201~")
        if (enter) main.postDelayed({ if (tab.ended == null) tab.session.write("\r") }, ENTER_DELAY_MS)
        if (text.isNotBlank()) {
            history.remove(text)
            history.add(0, text)
            while (history.size > HISTORY) history.removeAt(history.lastIndex)
        }
    }

    private fun newSession(host: String, name: String): TerminalSession {
        val env = tools.env.map { (k, v) -> "$k=$v" }.toTypedArray()
        val argv = openArgv(host, name)
        // argv[0] is the program ("ssh"); Termux takes its path and the whole argv
        val session = TerminalSession(tools.program(argv[0]).path, tools.home.path, argv.toTypedArray(), env, TRANSCRIPT_ROWS, this)
        val (columns, rows, cellWidth, cellHeight) = lastSize.toList()
        session.updateSize(columns, rows, cellWidth, cellHeight)
        return session
    }

    /** KB-1: asks the host's tmux for its prefix, off the main thread; keeps C-b on failure. */
    private fun readPrefix(tab: Tab) {
        background.execute {
            runCatching { run(prefixArgv(tab.host), 15_000u) }
                .onSuccess { out -> prefixBytes(out.trim())?.let { tab.prefix = it } }
                .onFailure { Log.i(TAG, "prefix of ${tab.host}: ${it.message}") }
        }
    }

    private fun tabOf(session: TerminalSession) = tabs.firstOrNull { it.session === session }

    // TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.takeIf { it.currentSession === changedSession }?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val tab = tabOf(finishedSession) ?: return
        tab.ended = describeEnd(finishedSession.exitStatus, finishedSession.emulator?.screen?.transcriptText.orEmpty())
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        // a copy from a selection the user made, or OSC 52 from the host (TERM-5's setting)
        val fromSelection = view?.isSelectingText == true
        if (text == null || (!fromSelection && !settings.osc52Copy)) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tmls", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object {
        private const val TAG = "tmls"
        const val TRANSCRIPT_ROWS = 2000
        private const val HISTORY = 30
        private const val ENTER_DELAY_MS = 120L

        /** What the bar over an ended tab says (TERM-3; SEC-5 for a changed host key). */
        fun describeEnd(exitStatus: Int, transcript: String): Ended = when {
            transcript.contains("REMOTE HOST IDENTIFICATION HAS CHANGED") ->
                Ended("The host's key changed, so ssh refused to connect. If you expect this (the host was reinstalled), forget the old key and reattach.", hostKeyChanged = true)
            exitStatus == 0 -> Ended("Session ended (detached, or the session was closed).")
            exitStatus == 255 -> Ended("Connection lost or failed (ssh exit 255).")
            else -> Ended("Session ended (exit $exitStatus).")
        }

        /**
         * The bytes for a tmux key name as `show-options -gv prefix` prints it (`C-b`, `C-a`,
         * `` ` ``, `M-a`, `C-Space`); null for what this does not know (keep C-b).
         */
        fun prefixBytes(key: String): String? {
            if (key.startsWith("M-") && key.length > 2) return prefixBytes(key.substring(2))?.let { "\u001b$it" }
            if (key.startsWith("C-") || key.startsWith("^")) {
                val rest = key.removePrefix("C-").removePrefix("^")
                if (rest.equals("Space", ignoreCase = true) || rest == "@") return "\u0000"
                if (rest.length != 1) return null
                val c = rest[0]
                return when (c) {
                    in 'a'..'z' -> (c.code - 'a'.code + 1).toChar().toString()
                    in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar().toString()
                    in "[\\]^_" -> (c.code - 0x40).toChar().toString()
                    else -> null
                }
            }
            if (key.equals("Space", ignoreCase = true)) return " "
            return key.takeIf { it.length == 1 }
        }
    }
}
