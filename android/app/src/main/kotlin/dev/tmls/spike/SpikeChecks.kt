package dev.tmls.spike

import android.os.Handler
import android.os.Looper
import dev.tmls.core.CoreException
import dev.tmls.core.attachArgv
import dev.tmls.core.run as coreRun
import dev.tmls.core.sshArgv
import dev.tmls.core.sshBase
import dev.tmls.core.useControlMaster
import dev.tmls.ssh.BundledTools
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plan M1: can the bundled ssh be run from Kotlin and from Rust on this device, share a
 * ControlMaster connection, authenticate (Tailscale SSH or a key), and read `$HOME/.ssh/config`?
 *
 * Throwaway spike code: the checks call the same `client-core` builders the real app will
 * (`ssh_base`, `attach_argv`, `use_control_master`, `tmux::run`) through `core-ffi`. Everything
 * here runs off the main thread except the [TerminalSession] parts, which post to it.
 */
class SpikeChecks(private val tools: BundledTools) {

    enum class Outcome { PASS, FAIL, SKIP }

    data class Check(val id: String, val title: String, val outcome: Outcome, val detail: String)

    data class Proc(val code: Int, val out: String, val err: String, val millis: Long)

    /** Runs `argv` with the app's environment, from Kotlin (not Rust). */
    fun exec(argv: List<String>, timeoutSec: Long = 20): Proc {
        val started = System.nanoTime()
        val builder = ProcessBuilder(argv).directory(tools.home)
        builder.environment().putAll(tools.env)
        val process = builder.start()
        process.outputStream.close()
        var out = ""
        var err = ""
        val readers = listOf(
            Thread { out = process.inputStream.bufferedReader().readText() },
            Thread { err = process.errorStream.bufferedReader().readText() },
        )
        readers.forEach { it.start() }
        val done = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) process.destroyForcibly()
        readers.forEach { it.join(2000) }
        val code = if (done) process.exitValue() else -1
        return Proc(code, out, if (done) err else "$err\n(timed out after ${timeoutSec}s)", (System.nanoTime() - started) / 1_000_000)
    }

    /** Writes the probe host block (and, when given, the target's block) into ~/.ssh/config. */
    fun writeDefaultConfig(text: String) {
        val config = File(tools.sshDir, "config")
        config.writeText(text)
        android.system.Os.chmod(config.absolutePath, "600".toInt(8))
    }

    fun readConfig(): String = File(tools.sshDir, "config").takeIf { it.exists() }?.readText() ?: ""

    /** Generates ~/.ssh/id_ed25519 with the bundled ssh-keygen if there is none; the public key. */
    fun ensureKey(comment: String = "tmls@android"): Result<String> = runCatching {
        val key = File(tools.sshDir, "id_ed25519")
        if (!key.exists()) {
            val p = exec(listOf(tools.program("ssh-keygen").absolutePath, "-q", "-t", "ed25519", "-N", "", "-C", comment, "-f", key.absolutePath))
            check(p.code == 0) { "ssh-keygen failed (${p.code}): ${p.err.trim()}" }
        }
        File(tools.sshDir, "id_ed25519.pub").readText().trim()
    }

    /** Every check, in order; host checks are skipped when [host] is blank. */
    fun runAll(host: String, onProgress: (Check) -> Unit = {}): List<Check> {
        val results = mutableListOf<Check>()
        fun add(check: Check) { results += check; onProgress(check) }

        add(tools())
        add(kotlinExec())
        add(configRead())
        add(rustExec())
        if (host.isBlank()) {
            add(Check("host", "Host checks", Outcome.SKIP, "no host given"))
            return results
        }
        val masterUp = runCatching { useControlMaster(tools.controlDir.absolutePath) }
        add(Check("cm-setup", "ControlMaster dir", if (masterUp.isSuccess) Outcome.PASS else Outcome.FAIL,
            masterUp.exceptionOrNull()?.message ?: tools.controlDir.absolutePath))
        add(terminal("1a", "Terminal: create/attach session over ssh -t (TerminalSession)", host,
            sshTtyArgv(host, "tmux -u new-session -A -s $SPIKE_SESSION")))
        add(rustRemote(host))
        add(controlMaster(host))
        add(auth(host))
        add(terminal("1b", "Terminal: client-core attach_argv (TerminalSession)", host, attachArgv(host, SPIKE_SESSION)))
        return results
    }

    private fun tools(): Check {
        val missing = tools.missing()
        val listing = tools.nativeLibDir.list()?.sorted()?.joinToString(" ") ?: "(unreadable)"
        return if (missing.isEmpty()) Check("0", "Bundled programs linked", Outcome.PASS, "bin → ${tools.nativeLibDir}\n$listing")
        else Check("0", "Bundled programs linked", Outcome.FAIL, "missing: $missing\nnativeLibraryDir: $listing")
    }

    private fun kotlinExec(): Check = guarded("2k", "Kotlin exec: ssh -V") {
        val p = exec(listOf(tools.program("ssh").absolutePath, "-V"))
        val version = (p.err + p.out).trim()
        val ok = p.code == 0 && version.startsWith("OpenSSH_")
        Check("2k", "Kotlin exec: ssh -V", if (ok) Outcome.PASS else Outcome.FAIL, "exit ${p.code}: $version")
    }

    private fun configRead(): Check = guarded("5", "ssh reads \$HOME/.ssh/config") {
        val p = exec(listOf(tools.program("ssh").absolutePath, "-G", PROBE_HOST))
        val known = "${tools.home.absolutePath}/.ssh/known_hosts"
        val port = p.out.lines().any { it == "port $PROBE_PORT" }
        val knownHosts = p.out.lines().any { it.startsWith("userknownhostsfile ") && it.contains(known) }
        val ok = p.code == 0 && port && knownHosts
        val shown = p.out.lines().filter { it.startsWith("port ") || it.startsWith("userknownhostsfile ") || it.startsWith("identityfile ") || it.startsWith("user ") }
        Check("5", "ssh reads \$HOME/.ssh/config", if (ok) Outcome.PASS else Outcome.FAIL,
            "exit ${p.code}; probe port from config: $port; known_hosts under HOME: $knownHosts\n${shown.joinToString("\n")}\n${p.err.trim()}")
    }

    private fun rustExec(): Check = guarded("2r", "Rust Command: ssh found on PATH") {
        val out = coreRun(listOf("ssh", "-G", PROBE_HOST), 10_000u)
        val ok = out.lines().any { it == "port $PROBE_PORT" }
        Check("2r", "Rust Command: ssh found on PATH", if (ok) Outcome.PASS else Outcome.FAIL, out.lines().firstOrNull { it.startsWith("port ") } ?: out.take(200))
    }

    private fun rustRemote(host: String): Check = guarded("2", "Rust Command: ssh $host (BatchMode)") {
        val started = System.nanoTime()
        val out = coreRun(sshArgv(host, "echo tmls-ok; tmux -V; uname -n"), 30_000u)
        val ms = (System.nanoTime() - started) / 1_000_000
        Check("2", "Rust Command: ssh $host (BatchMode)", if (out.contains("tmls-ok")) Outcome.PASS else Outcome.FAIL, "${ms} ms\n${out.trim()}")
    }

    private fun controlMaster(host: String): Check = guarded("3", "ControlMaster shared connection") {
        val sockets = tools.controlDir.listFiles()?.map { it.name }.orEmpty()
        val check = runCatching { coreRun(sshBase(host, listOf("-O", "check")), 10_000u) }
        val started = System.nanoTime()
        val again = runCatching { coreRun(sshArgv(host, "true"), 15_000u) }
        val ms = (System.nanoTime() - started) / 1_000_000
        val ok = sockets.any { it.startsWith("cm-") } && check.isSuccess && again.isSuccess
        Check("3", "ControlMaster shared connection", if (ok) Outcome.PASS else Outcome.FAIL,
            "sockets in ${tools.controlDir}: $sockets\nssh -O check: ${check.exceptionOrNull()?.message ?: "master running"}\nsecond command through the master: ${ms} ms")
    }

    private fun auth(host: String): Check = guarded("4", "Authentication and name resolution") {
        // Its own connection (not the master), verbose, to see how it authenticates.
        val p = exec(listOf(tools.program("ssh").absolutePath, "-v", "-o", "ControlPath=none", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host, "true"), 30)
        val connecting = p.err.lines().firstOrNull { "Connecting to" in it }?.substringAfter("debug1: ")
        val method = Regex("Authenticated to .* using \"([^\"]+)\"").find(p.err)?.groupValues?.get(1)
        val tailscale = p.err.contains("Tailscale", ignoreCase = true)
        val outcome = if (p.code == 0 && method != null) Outcome.PASS else Outcome.FAIL
        val tail = if (outcome == Outcome.FAIL) "\n" + p.err.lines().filter { "debug1" !in it || "Authentications" in it || "denied" in it }.takeLast(8).joinToString("\n") else ""
        Check("4", "Authentication and name resolution", outcome,
            "${connecting ?: "no 'Connecting to' line"}\nauth method: ${method ?: "none succeeded"}${if (method == "none" || tailscale) " (Tailscale SSH)" else ""}\nexit ${p.code}$tail")
    }

    /** Runs [argv] in a Termux [TerminalSession] (a real PTY) and reads its screen. */
    private fun terminal(id: String, title: String, host: String, argv: List<String>): Check = guarded(id, title) {
        val main = Handler(Looper.getMainLooper())
        val changed = CountDownLatch(1)
        var session: TerminalSession? = null
        var finished = false
        val client = QuietClient(onText = {}, onFinished = { finished = true })
        val program = tools.program("ssh").absolutePath
        val env = tools.env.map { (k, v) -> "$k=$v" }.toTypedArray()
        main.post {
            session = TerminalSession(program, tools.home.absolutePath, argv.toTypedArray(), env, 2000, client).also {
                it.updateSize(100, 30, 10, 20)
            }
            changed.countDown()
        }
        changed.await(5, TimeUnit.SECONDS)
        var screen = ""
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            screen = onMain(main) { session?.emulator?.screen?.transcriptText ?: "" }
            if (STATUS_MARK in screen || "login.tailscale.com" in screen || finished) break
        }
        val exit = onMain(main) { session?.let { if (it.isRunning) null else it.exitStatus } }
        main.post { session?.finishIfRunning() }
        val url = Regex("https://login\\.tailscale\\.com/\\S+").find(screen)?.value
        val ok = STATUS_MARK in screen
        val lastLines = screen.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
        Check(id, title, if (ok) Outcome.PASS else Outcome.FAIL,
            buildString {
                append("argv: ${argv.joinToString(" ")}\n")
                if (url != null) append("Tailscale check needed: open $url then run again\n")
                if (exit != null) append("ssh exited with $exit\n")
                append("screen (last lines):\n$lastLines")
            })
    }

    private fun <T> onMain(main: Handler, block: () -> T): T {
        val latch = CountDownLatch(1)
        var value: Result<T>? = null
        main.post { value = runCatching(block); latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        return value!!.getOrThrow()
    }

    /** client-core's ssh base with -t (ssh may prompt here: it has the terminal), then the command. */
    private fun sshTtyArgv(host: String, command: String): List<String> = sshBase(host, listOf("-t")) + command

    private fun guarded(id: String, title: String, block: () -> Check): Check =
        try {
            block()
        } catch (e: CoreException) {
            Check(id, title, Outcome.FAIL, e.message ?: e.toString())
        } catch (e: Exception) {
            Check(id, title, Outcome.FAIL, e.toString())
        }

    private class QuietClient(val onText: () -> Unit, val onFinished: () -> Unit) : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = onText()
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) = onFinished()
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "", e) }
    }

    companion object {
        const val SPIKE_SESSION = "tmls-spike"
        /** tmux's default status-left is "[session] " cut at 10 characters. */
        const val STATUS_MARK = "[tmls-spik"
        const val PROBE_HOST = "tmls-config-probe"
        const val PROBE_PORT = 2222

        fun defaultConfig(host: String, user: String): String = buildString {
            append("# ~/.ssh/config of tmls (M1 spike). Edit freely; Save writes it.\n")
            append("# The probe host only exists to check that ssh reads this file (check 5).\n")
            append("Host $PROBE_HOST\n  HostName 192.0.2.1\n  Port $PROBE_PORT\n\n")
            if (host.isNotBlank()) append("Host $host\n  User $user\n\n")
            append("Host *\n  StrictHostKeyChecking accept-new\n")
        }

        fun report(results: List<Check>): String = results.joinToString("\n\n") { c ->
            "[${c.outcome}] ${c.id} ${c.title}\n${c.detail.trim()}"
        }
    }
}
