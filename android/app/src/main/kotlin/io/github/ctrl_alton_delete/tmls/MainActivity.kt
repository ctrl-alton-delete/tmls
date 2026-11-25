package io.github.ctrl_alton_delete.tmls

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ctrl_alton_delete.tmls.core.agentRules
import io.github.ctrl_alton_delete.tmls.core.version

/** M0: proves the app reaches the Rust core. Replaced by the session list in M3. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = runCatching { CoreInfo(version(), agentRules().count.toInt()) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    CoreStatus(core, Modifier.padding(padding).padding(16.dp))
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

@Composable
private fun CoreStatus(core: Result<CoreInfo>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("tmls", style = MaterialTheme.typography.headlineMedium)
        describe(core).forEach { Text(it, fontFamily = FontFamily.Monospace) }
    }
}
