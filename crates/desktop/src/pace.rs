//! `TMLS_RS_PACELOG=FILE`: one line per redraw step (monotonic ns, what, details), to see how
//! frames are paced around focus and monitor changes. Off (and free) without the variable.
//!
//! The monotonic clock starts again at each boot and stops in suspend, and the file is appended
//! to by every run: so a run starts with `# boot BOOT_ID pid PID`, and a `# clock WALL_NS MONO_NS`
//! line follows it and comes again at least every `CLOCK_EVERY` (tooling/pace maps lines to the
//! wall clock with the newest one).

use std::io::Write;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

pub const PACELOG: &str = "TMLS_RS_PACELOG";

const CLOCK_EVERY: u128 = 10_000_000_000;

struct Pace {
    file: std::fs::File,
    /// Monotonic ns of the last `# clock` line.
    clock_at: u128,
}

fn file() -> &'static Option<Mutex<Pace>> {
    static FILE: OnceLock<Option<Mutex<Pace>>> = OnceLock::new();
    FILE.get_or_init(|| {
        let path = std::env::var_os(PACELOG)?;
        let mut file = std::fs::OpenOptions::new().create(true).append(true).open(path).ok()?;
        let boot = std::fs::read_to_string("/proc/sys/kernel/random/boot_id")
            .map(|id| id.trim().to_string())
            .unwrap_or_else(|_| "-".into());
        let _ = writeln!(file, "# boot {boot} pid {}", std::process::id());
        let mut pace = Pace { file, clock_at: 0 };
        clock(&mut pace, s2_core::now_ns());
        Some(Mutex::new(pace))
    })
}

fn clock(pace: &mut Pace, mono: u128) {
    let wall = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_nanos());
    let _ = writeln!(pace.file, "# clock {wall} {mono}");
    pace.clock_at = mono;
}

pub fn on() -> bool {
    file().is_some()
}

pub fn line(what: std::fmt::Arguments) {
    if let Some(file) = file() {
        if let Ok(mut pace) = file.lock() {
            let now = s2_core::now_ns();
            if now >= pace.clock_at + CLOCK_EVERY {
                clock(&mut pace, now);
            }
            let _ = writeln!(pace.file, "{now} {what}");
        }
    }
}
