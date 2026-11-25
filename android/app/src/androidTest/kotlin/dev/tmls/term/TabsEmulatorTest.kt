package dev.tmls.term

import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tmls.TmlsApp
import dev.tmls.core.run
import dev.tmls.core.sshArgv
import dev.tmls.settings.Settings
import dev.tmls.ssh.HostEntry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plan M2 on the emulator (CI): tabs against the workflow's test host (sshd + tmux at
 * 10.0.2.2:2222, key in the `sshKey` argument), through the host editor's config and the same
 * [SessionManager] the app uses: open (creates the session), composer send, detach, reattach.
 */
@RunWith(AndroidJUnit4::class)
class TabsEmulatorTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TmlsApp
    private val main = Handler(Looper.getMainLooper())
    private lateinit var manager: SessionManager
    private val session = "m2-${System.currentTimeMillis() % 100000}"

    @Before
    fun setUp() {
        val key = InstrumentationRegistry.getArguments().getString("sshKey")
        assumeTrue("no sshKey argument: no test host", !key.isNullOrBlank())
        val identity = File(app.tools.sshDir, "id_testhost")
        identity.writeBytes(Base64.decode(key, Base64.DEFAULT))
        android.system.Os.chmod(identity.absolutePath, "600".toInt(8))
        app.hosts.saveHost(HostEntry("testhost", hostName = "10.0.2.2", port = 2222, user = "tmls", identityFile = "~/.ssh/id_testhost"), null)
        manager = onMain { SessionManager(app, app.tools, Settings(app), onTabCount = {}) }
    }

    @Test
    fun openSendDetachReattach() {
        val tab = onMain { manager.open("testhost", session) }
        waitForScreen(tab, "[$session") // tmux's status line: the session exists and is shown

        onMain { manager.sendText(tab, "echo composer-\$((6*7))-ok", enter = true) }
        waitForScreen(tab, "composer-42-ok")

        // detach: ssh ends, the tmux session lives on
        onMain { manager.close(tab) }
        waitFor("tmux session kept after detach") {
            runCatching { run(sshArgv("testhost", "tmux has-session -t =$session && echo yes"), 15_000u) }.getOrNull()?.trim() == "yes"
        }

        // a tab whose ssh died shows why, and Reattach brings the same session back
        val again = onMain { manager.open("testhost", session) }
        waitForScreen(again, "composer-42-ok")
        onMain { again.session.finishIfRunning() }
        waitFor("ended bar") { again.ended != null }
        assertNotNull(again.ended)
        onMain { manager.reattach(again) }
        assertNull(again.ended)
        waitForScreen(again, "[$session")

        onMain { manager.closeAll() }
        runCatching { run(sshArgv("testhost", "tmux kill-session -t =$session"), 15_000u) }
    }

    @Test
    fun defaultExtraKeysParse() {
        val info = TerminalInput.parseLayout(Settings.DEFAULT_EXTRA_KEYS)
        assertNotNull(info)
        val keys = info!!.matrix.flatten().map { it.key }
        assertTrue(keys.toString(), keys.containsAll(listOf("ESC", "PREFIX", "CTRL", "ALT", "KEYBOARD")))
        assertTrue(info.matrix.flatten().first { it.key == "-" }.popup?.key == "|")
    }

    private fun waitForScreen(tab: Tab, text: String) {
        runCatching { waitFor("'$text' on screen") { screen(tab).contains(text) } }
            .onFailure { throw AssertionError("${it.message}; screen:\n${screen(tab)}") }
    }

    private fun screen(tab: Tab): String = onMain { tab.session.emulator?.screen?.transcriptText.orEmpty() }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (done()) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post { result = runCatching(block); latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "main thread busy" }
        return result!!.getOrThrow()
    }
}
