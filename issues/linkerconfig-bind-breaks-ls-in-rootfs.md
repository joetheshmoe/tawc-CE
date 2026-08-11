# `ls /` in the rootfs errors on /linkerconfig

Any `ls` that stats the entries of `/` fails on the `/linkerconfig`
bind:

    $ ls -l /
    ls: cannot access '/linkerconfig': Permission denied
    d??????????   ? ?    ?       ?            ? linkerconfig

Bare `ls /` hits it too in a terminal — coreutils takes the plain
listing from `getdents` `d_type`, but with color on *and* `LS_COLORS`
set it stats every entry (sticky / other-writable / orphan classes
aren't derivable from `d_type`). Verified in the rootfs: with
`LS_COLORS` unset `ls --color=always /` exits 0 silently, with it set
the same command exits 1 with the error. Every interactive shell sets
it, so this is the first thing a user sees on `ls /`; the broker and
test paths are non-interactive, which is why nothing caught it.

## Cause

`/linkerconfig` is one of `LIBHYBRIS_BIND_DIRS` (TawcrootMethod.kt:404,
mirrored in ProotMethod.kt and ChrootMounter.kt). tawcroot rewrites
`stat("/linkerconfig")` onto Android's real `/linkerconfig`, which is
`linkerconfig_file` on tmpfs, and AOSP's `domain.te` grants every
domain only `linkerconfig_file:file r_file_perms` plus
`linkerconfig_file:dir search` — no `getattr` on the dir:

    avc: denied { getattr } for comm="ls" path="/linkerconfig"
      dev="tmpfs" ino=3 scontext=u:r:runas_app:s0:...
      tcontext=u:object_r:linkerconfig_file:s0 tclass=dir permissive=0

Cosmetic only. Files *inside* are fine — `stat -c %s
/linkerconfig/ld.config.txt` exits 0 and `head` reads it — which is
exactly libhybris's access pattern: the hybris linker `file_exists()`es
then reads `/linkerconfig/ld.config.txt` (`kLdGeneratedConfigFilePath`,
deps/libhybris/hybris/common/q/linker.cpp:111) and never touches the
directory or its subdirs. Per-APEX configs come from
`/apex/<n>/etc/ld.config.txt`, not from here.

## Fix

Copy rather than bind: at spawn, read Android's
`/linkerconfig/ld.config.txt` (235 KB; reading the file is permitted)
and write it into the rootfs at the same path, then drop
`/linkerconfig` from `LIBHYBRIS_BIND_DIRS` in all three methods. The
guest then sees a real app-owned file, `ls /` is clean, no SELinux
involved, and hybris finds the config at the path it probes. Re-copy
per spawn — Android regenerates `/linkerconfig` every boot.

One behaviour change: bound, the file was read-only (the libhybris
binds are `ro = true`); copied, an in-rootfs root can rewrite it. Not a
new boundary — the guest already controls its own rootfs and
`LD_LIBRARY_PATH`, and the config only shapes that guest's own bionic
namespaces.

Rejected alternatives: dropping the bind outright makes hybris fall
back to `init_default_namespace_no_config` plus the configured
`--with-default-hybris-ld-library-path`, which may well work but loses
the namespace links and permitted paths some vendor blobs dlopen
across; binding just the file needs `tawcroot_path_add_bind` to stop
opening bind sources `O_PATH|O_DIRECTORY` (tawcroot/src/path.c:368)
plus empty-suffix routing, for no gain over copying.

## Where it bites

Only on real devices does the config matter at all.
`get_ld_config_file_path()` checks `/system/etc/ld.config.arm64.txt`
*before* `/linkerconfig`, and since Android 11 that static per-arch
file is gone (linkerconfig generates the config at boot instead) — so
on any Android 11+ phone `/linkerconfig` is the only source. On
Android ≤10 it wins, but there `/linkerconfig` doesn't exist and the
`File.exists()` filter already drops the bind.

The emulator is the exception in both directions: it *does* ship
`/system/etc/ld.config.arm64.txt`, but as an ARM-translation artifact
(Chromium copyright header, `/system/${LIB}/arm64` search paths,
`ro.enable.native.bridge.exec=1`), so step 2 wins and `/linkerconfig`
is never read — and libhybris doesn't run there anyway, the asset is
`arm64-v8a` only. The bind is pure dead weight on x86_64, which is
exactly where the error is easiest to hit.

Related, found alongside: `ChrootMounter` skips the libhybris-only
mounts on emulators (`isEmulator`, ChrootMounter.kt:26,72) but
`TawcrootMethod` has no equivalent gate — `LIBHYBRIS_BIND_DIRS` is
filtered by `File.exists()` alone, so tawcroot binds `/apex /vendor
/system /system_ext /linkerconfig` on the emulator where none of them
are used. Worth the same gate independent of this fix.

## Status

Reproduced on the x86_64 emulator (API 36) with the tawcroot method.
**Not verified on the physical device** — the mechanism (generic AOSP
policy, unprivileged app uid, bind present on Android 11+) predicts it
reproduces identically there, and that's the case the fix is for.
`ls /system/etc/ld.config*` on the phone plus `ls /` in its rootfs
settles both. The chroot method runs as root and is unaffected.

Found while investigating the `ls /` error on the emulator, 2026-08.
