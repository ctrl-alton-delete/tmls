//! Dictation: the mic button over the terminal talks to the voice engine, voice-typing's
//! `stream.py` in the `voice-engine` container (streaming speech to text on the CPU), over its
//! Unix socket `$XDG_RUNTIME_DIR/voice-typing/engine.sock`.
//!
//! The protocol is one JSON object per line. tmls sends `{"cmd":"start"}` and `{"cmd":"stop"}`;
//! the engine sends `ready` on connect, `recording`, `text` (what to insert, in order: it only
//! ever appends, never corrects), `stopped` once the last text is out, and `error`. The engine
//! exits after some idle minutes; a click then starts its container again (`docker start`).
//!
//! `connect` runs on a thread: it may start the container and wait for the model to load. Each
//! dictation has its own connection, closed when it is over.

use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use s2_core::Frame;

/// The container `connect` starts when no engine listens.
pub const CONTAINER: &str = "voice-engine";
/// How long `connect` waits for a started engine to listen (container start + model load ~2 s).
const START_WAIT: Duration = Duration::from_secs(20);

/// What the engine said, as the window's thread gets it.
#[derive(Debug, PartialEq, Eq)]
pub enum Said {
    Recording,
    Text(String),
    Stopped,
    Error(String),
    /// The connection ended without a `stopped` (the engine exited or crashed).
    Gone,
}

/// The engine's socket.
pub fn socket_path() -> PathBuf {
    let dir = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(format!("/run/user/{}", uid())));
    dir.join("voice-typing").join("engine.sock")
}

fn uid() -> u32 {
    // No libc dependency for one number: the owner of /proc/self is this process's user.
    std::fs::metadata("/proc/self")
        .map(|m| std::os::unix::fs::MetadataExt::uid(&m))
        .unwrap_or(1000)
}

/// One line from the engine; `None` for `ready` and anything not understood.
pub fn parse(line: &str) -> Option<Said> {
    let v: serde_json::Value = serde_json::from_str(line).ok()?;
    let text = |key| {
        v.get(key)
            .and_then(|t| t.as_str())
            .unwrap_or("")
            .to_string()
    };
    match v.get("ev")?.as_str()? {
        "recording" => Some(Said::Recording),
        "text" => Some(Said::Text(text("text"))),
        "stopped" => Some(Said::Stopped),
        "error" => Some(Said::Error(text("msg"))),
        _ => None,
    }
}

/// A connection to the engine, recording once `start` was sent.
pub struct Engine {
    stream: UnixStream,
}

impl Engine {
    /// Connects, starting the container first if nothing listens, and waits for `ready`.
    /// Blocks for up to `START_WAIT`: call it off the window's thread.
    pub fn connect() -> Result<(Engine, BufReader<UnixStream>), String> {
        let path = socket_path();
        let stream = match UnixStream::connect(&path) {
            Ok(stream) => stream,
            Err(_) => {
                // The container mounts this folder; it lives in the runtime dir, which a reboot
                // empties, and a folder docker made would be root's, where the engine cannot listen.
                if let Some(dir) = path.parent() {
                    use std::os::unix::fs::DirBuilderExt;
                    let _ = std::fs::DirBuilder::new().recursive(true).mode(0o700).create(dir);
                }
                let started = Command::new("docker")
                    .args(["start", CONTAINER])
                    .stdout(Stdio::null())
                    .stderr(Stdio::piped())
                    .output()
                    .map_err(|e| format!("docker start {CONTAINER}: {e}"))?;
                if !started.status.success() {
                    let why = String::from_utf8_lossy(&started.stderr);
                    return Err(format!("docker start {CONTAINER}: {}", why.trim()));
                }
                let deadline = Instant::now() + START_WAIT;
                loop {
                    match UnixStream::connect(&path) {
                        Ok(stream) => break stream,
                        Err(_) if Instant::now() < deadline => {
                            std::thread::sleep(Duration::from_millis(200))
                        }
                        Err(e) => return Err(format!("the voice engine did not start: {e}")),
                    }
                }
            }
        };
        let mut reader = BufReader::new(stream.try_clone().map_err(|e| e.to_string())?);
        let mut line = String::new();
        stream
            .set_read_timeout(Some(START_WAIT))
            .map_err(|e| e.to_string())?;
        reader
            .read_line(&mut line)
            .map_err(|e| format!("the voice engine did not answer: {e}"))?;
        if !line.contains("\"ready\"") {
            return Err(format!("the voice engine said {:?}", line.trim()));
        }
        stream.set_read_timeout(None).map_err(|e| e.to_string())?;
        Ok((Engine { stream }, reader))
    }

    pub fn start(&mut self) -> Result<(), String> {
        self.send("start")
    }

    pub fn stop(&mut self) -> Result<(), String> {
        self.send("stop")
    }

    fn send(&mut self, cmd: &str) -> Result<(), String> {
        writeln!(self.stream, "{{\"cmd\":\"{cmd}\"}}").map_err(|e| format!("voice engine: {e}"))
    }
}

impl std::fmt::Debug for Engine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Engine")
    }
}

/// Reads the engine's lines until `stopped` or the end of the connection, handing each to
/// `said`. Returns when the dictation is over.
pub fn read(reader: BufReader<UnixStream>, mut said: impl FnMut(Said)) {
    for line in reader.lines() {
        let Ok(line) = line else { break };
        if let Some(event) = parse(&line) {
            let over = event == Said::Stopped;
            said(event);
            if over {
                return;
            }
        }
    }
    said(Said::Gone);
}

/// The terminal row the mic button sits on: the last line of Claude Code's input box (the
/// row above the lowest `─` rule that has another rule a few rows over it), so it moves with
/// the box. Without such a box (a shell, Codex), the cursor's row. A hidden cursor keeps `last`:
/// Codex hides it and jumps to its status line on every timer tick, which made the mic blink.
/// `None` when there is no box, no cursor and no `last`.
pub fn mic_row(frame: &Frame, last: Option<u16>) -> Option<u16> {
    let rule = |y: u16| {
        let text = frame.row_text(y);
        let text = text.trim_start();
        text.starts_with('─') && text.chars().filter(|&c| c == '─').count() * 2 >= frame.cols as usize
    };
    let rules: Vec<u16> = (0..frame.rows).filter(|&y| rule(y)).collect();
    let boxed = rules
        .windows(2)
        .rev()
        .find(|pair| pair[1] - pair[0] >= 2 && pair[1] - pair[0] <= 16)
        .map(|pair| pair[1] - 1);
    boxed.or_else(|| match frame.cursor {
        Some(c) if c.visible => Some(c.row),
        _ => last,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use s2_core::{Cell, Cursor, CursorShape};

    fn frame(rows: &[&str], cursor: Option<u16>) -> Frame {
        let cols = 20u16;
        let mut cells = Vec::new();
        for row in rows {
            let mut chars: Vec<Cell> = row
                .chars()
                .map(|c| Cell {
                    text: c.to_string(),
                    ..Cell::default()
                })
                .collect();
            chars.resize(cols as usize, Cell::default());
            cells.extend(chars);
        }
        Frame {
            cols,
            rows: rows.len() as u16,
            cells,
            cursor: cursor.map(|row| Cursor {
                col: 0,
                row,
                shape: CursorShape::Block,
                visible: true,
            }),
            dirty_rows: vec![false; rows.len()],
            seq: 0,
            t_output_ns: 0,
            keys: Vec::new(),
        }
    }

    #[test]
    fn the_mic_sits_on_the_input_box() {
        let rule = "────────────────────";
        let named = "──────────── name ──";
        let f = frame(&["hello", named, "> one", "  two", rule, "  footer", "[tmux]"], Some(0));
        assert_eq!(mic_row(&f, None), Some(3));
        // No box: the cursor's row; no cursor either: where it was, else nowhere.
        let f = frame(&["$ ls", "a b", "$", ""], Some(2));
        assert_eq!(mic_row(&f, None), Some(2));
        let f = frame(&["$ ls", "a b", "$", ""], None);
        assert_eq!(mic_row(&f, None), None);
        assert_eq!(mic_row(&f, Some(2)), Some(2));
    }

    #[test]
    fn the_mic_stays_put_while_codex_updates_its_timer() {
        // Codex's frame mid-update: cursor hidden on the status line, not at the `›` prompt.
        let mut f = frame(&["• Working (1m 44s)", "", "› ", "", "  footer"], Some(0));
        f.cursor.as_mut().unwrap().visible = false;
        assert_eq!(mic_row(&f, Some(2)), Some(2));
    }

    #[test]
    fn parses_the_engine_lines() {
        assert_eq!(parse(r#"{"ev": "recording"}"#), Some(Said::Recording));
        assert_eq!(
            parse(r#"{"ev": "text", "text": " fellow"}"#),
            Some(Said::Text(" fellow".into()))
        );
        assert_eq!(parse(r#"{"ev": "stopped"}"#), Some(Said::Stopped));
        assert_eq!(
            parse(r#"{"ev": "error", "msg": "no mic"}"#),
            Some(Said::Error("no mic".into()))
        );
        assert_eq!(parse(r#"{"ev": "ready"}"#), None);
        assert_eq!(parse("not json"), None);
    }

    #[test]
    fn reads_until_stopped() {
        let (mut ours, theirs) = UnixStream::pair().unwrap();
        ours.write_all(b"{\"ev\":\"recording\"}\n{\"ev\":\"text\",\"text\":\"Hi\"}\n{\"ev\":\"stopped\"}\n{\"ev\":\"text\",\"text\":\"late\"}\n").unwrap();
        let mut got = Vec::new();
        read(BufReader::new(theirs), |s| got.push(s));
        assert_eq!(
            got,
            [Said::Recording, Said::Text("Hi".into()), Said::Stopped]
        );
    }

    #[test]
    fn an_ended_connection_is_gone() {
        let (mut ours, theirs) = UnixStream::pair().unwrap();
        ours.write_all(b"{\"ev\":\"text\",\"text\":\"Hi\"}\n")
            .unwrap();
        drop(ours);
        let mut got = Vec::new();
        read(BufReader::new(theirs), |s| got.push(s));
        assert_eq!(got, [Said::Text("Hi".into()), Said::Gone]);
    }
}
