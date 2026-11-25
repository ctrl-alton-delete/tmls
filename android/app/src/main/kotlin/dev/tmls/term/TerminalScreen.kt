package dev.tmls.term

import android.graphics.Typeface
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.view.TerminalView
import dev.tmls.settings.Settings

/**
 * The terminal (spec TERM-1…TERM-6, KB-1…KB-8): the tab strip, the current tab's screen, the bar
 * over an ended tab, the composer, and the extra-keys row right above the soft keyboard.
 */
@Composable
fun TerminalScreen(
    manager: SessionManager,
    settings: Settings,
    onHome: () -> Unit,
    onForgetHostKey: (Tab) -> Unit,
) {
    val tab = manager.current
    var composerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val input = remember { TerminalInput(context, settings, { manager.current }, { composerOpen = !composerOpen }) }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TabStrip(manager, onHome = onHome, onCompose = { composerOpen = !composerOpen })

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal"),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    input.terminalView = this
                    setTerminalViewClient(input)
                    setTypeface(Typeface.MONOSPACE)
                    setTextSize(settings.textSizePx.takeIf { it > 0 } ?: TerminalInput.defaultTextSizePx(ctx))
                    isFocusable = true
                    isFocusableInTouchMode = true
                    keepScreenOn = false
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        val view = v as TerminalView
                        view.currentSession?.emulator?.let { manager.lastSize = intArrayOf(it.mColumns, it.mRows, 12, 24) }
                    }
                }
            },
            update = { view -> tab?.let { manager.attach(view, it) } },
        )
        DisposableEffect(Unit) { onDispose { manager.view = null } }

        tab?.ended?.let { ended -> EndedBar(ended, onReattach = { manager.reattach(tab) }, onForget = { onForgetHostKey(tab) }, onClose = { manager.close(tab) }) }

        if (composerOpen && tab != null) Composer(manager, tab, settings, onClose = { composerOpen = false })

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(EXTRA_KEYS_ROW_HEIGHT * 2),
            factory = { ctx ->
                ExtraKeysView(ctx, null).apply {
                    input.extraKeysView = this
                    setExtraKeysViewClient(input.extraKeysClient())
                    setButtonTextAllCaps(true)
                    reload(TerminalInput.parseLayout(settings.extraKeys) ?: TerminalInput.parseLayout(Settings.DEFAULT_EXTRA_KEYS))
                }
            },
        )
    }

    LaunchedEffect(tab?.id) { if (tab != null && !composerOpen) input.showKeyboard() }
    if (tab == null) LaunchedEffect(Unit) { onHome() }
}

@Composable
private fun TabStrip(manager: SessionManager, onHome: () -> Unit, onCompose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onHome) { Text("☰") }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            manager.tabs.forEach { t ->
                var menu by remember { mutableStateOf(false) }
                FilterChip(
                    selected = t == manager.current,
                    onClick = { if (t == manager.current) menu = true else manager.show(t) },
                    label = {
                        Text((if (t.ended != null) "⊘ " else "") + t.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
                    },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reattach") }, onClick = { menu = false; manager.reattach(t) })
                    DropdownMenuItem(text = { Text("Close tab (detach)") }, onClick = { menu = false; manager.close(t) })
                }
            }
        }
        TextButton(onClick = onCompose, modifier = Modifier.testTag("compose")) { Text("✎") }
    }
}

@Composable
private fun EndedBar(ended: Ended, onReattach: () -> Unit, onForget: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(ended.message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ended.hostKeyChanged) TextButton(onClick = onForget) { Text("Forget old key") }
                TextButton(onClick = onReattach, modifier = Modifier.testTag("reattach")) { Text("Reattach") }
                TextButton(onClick = onClose) { Text("Close tab") }
            }
        }
    }
}

/** KB-8: a normal Android text field (suggestions, dictation, swipe); Send pastes it as one. */
@Composable
private fun Composer(manager: SessionManager, tab: Tab, settings: Settings, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    var historyOpen by remember { mutableStateOf(false) }
    var enter by remember { mutableStateOf(settings.sendPressesEnter) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = tab.draft,
                onValueChange = { tab.draft = it },
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).focusRequester(focus).testTag("composer"),
                placeholder = { Text("Message to ${tab.name}") },
                maxLines = 6,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        manager.sendText(tab, tab.draft, enter)
                        tab.draft = ""
                    },
                    enabled = tab.draft.isNotEmpty() && tab.ended == null,
                    modifier = Modifier.testTag("send"),
                ) { Text(if (enter) "Send ⏎" else "Send") }
                FilterChip(selected = enter, onClick = { enter = !enter; settings.sendPressesEnter = enter }, label = { Text("⏎") })
                Column {
                    TextButton(onClick = { historyOpen = true }, enabled = manager.history.isNotEmpty()) { Text("History") }
                    DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                        manager.history.forEach { h ->
                            DropdownMenuItem(
                                text = { Text(h, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = { tab.draft = h; historyOpen = false },
                            )
                        }
                    }
                }
                AssistChip(onClick = onClose, label = { Text("Close") })
            }
        }
    }
    LaunchedEffect(tab.id) { focus.requestFocus() }
}

private val EXTRA_KEYS_ROW_HEIGHT = 40.dp
