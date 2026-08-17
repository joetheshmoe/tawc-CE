# SDL clients see the display as portrait regardless of the compositor's landscape output

## Symptom

SuperTuxKart (an SDL2/irrlicht game) renders hardware-accelerated
(`Using renderer: OpenGL ES 3.2`, wlegl buffers, thousands of frames) but
blocks on its minimum-resolution check with "Your screen resolution is too
low to run STK" (`irr_driver->getActualScreenSize().Height < 480`), even
when the compositor output is landscape 1450x671 at scale 1.0.

STK's config (`config-0.10/config.xml`) recorded `real_width=720
real_height=1450` — the *portrait* dimensions — after a run, and irrlicht's
`getDesktopResolution()` ("window size in user config is larger than your
screen") also clamps against a portrait-ish size. So SDL reports the
display to clients as portrait even though tawc's `wl_output` mode is the
landscape size (1450x671) with `Transform::Normal`.

## Likely cause

tawc sets `wl_output` mode to the physical host size and never sets a
`wl_output.transform` to tell clients the content is rotated. SDL's
`SDL_GetDesktopDisplayMode` returns the mode in the display's natural
(portrait) orientation, so clients that key off the desktop resolution
(not the configured window size) see 720x1450 instead of 1450x671.

## Impact

Any SDL/irrlicht game or app that reads the desktop resolution rather than
its own window size will misbehave: STK refuses to run its GUI; other
fullscreen SDL games likely show a portrait-sized viewport in landscape.

## Notes

- Verified on moto g 2025 (720x1604, Debian sid, tawcroot, libhybris),
  2026-08-12.
- STK itself is not the problem: it installs, launches, and drives GLES 3.2
  through libhybris — only the resolution check fails.
- The `set-output-scale` broker action does update `wl_output.scale`
  (logical size changes), but that doesn't change the *orientation* SDL
  reports.
