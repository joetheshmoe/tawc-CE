# Advertise `wl_output` at compositor start, not at first Activity surface

Today the compositor creates its `wl_output` global only when the first
Android Activity surface registers. A client that connects before any
window exists sees a registry with **no output at all**, and clients
that require a display at init die there.

Tracked as blocker 1 of
[issues/usecase_tests/sdl-games-blocked-cold-start-output-and-egl-config.md](../issues/usecase_tests/sdl-games-blocked-cold-start-output-and-egl-config.md).

## Why this is worth doing

Zero outputs is a state very little client code handles. SDL is only
the loudest case:

- `SDL_VideoInit` fails outright if the video driver adds no displays,
  so **every** SDL app dies at startup with "The video driver did not
  add any displays" / "Video subsystem has not been initialized".
- Xwayland derives its root-window and RandR geometry from the output
  list; with none, X11 clients start against a screen that has no
  meaningful size and no CRTC.
- Toolkits that enumerate monitors or compute DPI at startup have no
  input.

It also finally explains a long-standing mystery. This *is* the
`supertuxkart-sdl-no-displays` bug that sat open for months looking
intermittent: focused single-test runs hit a cold compositor and failed,
while the full integration suite passed because earlier tests had
already left windows open. Nothing about it was random — the state
depended entirely on whether some other client had opened a window
first.

Verified on physical 50f4ca18 / Arch tawcroot (2026-08-10):

    # cold app process: hosts=0, output_advertised=false
    SDL_Init(VIDEO) -> -1 'The video driver did not add any displays'  displays: 0

    # with an unrelated xterm open: hosts=1, output_advertised=true
    SDL_Init(EVERYTHING) -> 0                                          displays: 1

## Current shape

- `compositor/src/lib.rs` builds the `Output` object at startup already
  — name `tawc-0`, hardcoded 68x150 mm physical properties — but passes
  `(0, 0)` for both logical and physical size into `TawcState::new`,
  and does **not** call `create_global`.
- `compositor/src/compositor.rs`:
  - `output_advertised: bool`, `advertised_output_host: Option<ActivityId>`
  - `sync_primary_output_to_host` sets the mode/scale from a host's
    dimensions.
  - `sync_advertised_output_to_host_if_visible` calls that and then
    `create_global` the first time, gated on the host being foreground
    or on a bootstrap branch when nothing is advertised yet.
- The rationale is stated in the `lib.rs` comment: geometry is unknown
  until an Activity registers, and the design deliberately avoids
  "service-side display-size guesses".

That rationale is worth taking seriously, and the plan below does not
discard it — it separates two things the current code ties together.

## Design

**Advertising an output and deferring toplevel configures are
independent, and only the first needs to change.**

The configure-deferral machinery ("Deferring initial configure for
`wl_surface@N` until host `a-…` registers") already prevents any
`configure(0,0)` reaching a client, and it stays exactly as is. So a
client can be told "there is a display, roughly this size" at bind time
while still being told its actual window size only when a real Activity
surface exists. Nothing gets configured off a guess.

1. **Publish provisional metrics at start.** `nativeStartCompositor`
   already carries `outputScale`; add the display width/height
   alongside it. `CompositorService` has the `Context` to read them
   (`WindowManager.currentWindowMetrics` on API 30+, `DisplayMetrics`
   below). `TawcState::new` already accepts `output_logical_size` and
   `output_physical_size`, so the Rust signature does not change —
   only the values, from `(0, 0)` to the real display.
2. **Create the global at init.** Move `create_global` out of
   `sync_advertised_output_to_host_if_visible` into startup, and set
   `output_advertised = true` there. Set the initial mode from the
   provisional metrics via the existing `change_current_state` /
   `set_preferred` path.
3. **Leave correction alone.** `sync_advertised_output_to_host_if_visible`
   keeps calling `sync_primary_output_to_host`; with the global already
   live, the first real host registration becomes an ordinary mode
   change rather than a first advertisement. Its bootstrap branch
   (`advertised_output_host == None`) already covers the pre-host state
   and needs no new condition.

A mode/scale change on a live output is a normal, well-exercised
Wayland event — it is the same path device rotation already takes — so
correcting a provisional mode is not a new class of behavior for
clients.

### The one real decision: what size to publish first

An Activity surface excludes system insets, so full display metrics
overestimate: this device reports a 1080x2400 panel and Activity
surfaces of 1080x2169 (and 1080x1412 with the IME up). Options:

- **Full display metrics.** Simplest, always available, wrong by the
  inset height until the first host corrects it.
- **Last known host size, persisted.** Right from the second launch
  onward on a given device, at the cost of a stored value and a
  first-run fallback.

Recommendation: start with full display metrics. The error is one
configure cycle, and persistence can be layered on later if anything
turns out to care.

### Adjacent cleanup while in this code

`WAYLAND_DEBUG` traces show the compositor emitting `wl_output.done()`
**twice** on bind — once with the `wl_output` event burst and again
after the `zxdg_output_v1` events. Harmless as far as anything observed,
but it is a real protocol wart in the code this plan touches, so check
it while here.

Also note the physical size is hardcoded to 68x150 mm. Deriving it from
display density would make `wl_output.geometry` truthful, but it is not
required for this plan and clients mostly use scale instead.

## Testing

- **Regression guard, cheap:** assert `output_advertised=true` from the
  `query-state` broker action immediately after compositor start with
  zero windows open.
- **The real test:** an integration test that starts the compositor and
  makes an SDL client (`supertuxkart`) the **first** client to connect,
  asserting it renders. This is precisely the case the existing suite
  masked — the current SuperTuxKart tests only pass because earlier
  tests warm the compositor — so it must either run first or restart
  the compositor itself.
- **Manual:** from a cold app process, before opening any window:

      TAWC_INSTALL_ID=arch scripts/rootfs-run.sh 'python -c "
      import ctypes; sdl=ctypes.CDLL(\"libSDL2-2.0.so.0\")
      sdl.SDL_GetError.restype=ctypes.c_char_p
      print(sdl.SDL_Init(0x20), sdl.SDL_GetError(), sdl.SDL_GetNumVideoDisplays())"'

- Re-run the `xwayland` and `rendering` integration filters: Xwayland
  now starts against a non-empty output list, which changes its
  root-window geometry at startup.

## Out of scope

- Multi-output / per-Activity outputs (see notes/multi-activity.md).
- Blocker 2 of the same issue — `SDL_CreateWindow(SDL_WINDOW_OPENGL)`
  failing `eglChooseConfig` with `EGL_BAD_ATTRIBUTE`. Independent
  layer, and it still blocks doomretro after this plan lands.
