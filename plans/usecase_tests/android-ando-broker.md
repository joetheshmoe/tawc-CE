# Usecase test: `ando` — Android commands from inside Linux

Read [README.md](README.md) first for shared procedure, cleanup, and
reporting rules.

## Run 2026-07-13 (physical OnePlus, Android 14): PARTIAL FAIL

- Steps 1–3, 6, 7 passed exactly as written: clean fail-closed refusal
  (exit 127 with enable instructions), broker enable/disable took
  effect, `getprop` printed `14`, pipes/`head`/`wc` behaved like normal
  pipes, refusal returned after disable.
- Step 4's literal command `ando -s 'id; echo $USER'` fails by design:
  `-s` uses the documented sudo-style join (notes/ando.md), same as
  real sudo. Plan bug. Intent verified instead with `ando -s id` /
  `ando /system/bin/sh -c 'id; echo $USER'` → real app uid 10250,
  `untrusted_app`, vs fake root inside the rootfs; exit codes propagate
  (`sh -c "exit 42"` → 42).
- **Step 5 FAILED**: `ando am start …` (and the Settings substitute)
  exits 255 silently — Android restricts `cmd activity` to root/shell;
  identical failure as app uid outside ando via the debug broker, so
  not an ando bug. `ando -r am start …` (rooted phone) worked and
  Firefox came foreground with example.com (screenshot-verified).
- Outcome: platform limit, now documented in notes/ando.md ("Semantics
  and known limits"); unrooted fix planned in
  [plans/ando-am.md](../ando-am.md).
- Cleanup done: ando disabled, screenshots deleted (device + host),
  device returned to home screen, nothing installed or left behind.

**Target:** emulator or physical.
**Usecase:** a Linux-side user scripts against Android: query properties, launch apps/URLs, glue the two worlds.

Read notes/ando.md first. ando is per-distro, default **off**,
fail-closed.

## Prerequisites

- A READY Arch tawcroot install; know its install id.

## Steps

1. Fail-closed check first: with ando disabled (default), run
   `scripts/rootfs-run.sh 'ando getprop ro.build.version.release'` —
   must refuse with instructions on enabling, not hang or half-work.
2. Enable via the broker (in-memory override, self-cleaning on app
   death): `scripts/tawc-exec.sh --action set-ando --arg installId=<id> --arg enabled=true`.
3. `ando getprop ro.build.version.release` — prints the Android version.
4. `ando /system/bin/sh -c 'id; echo $USER'` — runs as the app uid
   outside the rootfs env (contrast with `id` inside the rootfs, which
   reports fake root). Do **not** use `ando -s '…'` for this: `-s` does
   the sudo-style join, which escapes the `;` (notes/ando.md).
5. Open a URL: `ando am start -a android.intent.action.VIEW -d https://example.com`.
   **Known limit:** on unrooted devices this exits 255 silently —
   Android's `am`/`cmd activity` shell interface is root/shell-only
   (notes/ando.md "Semantics and known limits"); record it as such, not
   as a new failure. On a rooted device, verify the equivalent
   `ando -r am start …` instead: screenshot that an Android browser came
   to the foreground with the page (per README step 8). Once
   plans/ando-am.md ships, unrooted `ando am start` is expected to work
   and this step should be rewritten to test it.
6. Pipe integration: `ando getprop | grep ro.product` from inside the
   rootfs — stdio plumbing through the broker should behave like a
   normal pipe.
7. Disable again (`--arg enabled=false`) and re-verify refusal.

## Expected results

- Disabled → clean refusal; enabled → all commands work with correct
  stdio/exit codes; URL/intent launch brings the target app forward on
  rooted devices (`-r`), and fails with the documented silent 255 on
  unrooted ones until plans/ando-am.md ships.

## Known issues / caveats

- The `set-ando` override is in-memory only (cleared by app process
  death) — if results look stale, check whether the app restarted
  between steps.

## Cleanup

`set-ando … enabled=false`; return the device to the tawc app or home
screen (back out of the browser/settings you opened); delete
screenshots per README.
