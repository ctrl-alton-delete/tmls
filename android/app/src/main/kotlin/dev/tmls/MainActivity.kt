package dev.tmls

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tmls.core.agentRules
import dev.tmls.core.version
import dev.tmls.spike.SpikeChecks
import dev.tmls.spike.SpikeScreen
import dev.tmls.term.TerminalScreen
import dev.tmls.ui.HomeScreen
import dev.tmls.ui.HostsScreen
import dev.tmls.ui.KeysScreen
import dev.tmls.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/** M2: home (open tabs + temporary picker), the terminal, hosts, keys, settings, diagnostics. */
class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TmlsApp
        val core = app.coreError?.let { Result.failure(it) } ?: runCatching { CoreInfo(version(), agentRules().count.toInt()) }
        val status = describe(core).joinToString(" · ") + (app.tools.missing().takeIf { it.isNotEmpty() }?.let { " · missing: ${it.joinToString()}" } ?: "")
        // the tabs' foreground-service notification (ARCH-5) needs this on Android 13+
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var screen by rememberSaveable { mutableStateOf(if (app.sessions.current != null) Screen.Terminal else Screen.Home) }
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                if (screen == Screen.Terminal) {
                    TerminalScreen(
                        app.sessions, app.settings,
                        onHome = { screen = Screen.Home },
                        onForgetHostKey = { tab ->
                            @Suppress("OPT_IN_USAGE")
                            GlobalScope.launch(Dispatchers.IO) {
                                runCatching { app.keys.forgetHostKey(tab.host) }
                                launch(Dispatchers.Main) { app.sessions.reattach(tab) }
                            }
                        },
                    )
                } else {
                    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                        val inner = Modifier.padding(padding).padding(16.dp)
                        when (screen) {
                            Screen.Home -> HomeScreen(
                                status, app.hosts.hosts(), app.sessions, app.settings,
                                onOpened = { screen = Screen.Terminal },
                                onHosts = { screen = Screen.Hosts },
                                onKeys = { screen = Screen.Keys },
                                onSettings = { screen = Screen.Settings },
                                onDiagnostics = { screen = Screen.Diagnostics },
                                modifier = inner,
                            )
                            Screen.Hosts -> HostsScreen(app.hosts, app.keys.list(), onBack = { screen = Screen.Home }, modifier = inner)
                            Screen.Keys -> KeysScreen(app.keys, onBack = { screen = Screen.Home }, modifier = inner)
                            Screen.Settings -> SettingsScreen(app.settings, app.hosts, onBack = { screen = Screen.Home }, modifier = inner)
                            Screen.Diagnostics -> SpikeScreen(SpikeChecks(app.tools), status, inner)
                            Screen.Terminal -> {}
                        }
                    }
                }
            }
        }
    }

    enum class Screen { Home, Terminal, Hosts, Keys, Settings, Diagnostics }
}

data class CoreInfo(val version: String, val agentRules: Int)

/** One line per fact; a failure to load the Rust library shows its message instead. */
fun describe(core: Result<CoreInfo>): List<String> =
    core.fold(
        onSuccess = { listOf(it.version, "${it.agentRules} agent detection rules") },
        onFailure = { listOf("core failed to load", it.toString()) },
    )
