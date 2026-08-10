//! Cold-compositor invariants: what a client sees when it connects before
//! any Activity surface has ever registered.
//!
//! This is a **separate test binary** with `test = false` in `Cargo.toml`,
//! so `cargo test` never picks it up alongside the main suite.
//! `scripts/run-integration-tests.sh` invokes it explicitly
//! (`cargo test --test cold_start`) between the compositor-ready wait and
//! the main run — the only point where the cold state provably exists.
//! Running it later would find whatever windows earlier tests left open,
//! which is exactly the accident that hid this bug for months: focused
//! single-test runs of `supertuxkart` failed on a cold compositor while
//! the full suite passed.
//!
//! Everything here is one `#[test]` on purpose: libtest ordering inside a
//! binary is not a contract, and each step consumes a bit more of the cold
//! state (the SDL probe connects a client; supertuxkart registers a host).

use std::time::Duration;

use tawc_integration::{adb, compositor, GraphicsBackend};
use tawc_integration::helpers::{launch_and_wait_for_toplevel, TIMEOUT};

const BACKEND: GraphicsBackend = GraphicsBackend::Cpu;

/// Cold start is slower than the warm launches in `apps::` — nothing is
/// in page cache and the first client also pays compositor lazy init.
const STK_LAUNCH_TIMEOUT: Duration = Duration::from_secs(90);

/// `SDL_Init(SDL_INIT_VIDEO)` in the rootfs, printing the result and the
/// display count. ctypes instead of a compiled probe so this needs no
/// build step; the suite installs supertuxkart, so SDL2 is present.
const SDL_PROBE: &str = r#"python3 -c '
import ctypes
sdl = ctypes.CDLL("libSDL2-2.0.so.0")
sdl.SDL_GetError.restype = ctypes.c_char_p
rc = sdl.SDL_Init(0x20)
print("SDL_INIT_RC", rc, sdl.SDL_GetError().decode())
print("SDL_DISPLAYS", sdl.SDL_GetNumVideoDisplays())
'"#;

#[test]
fn test_cold_start_serves_display_dependent_clients() {
    tawc_integration::helpers::test_init();

    // 1. The output has a real mode with no host behind it. The mode is
    //    the Android panel metrics passed to nativeStartCompositor; the
    //    first Activity surface corrects it one mode-change later.
    let state = compositor::query_state(TIMEOUT).expect("query cold compositor state");
    assert_eq!(
        state.hosts, 0,
        "cold_start must run before any Activity surface registers, got {state:?}"
    );
    assert!(
        state.output_physical_w > 0 && state.output_physical_h > 0,
        "cold compositor must advertise a nonzero output mode, got {state:?}"
    );
    assert!(
        state.output_logical_w > 0 && state.output_logical_h > 0,
        "cold compositor output logical size must be nonzero, got {state:?}"
    );

    // 2. SDL as the first-ever client. `SDL_VideoInit` fails outright when
    //    the video driver adds no displays, which took down every SDL app
    //    started against a cold compositor.
    let out = adb::rootfs_run_with(BACKEND, SDL_PROBE).expect("run SDL probe in rootfs");
    let stdout = String::from_utf8_lossy(&out.stdout).to_string();
    let stderr = String::from_utf8_lossy(&out.stderr).to_string();
    let rc_line = stdout
        .lines()
        .find(|l| l.starts_with("SDL_INIT_RC"))
        .unwrap_or_else(|| panic!("SDL probe printed no result:\n{stdout}\n{stderr}"));
    assert!(
        rc_line.starts_with("SDL_INIT_RC 0 "),
        "SDL_Init(SDL_INIT_VIDEO) failed against a cold compositor: {rc_line}"
    );
    let displays: i32 = stdout
        .lines()
        .find_map(|l| l.strip_prefix("SDL_DISPLAYS "))
        .unwrap_or_else(|| panic!("SDL probe printed no display count:\n{stdout}\n{stderr}"))
        .trim()
        .parse()
        .expect("parse SDL display count");
    assert!(
        displays >= 1,
        "SDL should see at least one display on a cold compositor, got {displays}"
    );

    // 3. The case the warm suite masked end to end: an SDL game launched
    //    as the first client renders.
    let mut stk = launch_and_wait_for_toplevel(
        BACKEND,
        "supertuxkart",
        "supertuxkart (cold start)",
        STK_LAUNCH_TIMEOUT,
    );
    assert!(
        stk.is_running(),
        "supertuxkart exited shortly after mapping its window"
    );
    stk.stop()
        .expect("supertuxkart session failed to stop cleanly");
}
