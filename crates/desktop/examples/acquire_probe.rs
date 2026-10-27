//! Reproduces a swapchain acquire that blocks: a small window presents every 16 ms whether or
//! not the compositor sent a frame callback (as tmls-rs does after a late one, `STALL`), and
//! minimizes itself after `PROBE_HIDE_MS`. A minimized window is not painted, so the compositor
//! neither sends frame callbacks nor releases the buffers it holds.
//!
//! One line per present on stdout: `ms acquire_us kind callbacks`, where `callbacks` is how
//! many frame callbacks (RedrawRequested) came since the previous present. Then a summary.
//!
//! Env: PROBE_MODE=fifo|mailbox (default fifo), PROBE_LATENCY (default 1, as tmls-rs before
//! the fix), PROBE_SECS (default 8), PROBE_HIDE_MS (default 2000; 0 = never minimize).
//! Run in the nested KWin (tooling/pace/README.md) so nothing shows on the real screens.

use std::sync::Arc;
use std::time::{Duration, Instant};

use winit::application::ApplicationHandler;
use winit::event::WindowEvent;
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop};
use winit::window::{Window, WindowId};

struct Probe {
    window: Arc<Window>,
    surface: wgpu::Surface<'static>,
    device: wgpu::Device,
    queue: wgpu::Queue,
    start: Instant,
    next: Instant,
    callbacks: u32,
    hidden: bool,
    acquires: Vec<u128>,
}

#[derive(Default)]
struct App {
    probe: Option<Probe>,
    mode: String,
    latency: u32,
    secs: u64,
    hide_ms: u64,
}

impl ApplicationHandler for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.probe.is_some() {
            return;
        }
        let window = Arc::new(
            event_loop
                .create_window(Window::default_attributes().with_title("acquire-probe"))
                .expect("window"),
        );
        let mut desc = wgpu::InstanceDescriptor::new_with_display_handle_from_env(Box::new(
            event_loop.owned_display_handle(),
        ));
        desc.backends = wgpu::Backends::VULKAN;
        let instance = wgpu::Instance::new(desc);
        let surface = instance.create_surface(window.clone()).expect("surface");
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            compatible_surface: Some(&surface),
            ..Default::default()
        }))
        .expect("adapter");
        let (device, queue) =
            pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default()))
                .expect("device");
        let caps = surface.get_capabilities(&adapter);
        let size = window.inner_size();
        let mut config = surface
            .get_default_config(&adapter, size.width.max(1), size.height.max(1))
            .expect("config");
        config.present_mode = match self.mode.as_str() {
            "mailbox" => wgpu::PresentMode::Mailbox,
            _ => wgpu::PresentMode::Fifo,
        };
        config.desired_maximum_frame_latency = self.latency;
        surface.configure(&device, &config);
        println!(
            "# adapter {:?} modes {:?} using {:?} latency {}",
            adapter.get_info().name,
            caps.present_modes,
            config.present_mode,
            self.latency
        );
        let now = Instant::now();
        self.probe = Some(Probe {
            window,
            surface,
            device,
            queue,
            start: now,
            next: now,
            callbacks: 0,
            hidden: false,
            acquires: Vec::new(),
        });
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _: WindowId, event: WindowEvent) {
        match event {
            WindowEvent::CloseRequested => event_loop.exit(),
            WindowEvent::RedrawRequested => {
                if let Some(probe) = &mut self.probe {
                    probe.callbacks += 1;
                }
            }
            _ => {}
        }
    }

    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        let Some(probe) = &mut self.probe else {
            return;
        };
        let now = Instant::now();
        let ms = now.duration_since(probe.start).as_millis();
        if ms >= u128::from(self.secs) * 1000 {
            let slow = probe.acquires.iter().filter(|&&us| us > 20_000).count();
            let max = probe.acquires.iter().max().copied().unwrap_or(0);
            println!(
                "# summary presents {} acquire>20ms {} max_acquire_us {}",
                probe.acquires.len(),
                slow,
                max
            );
            event_loop.exit();
            return;
        }
        if !probe.hidden && self.hide_ms > 0 && ms >= u128::from(self.hide_ms) {
            probe.hidden = true;
            probe.window.set_minimized(true);
            println!("# {ms} minimized");
        }
        if now >= probe.next {
            probe.next = now + Duration::from_millis(16);
            let t = Instant::now();
            let current = probe.surface.get_current_texture();
            let us = t.elapsed().as_micros();
            probe.acquires.push(us);
            let (kind, texture) = match current {
                wgpu::CurrentSurfaceTexture::Success(texture) => ("ok", Some(texture)),
                wgpu::CurrentSurfaceTexture::Suboptimal(texture) => ("suboptimal", Some(texture)),
                wgpu::CurrentSurfaceTexture::Timeout => ("timeout", None),
                wgpu::CurrentSurfaceTexture::Occluded => ("occluded", None),
                wgpu::CurrentSurfaceTexture::Outdated => ("outdated", None),
                wgpu::CurrentSurfaceTexture::Lost => ("lost", None),
                wgpu::CurrentSurfaceTexture::Validation => ("validation", None),
            };
            println!("{ms} {us} {kind} {}", probe.callbacks);
            probe.callbacks = 0;
            if let Some(texture) = texture {
                let view = texture.texture.create_view(&Default::default());
                let mut encoder = probe.device.create_command_encoder(&Default::default());
                let shade = (ms % 1000) as f64 / 1000.0;
                drop(encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                    color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                        view: &view,
                        depth_slice: None,
                        resolve_target: None,
                        ops: wgpu::Operations {
                            load: wgpu::LoadOp::Clear(wgpu::Color { r: shade, g: 0.2, b: 0.3, a: 1.0 }),
                            store: wgpu::StoreOp::Store,
                        },
                    })],
                    ..Default::default()
                }));
                probe.queue.submit([encoder.finish()]);
                probe.window.pre_present_notify();
                probe.queue.present(texture);
                probe.window.request_redraw();
            }
        }
        event_loop.set_control_flow(ControlFlow::WaitUntil(probe.next));
    }
}

fn main() {
    let env = |name: &str, default: &str| std::env::var(name).unwrap_or_else(|_| default.into());
    let mut app = App {
        mode: env("PROBE_MODE", "fifo"),
        latency: env("PROBE_LATENCY", "1").parse().unwrap_or(1),
        secs: env("PROBE_SECS", "8").parse().unwrap_or(8),
        hide_ms: env("PROBE_HIDE_MS", "2000").parse().unwrap_or(2000),
        ..App::default()
    };
    let event_loop = EventLoop::new().expect("event loop");
    event_loop.run_app(&mut app).expect("run");
}
