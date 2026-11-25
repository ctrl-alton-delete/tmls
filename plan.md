# tmls for Android — Development plan

Status: draft 1 (2026-11-25). Companion to [spec.md](spec.md); requirement IDs (`KB-1`, `AG-5`, …)
refer to it. Milestones are in order; each ends with something that runs on the phone.

---

## 1. Approach

1. **Glue, not engines.** Termux supplies the terminal, tmls's `client-core` supplies the
   brains, OpenSSH supplies the transport, tmux supplies persistence. New code is Kotlin UI,
   a thin FFI crate, build scripts, and a hooks installer.
2. **Prove the riskiest thing first.** Everything rests on running a bundled `ssh` binary from
   both Kotlin (`TerminalSession`) and Rust (`std::process::Command`) on Android 14+. M1 is a
   throwaway spike that answers that before any UI is built.
3. **Usable early.** M2 is already a better ssh+tmux client than z2term's for Alton's daily use
   (one host, good keys). Each later milestone adds one capability on top.
4. **Keep `client-core` upstreamable.** Changes there are small, generic, and tested on Linux;
   Android specifics live in `crates/core-ffi` and `android/`.
5. **Build off the phone.** The Android SDK's build tools (aapt2 etc.) have no official
   linux-aarch64 builds, so APKs are built by GitHub Actions and on `alton-desktop`, not in
   z2term. The phone installs APKs from CI artifacts (or `adb install` over Tailscale from the desktop).

## 2. Repository layout

The fork stays a single repo (`ctrl-alton-delete/tmls`, branch `android`, later `main`):

```
tmls/
├─ spec.md, plan.md
├─ crates/
│  ├─ client-core/          # upstream, minimal changes (see §7)
│  ├─ core-ffi/             # NEW: UniFFI API for Kotlin; Android env setup
│  └─ desktop/              # upstream, untouched (not built for Android)
├─ android/                 # NEW: Gradle project
│  ├─ app/                  # Kotlin app (Compose UI, service, terminal host)
│  ├─ third_party/termux/   # vendored terminal-emulator, terminal-view, extrakeys (Q1)
│  │  └─ PATCHES.md
│  ├─ gradle/libs.versions.toml
│  └─ settings.gradle.kts
├─ tooling/android/
│  ├─ binaries/             # NEW: build OpenSSH (+ later mosh) with termux-packages
│  │  ├─ build.sh
│  │  └─ VERSIONS           # pinned termux-packages commit, package versions, sha256s
│  ├─ hooks/                # NEW: Claude hook script + installer (AG-3)
│  └─ testhost/             # NEW: Docker sshd + tmux + fake agents for tests
└─ .github/workflows/
   ├─ android.yml           # build + test on every push
   └─ release.yml           # signed APK on tag
```

## 3. Toolchain and builds

| Piece | Choice | Notes |
|-------|--------|-------|
| Kotlin / AGP / Gradle | current stable at M0 (pinned in `libs.versions.toml`) | JDK 17 |
| UI | Jetpack Compose (Material 3) + `AndroidView` for `TerminalView` | |
| Rust → Android | `cargo-ndk`, NDK r27+ (r29 is what Termux's JitPack build uses) | targets `aarch64-linux-android`, `x86_64-linux-android` |
| Bindings | UniFFI (proc-macro mode), Kotlin bindings generated in the Gradle build | |
| Native binaries | `termux-packages` Docker builder, our package name set in its properties | output copied to `android/app/src/main/jniLibs/<abi>/lib*.so` |
| CI | GitHub Actions: Linux runners; emulator via `reactivecircus/android-emulator-runner` | caches: Gradle, cargo, binaries by `VERSIONS` hash |
| Signing | release keystore as a GitHub Actions secret; debug builds unsigned-debug | |
| Distribution | GitHub Releases; Obtainium on the phone tracks releases | |

Local dev loop (on `alton-desktop`): `./gradlew :app:installDebug` over `adb connect` to the
phone via Tailscale (wireless debugging), or download the CI artifact on the phone.

**Practical note:** `gh` crashes under z2term's proot (Bus error), and pushing needs
credentials. Pushes happen from `alton-desktop`, or from the phone with a token in `GH_TOKEN`
and plain `git` over https.

## 4. Milestones

Effort is in focused days (hobby pace will stretch the calendar). Each milestone lists its
tasks, its exit criteria, and the spec IDs it covers.

### M0 — Repo and CI (1–2 days)

- [ ] Push branch `android` to the fork; keep `upstream` remote for syncing (§7).
- [ ] Create `android/` with an empty Compose app (package name per Q5) that builds in CI.
- [ ] `crates/core-ffi` with one function (`version()`), built by `cargo-ndk` from Gradle
      (a Gradle task that runs `cargo ndk` and generates UniFFI Kotlin bindings).
- [ ] `android.yml`: `cargo test -p client-core` (Linux), `cargo ndk build`, `./gradlew assembleDebug lint test`,
      upload APK artifact.
- **Exit:** a CI-built APK installs on the Fairphone and shows "core 0.1.0" from Rust.

### M1 — Spike: bundled ssh on Android (2–4 days, go/no-go)

Answers risk R1 and R2 before anything is built on top.

- [ ] `tooling/android/binaries/build.sh`: build `openssh` for aarch64 + x86_64 with
      termux-packages at a pinned commit, with our package name/prefix. Try a **static**
      build first (OpenSSL, zlib linked in) so one file = one `lib*.so`. Fallback: ship the
      shared deps as `lib*.so` too, with sonames rewritten by `patchelf`.
- [ ] In the spike app: create `$APP/bin/ssh → $nativeLibraryDir/libssh.so`, set `PATH`,
      `HOME=$APP/home`, and check:
  1. `TerminalSession` running `ssh -t alton-desktop tmux attach` shows a working tmux.
  2. Rust `Command::new("ssh")` from `core-ffi` runs `ssh -o BatchMode=yes host true`.
  3. ControlMaster: socket under `$APP/run/`, second command reuses it (`ssh -O check`).
  4. Tailscale SSH (no key) and key auth both work; MagicDNS names resolve.
  5. `ssh` reads `$HOME/.ssh/config`. If it reads `getpwuid()`'s home instead (OpenSSH does
     by default), we always pass `-F`, `-o UserKnownHostsFile=` and `-o IdentityFile=`
     explicitly (needs §7 change C1).
- [ ] Same checks on the emulator (x86_64) for CI.
- **Exit (go):** all five pass on the Fairphone. **No-go:** switch to the fallback in R1 and
  re-plan M3 (in-process ssh behind a `Transport` trait in `core-ffi`).

### M2 — Terminal MVP with keyboard (5–8 days)

The first build Alton can use daily: one tab per host session, good keys. No Rust listing yet.

- [ ] Vendor Termux `terminal-emulator`, `terminal-view`, and `termux-shared`'s
      `extrakeys/*` + their small dependencies into `android/third_party/termux/` (Q1),
      at the commit of termux-app 0.118.x. Record it in `PATCHES.md`.
- [ ] `TerminalHost` composable: `AndroidView(TerminalView)`, `ExtraKeysView` below it,
      insets handling so the keys sit right above the IME (`WindowInsets.ime`).
- [ ] `SessionManager` in a bound foreground service holding `TerminalSession`s (ARCH-5),
      so rotation and backgrounding keep tabs alive.
- [ ] Temporary host/session picker: hosts from the `hosts` file, a typed session name,
      attach argv built in Kotlin (replaced by Rust in M3).
- [ ] Keyboard: KB-1 default layout, KB-2 sticky modifiers (Termux behaviour, verify
      "modifier applies to soft-keyboard key" works with Gboard), KB-3 popups, KB-4 IME mode
      setting, KB-5 hardware keys, `PREFIX` and `KEYBOARD` keys.
- [ ] **Composer (KB-8)**: bottom sheet with a normal `TextField`; Send = bracketed paste.
      In M2 it sends via the PTY (`ESC[200~ … ESC[201~` then `\r`); M3 moves it to
      `tmux::send_text_argv`.
- [ ] TERM-1, TERM-3 (manual reattach only), TERM-4, TERM-5, TERM-6.
- [ ] Host editor + key generation (HOST-2, HOST-3, HOST-4, SEC-5).
- **Exit:** IME checklist (§5.3) passes on Gboard; Alton uses it for a day instead of z2term's
  ssh for remote tmux.

### M3 — Rust core over FFI: session list and tabs (5–8 days)

- [ ] `core-ffi` API (UniFFI):
  - `Core::new(paths, settings)`: sets `PATH`/`HOME`, loads `Config`, starts ControlMaster use.
  - `Core::start_polling(listener)`: wraps `Poller`; `listener.on_snapshot(HostSnapshot)`.
  - `refresh_now()`, `refresh_host(host)`, `set_period(fg|bg|off)`.
  - argv builders: `attach_argv`, `new_session_argv`, `rename_argv`, `kill_argv`,
    `send_text_argv`, `capture_argv`, `send_keys_argv` (returned to Kotlin as `List<String>`).
  - `run(argv, timeout)` for one-shot commands (wraps `tmux::run`).
  - records mirroring `HostSnapshot`, `SessionInfo`, `SessionKey`, `Status`, `Tool`.
- [ ] `SessionListScreen` (LIST-1…LIST-5): host headers, rows with marks, long-press menu,
      pull-to-refresh, offline/auth states.
- [ ] Tabs from the list (TERM-2); tab strip with status marks; restore open tabs on restart (NF-5).
- [ ] Automatic reattach with backoff + network callbacks (TERM-3, HOST-8).
- [ ] Pins (shared slug format) and `top` ordering (LIST-3).
- [ ] Phone as a host (HOST-7): setup screen for z2term/Termux sshd on localhost.
- **Exit:** scenario S1 and S5 work; three hosts (desktop, cachyos, phone) in one list.

### M4 — Agent status, notifications, background service (5–7 days)

- [ ] Wire `detect` results and Claude status into rows (AG-1, AG-2, LIST-2 agent names).
- [ ] `push::Tail` per online host via `core-ffi`; `· poll` header when absent.
- [ ] `seen.rs` integration: "looking" = tab in front + app resumed + screen on (AG-4).
- [ ] `MonitorService` owns polling + tails; cadence per NOTE-5; decide FGS type (Q3).
- [ ] Notification channels and per-session notifications with desktop alert rules
      (NOTE-1…NOTE-3), grouped per host; tap → open that tab.
- [ ] **Hooks installer (AG-3):** port `claude_hook.py`'s behaviour to a POSIX sh script
      (append one JSON line per hook event to `~/.cache/tmls/events.jsonl`; fields `t, ev, sid,
      pane, cwd, msg, bg, err, tool, nt`, matching what `push::parse_line` reads).
      Installer: copy script over ssh, merge hook entries into `~/.claude/settings.json`
      with `jq` if present (else a careful merge done in Rust), show the diff, then apply.
      Test against `client-core/tests/push.rs` fixtures.
- **Exit:** with the screen off, a Claude session turning `?` on `alton-desktop` notifies
  within 10 s (push) / 60 s (poll only).

### M5 — Approve, reply, composer polish (4–6 days)

- [ ] Approve sheet (AG-5) with re-check; result messages.
- [ ] Notification actions: Approve/Deny/Open, inline Reply (NOTE-4), auth required.
- [ ] Send/queue messages (AG-6) with the queue in `core-ffi` (port desktop `queue.rs` logic
      if it is UI-free; else reimplement from its tests), persisted (P1).
- [ ] Composer: drafts per tab, history, quick replies from `prompts` (KB-9), Send vs
      Send-without-Enter.
- [ ] Agent key layer (KB-6) with defaults for claude/agy/pi/opencode/codex; macros (KB-7).
- **Exit:** scenarios S2 and S3 work, including from a locked phone.

### M6 — Session management and the phone as a host (4–6 days)

- [ ] New-session form (SES-1) incl. recent folders and derived names; presets.
- [ ] Rename / Kill (SES-2); windows & panes sheet (SES-3).
- [ ] `top` restart (SES-4).
- [ ] List filters and cross-host search (LIST-6), host colours and `+` (LIST-7), swipes (LIST-8).
- [ ] Phone-host reliability work for Q2 (z2term vs Termux sshd, keep-alive advice).
- **Exit:** scenarios S4, S6, S7 work.

### M7 — Files, images, share target (4–6 days)

- [ ] Tappable URLs and paths (TERM-7) using the desktop's link rules (`links.rs` logic
      ported to Kotlin, or moved into `client-core` if it is UI-free; see §7 C3).
- [ ] File viewer and "images this session made" (FILE-1, FILE-2).
- [ ] Share target + composer attachments (FILE-3) via bundled `scp` and `imagedrop` argv.
- **Exit:** scenario S8 works.

### M8 — Polish and release (5–8 days)

- [ ] Themes and Termux colour import (TERM-8), bell vibration (TERM-9), fonts.
- [ ] Settings screens over `android.json` and the tmls files; raw editors; export/import (CONF-2…4).
- [ ] App lock (SEC-4), boot start (NOTE-6), battery-optimisation flow (NOTE-7).
- [ ] Battery and latency measurement on the Fairphone (NF-1, NF-2); tune NOTE-5.
- [ ] TalkBack labels (NF-6).
- [ ] `release.yml`: signed APK on tag `android-v*`; release notes; Obtainium instructions in README.
- **Exit:** acceptance criteria in spec §8 met → tag `android-v1.0.0`.

**Total:** roughly 35–55 focused days for v1.0. A daily-usable build exists after M2 (~2 weeks of work).

### After v1.0 (P2 backlog)

mosh tabs (HOST-9), widgets/QS tile (NOTE-8), keep-screen-on (TERM-10), layout editor (KB-10),
herdr rules auto-update (AG-8), worktrees (SES-5), clipboard image paste (FILE-4), herdr
backend (Q4), approvals for more agents beyond P1.

## 5. Testing

### 5.1 Automated

- **Rust:** `cargo test -p client-core` on Linux in CI, unchanged upstream tests (spec §8.6).
  New tests in `core-ffi` for the API mapping and env setup.
- **Kotlin unit tests:** view models (list sorting, alert rules, reattach backoff, composer
  paste framing, extra-keys layout parsing, notification decisions) with fake `Core`.
- **Instrumented (emulator, CI):** app talks to `tooling/android/testhost` (Docker container
  on the runner: sshd + tmux + scripted "agents" that print the screens in
  `client-core/tests/data/*`, e.g. `claude-waiting.txt`, `approve-dialog.txt`). Checks: list
  shows the right marks; attach shows tmux; approve sends `1` only when the dialog is unchanged;
  notification posted for `?`.
- **Screenshot tests** for list rows and extra-keys states (Compose preview screenshot testing).

### 5.2 On-device checklist (each milestone, Fairphone)

1. Attach to each host; `htop`, `vim` (insert/normal, `:wq`), `less`, tmux prefix + `c`/`n`/`d`.
2. `claude`: type a prompt, Shift+Tab mode cycle, Esc interrupt, `/` commands, approve a tool.
3. `agy`, `pi`, `opencode`: start, prompt, interrupt, quit.
4. Toggle airplane mode for 30 s → tab reattaches on its own.
5. Rotate, split-screen, keyboard show/hide → no garbled screen after tmux redraws.
6. Kill the app from recents → reopen → tabs restored and reattached.

### 5.3 IME checklist (KB-4), per keyboard (Gboard, FUTO, Unexpected Keyboard)

Letters with autocorrect on; Backspace held; Enter; sticky Ctrl + soft-keyboard `c`; Alt + `b`;
swipe typing a word; voice dictation in the composer; emoji; CJK via Gboard's Japanese layout;
text-mode vs char-mode setting behaves as documented.

## 6. Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|------------|--------|------------|
| R1 | Bundled `ssh` cannot be executed or misbehaves on Android 14+ (W^X, SELinux, symlinks) | Low–Med | High | M1 spike first. Fallback: in-process ssh (sshj in Kotlin, or libssh2 via Rust) behind a `Transport` trait; ControlMaster replaced by one persistent connection per host with exec channels |
| R2 | OpenSSH looks up home/config via `getpwuid`, ignoring `$HOME` | Med | Low | Always pass `-F` and explicit file options (§7 C1); Termux's patches as reference |
| R3 | Static OpenSSH build for Android is fiddly | Med | Med | Shared-lib fallback with `patchelf`; Termux's recipe already builds it, only packaging differs |
| R4 | Foreground-service limits (Android 15 `dataSync` 6 h cap) or OEM battery killers stop alerts | Med | High | `specialUse` FGS type; battery-optimisation exemption flow; push tails reduce needed polling; document Fairphone settings |
| R5 | Termux `terminal-emulator` gaps with modern TUIs (no kitty keyboard protocol, no images, synchronized output) cause flicker/garbling in agent UIs | Med | Med | Test claude/agy/pi/opencode in M2; tmux in between already normalises much; vendored copy allows targeted patches; libghostty (as Chuchu uses) is the long-term swap if needed |
| R6 | IME quirks (Gboard autocorrect, composing text) break typing | Med | High | Use Termux's proven char-based input; composer for prose; IME checklist each milestone |
| R7 | GPLv3 from `termux-shared` extra-keys | — | Low | Accepted: app is GPLv3 (like z2term); `client-core` stays MIT/Apache; or rewrite ExtraKeysView later if the licence ever matters |
| R8 | Upstream tmls changes `client-core` in ways that conflict | Med | Low | Small, upstreamable changes; regular sync (§7) |
| R9 | z2term's sshd not up when needed for the phone host (Q2) | Med | Med | Make phone host optional; support Termux sshd (with Termux:Boot) as the alternative; clear "phone host offline" state |
| R10 | Hooks installer edits `~/.claude/settings.json` wrongly | Low | Med | Show diff, back up the file first, merge only our entries, idempotent; tests on fixtures |

## 7. Staying in sync with upstream

- Remotes: `origin` = `ctrl-alton-delete/tmls`, `upstream` = `sagunkayastha/tmls`. Merge `upstream/main`
  into `android` at the start of each milestone; CI must stay green on `client-core` tests.
- Planned `client-core` changes, each small and offered upstream as a PR:
  - **C1** Configurable ssh program and extra base options (e.g. `-F path`, identity/known_hosts
    files), set once at start like `use_control_master`. Needed if R2 bites.
  - **C2** Make `Poller`'s period adjustable at runtime per host (foreground/background cadence,
    NOTE-5), if `slow_down` is not enough.
  - **C3** Move UI-free logic that today lives in `crates/desktop` (link/path detection from
    `links.rs`, the message queue from `queue.rs`, alert rules from `alerts.rs`) into `client-core`,
    so both front ends share it. Only if the code is cleanly separable; otherwise port to Kotlin
    and keep the desktop's tests as the reference cases.
- Everything Android-specific stays in `crates/core-ffi`, `android/` and `tooling/android/`.

## 8. Definition of done (per task)

- Requirement IDs it covers are named in the commit or PR.
- Unit tests for logic; instrumented or checklist coverage for UI.
- CI green (Rust tests, Android build, lint, unit and emulator tests).
- Works on the Fairphone (not only the emulator) for anything touching input, terminal or
  background behaviour.
- `spec.md` updated if behaviour differs from what it says.
