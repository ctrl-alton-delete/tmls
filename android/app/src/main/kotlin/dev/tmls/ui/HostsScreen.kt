package dev.tmls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tmls.ssh.HostEntry
import dev.tmls.ssh.HostStore
import dev.tmls.ssh.SshKey

/** The hosts (HOST-1, HOST-2): the list, the editor, and the raw `~/.ssh/config` editor. */
@Composable
fun HostsScreen(store: HostStore, keys: List<SshKey>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf<HostEntry?>(null) }
    var editingOriginal by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }

    val edit = editing
    val rawText = raw
    when {
        edit != null -> HostEditor(
            initial = edit,
            original = editingOriginal,
            keys = keys,
            onSave = { store.saveHost(it, editingOriginal); editing = null; version++ },
            onDelete = editingOriginal?.let { { store.deleteHost(it); editing = null; version++ } },
            onCancel = { editing = null },
            modifier = modifier,
        )
        rawText != null -> RawConfigEditor(rawText, onSave = { store.saveRaw(it); raw = null; version++ }, onCancel = { raw = null }, modifier = modifier)
        else -> {
            val config = remember(version) { store.config() }
            val listed = store.hostsFile.read()
            Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { TextButton(onClick = onBack) { Text("← Back") } }
                Text("Hosts", style = MaterialTheme.typography.headlineSmall)
                store.hosts().forEach { alias ->
                    val entry = config.host(alias)
                    Card(
                        onClick = { editingOriginal = alias; editing = entry ?: HostEntry(alias) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(alias, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                            val details = entry?.let { listOfNotNull(
                                it.user.ifEmpty { null }?.let { u -> "$u@" }.orEmpty() + it.hostName.ifEmpty { "(no HostName)" },
                                it.port?.let { p -> "port $p" },
                                it.identityFile.ifEmpty { null },
                            ).joinToString(" · ") } ?: "defined in your own config text"
                            Text(details + if (alias !in listed) " · not in the hosts file" else "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Button(onClick = { editingOriginal = null; editing = HostEntry("", user = config.defaultUser) }, modifier = Modifier.testTag("add-host")) { Text("Add a host") }
                OutlinedButton(onClick = { raw = store.rawConfig() }) { Text("Edit ~/.ssh/config as text") }
            }
        }
    }
}

@Composable
private fun HostEditor(
    initial: HostEntry,
    original: String?,
    keys: List<SshKey>,
    onSave: (HostEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    var alias by remember { mutableStateOf(initial.alias) }
    var hostName by remember { mutableStateOf(initial.hostName) }
    var user by remember { mutableStateOf(initial.user) }
    var port by remember { mutableStateOf(initial.port?.toString().orEmpty()) }
    var identity by remember { mutableStateOf(initial.identityFile) }
    var proxyJump by remember { mutableStateOf(initial.proxyJump) }
    var keyMenu by remember { mutableStateOf(false) }

    val entry = initial.copy(
        alias = alias.trim(), hostName = hostName.trim(), user = user.trim(),
        port = port.trim().takeIf { it.isNotEmpty() }?.toIntOrNull(), identityFile = identity.trim(), proxyJump = proxyJump.trim(),
    )
    val portError = port.isNotBlank() && port.trim().toIntOrNull() == null
    val problems = entry.problems() + if (portError) mapOf("port" to "a number") else emptyMap()

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (original == null) "New host" else "Host $original", style = MaterialTheme.typography.headlineSmall)
        Field("Name (what tmls and ssh call it)", alias, { alias = it }, problems["alias"], "alias")
        Field(
            "HostName", hostName, { hostName = it }, problems["hostName"], "hostname",
            help = "The full Tailscale name (box.your-tailnet.ts.net) or 100.x.y.z address, or a LAN name/IP. " +
                "Leave empty only if the name above is itself a real DNS name.",
        )
        Field("User", user, { user = it }, problems["user"], "user", help = "Empty: the default user from Settings.")
        Field("Port", port, { port = it }, problems["port"], "port", keyboard = KeyboardType.Number, help = "Empty: 22.")
        Column {
            Field("Key (IdentityFile)", identity, { identity = it }, problems["identityFile"], "identity",
                help = "Empty: ssh tries ~/.ssh/id_ed25519 and its other defaults. Tailscale SSH needs no key.")
            TextButton(onClick = { keyMenu = true }, enabled = keys.isNotEmpty()) { Text("Choose a key") }
            DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                DropdownMenuItem(text = { Text("(ssh's defaults)") }, onClick = { identity = ""; keyMenu = false })
                keys.forEach { k -> DropdownMenuItem(text = { Text(k.name) }, onClick = { identity = k.configPath; keyMenu = false }) }
            }
        }
        Field("ProxyJump (optional)", proxyJump, { proxyJump = it }, problems["proxyJump"], "proxyjump")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(entry) }, enabled = problems.isEmpty(), modifier = Modifier.testTag("save-host")) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    tag: String,
    help: String? = null,
    keyboard: KeyboardType = KeyboardType.Ascii,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: help)?.let { { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun RawConfigEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var text by remember { mutableStateOf(initial) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("~/.ssh/config", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The sections between the tmls markers are rewritten by the host editor and Settings; edit everything else freely.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text) }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
