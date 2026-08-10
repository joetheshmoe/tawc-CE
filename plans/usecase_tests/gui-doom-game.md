# Usecase test: play a simple game (Chocolate Doom + Freedoom)

Read [README.md](README.md) first for shared procedure, cleanup, and
reporting rules.

**Target:** physical only — SDL/GL rendering needs libhybris.
**Usecase:** a user installs a small game and plays it. Games are the sharpest test of the SDL → Wayland → libhybris path, and there is already a suspicious SDL issue on file.

## Prerequisites

- Cache proxy up (README step 6).
- `pacman -S --noconfirm chocolate-doom freedoom` (if either is missing
  from the repos, `prboom-plus` + `freedoom` is an acceptable
  substitute; adjust flags accordingly).

## Steps

1. Find the WAD path (`pacman -Ql freedoom | grep wad`).
2. Launch without audio (there is no audio bridge — plans/audio.md):
   `scripts/rootfs-run.sh 'chocolate-doom -iwad /usr/share/doom/freedoom1.wad -nosound' &`
   (rootfs env already sets `SDL_VIDEODRIVER=wayland,x11`).
3. Screenshot: the game menu/title screen should render fullscreen-ish,
   un-tinted (GL path).
4. Drive it a little: Enter through the menu into a level via broker
   `hardware-key` actions, send a few movement keys, screenshot twice
   and confirm the view changed (i.e. it's actually playing, not a
   frozen frame).
5. Watch stability ~2 minutes of input; then quit via the in-game menu
   (Esc → Quit) rather than killing it, to test clean SDL teardown.
6. If launch fails with SDL "video subsystem not initialized": that is
   `issues/usecase_tests/sdl-haptic-udev-netlink-kills-video-init.md`
   — the game's single `SDL_Init` call includes `SDL_INIT_HAPTIC`,
   which fails on Android's denied udev netlink socket and rolls the
   already-initialized video subsystem back. Confirm it is the same bug
   rather than filing a new one; there is no app-side workaround until
   tawcroot fakes the netlink socket.

## Expected results

- Game starts, renders, responds to keys, and exits cleanly with
  `-nosound`. Audio is expected to be absent, and its *absence must not
  crash the game* — if the game refuses to run without the `-nosound`
  flag (i.e. default audio init is fatal), record that as a finding:
  users won't know the flag.

## Known issues / caveats

- `issues/usecase_tests/sdl-haptic-udev-netlink-kills-video-init.md` —
  the blocker for this test. Any SDL app whose main `SDL_Init` asks for
  video *and* haptic loses video entirely.
- No audio bridge exists (plans/audio.md); silence is expected.
- `issues/hardware-backspace-stuck-down.md` if you use Backspace in
  menus.

## Cleanup

Quit the game, `pacman -Rns chocolate-doom freedoom`, delete
screenshots on device and host.

## Run result (2026-07-13, physical 50f4ca18, Arch tawcroot) — FAIL

Package availability: chocolate-doom, freedoom, prboom-plus, gzdoom and
crispy-doom are **all absent from the Arch Linux ARM repos** (`pacman -Ssq`
finds only `doomretro`). The plan's chocolate/prboom substitutes do not
exist here. Substituted `doomretro` (SDL2 engine) + a freedoom1.wad v0.13.0
to still exercise the SDL → Wayland → libhybris path. (A real user following
this usecase on Arch ARM would hit the missing-package wall immediately — a
doom-specific packaging gap, not a tawc bug. Note the GitHub release URL
does not work through the cache proxy, which does not follow the 302 to
`release-assets.githubusercontent.com`; fetch the WAD directly.)

Outcome: doomretro fails SDL video init 100% of the time (`SDL_GetNumVideoDisplays
... "Video subsystem has not been initialized"`), under both
`SDL_VIDEODRIVER=wayland` and `x11`. Only SDL's standalone error message box
renders; the game never launches, so no frame ever rendered and the
input/stability/clean-exit steps were not reached.

## Run result (2026-08-10, physical 50f4ca18, Arch tawcroot) — FAIL, root-caused

Still fails, identically, on ~8 fresh attempts against the 2026-08-10
build. But the cause is now pinned and it is **not** the compositor's
`wl_output`, which was the previous theory:

- `SDL_Init(SDL_INIT_VIDEO)` alone succeeds on tawc — driver `wayland`,
  1 display (checked by driving `libSDL3.so.0` / `libSDL2-2.0.so.0`
  straight from python `ctypes`).
- Subsystem bisect: only `SDL_INIT_HAPTIC` fails, with
  `Could not initialize UDEV` — `udev_monitor_new_from_netlink()`
  returns NULL because Android denies `untrusted_app` the uevent
  netlink socket.
- `SDL_Init(VIDEO|HAPTIC)` in one call then **rolls video back**
  (`SDL_WasInit(VIDEO)`=0, displays 0), which is what produces the
  game's "Video subsystem has not been initialized". Two-step init
  (`SDL_Init(VIDEO)` then `SDL_InitSubSystem(HAPTIC)`) keeps video.
- Audio is *not* the problem: `SDL_Init(AUDIO)` succeeds (driver `alsa`,
  1 device) with no audio bridge, so this plan's "is default audio init
  fatal?" question is answered — it is not.
- SuperTuxKart, the other app in the old issue, **now renders fine**
  (`clients=1 toplevels=1 surfaces_wlegl=1 frames=443`, true-color
  screenshot of its real menu). It only logs the same haptic error and
  survives, because it inits haptic separately.

Filed as `issues/usecase_tests/sdl-haptic-udev-netlink-kills-video-init.md`
(replaces the now-disproven `supertuxkart-sdl-no-displays.md`, deleted).
Re-run this plan once tawcroot fakes the netlink socket. Not added to
Completed.

Cleanup done: doomretro + SDL deps removed, `/root/usecase-doom` and
`/tmp/*.log` deleted, screenshots deleted device-side and host-side.
