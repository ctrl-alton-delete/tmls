use std::time::{Duration, Instant};

use desktop::present::{HOLD, blame, choose, may_draw_unpaced};
use wgpu::PresentMode;

#[test]
fn mailbox_with_three_images_when_offered() {
    let choice = choose(&[PresentMode::Fifo, PresentMode::Mailbox, PresentMode::Immediate]);
    assert_eq!(choice.mode, PresentMode::Mailbox);
    assert_eq!(choice.latency, 2);
    assert_eq!(choice.name(), "mailbox");
}

#[test]
fn fifo_with_the_shortest_queue_otherwise() {
    let choice = choose(&[PresentMode::Fifo, PresentMode::Immediate]);
    assert_eq!(choice.mode, PresentMode::Fifo);
    assert_eq!(choice.latency, 1);
    assert_eq!(choice.name(), "fifo");
}

#[test]
fn unpaced_draws_wait_after_a_timeout() {
    let now = Instant::now();
    assert!(may_draw_unpaced(None, now));
    assert!(!may_draw_unpaced(Some(now), now));
    assert!(!may_draw_unpaced(Some(now), now + HOLD - Duration::from_millis(1)));
    assert!(may_draw_unpaced(Some(now), now + HOLD));
}

#[test]
fn blame_tells_gpu_compositor_and_driver_apart() {
    assert_eq!(blame(false, false), "gpu");
    assert_eq!(blame(true, false), "gpu");
    assert_eq!(blame(false, true), "compositor");
    assert_eq!(blame(true, true), "driver");
}
