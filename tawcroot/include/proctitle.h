/* Kernel-visible process identity for manually-loaded guests.
 *
 * tawcroot never lets the kernel exec the guest binary — the loader
 * maps it and jumps — so /proc/<pid>/comm and /proc/<pid>/cmdline keep
 * whatever the last real execveat set: "tawcroot --exec-child <fd>".
 * That breaks every by-name process tool inside the guest (pgrep,
 * pkill, killall, ps COMMAND column) for every guest process.
 *
 * Fix, per incarnation, applied right before the jump to guest entry:
 *   - comm: prctl(PR_SET_NAME, basename(exec path)) — matches the
 *     kernel's rule (script basename for shebangs, since the kernel
 *     names after the file passed to execve).
 *   - cmdline: overwrite the kernel arg region ([arg_start, arg_end),
 *     i.e. the initial-stack argv strings) in place with the guest's
 *     NUL-joined argv, NUL-filling the tail — the classic unprivileged
 *     setproctitle technique. The region can't be grown without
 *     CAP_SYS_RESOURCE, so the exec handler pre-sizes it by passing
 *     the space-joined guest cmdline (exec_state's proctitle) as
 *     argv[0] of the execveat-into-self. Truncation only happens when
 *     a shebang chain expands past the writer's slack.
 *
 * /proc/<pid>/exe is left alone: PR_SET_MM_EXE_FILE needs
 * CAP_SYS_RESOURCE, and /proc/self/exe must keep naming libtawcroot.so
 * anyway — the exec handler re-execs through it. The guest's own view
 * is already synthesized (path.h guest_exe).
 */

#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/* Record the kernel arg region from the initial stack. Call once,
 * first thing in tawcroot_main, before anything writes through argv. */
void tawcroot_proctitle_stash(int argc, char **argv);

/* Set comm + rewrite the stashed arg region. `argv` is the final
 * guest-visible argv (post shebang resolution); `exec_path` the path
 * the guest asked to exec. Call after the synthesized stack is built
 * (the rewrite may clobber the strings argv points at when they live
 * in the initial-stack region — sources are bounced first). */
void tawcroot_proctitle_apply(const char *exec_path,
                              int argc, const char *const *argv);

#ifdef __cplusplus
}
#endif
