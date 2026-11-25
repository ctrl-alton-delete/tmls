package dev.tmls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.tmls.core.sessionNameProblem
import dev.tmls.settings.Settings
import dev.tmls.term.SessionManager

/**
 * M2's start screen: the open tabs, and a temporary picker (host + typed session name) until the
 * session list arrives in M3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    status: String,
    hosts: List<String>,
    manager: SessionManager,
    settings: Settings,
    onOpened: () -> Unit,
    onHosts: () -> Unit,
    onKeys: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var host by remember { mutableStateOf(settings.lastHost.takeIf { it in hosts } ?: hosts.firstOrNull().orEmpty()) }
    var name by remember { mutableStateOf(settings.lastSession.ifEmpty { "main" }) }
    var hostMenu by remember { mutableStateOf(false) }
    val problem = if (name.isEmpty()) null else runCatching { sessionNameProblem(name) }.getOrNull()

    fun open() {
        if (host.isEmpty() || name.isEmpty() || problem != null) return
        settings.lastHost = host
        settings.lastSession = name
        manager.open(host, name)
        onOpened()
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("tmls", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodySmall)

        if (manager.tabs.isNotEmpty()) {
            Text("Open tabs", style = MaterialTheme.typography.titleMedium)
            manager.tabs.forEach { tab ->
                Card(onClick = { manager.show(tab); onOpened() }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        Text((if (tab.ended != null) "⊘ " else "● ") + tab.label, Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { manager.close(tab) }) { Text("Detach") }
                    }
                }
            }
        }

        Text("Open a session", style = MaterialTheme.typography.titleMedium)
        if (hosts.isEmpty()) {
            Text("No hosts yet. Add one first.")
            Button(onClick = onHosts) { Text("Add a host") }
        } else {
            ExposedDropdownMenuBox(expanded = hostMenu, onExpandedChange = { hostMenu = it }) {
                OutlinedTextField(
                    value = host,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Host") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(hostMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("host"),
                )
                DropdownMenu(expanded = hostMenu, onDismissRequest = { hostMenu = false }) {
                    hosts.forEach { h -> DropdownMenuItem(text = { Text(h) }, onClick = { host = h; hostMenu = false }) }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim() },
                label = { Text("Session (created if it does not exist)") },
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { open() }),
                modifier = Modifier.fillMaxWidth().testTag("session"),
            )
            Button(onClick = ::open, enabled = host.isNotEmpty() && name.isNotEmpty() && problem == null, modifier = Modifier.testTag("open")) {
                Text("Open")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onHosts) { Text("Hosts") }
            OutlinedButton(onClick = onKeys) { Text("Keys") }
            OutlinedButton(onClick = onSettings) { Text("Settings") }
        }
        TextButton(onClick = onDiagnostics) { Text("Diagnostics (M1 checks)") }
    }
}
