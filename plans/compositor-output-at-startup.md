# Advertise `wl_output` at compositor start, not at first Activity surface

Today the compositor creates its `wl_output` global only when the first
Android Activity surface registers
(`sync_advertised_output_to_host_if_visible`, compositor.rs). A client
that connects before any window exists sees a registry with **no output
at all**, and clients that require a display at init die there.

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

## Design

**Advertising an output and deferring toplevel configures are
independent, and only the first needs to change.**

The configure-deferral machinery (`configure_toplevel_for_host`
returning `None` until `host_logical_size` is known; "Deferring initial
configure for `wl_surface@N` until host `a-…` registers") already
prevents any `configure(0,0)` reaching a client, and it stays exactly
as is. So a client can be told "there is a display, roughly this size"
at bind time while still being told its actual window size only when a
real Activity surface exists. Nothing gets configured off a guess —
which preserves the intent of the existing lib.rs comment about
avoiding "service-side display-size guesses"; that comment gets
rewritten to say the guess-avoidance now lives only in configure
deferral.

The invariant after this change: **the `wl_output` global exists with a
nonzero mode for the entire life of the compositor.** No flag, no
lazy path, no cold/warm distinction.

1. **Publish provisional metrics at start.** Add `displayWidthPx` /
   `displayHeightPx` ints to `nativeStartCompositor` next to
   `outputScale`. In `CompositorService.ensureCompositorRunning`, read
   them via `DisplayManager.getDisplay(DEFAULT_DISPLAY)` +
   `Display.getRealMetrics`.
   - Why this API: it is one code path that works at minSdk 29 and is
     safe to call from a Service. `WindowManager.currentWindowMetrics`
     is API 30+ **and** has version-dependent behavior from non-visual
     contexts (Service); a two-branch version split for a provisional
     value is not worth it. `getRealMetrics` is deprecated since API 31
     but stable, and we deliberately want the full panel size here.
   - Full panel size overestimates the eventual Activity surface (this
     device: 1080x2400 panel vs 1080x2169 Activity) — accepted; the
     first host registration corrects it one mode-change later, and
     toplevels are never configured from it (see above). Rejected
     alternative: persisting the last host size across runs — a stored
     value plus first-run fallback for an error that lasts one
     configure cycle.
2. **One helper owns the output mode.** Add a small
   `TawcState::set_output_mode(physical_px: (i32, i32))` that sets
   `output_physical_size`, derives `output_logical_size` via
   `OutputScale::logical_size`, and does the
   `change_current_state` + `set_preferred` dance (refresh 60_000,
   Transform::Normal, current scale). Callers:
   - startup (provisional metrics),
   - `sync_primary_output_to_host` (host pixels, then records
     `advertised_output_host`),
   - `apply_output_scale`'s no-host branch (event_loop.rs ~1024), which
     today duplicates the `change_current_state` block and carries its
     own `(1,1)` clamp.
   Sanitize once, in the helper: non-positive dimensions log an error
   and clamp to `(1,1)` (the degenerate the scale path already uses),
   so a nonsense JNI value can't produce a zero mode. Don't invent a
   fallback resolution — that would be a guess.
3. **Create the global at init, unconditionally.** In `TawcState::new`,
   after `set_output_mode(initial)`, call
   `output.create_global::<TawcState>(&dh)` next to the other
   `create_global` calls. That is where every other global is born;
   the output stops being special.
   - **Delete `output_advertised` entirely** — state field, the
     `if !self.output_advertised { create_global }` block in
     `sync_advertised_output_to_host_if_visible`, the query-state line
     item (event_loop.rs), and the field + parser line in
     `tests/integration/src/compositor.rs`. A flag that is
     constant-true is worse than no flag.
   - `TawcState::new` currently takes both `output_logical_size` and
     `output_physical_size`; with the helper deriving logical from
     physical + scale, drop the logical parameter. One fewer way for
     the two to disagree.
4. **Leave correction alone.** `sync_advertised_output_to_host_if_visible`
   keeps its gating and `sync_primary_output_to_host` call; the first
   real host registration becomes an ordinary mode change. Its
   bootstrap branch (`advertised_output_host == None`) already covers
   the pre-host state. `advertised_output_host` keeps its meaning:
   which host currently backs the mode (None = provisional).

A mode/scale change on a live output is a normal, well-exercised
Wayland event — the same path device rotation and the scale setting
already take — so correcting a provisional mode is not a new class of
behavior for clients.

Non-changes, checked: `nativeStartCompositor` is idempotent-no-op when
already running, so the metrics are only read at actual start; nothing
besides query-state reads `output_advertised`; Xwayland starts lazily
and will see the output whenever it binds. Display geometry changes
while the service runs with zero hosts (fold/unfold) stay provisional
until the first host registers — same one-mode-change correction,
fine.

## Existing tests that pin the old behavior

`tests/integration/tests/settings.rs`
`test_initial_configure_waits_for_real_host_size` currently asserts a
fresh compositor advertises **zero** `wl_output` globals
(`INITIAL_OUTPUT_GLOBALS == 0`) and that the global arrives only after
registration. Invert it — and it becomes the strongest regression
guard for this plan:

- `INITIAL_OUTPUT_GLOBALS == 1`, unconditionally (drop the
  `state_before.output_advertised` branch).
- The debug app already emits `OUTPUT_MODE` on the current-mode flag;
  assert a nonzero mode arrives in the pre-Activity registry phase,
  before `CONFIGURE_SIZE`.
- Keep every configure-side assertion as is (`CONFIGURE_SIZE` after the
  registry phase, sized/bounded to the host logical size) — that is the
  "deferral stays" half of the design.

The query-state struct in `tests/integration/src/compositor.rs` loses
`output_advertised` (see step 3).

## Testing

- **Cold-start placement matters.** `run-integration-tests.sh`
  force-stops the app and starts one compositor for the whole suite;
  tests assert it is running and must never (re)start it, and libtest
  ordering across binaries is not a contract. So the only deterministic
  cold point is in the script, between the `COMPOSITOR_READY` wait and
  the main `cargo test` run. Put the cold assertions in a dedicated
  `tests/integration/tests/cold_start.rs` that the script invokes
  explicitly there (`cargo test --test cold_start`), excluded from or
  harmless in the main run (its assertions are invariants, so a warm
  re-run also passes — just redundant):
  - query-state: `output_physical_w/h > 0` with `hosts=0` (replaces the
    old plan's `output_advertised=true` guard, which the design change
    makes vacuous);
  - SDL as the first-ever client: `SDL_Init(SDL_INIT_VIDEO)` succeeds
    and reports ≥1 display, via the python/ctypes probe below through
    the broker (the suite rootfs installs supertuxkart, so SDL2 is
    present).
- **The real test:** SuperTuxKart launched from that same cold state,
  asserting it renders — the exact case the warm suite masked.
  If STK-in-cold_start is too slow for every run, the SDL_Init probe
  is the load-bearing part; keep the existing warm STK tests for
  rendering.
- **Manual:** from a cold app process, before opening any window:

      TAWC_INSTALL_ID=arch scripts/rootfs-run.sh 'python -c "
      import ctypes; sdl=ctypes.CDLL(\"libSDL2-2.0.so.0\")
      sdl.SDL_GetError.restype=ctypes.c_char_p
      print(sdl.SDL_Init(0x20), sdl.SDL_GetError(), sdl.SDL_GetNumVideoDisplays())"'

- Re-run the `settings`, `xwayland`, and `rendering` integration
  filters: settings for the inverted test above; Xwayland now starts
  against a non-empty output list, which changes its root-window
  geometry at startup.

## Not bugs / out of scope

- **Double `wl_output.done` on bind is (almost certainly) not a wart.**
  `WAYLAND_DEBUG` shows `done` once with the `wl_output` event burst
  and again after the `zxdg_output_v1` events. xdg-output ≥ v3
  deprecates `xdg_output.done` and **requires** a `wl_output.done`
  after the xdg_output property batch — two binds, two atomic batches,
  two dones. Verify the trace matches that shape (second done tied to
  the zxdg bind, not duplicated within one burst) and then leave it
  alone; "fixing" it would be the bug.
- Physical size stays hardcoded 68x150 mm. Deriving mm from
  xdpi/ydpi would make `wl_output.geometry` truthful, but clients use
  scale, and it's separable — don't let it ride along.
- Multi-output / per-Activity outputs (see notes/multi-activity.md).
- Blocker 2 of the same issue — `SDL_CreateWindow(SDL_WINDOW_OPENGL)`
  failing `eglChooseConfig` with `EGL_BAD_ATTRIBUTE`. Independent
  layer (libhybris EGL), still blocks doomretro after this plan lands.
