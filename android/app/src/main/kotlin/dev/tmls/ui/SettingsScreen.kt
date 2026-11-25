package dev.tmls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tmls.settings.Settings
import dev.tmls.ssh.HostStore
import dev.tmls.term.TerminalInput

@Composable
fun SettingsScreen(settings: Settings, store: HostStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val config = remember { store.config() }
    var defaultUser by remember { mutableStateOf(config.defaultUser) }
    var strict by remember { mutableStateOf(config.strictHostKeys) }
    var textMode by remember { mutableStateOf(settings.textInputMode) }
    var osc52 by remember { mutableStateOf(settings.osc52Copy) }
    var layout by remember { mutableStateOf(settings.extraKeys) }
    var saved by remember { mutableStateOf<String?>(null) }
    val layoutOk = TerminalInput.parseLayout(layout) != null

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { TextButton(onClick = onBack) { Text("← Back") } }
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("ssh", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            defaultUser, { defaultUser = it.trim() }, label = { Text("Default user") }, singleLine = true,
            supportingText = { Text("Used for every host that sets no User. Empty: ssh logs in as the app's Android user (u0_a…), which is never right.") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        Toggle("Refuse unknown host keys (StrictHostKeyChecking yes)", strict) { strict = it }
        Text("Off: a new host's key is accepted the first time and checked after that (accept-new).", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { store.saveDefaults(defaultUser, strict); saved = "Saved to ~/.ssh/config." }) { Text("Save ssh defaults") }

        Text("Keyboard", style = MaterialTheme.typography.titleMedium)
        Toggle("Text input mode (for keyboards that misbehave in the default mode)", textMode) { textMode = it; settings.textInputMode = it }
        Text("Takes effect when the terminal next opens.", style = MaterialTheme.typography.bodySmall)
        Toggle("Copy to the clipboard when the host asks (OSC 52)", osc52) { osc52 = it; settings.osc52Copy = it }
        OutlinedTextField(
            layout, { layout = it }, label = { Text("Extra keys (Termux syntax)") },
            isError = !layoutOk,
            supportingText = { Text(if (layoutOk) "Extra names: PREFIX (the host's tmux prefix), KEYBOARD, COMPOSE." else "Does not parse; the default is used.") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { settings.extraKeys = layout; saved = "Extra keys saved." }, enabled = layoutOk) { Text("Save keys") }
            OutlinedButton(onClick = { layout = Settings.DEFAULT_EXTRA_KEYS }) { Text("Default") }
        }
        OutlinedButton(onClick = { settings.textSizePx = 0; saved = "Text size reset." }) { Text("Reset text size") }
        saved?.let { Text(it) }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
