# Docker build host

The build needs nothing installed on the host: Rust 1.96 and Zig 0.15.2 live in a Docker image;
cargo's registry cache lives in a named volume.

Build the image (from the repo root):

    docker build -t tmls-rs-build:latest -f tooling/docker/Dockerfile.build tooling/docker

Run cargo (artifacts go to `target-docker/`, git-ignored):

    tooling/docker/build.sh test -p client-core -p desktop
    tooling/docker/build.sh build --release -p desktop
    tooling/docker/build.sh --shell        # interactive shell

Integration tests write under cargo's `CARGO_TARGET_TMPDIR` (`target-docker/tmp/`).

The terminal emulator crate builds `libghostty-vt-sys`, which needs **Zig 0.15.2** exactly (0.16
fails); the image has it. Without a `GHOSTTY_SOURCE_DIR` checkout that build git-clones ghostty, so it
needs network.

The image also carries the build-time libraries for the GUI crates (`s2-wgpu`, `desktop`): xkbcommon,
wayland, vulkan, X11, fontconfig, freetype, cmake, clang. Build them with `-p` (they are not default
members):

    tooling/docker/build.sh build --release -p desktop
    ldd target-docker/release/tmls-rs | grep "not found"     # must print nothing on the host

Remove everything:

    docker rmi tmls-rs-build:latest; docker volume rm tmls-rs-cargo
