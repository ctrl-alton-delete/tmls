# Handoff: tmls for Android (from the phone session, 2026-11-25)

Read `spec.md` (what) and `plan.md` (how) in this directory. Essentials:

- **Repo:** fork `ctrl-alton-delete/tmls` of `sagunkayastha/tmls`, cloned at `~/Applications/src/tmls-android`; work on branch `android`.
  Remotes: `origin` = fork, `upstream` = sagunkayastha. Nothing committed yet: commit
  `spec.md`, `plan.md` and this file as the first commit on `android`, then push.
- **Direction chosen by Alton:** build on Termux's libraries (`terminal-emulator`, `terminal-view`
  under Apache-2.0; `termux-shared` extra keys under GPLv3, so the app is GPLv3). Rejected or deferred:
  using Collie+herdr as-is, forking Chuchu (libghostty), porting tmls's wgpu desktop UI.
- **Core idea:** `crates/client-core` already does everything as `ssh … HOST COMMAND` argv with
  ControlMaster. Ship OpenSSH built with termux-packages as `lib*.so` in the APK, symlink it as
  `ssh` on PATH, and reuse client-core almost unchanged via a new `crates/core-ffi` (UniFFI).
  Termux `TerminalSession` runs `ssh -t HOST tmux attach -t =NAME` per tab.
- **Do first: M0 (repo + CI) then M1 (go/no-go spike):** can a bundled `ssh` be exec'd from
  both Kotlin and Rust on Android 14+, with ControlMaster, Tailscale SSH, and `$HOME/.ssh/config`?
  Fallback if not: in-process ssh behind a `Transport` trait (plan risk R1).
- **Alton's priorities:** (1) mobile keyboard, meaning Android IME plus a Termux-style extra-keys
  row with Esc/Alt/Ctrl/Tab, sticky modifiers, a composer for voice/autocorrect prose;
  (2) reuse open source, don't reinvent; (3) run claude, agy, pi, opencode fluently on the phone
  and on remote machines; (4) point at existing tools if they're good enough.
- **Environment facts:** the phone is a Fairphone (arm64, Android 14) running z2term (Alpine in
  proot); `gh` crashes there and Android builds can't run there, so builds happen here or in
  GitHub Actions. The phone's own sessions are meant to be reached via z2term/Termux sshd on
  localhost (spec HOST-7, open question Q2).
- **Leave `~/Applications/src/tmls` alone:** it is Alton's upstream clone with uncommitted desktop-UI work on `main`.
- **Reference:** termux-app v0.118.0 (commit 8629e63) was used for the license/API checks; clone it as needed.
