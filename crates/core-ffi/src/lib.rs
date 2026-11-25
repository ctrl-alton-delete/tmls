//! tmls for Android: the Kotlin-facing API over `client-core` (spec ARCH-2).
//!
//! M0 has only enough to prove the chain Gradle → cargo-ndk → UniFFI → Kotlin works on a
//! device. The real API (plan M3: `Core`, polling, argv builders) grows here; `client-core`
//! itself stays Android-free.

uniffi::setup_scaffolding!();

/// What the app shows as the core's version: `core X.Y.Z`.
#[uniffi::export]
pub fn version() -> String {
    format!("core {}", env!("CARGO_PKG_VERSION"))
}

/// How many herdr agent-detection rule files `client-core` has built in, and the herdr commit
/// they come from: a cheap call that only works if `client-core` linked into the library.
#[uniffi::export]
pub fn agent_rules() -> AgentRules {
    AgentRules {
        count: client_core::detect::RULE_FILES.len() as u32,
        herdr_commit: client_core::detect::HERDR_COMMIT.to_string(),
    }
}

#[derive(uniffi::Record, Debug, PartialEq, Eq)]
pub struct AgentRules {
    pub count: u32,
    pub herdr_commit: String,
}

/// What the app's local machine is called inside `client-core` (spec HOST-7): never a host, so
/// `client-core` never takes its "run tmux here, without ssh" branch on Android.
pub const LOCAL_NAME: &str = "tmls-app";

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    // not `message`: Kotlin's Throwable already has one
    #[error("{reason}")]
    Failed { reason: String },
}

impl CoreError {
    fn from_display(error: impl std::fmt::Display) -> CoreError {
        CoreError::Failed { reason: error.to_string() }
    }
}

/// Sets environment variables of this process (`PATH` with the app's `bin` first, `HOME`,
/// `TMPDIR`), which every command `client-core` starts inherits. Call once, at start, before
/// any other call into the core.
#[uniffi::export]
pub fn set_env(vars: std::collections::HashMap<String, String>) {
    for (key, value) in vars {
        // SAFETY: called once at app start, before the core starts any thread; the JVM's own
        // threads do not read the environment through libc concurrently in practice (the same
        // trade-off Termux makes). A later API may pass the environment per command instead.
        unsafe { std::env::set_var(key, value) };
    }
}

/// From now on every ssh `client-core` starts shares one connection per host through sockets in
/// `dir` (spec HOST-6). `dir` must be short enough for a Unix socket path (`control_dir`'s rule:
/// at most 46 bytes, no whitespace or `%`).
#[uniffi::export]
pub fn use_control_master(dir: String) -> Result<(), CoreError> {
    // The same rule as `client_core::tmux::control_dir` (which names its own directory, so it
    // cannot check one given here): ControlPath is not quoted, and `%` starts an ssh token.
    let plain = !dir.bytes().any(|b| b.is_ascii_whitespace() || b == b'%');
    if !plain || dir.len() + 44 > 90 {
        return Err(CoreError::Failed { reason: format!("control dir {dir} is too long or has whitespace or %") });
    }
    client_core::tmux::use_control_master(std::path::Path::new(&dir)).map_err(CoreError::from_display)
}

/// The argv `client-core` uses to run `command` on `host` without ever prompting (the
/// listing's ssh: keepalive, the shared connection once switched on, BatchMode, ConnectTimeout).
#[uniffi::export]
pub fn ssh_argv(host: String, command: String) -> Vec<String> {
    let mut argv = client_core::tmux::ssh_base(&host, &["-o", "BatchMode=yes", "-o", "ConnectTimeout=5"]);
    argv.push(command);
    argv
}

/// `client-core`'s start of every ssh argv: `ssh`, keepalive, the shared connection once
/// switched on, `flags`, then `host` (the caller adds a command, if any).
#[uniffi::export]
pub fn ssh_base(host: String, flags: Vec<String>) -> Vec<String> {
    let flags: Vec<&str> = flags.iter().map(String::as_str).collect();
    client_core::tmux::ssh_base(&host, &flags)
}

/// The argv for a terminal attached to session `name` on `host` (`ssh -t HOST tmux attach`).
#[uniffi::export]
pub fn attach_argv(host: String, name: String) -> Vec<String> {
    client_core::tmux::attach_argv(&client_core::SessionKey { host, name }, LOCAL_NAME)
}

/// Runs `argv` as `client-core` runs every command (`tmux::run`: killed with its process group
/// after `timeout_ms`), resolving the program through this process's `PATH`; what it printed on
/// stdout, or why it failed.
#[uniffi::export]
pub fn run(argv: Vec<String>, timeout_ms: u64) -> Result<String, CoreError> {
    client_core::tmux::run(&argv, std::time::Duration::from_millis(timeout_ms)).map_err(|e| CoreError::Failed { reason: format!("{e:#}") })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_names_the_crate_version() {
        assert_eq!(version(), format!("core {}", env!("CARGO_PKG_VERSION")));
    }

    #[test]
    fn ssh_argv_ends_with_host_and_command() {
        let argv = ssh_argv("box".to_string(), "true".to_string());
        assert_eq!(argv[0], "ssh");
        assert!(argv.windows(2).any(|w| w == ["-o", "BatchMode=yes"]));
        assert_eq!(&argv[argv.len() - 2..], ["box", "true"]);
    }

    #[test]
    fn attach_argv_goes_through_ssh_for_every_host() {
        let argv = attach_argv("phone".to_string(), "work".to_string());
        assert_eq!(argv[0], "ssh");
        assert!(argv.contains(&"-t".to_string()));
    }

    #[test]
    fn run_reports_output_and_failure() {
        assert_eq!(run(vec!["echo".into(), "hi".into()], 5000).unwrap().trim(), "hi");
        assert!(run(vec!["false".into()], 5000).is_err());
    }

    #[test]
    fn control_dir_must_fit_a_socket_path() {
        assert!(use_control_master("/".to_string() + &"x".repeat(60)).is_err());
    }

    #[test]
    fn agent_rules_come_from_client_core() {
        let rules = agent_rules();
        assert!(rules.count > 0);
        assert_eq!(rules.herdr_commit.len(), 40);
    }
}
