# Desktop environments

tawc can run full desktop environments inside a rootfs. They are
discovered by the launcher and launched like apps, with per-DE screen
orientation defaults (the shipped desktop DEs force landscape).
Verified on a moto g 2025 (Debian sid, tawcroot, libhybris) 2026-08-12.

## How a DE becomes launchable

Two mechanisms, both rendering as ordinary launcher rows:

1. **`.desktop` file** (distro-side, any DE): the launcher already
   scans `root/.local/share/applications` and `/usr/share/applications`.
   A DE session can be a plain entry whose `Exec=` starts the session
   (e.g. `Exec=dbus-run-session -- xfce4-session`). The optional key
   `X-Tawc-Orientation=landscape|portrait` tells the launcher to force
   that screen orientation when the entry is launched.
2. **Known-DE table** (app-side, zero-config): `compositor/src/launcher.rs`
   holds `KNOWN_DESKTOPS` — a table of `(id, name, comment, session-binary,
   exec, icon, orientation)`. When a rootfs contains the session binary
   (a **regular file** under `usr/bin` — on-device the scanner's
   `is_file()` does not follow symlinks) the scanner synthesises a
   launcher entry for it (id prefixed `tawc-de-`). Installing the DE's
   package is enough for it to appear in the launcher — no file authoring.

   Adding a new DE = one row in `KNOWN_DESKTOPS` (plus whatever the DE
   needs to actually run, e.g. dbus, a WM). The table's exec lines wrap
   sessions in `dbus-run-session` because tawcroot installs run without
   any session bus otherwise.

3. **Install-time checkbox** (user-facing): `DesktopOptions` (Kotlin)
   lists the same DEs with their apt package lists + per-DE setup
   scripts. The install form renders them as optional checkboxes
   (gated on `Distro.supportsExtraPackages` — apt family only), and the
   install pipeline runs `Distro.installExtraPackages` after the base
   set when any are ticked. This is the "easy" path; the known-DE table
   is the fallback for DEs installed later by hand.

## Orientation forcing

Desktop DEs are portrait-hostile (panels/wallpapers assume a wide
screen), so the table defaults desktop DEs to `landscape` and mobile
DEs to `portrait`. Mechanics:

- The scanner reads `X-Tawc-Orientation` from `.desktop` files and the
  table's `orientation` field, and ships it in the launcher JSON.
- `EntryLauncher` resolves the effective orientation (per-entry
  override from `Installation.desktopOrientations` wins, else the
  scanner value), then sets `NativeBridge.orientationSession` for the
  launch's lifetime. `CompositorActivity.onCreate` applies it via
  `setRequestedOrientation` when the compositor spawns a window — the
  launch-scoped session is read at each `spawnActivity`, so every
  window of a DE session inherits the force and later launches start
  clean.
- **Disabling the force**: the launcher's long-press menu on any entry
  offers Force landscape / Force portrait / Follow system. "Follow
  system" clears the per-entry override (and explicitly overrides a
  `.desktop` default).

Verified: launching XFCE rotates the compositor output to landscape
(1604x720 panel → 1450x671 surface minus system bars) and holds it;
Sxmo holds portrait.

## Single-task DE sessions

tawc's default is multi-activity: every toplevel gets its own Android
task/recents card. That made a DE session (desktop + panel + apps)
spawn several cards and burn RAM. DE launches (`tawc-de-*` entries) now
set `NativeBridge.desktopSession`, which flips the compositor to
`single_activity_mode`: all the DE's windows — and any app launched
while the DE runs — collapse onto one host/task. Apps launched before
the DE (or after it exits) keep their own tasks.

Implementation notes (the parts that took debugging):
- `nativeSetDesktopSession` writes a sticky `host::desktop_session()`
  static in addition to the `SurfaceEvent` — EntryLauncher sets the flag
  *before* the compositor is running, and `send_surface_event` drops
  events with no running loop. The event-loop start seeds
  `TawcState::single_activity_mode` from the static.
- Two DE windows can map before the first host's Activity registers
  (Android spawn latency) and mint two hosts anyway. `TawcState`
  records the first host minted during a session in
  `desktop_session_host`; all assignment paths
  (`assign_toplevel_to_host`, xwayland's `assign_host_for_x11`) route
  their "existing host" lookup through `existing_host_for_session()`
  and record via `note_desktop_session_host`. Cleared when the session
  ends.

## Runtime requirements for DE sessions

- **dbus**: none of the DE sessions start a bus on their own, so exec
  lines wrap them in `dbus-run-session`. The bus works under tawcroot.
- **SCM_CREDENTIALS**: glib's GDBus sends SCM_CREDENTIALS during its
  unix auth handshake, filled from the faked root `getuid()`/`getgid()`.
  The kernel rejects the mismatch with EPERM, so every glib/dbus client
  (xfconf, gvfs, gnome-shell) failed to connect to the session bus.
  Fixed in `tawcroot/src/syscalls_socket.c`: `handle_sendmsg` now
  rewrites SCM_CREDENTIALS cmsgs to the process's real pid/uid/gid.
- **WMs**: tawc's own X11 WM (smithay X11Wm) manages X11 toplevels,
  and DE sessions that detect "a WM is running" correctly skip their
  own WM (xfce4-session skips xfwm4, lxqt-session runs without
  openbox). A DE that *unconditionally* starts its own X11 WM
  (openbox) conflicts with tawc's XWM and dies.

## DE test status

The app ships **two** desktop environments (the known-DE table and the
install-time picker both offer exactly these). Everything else was trialled
and cut until it can render properly.

| DE | Type | Status |
|----|------|--------|
| XFCE (`xfce4`) | desktop | **Shipped + proper.** Blue-gradient wallpaper + full-width top panel (clock/date/app-menu/workspace switcher) + desktop icons, all via wlr-layer-shell. Whisker menu opens on tap and lists apps. Single task, landscape forced. |
| LXQt (`lxqt` + `openbox`) | desktop | **Shipped + proper.** Dark panel + blue-gradient wallpaper + desktop icons (Computer/Network/Trash) via wlr-layer-shell. Minor: lxqt-powermanagement crashes (no system bus). |

Cut (removed from the table + picker; trialled and documented here for when
they're revisited):

| DE | Reason cut |
|----|------------|
| MATE | `mate-settings-daemon` hardcodes `gdk_x11_window_get_xid`, so the session can't run on Wayland; on X11 the panel works (dock sizing fixed) but the session-manager path is fragile. |
| Sxmo / Weston | Nested-compositor shells; work, but are separate compositors rather than native DEs — pulled for a cleaner lineup. |
| KDE Plasma | `kwin_x11` can't claim the X root (tawc's XWM holds it); plasmashell needs a session backend. |
| GNOME Shell / Phosh | GNOME 50 requires systemd session targets; Phosh needs more wlr protocols. |
| LXDE | `lxsession` segfaults under libhybris; openbox conflicts with tawc's XWM. |

## wlr-layer-shell

Implemented (2026-08-12) — this was the single biggest "DEs look wrong"
gap. Panels, docks and wallpapers register `zwlr_layer_shell_v1` surfaces;
without it they either abort (mate-panel on Wayland) or get forced to the
full output size and render as a stretched blob.

Integration points (`compositor/`):
- `TawcState.layer_shell_state` global + `WlrLayerShellHandler` impl. Layer
  surfaces map onto tawc's single output; `LayerMap::arrange` computes the
  anchored geometry (full-width top bar for a top-anchored panel, etc.).
- Render (`render.rs`): Background/Bottom layers draw below windows,
  Top/Overlay above. `LayerSurface`'s `AsRenderElements` walks the layer
  surface plus its popups (whisker menu).
- Input (`event_loop.rs`): Overlay/Top layer surfaces are hit-tested before
  windows, so panel taps reach the panel.
- Host minting: a DE whose windows are *all* layer surfaces (XFCE: panel +
  `desktop` + `desktop-icons`) never maps a toplevel, so `new_layer_surface`
  mints a host (pinned via `desktop_session_host`) so there's a surface to
  draw into.
- Commit + output resize re-run `arrange_layers`.
- Layer popups are configured/tracked by the *xdg* `new_popup` path (a
  layer-shell popup is created via `xdg_surface.get_popup` first); the layer
  `new_popup` is a no-op.

## X11 Dock sizing

`configure_x11_toplevel_for_host` no longer force-sizes `_NET_WM_WINDOW_TYPE_DOCK`
windows — an X11 panel keeps its natural geometry and renders as a thin bar
at the top instead of stretching to fill the screen.

## Adding a DE: checklist

1. `apt-get install -y --no-install-recommends <de-package>` in the rootfs
   (plus a WM if the DE needs one and won't inherit tawc's XWM).
2. Add a row to `KNOWN_DESKTOPS` in `compositor/src/launcher.rs`
   (session binary as a **regular file** under `usr/bin`,
   `dbus-run-session`-wrapped exec, icon name, orientation default).
3. Launch from the launcher; check the orientation force, panel/desktop
   fit, single-task behavior, and that apps launch from the DE's own UI.
4. For DEs whose window manager unconditionally grabs the X root, either
   let tawc's XWM stand in (configure the DE to not start a WM) or
   document the conflict.
