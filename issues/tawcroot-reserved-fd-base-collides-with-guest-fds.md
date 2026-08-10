# Reserved fd base (1000) collides with ordinary guest fds

`TAWCROOT_RESERVED_FD_BASE` is 1000 (fdtab.h) and the fd handlers treat
the whole half-space `[1000, ∞)` as ours, not just the ~8 slots we
actually hold. Nothing traps `setrlimit`/`prlimit64`, so a guest raises
`RLIMIT_NOFILE` (soft is already 32768+ on Android, 524288 on the host
box) and the kernel hands it fds well past 1000. From then on the guest
is fighting us over its own descriptors:

- `handle_dup3` / `handle_dup2` reject any `newfd >= 1000` outright
  (syscalls_fd.c) → `-EBADF` on a descriptor the guest owns.
- `handle_fcntl` returns `-EINVAL` for `F_DUPFD`/`F_DUPFD_CLOEXEC` with
  `arg >= 1000`.
- `handle_close_range` trims `last` to 999, so `close_range(3, ~0U, 0)`
  **silently leaves the guest's own fds ≥ 1000 open** — an fd leak
  across exec for exactly the programs careful enough to closefrom.

Verified on the host build:

```
native:         opened 1400 fds, highest=1402
                dup2(0,1402) -> ok ; fcntl(F_DUPFD,1402) -> ok
under tawcroot: opened 1400 fds, highest=1408
                dup2(0,1408) -> EBADF ; fcntl(F_DUPFD,1408) -> EINVAL
```

fdtab.h's rationale for the base — "if a guest does manage to push
past, fd creation will start failing with `-EMFILE` … no escape" — is
wrong: creation succeeds, the kernel just skips our occupied slots.

Not a containment problem (the guest cannot get *our* fds; the whole-
range rejection is what makes sure of that). It is a compatibility
problem, and the workloads it hits are ones we now care about: an sshd
with many sessions, a browser, a parallel build, anything using a large
poll set.

## Fix ideas

- Raise the base far above any plausible guest fd count and keep the
  range rejection (simplest; picks a new arbitrary number, so the same
  bug returns at a higher threshold).
- Base it on the hard `RLIMIT_NOFILE` at init and re-derive on
  `setrlimit` (trapping `setrlimit`/`prlimit64` for that purpose), so
  "above everything the guest can open" is true by construction rather
  than by hope.
- Or drop the range rule and reject only the fds actually in
  `tawcroot_reserved_fds`. Costs the "a guest fd inside the range is
  indistinguishable from ours to future reservations" property the
  current comment relies on, so the reservation path would need to
  refuse to hand out an fd number the guest already holds — which the
  kernel gives us for free, since it never returns an in-use fd.

Whichever way, `close_range` must stop silently skipping guest fds
above the base: split the range around our actual slots instead of
truncating it.

Found in the 2026-08 tawcroot security audit.
