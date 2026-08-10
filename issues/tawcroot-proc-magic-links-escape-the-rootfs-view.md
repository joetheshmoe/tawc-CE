# /proc magic links escape the rootfs view entirely

`/proc` is bound RW into every rootfs, and a path that matches a bind
takes the early-return in `tawcroot_path_translate_with_ctx`
(path_orchestrate.c, "Bind first … skip memo and resolver") — so the
suffix goes to the kernel with no symlink resolution at all. The
kernel then resolves `/proc/<pid>/root`, which is the *real* host root
(tawcroot never `chroot`s), and the guest lands anywhere on the host
filesystem, read and write. `cwd`, `fd/<n>` and `map_files/<n>` are
the same shape.

Verified on the host build (fake rootfs, standard bind set):

```
$ tawcroot -r ./rf -b /proc:/proc … -- /usr/bin/cat /etc/hostname
GUEST-ROOTFS-HOSTNAME                     # contained
$ tawcroot -r ./rf -b /proc:/proc … -- /usr/bin/cat /proc/self/root/etc/hostname
osprey                                    # the HOST's /etc/hostname
$ tawcroot … -- sh -c 'echo X > /proc/self/root/tmp/probe'   # host /tmp/probe: X
```

`..` is not involved — the lexical fold collapses `..` before
translation, so the documented "post-fold suffixes contain no `..`,
which is what makes rootfs-escape containment trivially auditable"
(notes/tawcroot/status.md) is true and also not sufficient.

## Why this is worth fixing even though tawcroot is not a sandbox

It needs no exploit and no escape from the virtualization layer — it
is an ordinary path through a supported bind, reachable from any
`open()`. Two things currently documented as holding do not:

- **The per-distro ando gate.** notes/ando.md §"Per-distro gating"
  claims "No bind covers another distro's `distros/<id>/ando/` … an
  enabled distro still can't reach a disabled (or another) distro's
  socket." A `connect()` to
  `/proc/self/root/data/data/me.phie.tawc/distros/<other>/ando/ando.sock`
  translates through the `/proc` bind like any other path, so any
  distro reaches any other distro's listener.
- **Cross-distro data separation.** Any guest can read and modify any
  other install's rootfs and the app's private files.

The "same-uid process that escapes the virtualization layer" caveat in
notes/ando.md is about a guest that defeats the monitor. This is the
monitor working as written.

## Fix hook

The machinery already exists and is used for the read-only-bind case:
`tawcroot_proc_magic_link_prefix` (proc_shadow.c) recognises exactly
these prefixes, and `ro_check_proc_magic_link` (path.c) readlinks the
prefix, joins the remainder, and prefix-checks the resolved host path.
Containment wants the same shape one step further: if the resolved
host path is outside the view (`tawcroot_host_path_to_guest_abs`
returns `-ENOENT`), refuse. Deciding the errno is the design question
— `-ENOENT` matches "this path does not exist in your world", which is
what the guest should believe.

Costs a readlink per magic-link-prefixed path; those are rare outside
`/proc/self/fd/<n>` (which resolves inside the view and passes) so the
hot path is unaffected.

Note the reverse-translation surface has the same question: `fd/<n>`
links legitimately name in-view objects and must keep working, so the
check has to be "resolve, then contain", not "reject the prefix".

`plans/tawcroot-landlock.md` would close this class in the kernel on
5.13+ devices, but the primary device is 5.4, so an in-resolver fix is
still wanted.

Found in the 2026-08 tawcroot security audit.
