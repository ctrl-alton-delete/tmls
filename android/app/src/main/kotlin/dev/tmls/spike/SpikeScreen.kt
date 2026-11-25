package dev.tmls.spike

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** M1 spike screen: run the checks against a host, edit ~/.ssh/config, make a key, copy a report. */
@Composable
fun SpikeScreen(checks: SpikeChecks, header: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("spike", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "alton-desktop")!!) }
    var user by remember { mutableStateOf(prefs.getString("user", "alton")!!) }
    var config by remember { mutableStateOf(checks.readConfig().ifBlank { SpikeChecks.defaultConfig(host, user) }) }
    var running by remember { mutableStateOf(false) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<SpikeChecks.Check>() }

    fun copy(label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics (M1 checks)", style = MaterialTheme.typography.headlineSmall)
        Text(header, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(user, { user = it.trim() }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            config, { config = it }, label = { Text("~/.ssh/config") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running, onClick = {
                prefs.edit().putString("host", host).putString("user", user).apply()
                checks.writeDefaultConfig(config)
                running = true
                results.clear()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        checks.runAll(host) { check -> scope.launch(Dispatchers.Main) { results += check } }
                    }
                    running = false
                }
            }) { Text(if (running) "Running…" else "Save config + run checks") }
            OutlinedButton(enabled = results.isNotEmpty() && !running, onClick = { copy("M1 report", SpikeChecks.report(results)) }) { Text("Copy report") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val key = withContext(Dispatchers.IO) { checks.ensureKey() }
                    publicKey = key.getOrElse { "failed: ${it.message}" }
                }
            }) { Text("Make/show ssh key") }
            publicKey?.let { key -> OutlinedButton(onClick = { copy("public key", key) }) { Text("Copy public key") } }
        }
        publicKey?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }

        HorizontalDivider()
        for (check in results) {
            val color = when (check.outcome) {
                SpikeChecks.Outcome.PASS -> Color(0xFF4CAF50)
                SpikeChecks.Outcome.FAIL -> Color(0xFFE57373)
                SpikeChecks.Outcome.SKIP -> Color.Gray
            }
            Text("${check.outcome} · ${check.id} ${check.title}", color = color, style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(check.detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
