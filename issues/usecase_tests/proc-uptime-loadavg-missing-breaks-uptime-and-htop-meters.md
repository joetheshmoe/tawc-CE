# `/proc/uptime` and `/proc/loadavg` are unreadable — `uptime` fails, htop meters read broken

Found as a side finding while re-running the `cli-tmux-curses` usecase
test (physical, Arch tawcroot, 2026-08-10). Does not block that test —
htop still draws a correct process table — but it is the most visibly
"wrong-looking" thing a terminal user sees.

## Symptoms

Inside the rootfs:

    # uptime
    uptime: Cannot get system uptime: Permission denied
    # cat /proc/loadavg
    cat: /proc/loadavg: Permission denied

htop renders, but its whole header is degraded:

    0[   offline]  4[   offline]      Tasks: 6, 29 thr, 0 kthr; 1 running
    ...                               Load average: nan nan nan
    Mem[||||2.86G/7.10G]              Uptime: (unknown)

All 8 CPU meters show `offline`, load average is `nan nan nan`, uptime
is `(unknown)`.

## Cause

SELinux denies `untrusted_app` the real files — confirmed outside
tawcroot too:

    $ adb shell run-as me.phie.tawc cat /proc/uptime    # Permission denied
    $ adb shell run-as me.phie.tawc cat /proc/loadavg   # Permission denied
    $ adb shell run-as me.phie.tawc cat /proc/stat      # Permission denied

tawcroot already shadows `/proc/stat` (`proc_shadow.c`, added for
procps `ps`, which hard-requires `btime`), but the shadow carries only a
synthesized `btime` plus a single idle-only aggregate `cpu` line and no
`cpuN` lines — hence htop's per-core meters showing `offline`.
`/proc/uptime` and `/proc/loadavg` are not shadowed at all, so they hit
the raw EACCES.

## Possible fix

- `/proc/uptime` is *recoverable with real data*: `CLOCK_BOOTTIME` is
  readable by an app, so the shadow can emit a truthful uptime (the
  idle field has no source — `0.00` is the honest placeholder). This
  also fixes htop's `Uptime:` line and the `uptime` command.
- `/proc/loadavg` has no available source. Options are leave it denied
  (status quo, `uptime` still fails if it is fixed to read loadavg) or
  emit zeros, which would be a lie. Prefer leaving it absent unless a
  real workload needs it.
- Per-`cpuN` lines in the `/proc/stat` shadow would turn htop's meters
  from `offline` to a flat `0.0%` — cosmetically nicer but no more
  truthful, since no per-core data is readable. Low value; decide
  deliberately.

The `/proc/stat` shape is documented as an accepted divergence in
notes/tawcroot/status.md ("More /proc shadows"); this issue is about
extending that list to `/proc/uptime`, not about the existing choice.

## Repro

    TAWC_INSTALL_ID=arch scripts/rootfs-run.sh 'uptime; cat /proc/uptime /proc/loadavg; head -3 /proc/stat'
