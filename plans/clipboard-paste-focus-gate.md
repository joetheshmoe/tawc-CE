# Gate Android-clipboard pastes on client focus

The Android system clipboard is readable by guest programs through the
compositor selection (notes/clipboard.md). Wayland clients are already
gated; X11 clients are not, and one Xwayland instance is shared by every
installed distro. Close that.

## Current state

`ClipboardBridge.getTextForPaste()` is the single real
`getPrimaryClip()` read, reached by reverse JNI from a
`clipboard-fetch-android` thread. Two callers spawn that thread:

- **Wayland** — `SelectionHandler::send_selection` in
  `compositor/src/compositor.rs`, driven by smithay's data device.
  Smithay only sends `wl_data_device.selection` to the client holding
  keyboard focus, so an unfocused Wayland client never gets an offer to
  `receive` from. Effectively already gated.
- **X11** — `XwmHandler::send_selection` in
  `compositor/src/xwayland.rs` (~line 714). This serves any X11 client's
  `XConvertSelection` on `CLIPBOARD`. X11 has no focus requirement for
  selection conversion, so **any** X11 client can pull the Android
  clipboard whenever the compositor holds the Android selection.

And the X11 side is cross-distro: `TawcrootMethod.bindSpecs` binds
`<appData>/share/xtmp/.X11-unix` into *every* rootfs at
`/tmp/.X11-unix`, so all distros share one X display. An idle X11
program in distro A can read the Android clipboard while a window from
distro B is the one on screen.

Android's own rule (clipboard reads denied when the app isn't
foreground) bounds this to "while some tawc window is focused" — it does
not bound it to "while *this* client is focused".

## Target behaviour

`write_android_clipboard_to_fd` runs only when the requesting client is
the one whose surface currently holds compositor focus. Everything else
gets the existing refusal path (drop the fd → client sees an empty
paste), which is already a documented, accepted outcome for the
tawc-not-focused case.

## Approach

The work is compositor-side; the Kotlin surface shouldn't need to
change. `fetchClipboardText` stays the single read point, and
`NativeBridge` already tracks focus per `activityId` if a Kotlin-side
cross-check turns out to be wanted.

1. **Find out whether the requestor is reachable.** The open question is
   whether smithay's `XwmHandler::send_selection` exposes the requesting
   X11 window/client. It is not in the signature today (`XwmId`,
   `SelectionTarget`, `mime_type`, `fd`). If smithay's xwm tracks it
   internally, this needs a small accessor — we already carry a smithay
   fork (`deps/smithay`, pinned in `deps/deps.list`), so adding one is
   in scope, but it decides the shape of everything below. Settle this
   first.

2. **If the requestor is reachable:** compare it against the focused
   toplevel. Serve only when the focused toplevel is an X11 surface
   belonging to the same X client. This is the real fix.

3. **If it is not reachable (fallback):** gate on "is the currently
   focused toplevel an X11 surface at all". Coarser — any X11 client can
   still read while any X11 window is focused — but it closes the case
   where a Wayland window (or no tawc window) is focused, which is the
   larger share of the exposure. Land this only as a stepping stone and
   say so in the code.

## Explicitly out of scope

Per-distro clipboard isolation. That is a different boundary: it needs
one Xwayland instance and one X11 socket dir per distro, not a focus
check. Worth a separate decision — today the clipboard, the Wayland
socket, and the X display are all shared across every install by design.

## Verification

- Integration test: two X11 clients on one display, one focused and one
  idle; assert the idle one's `XConvertSelection` on `CLIPBOARD` yields
  an empty paste while the focused one still works. `tests/integration`
  already has clipboard coverage and a `clipboard-debug-state` broker
  action to assert against.
- Manual: copy in an Android app, focus a Wayland guest window, confirm
  paste still works; then confirm a background X11 client (e.g. a second
  terminal running `xclip -o -selection clipboard`) gets nothing.
- Watch for a regression in the normal Xwayland copy/paste path — the
  same handler serves client-owned X11 selections
  (`SelectionUserData::X11`), which must stay unaffected.
