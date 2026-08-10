# No stated stack budget for the SIGSYS handler

The handler runs on the trapping thread's own stack — which is the
*guest's* stack, whatever size the guest's runtime chose (musl's
default thread stack is 128 KB; some runtimes go lower, and
`tests/integration/programs/static_small_stack_open_argv1.S` exists
because we already care).

`path_scratch.c` exists precisely so path buffers don't live there:
"Path buffers are fixed-size on the handler's stack" was the original
design, and the scratch pool replaced it. But the rule was never
written down as a budget, so handlers written since put sizeable
buffers straight back on the stack:

- `syscalls_socket.c`: `char parent[1024]` in `render_parent_fd_path`,
  `char full[1200]` + two 109-byte buffers in
  `reverse_translate_unix_sockaddr`, plus a `sockaddr_un` in each
  bind/connect/sendto/sendmsg frame — and these nest.
- `syscalls_fs.c`: `char buf[256]` in the statx fdinfo path, several
  smaller ones.
- `syscalls_control.c`: `unsigned char scratch[256]` in
  `filter_fake_accept`.
- `exec_handler.c` avoids it correctly and says why (static buffers,
  "16 KB of name copies would blow the handler stack budget") — but
  the budget it refers to is not defined anywhere.

Nothing is known to overflow today. The problem is that there is no
number to check a new handler against, and the deepest chains
(exec → shebang resolution → `tawcroot_open_in_view` → translate →
resolve) are the ones nobody measures.

## What would settle it

- Pick and document a number in `notes/tawcroot/sigsys-handler.md`:
  worst-case handler frame budget, and the rule for what must come
  from `path_scratch` instead (anything ≥ N bytes, anything sized by
  a guest-controlled length).
- Measure the real worst case rather than guessing — a debug build
  that paints the stack below `sp` on handler entry and reports the
  high-water mark would give the actual depth for the exec chain.
- Then either move the offenders above onto the scratch pool, or
  confirm the budget covers them.

`sigaltstack` is not the answer: it is per-thread state the guest owns
and can replace, so we cannot rely on it being installed.

Found in the 2026-08 tawcroot security audit.
