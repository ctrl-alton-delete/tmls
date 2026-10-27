//! Starting the session pinned on top (see the `top` config file) again when it was
//! closed by mistake: on the machine
//! its home file names, in its folder, its command as the tmux session's own, and never a
//! second one: nothing starts while a session of that name runs on any host.
//!
//! What to start is the user's `top.json` in the tmls config directory, for example
//! `{"name": "Ops", "folder": "~/agents/Ops", "command": "claude --name Ops",
//!   "home_file": "~/.config/ops/home", "default_home": "desktop-pc"}`.
//! Without it there is nothing to start.

use std::path::Path;
use std::time::Duration;

use serde_json::Value;

use crate::tmux;

/// How long one host may take to answer, or to start the session.
pub const TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TopStart {
    pub name: String,
    pub folder: String,
    pub command: String,
    /// Read on the hosts: its first line names the machine the session lives on.
    pub home_file: Option<String>,
    /// The machine when no home file says.
    pub default_home: Option<String>,
}

impl TopStart {
    /// `top.json` in `dir`, or None when there is none or it is not usable.
    pub fn load(dir: &Path) -> Option<TopStart> {
        Self::parse(&std::fs::read_to_string(dir.join("top.json")).ok()?)
    }

    pub fn parse(text: &str) -> Option<TopStart> {
        let v: Value = serde_json::from_str(text).ok()?;
        let text = |key: &str| v.get(key).and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string);
        let optional = |key: &str| match v.get(key) {
            None | Some(Value::Null) => Some(None),
            Some(Value::String(s)) => Some(Some(s.clone())),
            Some(_) => None,
        };
        let name = text("name")?;
        if !name.chars().all(|c| c.is_alphanumeric() || "_.-".contains(c)) {
            return None;
        }
        Some(TopStart {
            name,
            folder: text("folder")?,
            command: text("command")?,
            home_file: optional("home_file")?,
            default_home: optional("default_home")?,
        })
    }

    /// Prints `running` when the session is there, then the home file's first line if any.
    pub fn probe_script(&self) -> String {
        let mut s = format!("tmux has-session -t {} 2>/dev/null && echo running; ", quote(&format!("={}", self.name)));
        if let Some(file) = &self.home_file {
            s += &format!("head -n 1 {} 2>/dev/null; ", home_path(file));
        }
        s + "true"
    }

    /// As `ops`: the tmux session runs the command itself, in its folder (it ends with it).
    pub fn start_script(&self) -> String {
        let name = &self.name;
        format!(
            "cd {} 2>/dev/null || {{ echo 'no such folder: {}'; exit 3; }}; \
             tmux has-session -t {} 2>/dev/null && {{ echo '{name} is running already'; exit 3; }}; \
             exec tmux new-session -d -s {} -c \"$PWD\" {}",
            home_path(&self.folder),
            self.folder.replace('\'', ""),
            quote(&format!("={name}")),
            quote(name),
            quote(&self.command),
        )
    }

    /// The host to start on, or why not. `answers`: each host's probe, in host order (an error
    /// for a host that did not answer).
    pub fn decide(&self, answers: &[(String, Result<String, String>)]) -> Result<String, String> {
        let mut homes = Vec::new();
        for (host, answer) in answers {
            let Ok(out) = answer else { continue };
            let lines: Vec<&str> = out.lines().map(str::trim).filter(|l| !l.is_empty()).collect();
            if lines.contains(&"running") {
                return Err(format!("{} is running on {host}", self.name));
            }
            homes.extend(lines.into_iter().filter(|l| *l != "running").map(str::to_string));
        }
        let home = homes.into_iter().next().or_else(|| self.default_home.clone());
        let Some(home) = home else {
            return Err(format!("no home machine for {}", self.name));
        };
        match answers.iter().find(|(host, _)| *host == home) {
            None => Err(format!("{} lives on {home}, which this app doesn't list", self.name)),
            Some((_, Err(_))) => Err(format!("{home} doesn't answer")),
            Some((host, Ok(_))) => Ok(host.clone()),
        }
    }

    /// Asks every host (at once), then starts it where it lives: the host, or why not. Blocks:
    /// not for the UI thread.
    pub fn start(&self, hosts: &[String], local_name: &str) -> Result<String, String> {
        let answers: Vec<(String, Result<String, String>)> = std::thread::scope(|scope| {
            let asks: Vec<_> = hosts
                .iter()
                .map(|host| {
                    let argv = tmux::script_argv(host, self.probe_script(), local_name);
                    (host.clone(), scope.spawn(move || tmux::run(&argv, TIMEOUT).map_err(|e| format!("{e:#}"))))
                })
                .collect();
            asks.into_iter()
                .map(|(host, ask)| (host, ask.join().unwrap_or_else(|_| Err("crashed".to_string()))))
                .collect()
        });
        let host = self.decide(&answers)?;
        let argv = tmux::script_argv(&host, self.start_script(), local_name);
        tmux::run(&argv, TIMEOUT).map_err(|e| {
            let said = format!("{e:#}");
            match said.trim() {
                "" => format!("couldn't start {} on {host}", self.name),
                said => said.lines().next().unwrap_or(said).to_string(),
            }
        })?;
        Ok(host)
    }
}

/// `'…'` for sh.
fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

/// `~/x` as `"$HOME"/'x'`, anything else quoted.
fn home_path(path: &str) -> String {
    match path.strip_prefix("~/") {
        Some(rest) => format!("\"$HOME\"/{}", quote(rest)),
        None => quote(path),
    }
}
