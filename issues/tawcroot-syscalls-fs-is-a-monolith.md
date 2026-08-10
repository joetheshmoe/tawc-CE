# syscalls_fs.c has grown into the file the design says not to have

notes/tawcroot/overview.md §"Designed for expansion" sells per-subsystem
handler files "so a new feature is 'add a `.c` and a dispatch entry,'
not 'edit one giant file.'" `src/syscalls_fs.c` is now 2672 lines —
roughly a fifth of production tawcroot, and larger than the thing the
sentence was written to avoid.

It currently holds, in one translation unit: the openat/stat/statx
family, readlink and `/proc/self/exe` substitution, the chmod/chown
fake-root gates, the RO-bind fd probes, mknod device faking, the
`/dev/shm` intercepts threaded through openat/unlinkat/stat, statx
`STATX_MNT_ID` synthesis from fdinfo, hardlink-emulation fixups in the
stat/link/rename paths, xattr wrappers, and the whole x86_64 legacy
compatibility set.

Nothing here is wrong; it accreted one justified handler at a time.
The cost is that the seams the design promised aren't there any more:
the shm intercepts and the linkstore fixups are cross-cutting concerns
stitched into unrelated handlers, so "add a feature" now means finding
every fs handler it has to touch.

## Split worth considering

- `syscalls_stat.c` — stat/lstat/fstat/newfstatat/statx plus the
  fake-root decoration and mnt_id synthesis (the decoration logic is
  shared and self-contained).
- `syscalls_xattr.c` — the xattr macro set, which is nearly free-standing
  already.
- Legacy x86_64 path aliases (`open`, `creat`, `stat`, `chmod`,
  `mkdir`, …) into their own file, matching how syscalls_fd.c fences
  its legacy block with one `#if defined(__x86_64__)`.
- The `/dev/shm` and linkstore intercepts want a stated pattern rather
  than a home: right now each is an `if` near the top of several
  handlers. A small "intercept chain" the fs handlers consult once
  would make both features additive again.

No behaviour change; do it when something forces a real edit to the
file rather than as a standalone churn commit. The test suite
(`tawcroot/test.sh`) covers this surface well, so a split is
low-risk when it happens.

Found in the 2026-08 tawcroot security audit.
