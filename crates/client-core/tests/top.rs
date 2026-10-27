use client_core::top::TopStart;

fn cfg() -> TopStart {
    TopStart::parse(r#"{"name": "Ops", "folder": "~/agents/Ops", "command": "$HOME/.local/bin/claude --name Ops",
        "home_file": "~/.config/ops/home", "default_home": "desktop-pc"}"#).unwrap()
}

fn ok(s: &str) -> Result<String, String> {
    Ok(s.to_string())
}

#[test]
fn only_a_usable_file_is_taken() {
    assert_eq!(cfg().name, "Ops");
    for bad in [r#"{"name":"a b;rm","folder":"x","command":"y"}"#, r#"{"name":"Ops","folder":"x"}"#,
                r#"{"name":"Ops","folder":"x","command":"y","default_home":3}"#, "[1]", "nope"] {
        assert_eq!(TopStart::parse(bad), None, "{bad}");
    }
}

#[test]
fn never_a_second_one_and_where_it_lives() {
    let c = cfg();
    let pc = || ("desktop-pc".to_string(), ok("running\ndevhost\n"));
    assert_eq!(c.decide(&[("devhost".into(), ok("devhost\n")), pc()]), Err("Ops is running on desktop-pc".into()));
    assert_eq!(c.decide(&[("devhost".into(), ok("devhost\n")), ("desktop-pc".into(), ok("devhost\n"))]), Ok("devhost".into()));
    assert_eq!(c.decide(&[("devhost".into(), ok("")), ("desktop-pc".into(), ok(""))]), Ok("desktop-pc".into()));
    assert_eq!(c.decide(&[("devhost".into(), ok("desktop-pc\n")), ("desktop-pc".into(), Err("timed out".into()))]),
               Err("desktop-pc doesn't answer".into()));
}

#[test]
fn the_scripts_run_as_rem_does() {
    use std::os::unix::fs::PermissionsExt;
    let dir = std::path::PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!("top-{}", std::process::id()));
    let home = dir.join("home");
    std::fs::create_dir_all(home.join(".config/ops")).unwrap();
    std::fs::write(home.join(".config/ops/home"), "devhost\n").unwrap();
    let bin = dir.join("bin");
    std::fs::create_dir_all(&bin).unwrap();
    let log = dir.join("tmux.log");
    std::fs::write(bin.join("tmux"), format!("#!/bin/sh\necho \"$PWD|$*\" >> {}\n[ \"$1\" = has-session ] && [ -e {}/up ] && exit 0\n[ \"$1\" = has-session ] && exit 1\nexit 0\n", log.display(), dir.display())).unwrap();
    std::fs::set_permissions(bin.join("tmux"), std::fs::Permissions::from_mode(0o755)).unwrap();
    let run = |s: String| std::process::Command::new("sh").arg("-c").arg(s).env("HOME", &home)
        .env("PATH", format!("{}:/usr/bin:/bin", bin.display())).output().unwrap();
    let c = cfg();
    assert_eq!(String::from_utf8_lossy(&run(c.probe_script()).stdout), "devhost\n");
    std::fs::write(dir.join("up"), "").unwrap();
    assert_eq!(String::from_utf8_lossy(&run(c.probe_script()).stdout), "running\ndevhost\n");
    assert_eq!(String::from_utf8_lossy(&run(c.start_script()).stdout).trim(), "no such folder: ~/agents/Ops");
    std::fs::create_dir_all(home.join("agents/Ops")).unwrap();
    assert_eq!(String::from_utf8_lossy(&run(c.start_script()).stdout).trim(), "Ops is running already");
    std::fs::remove_file(dir.join("up")).unwrap();
    assert!(run(c.start_script()).status.success());
    let last = std::fs::read_to_string(&log).unwrap().lines().last().unwrap().to_string();
    let folder = home.join("agents/Ops");
    assert_eq!(last, format!("{}|new-session -d -s Ops -c {} $HOME/.local/bin/claude --name Ops", folder.display(), folder.display()));
    std::fs::remove_dir_all(&dir).unwrap();
}
