# shm.c publishes reserved fds without the release store

`tawcroot_reserved_fds` / `tawcroot_n_reserved_fds` are read lock-free
from the SIGSYS handler on sibling threads (`tawcroot_fd_is_reserved`,
fdtab.h). `tawcroot_fd_reserve` respects that: it writes the slot,
then publishes the count with `__atomic_store_n(..., __ATOMIC_RELEASE)`
and documents why.

`add_to_reserved_list` in shm.c does the same job with a plain
`tawcroot_reserved_fds[tawcroot_n_reserved_fds++] = fd;` — no release
store, no atomic increment. Two writers into the same table with two
different disciplines.

Consequences, both narrow but real: a concurrent reader can observe the
incremented count before the slot store lands (so it reads a garbage or
zero fd number and mis-answers `is_reserved`), and two threads calling
`shm_open` for different names can race the read-modify-write of the
count and lose an entry. The shm table itself is under `g_shm_lock`, so
this is only the *reserved-fd* publication, not the shm slot.

Fix: call `tawcroot_fd_reserve`'s publication path rather than
open-coding it — either export a small `tawcroot_fd_reserve_existing(fd)`
that does the store/release without the `F_DUPFD` (shm needs the fd to
stay non-CLOEXEC, which is why it dups itself in
`dup_to_reserved_inheritable`), or give `tawcroot_fd_reserve` a flag for
"already in range, don't re-dup". Having exactly one function that
publishes into that table is the point.

Found in the 2026-08 tawcroot security audit.
