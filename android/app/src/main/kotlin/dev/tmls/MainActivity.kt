package dev.tmls

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tmls.core.agentRules
import dev.tmls.core.version
import dev.tmls.spike.SpikeChecks
import dev.tmls.spike.SpikeScreen

/** M1: the spike screen. Replaced by the session list in M3. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TmlsApp
        val core = app.coreError?.let { Result.failure(it) } ?: runCatching { CoreInfo(version(), agentRules().count.toInt()) }
        val checks = SpikeChecks(app.tools)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SpikeScreen(checks, describe(core).joinToString(" · "), Modifier.padding(padding).padding(16.dp))
                }
            }
        }
    }
}

data class CoreInfo(val version: String, val agentRules: Int)

/** One line per fact; a failure to load the Rust library shows its message instead. */
fun describe(core: Result<CoreInfo>): List<String> =
    core.fold(
        onSuccess = { listOf(it.version, "${it.agentRules} agent detection rules") },
        onFailure = { listOf("core failed to load", it.toString()) },
    )
