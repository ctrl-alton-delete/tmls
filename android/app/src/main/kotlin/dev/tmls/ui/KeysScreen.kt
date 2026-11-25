package dev.tmls.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.tmls.ssh.Keys
import dev.tmls.ssh.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** HOST-3: the app's keys; make one, import one, copy or share the public half. */
@Composable
fun KeysScreen(keys: Keys, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(keys.list()) }
    var name by remember { mutableStateOf(if (list.none { it.name == "id_ed25519" }) "id_ed25519" else "id_tmls") }
    var passphrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<SshKey?>(null) }

    fun busy(work: () -> String) {
        scope.launch {
            message = withContext(Dispatchers.IO) { runCatching(work).fold({ it }, { "Failed: ${it.message}" }) }
            list = keys.list()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) busy {
            val key = context.contentResolver.openInputStream(uri)!!.use { keys.import(name, it, passphrase) }
            if (key.publicKey == null) "Imported ${key.name}. It has a passphrase: ssh asks for it in the terminal." else "Imported ${key.name}."
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { TextButton(onClick = onBack) { Text("← Back") } }
        Text("Keys", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Tailscale SSH needs no key. For other hosts, add a public key below to the host's ~/.ssh/authorized_keys.",
            style = MaterialTheme.typography.bodySmall,
        )
        list.forEach { key ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(key.name, style = MaterialTheme.typography.titleMedium)
                    val pub = key.publicKey
                    if (pub != null) {
                        SelectionContainer { Text(pub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ssh public key", pub))
                                message = "Copied the public key of ${key.name}."
                            }) { Text("Copy") }
                            TextButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pub)
                                context.startActivity(Intent.createChooser(send, "Share public key"))
                            }) { Text("Share") }
                            TextButton(onClick = { confirmDelete = key }) { Text("Delete") }
                        }
                    } else {
                        Text("Public key unknown (the key has a passphrase).", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { confirmDelete = key }) { Text("Delete") }
                    }
                    if (confirmDelete == key) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Delete ${key.name} for good?")
                            TextButton(onClick = { keys.delete(key); confirmDelete = null; list = keys.list() }) { Text("Delete") }
                            TextButton(onClick = { confirmDelete = null }) { Text("Keep") }
                        }
                    }
                }
            }
        }

        Text("New key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it.trim() }, label = { Text("File name in ~/.ssh") }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth().testTag("key-name"))
        OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase (optional)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("With a passphrase, ssh asks for it in the terminal on every connection; listing (M3) can't use such a key.") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { busy { keys.generate(name, passphrase).let { "Made ${it.name} (ed25519)." } } }, modifier = Modifier.testTag("generate-key")) { Text("Generate ed25519") }
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import a private key…") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
