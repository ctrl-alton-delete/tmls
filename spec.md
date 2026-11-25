# tmls for Android — Specification

Status: draft 1 (2026-11-25). Fork: `ctrl-alton-delete/tmls`, branch `android`. Upstream:
`sagunkayastha/tmls` (Rust desktop app, MIT OR Apache-2.0). Companion: [plan.md](plan.md).

This document says **what** the Android app does and why. `plan.md` says how and in what order.
Each requirement has an ID (`KB-3`, `AG-2`, …) so the plan, issues and tests can point at it.

---

## 1. Summary and goals

tmls for Android is a native Android app for running and supervising coding agents (Claude
Code, agy (Antigravity CLI), pi, opencode, Codex and others) in tmux sessions. The sessions can
live on the phone itself and on remote machines reached over ssh/Tailscale. It is a mobile front end
for the same idea as tmls on the desktop: **one list of every tmux session on every host, which
knows what each agent is doing, and a real terminal for each one.**

The four goals, in priority order:

| # | Goal | What it means here |
|---|------|--------------------|
| G1 | **Mobile-first input** | The normal Android keyboard (Gboard, voice dictation, swipe typing) stays the main input. A Termux-style extra-keys row adds the keys phone keyboards lack: Esc, Tab, Ctrl, Alt, arrows, and an agent-aware layer on top. |
| G2 | **Don't reinvent the wheel** | Termux's terminal emulator and views, tmls's Rust core, herdr's agent-detection rules, and Termux's OpenSSH build recipes are reused, not rewritten. New code is the glue and the mobile UI. |
| G3 | **Agents here and on remote machines, fluently** | Starting an agent, seeing it's waiting, answering it, and getting back to it after a network drop should each take one or two taps, on any host. |
| G4 | **Terminal multiplexer power** | tmux does the real work: sessions survive the app, the phone and the network. Every tmux session is a full terminal, not a chat imitation. |

## 2. Non-goals

- **Not a Linux distribution.** The app does not ship a userland (no proot, no package
  manager). Agents that run *on the phone* run in z2term or Termux, which the app reaches as an
  ssh host on `localhost` (see HOST-7). This keeps the app small and leaves the hard part to
  projects that already do it.
- **Not a replacement for the tmls desktop UI.** The desktop crate (`crates/desktop`, winit +
  wgpu + libghostty) is not ported. Only `crates/client-core` is shared.
- **Not a chat UI for agents.** Collie/Happy-style chat views are out of scope for v1; the
  terminal is the truth. A composer (KB-8) and approve buttons (AG-5) are the only "chat-like" parts.
- **No cloud relay, no accounts.** Hosts are reached directly over the network (Tailscale in
  practice). Nothing leaves the user's own machines.
- **No Play Store in v1.** Distributed as signed APKs from GitHub releases (and Obtainium).
- **Not iOS.**
- **herdr and zellij as session backends**: not in v1 (open question Q4).

## 3. Scenarios

These are the cases the design is checked against. "Alton" is the user; the phone is a
Fairphone (arm64, Android 14+), with the Tailscale app connected.

- **S1 — Glance.** On the bus, Alton opens the app. Within 2 s it shows every session on
  `alton-desktop`, `alton-cachyos` and the phone, with `?` on the two agents that wait for him.
- **S2 — Approve from the notification.** The phone buzzes: "alton-desktop · pollen-api ? Bash:
  `cargo test`". He taps **Approve** on the notification without opening the app. The prompt is
  re-read just before the key is sent; if it changed, nothing is sent and the notification says so.
- **S3 — Reply by voice.** A Claude session finished (`◆`). He taps it, opens the composer, dictates
  "now run the integration tests and open a PR", edits one word with Gboard's autocorrect, and sends.
  The text arrives in Claude's input as one paste, then Enter.
- **S4 — Real terminal work.** He opens a shell session on `alton-cachyos`, runs `htop`, uses
  `Ctrl+C`, `Esc`, `Tab` completion, arrow keys and the tmux prefix from the extra-keys row,
  without a hardware keyboard.
- **S5 — Flaky network.** The train enters a tunnel. The tab shows "connection lost — reconnecting".
  When the network returns, the tab reattaches on its own; tmux kept the session running.
- **S6 — Start an agent.** He taps **+** on `alton-desktop`, picks folder `~/src/pollen-api`,
  start = `claude`, and the new session opens in a tab.
- **S7 — Agent on the phone.** He starts `pi` in a session on the phone itself (z2term's Alpine
  via localhost sshd). It shows in the same list as the remote sessions.
- **S8 — Share a screenshot to an agent.** From the gallery he shares a screenshot to tmls,
  picks the session, and the image is copied to that session's host and its path pasted into
  the agent's input.

## 4. Prior art and what we reuse

| Need | Source | Licence | How it is used |
|------|--------|---------|----------------|
| Terminal emulation (VT parser, screen, scrollback, mouse, truecolor) | Termux `terminal-emulator` | Apache-2.0 | Dependency via JitPack (or vendored fork) |
| Terminal view (rendering, selection, IME glue, gestures) | Termux `terminal-view` | Apache-2.0 | Dependency; extended by subclassing where possible |
| Extra-keys row (layout syntax, popups, sticky modifiers) | Termux `termux-shared` `extrakeys/*` | GPLv3 | Dependency or vendored copy; makes the app GPLv3 |
| Hosts, listing, status, tmux commands, agent detection, approve, push events, config | tmls `crates/client-core` | MIT OR Apache-2.0 | Compiled for Android (aarch64) and called over a UniFFI binding |
| Agent detection rules (claude, agy, pi, opencode, codex, gemini, …) | herdr `agent-detection/*.toml` (already copied in tmls) | Apache-2.0 | Unchanged, updated from herdr on a schedule |
| `ssh` (with ControlMaster), `scp`, later `mosh-client` | OpenSSH / mosh, built with Termux's `termux-packages` recipes | BSD / GPLv3 | Prebuilt Android binaries, shipped as `lib*.so` in the APK (see ARCH-3) |
| Boot start | Termux:Boot (idea) | — | `BOOT_COMPLETED` receiver starting the monitor service |
| Device features from the shell | Termux:API, z2term `z2-*` (ideas) | — | Not in v1; see Q6 |
| UX references | Collie (agent dashboard, quick replies, key pad), Chuchu (libghostty ssh client, accessory keys), z2term | — | Ideas only; no code |

The whole app is **GPLv3** (because of `termux-shared`'s extra-keys code). `client-core` keeps
its own MIT OR Apache-2.0 licence, so changes to it can go back upstream.

## 5. Architecture

```
┌──────────────────────────── Android app (Kotlin) ─────────────────────────────┐
│                                                                              │
│  UI (Jetpack Compose)                         Terminal (Android Views)       │
│  ├ SessionListScreen                          ├ TerminalView (termux)        │
│  ├ TabsScreen  ── hosts ──────────────────────┤ ExtraKeysView (termux)       │
│  ├ NewSession / Rename / Kill dialogs         └ Composer sheet               │
│  ├ Approve sheet, Alerts, Settings                                           │
│  └ Host & key editor                                                         │
│                                                                              │
│  App state (ViewModels, Kotlin Flows)                                        │
│      ▲ snapshots / events            │ commands (argv)                       │
│      │                               ▼                                       │
│  ┌───────────── tmls-core (Rust, .so via UniFFI) ──────────────┐             │
│  │ client-core: Config, Poller, listing, detect, push Tail,    │             │
│  │ approve, seen/unread, tmux argv builders, files             │             │
│  │ core-ffi: Kotlin-facing API, callbacks, Android env setup   │             │
│  └─────────────────────────────┬───────────────────────────────┘             │
│                                │ fork/exec                                   │
│  TerminalSession (termux) ─────┤ `ssh -t HOST tmux attach -t =NAME`          │
│                                ▼                                             │
│  Bundled binaries in nativeLibraryDir: libssh.so, libscp.so, (libmosh.so)    │
│  + $APP/bin symlinks: ssh → libssh.so …   $HOME=$APP/home  (~/.ssh, ~/.config/tmls) │
│                                                                              │
│  MonitorService (foreground service): owns the Poller + push tails while the │
│  app is in the background; posts notifications with Approve / Reply actions. │
└──────────────────────────────────────────────────────────────────────────────┘
          │ TCP over the Tailscale VPN (system VPN; no tsnet in the app)
          ▼
   alton-desktop, alton-cachyos, …: sshd + tmux (+ tmls hooks for Claude)
   phone itself: z2term/Termux sshd on 127.0.0.1:PORT + tmux
```

Decisions:

- **ARCH-1 — Kotlin + Views for the terminal, Compose for everything else.** Termux's
  `TerminalView` is a View; it is hosted in Compose with `AndroidView`. No custom renderer.
- **ARCH-2 — Rust core over UniFFI.** `client-core` is built for `aarch64-linux-android`
  (and `x86_64-linux-android` for the emulator) with `cargo-ndk`. A thin new crate,
  `crates/core-ffi`, exposes a Kotlin-friendly API (records, enums, callback interfaces).
  `client-core` itself changes as little as possible (see plan §7).
- **ARCH-3 — Real OpenSSH, run as a program.** `client-core` already does everything by
  running `ssh … HOST COMMAND` with a shared ControlMaster connection. We keep that model.
  OpenSSH built for Android (Termux recipe, our package name) ships in the APK as
  `lib/arm64-v8a/libssh.so` etc., because on Android 10+ only files in the app's
  `nativeLibraryDir` may be executed. At start the app makes `$APP/bin/ssh → libssh.so` symlinks and puts
  `$APP/bin` first on `PATH` for both the Rust core and every `TerminalSession`.
  If the M1 spike shows this does not work, the fallback is an in-process ssh library
  (sshj or libssh2) behind a `Transport` trait; see plan risk R1.
- **ARCH-4 — tmux only on the hosts.** The app does not run tmux itself. A tab is a
  `TerminalSession` running `ssh -t HOST tmux attach -t =NAME` (the argv from
  `tmux::attach_argv`). The phone's own sessions go through the localhost sshd (HOST-7).
- **ARCH-5 — One process, one foreground service.** The Poller and push tails live in a
  `MonitorService` (foreground service type `dataSync`; `specialUse` if `dataSync` time limits
  bite on Android 15+) so alerts keep working with the app in the background. Terminal sessions
  are owned by the same service so tabs survive the Activity being destroyed.
- **ARCH-6 — Files.** App-private storage only: `$APP/home/.ssh/` (config, keys,
  known_hosts, ControlMaster sockets in `$APP/run/`), `$APP/home/.config/tmls/` (tmls's own
  config files, same formats as the desktop). Nothing in shared storage.

## 6. Functional requirements

Priority: **P0** = needed for the first usable build (M2–M5), **P1** = v1.0, **P2** = after v1.0.

### 6.1 Hosts and connections

- **HOST-1 (P0)** Hosts are names `ssh` accepts, as on the desktop: the `hosts` file in
  `~/.config/tmls/`, one per line. Aliases, users, ports and keys live in `~/.ssh/config`.
- **HOST-2 (P0)** A host editor in the app writes both files: name, hostname, user, port, identity,
  optional `ProxyJump`. Advanced users can edit the raw `~/.ssh/config` in a text editor screen.
- **HOST-3 (P0)** Keys: generate an ed25519 key in the app (optional passphrase), show and copy
  the public key, and offer to share it. Import an existing private key from a file (SAF picker).
  Keys stay in app-private storage (OpenSSH needs a file; the Android Keystore cannot be used by
  OpenSSH directly).
- **HOST-4 (P0)** **Tailscale SSH** works with no key at all when the tailnet's ACLs allow it:
  the app needs nothing special beyond the system Tailscale VPN. If Tailscale SSH asks for a
  browser check, the URL it prints in the tab is tappable (TERM-7).
- **HOST-5 (P0)** Listing never asks for a password (`BatchMode=yes`, as on the desktop): a host
  that would need one shows `· offline (auth)`. Attaching runs ssh in the tab, so a password or
  host-key prompt shows there and can be answered.
- **HOST-6 (P0)** Shared connections: ControlMaster sockets in `$APP/run/cm-%C`, with
  `ControlPersist` so the list, push tails and attaches share one TCP connection per host. They are closed
  when the service stops.
- **HOST-7 (P0)** **The phone as a host.** A built-in host entry "this phone" points at
  `127.0.0.1` on a configurable port (z2term's `sshd`, or Termux's 8022). The setup screen
  explains the one-time steps (start sshd, add the app's public key). It is listed first, named
  by the `name` file (default: the device model).
- **HOST-8 (P1)** Network changes (Wi-Fi ↔ mobile, VPN reconnect) trigger an immediate refresh
  of every host and a reattach of dropped tabs (`ConnectivityManager` callbacks).
- **HOST-9 (P2)** mosh for tabs (`mosh HOST -- tmux attach …`) as a per-host option.

### 6.2 Session list

- **LIST-1 (P0)** One list of every session on every host, grouped under a header per host,
  refreshed every 5 s while visible (`TMLS_RS_POLL_S` equivalent in settings). A host that does
  not answer shows `· offline` and delays no other host (the Poller's one thread per host).
- **LIST-2 (P0)** Each row: status mark (`●` running, `?` waiting, `◆` done unseen, `✕` error,
  `○` idle), name, agent name (claude / agy / pi / opencode / …), windows count, attached
  marker, last activity age, unread dot `•`, pin `★`. What a waiting agent asks (one line) is shown under
  the row.
- **LIST-3 (P0)** Sort: `top` sessions, then per host pinned first, then waiting, then by
  activity. Host order follows the `hosts` file.
- **LIST-4 (P0)** Tap = attach in a tab (or switch to its tab). Long-press = the session's
  menu (Approve…, Send message…, Rename, Kill…, Pin, Copy attach command).
- **LIST-5 (P0)** Pull to refresh = `refresh_now`.
- **LIST-6 (P1)** Filter chips: All / Needs me (`?` `✕`) / Agents / Unread. A search field
  (the desktop's go-to picker) narrows across all hosts as you type.
- **LIST-7 (P1)** Host header: colour from `host-colors.json`, `· poll` when push events are not
  set up on that host, `+` for a new session there.
- **LIST-8 (P1)** Swipe a row right = pin/unpin; left = menu. Configurable.

### 6.3 Terminal

- **TERM-1 (P0)** A tab is a Termux `TerminalSession` running the attach argv. Scrollback
  follows Termux defaults (configurable); with tmux `mouse on`, scrolling goes to tmux.
- **TERM-2 (P0)** Tabs: a tab strip (or swipe left/right on the extra-keys row) between open
  sessions; a tab shows its host colour and status mark. Closing a tab detaches; the
  session lives on.
- **TERM-3 (P0)** A tab whose ssh ended keeps its screen and shows a bar: "session ended" or
  "connection lost — reconnecting in Ns" with **Reattach** now. Automatic retry with backoff
  (1, 2, 5, 10, 30 s) while the host is reachable; immediately on network change (HOST-8).
- **TERM-4 (P0)** Pinch to change font size; double-tap on the extra-keys row toggles the soft
  keyboard (Termux behaviour). Font: bundled monospace with Nerd-symbol fallback (configurable).
- **TERM-5 (P0)** Selection by long-press (Termux), copy to the Android clipboard; paste from the
  menu or the extra-keys row. OSC 52 copy from tmux/agents into the Android clipboard (setting, on by default).
- **TERM-6 (P0)** Resize follows the view (keyboard shown/hidden, rotation, split-screen):
  Termux sends SIGWINCH through the PTY; tmux and ssh follow.
- **TERM-7 (P1)** Tap a URL to open it (browser); tap an absolute path or `file:line` to open
  it in the file viewer (FILE-1).
- **TERM-8 (P1)** Themes: a few built-in palettes plus import of a Termux `colors.properties`.
  Per-host accent colour.
- **TERM-9 (P1)** Bell → short vibration (setting).
- **TERM-10 (P2)** Keep the screen on while a tab is in front (setting).

### 6.4 Keyboard and input (G1)

The soft keyboard stays the main input. The extra-keys row sits above it.

- **KB-1 (P0)** Extra-keys row using Termux's `ExtraKeysView` and its layout syntax, so
  existing Termux layouts paste straight in. Default (two rows):
  ```
  [['ESC','/',{key:'-',popup:'|'},'HOME','UP','END','PGUP','PREFIX'],
   ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN','KEYBOARD']]
  ```
  `PREFIX` sends the host's tmux prefix (default `C-b`; read from the host's tmux at attach).
  `KEYBOARD` toggles the soft keyboard.
- **KB-2 (P0)** Sticky modifiers: tap Ctrl/Alt/Shift = applies to the next key from *either*
  keyboard; double-tap = locked; tap again = off. Locked state is clearly coloured.
- **KB-3 (P0)** Popups: swipe up on a key for its popup key (Termux `popup:`), e.g. `-` → `|`,
  `/` → `\`, `ESC` → `` ` ``.
- **KB-4 (P0)** IME correctness. Termux's char-based input mode is the default (Gboard behaves;
  no autocorrect mangling); a setting switches to text mode for keyboards that need it.
  Backspace, Enter, and CJK/emoji input must work with Gboard, FUTO Keyboard and Unexpected
  Keyboard (all three are in the test matrix).
- **KB-5 (P0)** Hardware keyboards (Bluetooth/USB) work with all modifiers, as in Termux.
- **KB-6 (P1)** **Agent layer.** When the tab's session runs a known agent, a third row (or a
  page you swipe to) shows that agent's keys, defined in a config file, with defaults:
  - claude: `Esc`, `Shift+Tab` (mode), `Ctrl+C`, `/`, `@`, `!`, `↑`, `Ctrl+R` (history), `1`/`2`/`3`
  - agy / pi / opencode / codex: their own defaults, taken from each tool's key help
  - shell (no agent): the normal two rows only
- **KB-7 (P1)** Macros: a key may send a string or a key sequence (`{macro: 'C-b c'}`,
  `{text: 'git status\n'}`), as Termux supports.
- **KB-8 (P0)** **Composer.** A text field above the keyboard, opened from the extra-keys row,
  that is a normal Android text field: Gboard suggestions, autocorrect, voice dictation, swipe
  typing, multi-line. **Send** pastes it into the session as one bracketed paste (tmux
  `load-buffer` + `paste-buffer -p`, which `tmux::send_text_argv` already does), then Enter
  (setting: Send vs Send without Enter). The composer keeps a draft per tab and a short history.
- **KB-9 (P1)** Quick replies: saved messages (the desktop's `prompts` file) as chips in the
  composer; one tap sends.
- **KB-10 (P2)** Layout editor in the app (today: edit the text).

### 6.5 Agent awareness (G3)

All of this already exists in `client-core`. The Android work is wiring and UI.

- **AG-1 (P0)** Status per session from the listing script plus herdr's rules (`detect.rs`) for
  agy, pi, opencode, codex, gemini, qwen, and the others in `agent-detection/`.
- **AG-2 (P0)** Claude: status from Claude's own status files (listing) and, where installed, the
  hook events stream `~/.cache/tmls/events.jsonl` followed by a `tail -F` over the shared ssh
  connection (`push::Tail`). Without hooks the host shows `· poll`.
- **AG-3 (P0)** A hooks installer for remote hosts (`tmls hooks install` lives in the old Python
  tmls, which is not in this repo): the app offers "Set up instant status on this host", which
  copies a small hook script and merges the hook entries into `~/.claude/settings.json` on that
  host after showing the diff. (Plan M4 decides between porting `claude_hook.py` to a shell
  script or a tiny static binary.)
- **AG-4 (P0)** ◆ "done while you were not looking" and `•` unread follow `seen.rs`. "Looking"
  means the tab is in front and the app is in the foreground with the screen on.
- **AG-5 (P0)** **Approve.** On a `?` session the approve sheet shows the dialog as captured
  (`approve::request`/`shown`). **Yes** sends `1`, **No** sends Esc, only if a fresh capture still
  shows the same dialog (`approve::same`). Otherwise nothing is sent, with a message.
  Claude only in P0; other agents' permission dialogs (agy, opencode) are P1, using their herdr
  `blocked` rules to recognise the dialog and per-agent answer keys.
- **AG-6 (P0)** **Send / queue a message** (desktop `s`): to a busy session the message is
  queued and sent at the end of its turn. The queue is in memory; P1 persists it.
- **AG-7 (P1)** Token/context usage line for Claude sessions (listing's `usage …`).
- **AG-8 (P2)** Detection rules can be updated from herdr without an app update (download and
  verify a pinned commit's files; user-confirmed).

### 6.6 Notifications and background work

- **NOTE-1 (P0)** `MonitorService` runs while there is any tab open **or** notifications are
  on. Its persistent notification shows counts ("3 running · 1 waiting") and a Stop action.
- **NOTE-2 (P0)** Notification channels: *Waiting on you* (high importance), *Done* (default),
  *Errors* (default), *Service* (min). The user can tune each in Android settings.
- **NOTE-3 (P0)** A notification per session that turns `?`, `◆` or `✕`, following the
  desktop's rules: nothing for the first listing or for brand-new sessions; nothing for the tab
  in front while the app is in front ("silence focused"). Grouped by host.
- **NOTE-4 (P0)** Actions: `?` → **Approve** / **Deny** / **Open**; `◆` → **Reply** (inline
  `RemoteInput`, sent like AG-6) / **Open**; `✕` → **Open**. Approve from a locked phone requires
  unlocking (`setAuthenticationRequired(true)`).
- **NOTE-5 (P0)** Polling cadence: 5 s with the list visible; 30 s in the background when
  push tails are up for a host (push carries the urgent changes); 15 s otherwise.
  Screen off with no tabs: 60 s. All configurable.
- **NOTE-6 (P1)** Start the service at boot (`BOOT_COMPLETED`, Termux:Boot idea), setting off by default.
- **NOTE-7 (P1)** Ask for exemption from battery optimisation with a clear explanation; show
  in Settings whether the app is restricted.
- **NOTE-8 (P2)** Quick Settings tile and home-screen widget: counts, tap → list filtered to "Needs me".

### 6.7 Session management

- **SES-1 (P0)** New session form: Host, Folder (with recent folders per host), Name (derived
  from the folder or git repo, as the desktop's `derived_name_argv`), Start: `shell`, `claude`,
  `agy`, `pi`, `opencode`, then presets from `agent-presets.json`. Created at the tab's
  current size; the tab opens.
- **SES-2 (P0)** Rename (closes and reopens the tab under the new name), Kill (confirm dialog
  naming what runs in it; default button Cancel).
- **SES-3 (P1)** Windows and panes: list a session's windows; new window; split; kill pane
  (argv builders exist in `tmux.rs`). Switch window from a sheet instead of prefix keys.
- **SES-4 (P1)** `top` session (desktop `top`/`top.json`): restart it on its home host if gone.
- **SES-5 (P2)** Git worktree option in the new-session form (Collie idea).

### 6.8 Files, images, sharing

- **FILE-1 (P1)** File viewer: tap a path in the terminal → find it on the session's host
  (`files::locate_argv`) → show text (monospace, wrap toggle) or image (zoomable).
- **FILE-2 (P1)** "Images this session made" list (desktop Alt+Shift+F).
- **FILE-3 (P1)** **Share target.** Android share of an image or file → pick session → `scp`
  to the session's host (`imagedrop` builders) → paste its path into the session. Also from
  the composer: attach button (camera, gallery, files).
- **FILE-4 (P2)** Paste an image from the Android clipboard the same way.

### 6.9 Configuration

- **CONF-1 (P0)** Same files and formats as the desktop under `$HOME/.config/tmls/`: `hosts`,
  `name`, `host-colors.json`, `agent-presets.json`, `prompts`, `notifications.json`, `pins`,
  `top`, `top.json`. A config from the desktop can be copied over as-is.
- **CONF-2 (P0)** Android-only settings live in a new file `android.json` in the same
  directory (extra-keys layouts, agent layers, poll periods, font, theme, IME mode), so
  all config is files that can be backed up and diffed.
- **CONF-3 (P1)** Settings screens edit those files; a raw editor exists for each.
- **CONF-4 (P1)** Export / import the whole config directory as a zip (SAF), keys excluded unless
  ticked.

### 6.10 Security

- **SEC-1 (P0)** No listening sockets. The app only makes outbound ssh connections.
- **SEC-2 (P0)** Private keys and config in app-private storage; `android:allowBackup=false` for
  the keys directory (Android backup rules exclude `.ssh`).
- **SEC-3 (P0)** Nothing is ever approved or typed automatically. Every key sent to a session
  comes from a user action, and approvals re-check the dialog (AG-5).
- **SEC-4 (P1)** Optional app lock (biometric/device credential) on open and before any
  notification action.
- **SEC-5 (P0)** Host keys are checked (`StrictHostKeyChecking=accept-new` by default; `yes`
  as an option). A changed host key blocks the host with a clear message.
- **SEC-6 (P1)** Exported intents (share target, notification actions) carry no ability to run
  arbitrary commands: they name a session and an action from a fixed set.

## 7. Non-functional requirements

- **NF-1 Performance.** Cold start to a populated list: ≤ 2 s with warm ControlMasters, ≤ 4 s
  cold over Tailscale. Attach to an existing session: ≤ 1.5 s warm. Typing latency added by the
  app: ≤ 16 ms (one frame) on the Fairphone.
- **NF-2 Battery.** In the background with notifications on and 3 hosts: ≤ 3 %/hour on
  the Fairphone (measured in M8; the poll cadence of NOTE-5 is the knob).
- **NF-3 Platforms.** minSdk 29 (Android 10, same as z2term), targetSdk current (35+).
  ABIs: arm64-v8a (required), x86_64 (emulator/CI). No 32-bit.
- **NF-4 Size.** APK ≤ 25 MB.
- **NF-5 Robustness.** No host can block another (inherited from the Poller). Killing the app
  process loses tabs but no sessions; reopening restores the list of tabs from disk and reattaches.
- **NF-6 Accessibility.** Status marks are also announced as words (TalkBack labels); colour is
  never the only signal.
- **NF-7 Compatibility with desktop tmls.** Same config files, same pins slugs, same hooks
  events file. A host set up for desktop tmls needs nothing extra for the app.

## 8. Acceptance criteria (v1.0)

v1.0 is done when, on the Fairphone, with `alton-desktop`, `alton-cachyos` and the phone as hosts:

1. Scenarios S1–S8 each work end to end (S8 may be P1-limited to images).
2. A full day of normal use (≥ 4 h of agent supervision) has no crash, no lost session, and no
   notification arriving more than 60 s late with the screen off.
3. `htop`, `vim`, `claude`, `agy`, `pi` and `opencode` are each usable from the soft keyboard plus
   extra keys alone (scripted checklist in plan §5).
4. Gboard, FUTO Keyboard and Unexpected Keyboard each pass the IME checklist (KB-4).
5. Battery and latency targets of §7 are met or the gap is written down and accepted.
6. `client-core`'s own test suite still passes unchanged on Linux (upstream compatibility).

## 9. Open questions

- **Q1** Termux libraries from JitPack (0.118.x) or a vendored fork? Vendoring makes it easy to
  patch `TerminalView`/`ExtraKeysView` (e.g. agent layer, gestures). Leaning: vendored git subtree,
  minimal patches, tracked in `android/third_party/termux/PATCHES.md`.
- **Q2** Does z2term's sshd stay reliably up for HOST-7, given the app is killed under memory
  pressure? If not, the phone host may need Termux (with Termux:Boot) instead, or a "start sshd"
  intent if z2term offers one.
- **Q3** Exact Android 15+ foreground-service type and its time limits for `MonitorService`
  (`dataSync` has a 6 h/day cap on Android 15; `specialUse` needs a justification string but no cap
  for side-loaded apps). Decide in M4.
- **Q4** herdr as a second backend next to tmux (herdr already lists sessions per machine and
  marks agent state; Collie talks to it). Worth it after v1 if Alton moves to herdr on the desktop.
- **Q5** Package name and app name. Working values: `io.github.ctrl_alton_delete.tmls`, "tmls".
- **Q6** Expose phone features to sessions (notifications, clipboard, share), as z2term's
  `z2-*` and Termux:API do? Out of scope for v1; z2term already covers the on-phone case.
