# SDL games: `SDL_CreateWindow(SDL_WINDOW_OPENGL)` fails EGL config selection

Successor to `sdl-games-blocked-cold-start-output-and-egl-config.md`,
whose blocker 1 (no `wl_output` on a cold compositor) is **fixed**: the
compositor now creates the output global at startup with the Android
panel metrics as a provisional mode, so `SDL_Init(SDL_INIT_VIDEO)`
succeeds with 1 display from a cold app process. See
notes/multi-activity.md "wl_output and toplevel sizing" and
`tests/integration/tests/cold_start.rs`.

This is what's left. Verified on physical 50f4ca18 / Arch tawcroot.

DOOM Retro gets all the way to window creation and dies there:

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

## Ruled out: the ordinary attribute set, and the EGL version

Probed the EGL the guest actually gets (`HYBRIS_EGLPLATFORM=wayland`,
`libEGL.so.1` driven from python ctypes):

    eglInitialize   -> 1.5
    EGL_VERSION      "1.5 Android META-EGL"     EGL_VENDOR "Android"
    47 extensions, including EGL_EXT_pixel_format_float

and every attribute SDL would plausibly pass is **accepted** by
`eglChooseConfig` on that display — `EGL_RED/GREEN/BLUE/ALPHA_SIZE`,
`EGL_BUFFER_SIZE`, `EGL_DEPTH_SIZE`, `EGL_STENCIL_SIZE`,
`EGL_SURFACE_TYPE`, `EGL_RENDERABLE_TYPE`, `EGL_CONFORMANT`,
`EGL_SAMPLE_BUFFERS`/`EGL_SAMPLES`, `EGL_MIN_SWAP_INTERVAL`, and
`EGL_COLOR_COMPONENT_TYPE_EXT` (the extension is present). All returned
`EGL_SUCCESS` with a non-empty config list.

So the cause is **not** an obviously-unsupported token on this display,
which leaves two candidates:

1. SDL passes something outside that set, or
2. SDL is not on this display at all. Its Wayland backend uses
   `eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, wl_display, …)`,
   which is a different entry point into the fork than the
   `HYBRIS_EGLPLATFORM=wayland` default display probed above — and a
   platform path that advertises 1.5 while implementing 1.4 semantics
   would produce exactly this.

Next step, and do this rather than guessing further: an `LD_PRELOAD`
interposer on `eglGetPlatformDisplay` + `eglChooseConfig` that logs the
display handle and dumps the attribute list, run against doomretro. It
needs `base-devel` in the guest (~5 min, see the cli-c-toolchain notes
in plans/usecase_tests/README.md). That separates the two candidates in
one run and hands you the exact token. The fix then lands either as a
`deps/libhybris` fork patch (a token it rejects but Android's driver
accepts) or as a truthfulness fix in the fork's platform-display
version reporting.

## Repro

    TAWC_INSTALL_ID=arch scripts/rootfs-run.sh 'python -c "
    import ctypes; sdl=ctypes.CDLL(\"libSDL2-2.0.so.0\")
    sdl.SDL_GetError.restype=ctypes.c_char_p
    sdl.SDL_CreateWindow.restype=ctypes.c_void_p
    sdl.SDL_CreateWindow.argtypes=[ctypes.c_char_p]+[ctypes.c_int]*4+[ctypes.c_uint]
    sdl.SDL_Init(0x20)
    w=sdl.SDL_CreateWindow(b\"t\",0,0,320,240,0x2)
    print(bool(w), sdl.SDL_GetError())"'

Game repro: `pacman -S doomretro`, a freedoom1.wad, and
`doomretro -iwad freedoom1.wad`.
