# SDL games still can't start: no `wl_output` on a cold compositor, and SDL's EGL config selection fails

Successor to `sdl-haptic-udev-netlink-kills-video-init.md`, whose bug is
**fixed** (tawcroot now stubs the uevent netlink socket, so
`SDL_Init(SDL_INIT_EVERYTHING)` succeeds). Fixing it uncovered two more
blockers underneath, both verified on physical 50f4ca18 / Arch tawcroot
on the 2026-08-10 build.

Note for whoever picks this up: the previous issue claimed "SDL video
init works fine on tawc". That measurement was taken with a window
already open. It does not hold from a cold start — see blocker 1.

## Blocker 1 — the `wl_output` global only exists after an Activity surface registers

`compositor.rs:241` documents it: the output global is "advertised only
after the first real Activity surface size arrives"
(`sync_advertised_output_to_host_if_visible` creates it). So a client
connecting to a compositor with no windows sees a registry with no
`wl_output` at all.

That is fatal for SDL specifically. `SDL_VideoInit` fails outright if the
driver adds no displays — GTK/Qt clients don't care, they create a
surface and take the output whenever it shows up.

Cold app process (`hosts=0`, `output_advertised=false`):

    SDL_Init(VIDEO) -> -1  'The video driver did not add any displays'  displays: 0

With any window already open (here `xterm` through Xwayland, giving
`hosts=1 output_advertised=true output_logical_w=540`):

    SDL_Init(EVERYTHING) -> 0   displays: 1

**This is the original `supertuxkart-sdl-no-displays` bug, and it
explains the "intermittent" part that never made sense.** Focused
single-test runs hit a cold compositor and failed; the full integration
suite passed both SuperTuxKart tests because earlier tests had left
windows open. Nothing about it is random.

Fix direction: advertise the output at startup from the Android display
metrics (which the app knows before any Activity surface exists) and let
the existing `sync_primary_output_to_host` correct the mode when the
first host registers. Waiting for real surface dimensions is the current
design's reason for the delay, so this needs a deliberate decision about
what to publish in the meantime, not just a reorder.

## Blocker 2 — `SDL_CreateWindow(SDL_WINDOW_OPENGL)` fails EGL config selection

With blocker 1 worked around (a window already open), DOOM Retro gets
all the way to window creation and dies there:

    The call to SDL_CreateWindow() failed on line 1,702 of i_video.c:
    "Couldn't find matching EGL config (call to eglChooseConfig failed,
     reporting an error of EGL_BAD_ATTRIBUTE)"

It is not doomretro-specific and not about requested GL attributes — a
bare window with no attributes set fails identically:

    SDL_CreateWindow(..., SDL_WINDOW_OPENGL)         -> FAIL, EGL_BAD_ATTRIBUTE
    SDL_CreateWindow(..., SDL_WINDOW_OPENGL) + depth -> FAIL, EGL_BAD_ATTRIBUTE
    SDL_CreateWindow(..., 0)                         -> ok
    SDL_CreateRenderer(SDL_RENDERER_SOFTWARE)        -> ok

So SDL's non-GL (SHM) path is entirely healthy; the failure is isolated
to SDL's own `eglChooseConfig` attribute list against libhybris EGL.
`EGL_BAD_ATTRIBUTE` points at an attribute *name* libhybris's EGL does
not recognize rather than an unsatisfiable value (which would return
zero configs instead).

SuperTuxKart is unaffected and renders fine, because Irrlicht manages
its own EGL context rather than asking SDL for a GL window — which is
why STK is not a canary for this.

Next step: dump the attribute list SDL passes (SDL3's
`SDL_EGL_ChooseConfig`) and diff it against what
`deps/libhybris`'s EGL accepts.

## Repro

    # blocker 1: from a cold app process, before opening any window
    TAWC_INSTALL_ID=arch scripts/rootfs-run.sh 'python -c "
    import ctypes; sdl=ctypes.CDLL(\"libSDL2-2.0.so.0\")
    sdl.SDL_GetError.restype=ctypes.c_char_p
    print(sdl.SDL_Init(0x20), sdl.SDL_GetError(), sdl.SDL_GetNumVideoDisplays())"'

    # blocker 2: with a window open (e.g. xterm running)
    TAWC_INSTALL_ID=arch scripts/rootfs-run.sh 'python -c "
    import ctypes; sdl=ctypes.CDLL(\"libSDL2-2.0.so.0\")
    sdl.SDL_GetError.restype=ctypes.c_char_p
    sdl.SDL_CreateWindow.restype=ctypes.c_void_p
    sdl.SDL_CreateWindow.argtypes=[ctypes.c_char_p]+[ctypes.c_int]*4+[ctypes.c_uint]
    sdl.SDL_Init(0x20)
    w=sdl.SDL_CreateWindow(b\"t\",0,0,320,240,0x2)
    print(bool(w), sdl.SDL_GetError())"'

Game repro: `pacman -S doomretro`, a freedoom1.wad, and
`doomretro -iwad freedoom1.wad` with a window already open.
