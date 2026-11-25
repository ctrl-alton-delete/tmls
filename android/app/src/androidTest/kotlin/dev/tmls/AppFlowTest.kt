package dev.tmls

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.util.Base64
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.tmls.ssh.HostEntry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * The phone's path through the real activity (M2 crash on Open): Home > Open starts the tab, the
 * foreground service and the terminal screen (TerminalView + extra keys); the composer sends.
 * Without the CI test host's key, ssh fails but everything up to the ended bar still runs.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TmlsApp
    private val compose = createEmptyComposeRule()
    private val session = "flow-${System.currentTimeMillis() % 100000}"

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant(),
        )
        .around(compose)

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.sessions.closeAll() }
    }

    @Test
    fun openFromHome() {
        val key = InstrumentationRegistry.getArguments().getString("sshKey")
        if (!key.isNullOrBlank()) {
            val identity = File(app.tools.sshDir, "id_testhost")
            identity.writeBytes(Base64.decode(key, Base64.DEFAULT))
            android.system.Os.chmod(identity.absolutePath, "600".toInt(8))
        }
        app.hosts.saveHost(HostEntry("testhost", hostName = "10.0.2.2", port = 2222, user = "tmls", identityFile = "~/.ssh/id_testhost"), null)
        app.settings.lastHost = "testhost"
        app.settings.lastSession = session

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag("open"), 10_000)
            compose.onNodeWithTag("open").performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("terminal"), 10_000)

            // the foreground service's notification (ARCH-5)
            val notifications = app.getSystemService(NotificationManager::class.java)
            compose.waitUntil(10_000) { notifications.activeNotifications.isNotEmpty() }

            if (!key.isNullOrBlank()) {
                compose.waitUntil(30_000) { screen().contains("[$session") }
                compose.onNodeWithTag("compose").performClick()
                compose.onNodeWithTag("composer").performTextInput("echo flow-\$((6*7))-ok")
                compose.onNodeWithTag("send").performClick()
                compose.waitUntil(30_000) { screen().contains("flow-42-ok") }
            }
            compose.waitForIdle()
            assertTrue(app.sessions.tabs.size == 1)
        }
    }

    private fun screen(): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = app.sessions.current?.session?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }
}
