//! How frames reach the compositor: the present mode, and when a redraw may ask for a surface
//! texture without the compositor's frame callback.
//!
//! On the PC (NVIDIA 615, KWin 6.8, Wayland) a fifo swapchain hands out an image only after
//! the compositor has painted the window with the one before: while it does not paint it (no
//! frame callback either), `get_current_texture` waits, and wgpu waits 1 s before it gives
//! up (`FRAME_TIMEOUT_MS`, fixed). tmls-rs draws without the frame callback when that is late
//! (`STALL` in `app`), so each such draw froze the window for 1 s (pace log 2026-10-26
//! 14:56:51 and 15:27:18: `acquire 1048698 timeout` four or five times in a row). Mailbox
//! does not wait for the compositor (`examples/acquire_probe.rs`).

use std::time::{Duration, Instant};

/// The present mode and swapchain depth for a surface that offers `modes`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Choice {
    pub mode: wgpu::PresentMode,
    /// `desired_maximum_frame_latency`: the swapchain has one image more than this.
    pub latency: u32,
}

impl Choice {
    pub fn name(&self) -> &'static str {
        match self.mode {
            wgpu::PresentMode::Mailbox => "mailbox",
            _ => "fifo",
        }
    }
}

/// Mailbox with three images when the surface has it: a present replaces the frame the
/// compositor has not taken yet instead of queueing behind it, so an image is free whether or
/// not the compositor paints the window. Otherwise fifo (every surface offers it) with the
/// shortest queue, as before.
pub fn choose(modes: &[wgpu::PresentMode]) -> Choice {
    if modes.contains(&wgpu::PresentMode::Mailbox) {
        Choice { mode: wgpu::PresentMode::Mailbox, latency: 2 }
    } else {
        Choice { mode: wgpu::PresentMode::Fifo, latency: 1 }
    }
}

/// After a texture timed out, draws without a frame callback wait this long at most for one:
/// with fifo each of them would wait 1 s again while the compositor does not paint the window.
pub const HOLD: Duration = Duration::from_secs(5);

/// May a draw without the frame callback (a late one, `STALL`) ask for a texture now?
/// `held_since`: when the last texture timed out, if no frame callback came since.
pub fn may_draw_unpaced(held_since: Option<Instant>, now: Instant) -> bool {
    held_since.is_none_or(|at| now >= at + HOLD)
}

/// Why a texture took long, from what was known just before asking for it: `callback`, the
/// compositor's frame callback came since the last present; `gpu_idle`, the GPU had finished
/// all work submitted. Written to the pace log next to each slow `acquire`.
pub fn blame(callback: bool, gpu_idle: bool) -> &'static str {
    match (gpu_idle, callback) {
        // The previous frame was still being drawn: the wait is the GPU's.
        (false, _) => "gpu",
        // The compositor has not painted the window since the last present: it still holds
        // the images.
        (true, false) => "compositor",
        // Painted and drawn, and still no image: the driver's presentation engine.
        (true, true) => "driver",
    }
}
