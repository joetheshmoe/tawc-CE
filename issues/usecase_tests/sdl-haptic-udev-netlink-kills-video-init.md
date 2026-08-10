# SDL games die at startup: haptic init needs udev's netlink socket, and a failed `SDL_Init` tears video back down

Replaces `issues/supertuxkart-sdl-no-displays.md`, whose premise
("the compositor advertises a `wl_output` SDL rejects") is **disproven**
below. SuperTuxKart now renders fine; the surviving failure is narrower
and fully characterized.

Found re-running the `gui-doom-game` usecase test (physical OnePlus
50f4ca18, Arch tawcroot, app build 2026-08-10).

## Symptom

DOOM Retro (the only doom engine in the Arch ARM repos) never launches —
100% reproducible over ~8 attempts, under `SDL_VIDEODRIVER=wayland` and
`x11` alike. It prints, then pops its standalone SDL error box:

    The call to SDL_GetNumVideoDisplays() failed on line 1,318 of i_video.c:
    "Video subsystem has not been initialized"

## Root cause

Not the compositor. SDL's *video* init works perfectly on tawc:

    # python -c 'import ctypes; sdl=ctypes.CDLL("libSDL3.so.0"); ...'
    SDL_Init(VIDEO) -> ok   driver: wayland   displays: 1

The failure is `SDL_INIT_HAPTIC`, and it takes video down with it.
Bisecting the subsystems through `libSDL2-2.0.so.0` (which on Arch ARM is
`sdl2-compat` 2.32.70 on top of SDL3 3.4.12):

    TIMER          -> 0
    AUDIO          -> 0
    VIDEO          -> 0
    JOYSTICK       -> 0
    HAPTIC         -> -1  err='Could not initialize UDEV'
    GAMECONTROLLER -> 0
    EVENTS         -> 0
    SENSOR         -> 0

and the combination is what kills apps:

    SDL_Init(VIDEO|HAPTIC) -> -1  err='Could not initialize UDEV'
      displays: 0   SDL_WasInit(VIDEO): 0x0        <-- video rolled back
    SDL_Init(VIDEO); SDL_InitSubSystem(HAPTIC) -> video survives
      displays: 1   SDL_WasInit(VIDEO): 0x20

So an app that asks for video and haptic (or `SDL_INIT_EVERYTHING`) in
**one** `SDL_Init` call ends up with no video at all, and every later
call reports "Video subsystem has not been initialized". The
`WAYLAND_DEBUG` teardown burst noted in the old issue (SDL binds a
complete `wl_output`, receives full state, then destroys everything) is
exactly this rollback, not SDL rejecting our output.

Why haptic fails: `SDL_UDEV_Init` needs a uevent netlink monitor, and
Android denies it to `untrusted_app`:

    udev_new()                          -> ok (non-NULL)
    udev_monitor_new_from_netlink(u,"udev") -> NULL

`socket(AF_NETLINK, …, NETLINK_KOBJECT_UEVENT)` is not reachable from an
app uid, so no udev, so no haptic — on any Android host, under any
install method.

## What this is *not*

- Not a `wl_output` advertisement gap. The compositor's output is
  complete and SDL accepts it (`displays: 1` when video is inited alone).
  The double `wl_output.done()` noted in the old issue is still there and
  still harmless.
- Not audio. `SDL_Init(AUDIO)` succeeds (driver `alsa`, 1 device
  enumerated) even with no audio bridge, so the "does the game need
  `-nosound`" question in the usecase plan is answered: it does not.
- Not SuperTuxKart-specific, and **STK is no longer broken**: it now
  renders normally on the GL/wlegl path (`clients=1 toplevels=1
  surfaces_wlegl=1 frames=443`, screenshot shows the real menu in true
  colors, no magenta). STK only logs
  `InputManager: Failed to init SDL haptics: Could not initialize UDEV`
  because it inits haptic in a *separate* call and survives. The old
  issue's STK "crashed/exited before first paint" symptom did not
  reproduce.

## Possible directions

- **Fake the uevent netlink socket in tawcroot.** Same shape as the
  existing guest-seccomp fake-accept: recognize
  `socket(AF_NETLINK, …, NETLINK_KOBJECT_UEVENT)` and hand back a
  socketpair end that never delivers events (plus whatever `bind`/
  `setsockopt` udev does on it). `SDL_UDEV_Init` would then succeed,
  haptic would enumerate zero devices, and combined `SDL_Init` calls
  would stop taking video down. This fixes every SDL game at once and
  is truthful (there really are no input hotplug events to deliver).
- SDL3 has a container-escape hatch (`Container detected, disabling udev
  integration`, keyed off `/.flatpak-info` and
  `/run/host/container-manager`), but **neither marker helped** when
  tested in the Arch rootfs — the check does not gate this failure path
  in 3.4.12.
- No app-side workaround exists for a game that requests haptic in its
  main `SDL_Init`: the flags are compiled in and no SDL hint disables
  the haptic subsystem.

## Repro

    TAWC_INSTALL_ID=arch scripts/rootfs-run.sh 'python -c "
    import ctypes
    sdl=ctypes.CDLL(\"libSDL2-2.0.so.0\"); sdl.SDL_GetError.restype=ctypes.c_char_p
    print(sdl.SDL_Init(0x20|0x1000), sdl.SDL_GetError(), sdl.SDL_GetNumVideoDisplays())"'
    # -> -1 b'Could not initialize UDEV' 0

Full game repro: `pacman -S doomretro`, fetch a freedoom1.wad, run
`doomretro -iwad freedoom1.wad`.
