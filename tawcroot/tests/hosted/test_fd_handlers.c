/* Hosted handler-level tests for syscalls_fd.c — reserved-fd
 * protection (close/dup/fcntl lies and rejections) and the getdents64
 * /proc/self/fd filter against REAL kernel getdents64 output. Runs
 * in-process under ASan; see hosted.h.
 *
 * The raw-syscall hook doubles as an observation point here: the
 * close_range trim test must not let a real close_range(0, …) reach
 * the kernel (it would shred the test binary's own fd table), so the
 * hook captures the handler's outgoing call instead. */

#include <cleat/test.h>

#include <dirent.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <unistd.h>

#include "hosted.h"

#include "errno_neg.h"
#include "fdtab.h"
#include "path.h"
#include "sysnr.h"

/* --- reserved-fd EBADF contract ---------------------------------------- */

test(hosted_reserved_fd_close_lies_success_and_fd_survives)
{
	th_view v;
	th_setup(&v, "fd-close");

	int rfd = tawcroot_rootfs_fd;
	test_int_eq(th_sys(TAWC_SYS_close, rfd, 0, 0, 0, 0, 0), 0);

	/* The fd must still be open and usable by the translator. */
	struct stat st;
	test_int_eq(fstat(rfd, &st), 0);
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);

	th_teardown(&v);
}

test(hosted_close_of_guest_fd_passes_through)
{
	th_view v;
	th_setup(&v, "fd-close2");

	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(th_sys(TAWC_SYS_close, fd, 0, 0, 0, 0, 0), 0);
	struct stat st;
	test_int_eq(fstat((int)fd, &st), -1);  /* really closed */

	th_teardown(&v);
}

test(hosted_reserved_fd_dup_family_ebadf)
{
	th_view v;
	th_setup(&v, "fd-dup");

	int rfd = tawcroot_rootfs_fd;
	test_int_eq(th_sys(TAWC_SYS_dup, rfd, 0, 0, 0, 0, 0), TAWC_EBADF);
	test_int_eq(th_sys(TAWC_SYS_dup3, rfd, 5, 0, 0, 0, 0), TAWC_EBADF);
	test_int_eq(th_sys(TAWC_SYS_fcntl, rfd, F_GETFD, 0, 0, 0, 0),
		    TAWC_EBADF);
	test_int_eq(th_sys(TAWC_SYS_fchdir, rfd, 0, 0, 0, 0, 0), TAWC_EBADF);

	/* dup3 onto a reserved newfd: -EBADF (would otherwise clobber). */
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(th_sys(TAWC_SYS_dup3, fd, rfd, 0, 0, 0, 0), TAWC_EBADF);
	test_int_eq(close((int)fd), 0);

	th_teardown(&v);
}

/* A guest fd above the base is the guest's, not ours: the dup/fcntl
 * handlers must not reject it just for being high. Regression for
 * issues/tawcroot-reserved-fd-base-collides-with-guest-fds.md, where the
 * whole half-space [base, ∞) was claimed and any guest that opened more
 * than ~1000 fds got -EBADF/-EINVAL on its own descriptors. */
test(hosted_guest_fds_above_reserved_base_are_usable)
{
	th_view v;
	th_setup(&v, "fd-highguest");

	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);

	/* F_DUPFD with a minimum above the base: the kernel picks a free
	 * slot, which is never one of ours. */
	long hi = th_sys(TAWC_SYS_fcntl, fd, F_DUPFD,
			 TAWCROOT_RESERVED_FD_BASE + 128, 0, 0, 0);
	test_true(hi >= TAWCROOT_RESERVED_FD_BASE + 128);
	test_false(tawcroot_fd_is_reserved((int)hi));

	/* dup2/dup3 onto another high number the guest picked itself. */
	int newfd = (int)hi + 3;
	test_int_eq(th_sys(TAWC_SYS_dup3, fd, newfd, 0, 0, 0, 0), newfd);
	struct stat st;
	test_int_eq(fstat(newfd, &st), 0);

	/* And the guest can close them again for real. */
	test_int_eq(th_sys(TAWC_SYS_close, newfd, 0, 0, 0, 0, 0), 0);
	test_int_eq(fstat(newfd, &st), -1);
	test_int_eq(th_sys(TAWC_SYS_close, hi, 0, 0, 0, 0, 0), 0);
	test_int_eq(fstat((int)hi, &st), -1);
	test_int_eq(close((int)fd), 0);

	th_teardown(&v);
}

/* --- close_range: observed via the hook, never executed ----------------- */

#define CR_MAX_SEEN 8
static size_t cr_n_seen;
static unsigned int cr_seen[CR_MAX_SEEN][3];
static bool cr_hook(long nr, const long args[6], long *ret)
{
	if (nr != TAWC_SYS_close_range) return false;
	if (cr_n_seen < CR_MAX_SEEN) {
		cr_seen[cr_n_seen][0] = (unsigned int)args[0];
		cr_seen[cr_n_seen][1] = (unsigned int)args[1];
		cr_seen[cr_n_seen][2] = (unsigned int)args[2];
	}
	cr_n_seen++;
	*ret = 0;
	return true;
}

/* The handler must close everything the guest asked for EXCEPT its own
 * reserved slots — i.e. split the range around them rather than trim it
 * at the base. Trimming left the guest's own fds above the base open
 * (issues/tawcroot-reserved-fd-base-collides-with-guest-fds.md). */
test(hosted_close_range_splits_around_reserved_fds)
{
	th_view v;
	th_setup(&v, "fd-crange");
	th_add_bind(&v, "/mnt/host");  /* a second reserved fd */

	test_true(tawcroot_n_reserved_fds == 2);
	unsigned int lo = (unsigned int)tawcroot_reserved_fds[0];
	unsigned int hi = (unsigned int)tawcroot_reserved_fds[1];
	if (lo > hi) { unsigned int t = lo; lo = hi; hi = t; }

	/* Whole-table sweep: gaps below, between (unless the two fds are
	 * adjacent, as they are in practice), and above our fds. */
	long want_segs = hi == lo + 1 ? 2 : 3;
	cr_n_seen = 0;
	tawcroot_test_raw_hook = cr_hook;
	test_int_eq(th_sys(TAWC_SYS_close_range, 3, ~0U, 0, 0, 0, 0), 0);
	test_int_eq((long)cr_n_seen, want_segs);
	test_int_eq(cr_seen[0][0], 3);
	test_int_eq(cr_seen[0][1], lo - 1);
	size_t k = 1;
	if (hi != lo + 1) {
		test_int_eq(cr_seen[k][0], lo + 1);
		test_int_eq(cr_seen[k][1], hi - 1);
		k++;
	}
	test_int_eq(cr_seen[k][0], hi + 1);
	test_int_eq(cr_seen[k][1], ~0U);

	/* Entirely above the base: still a real sweep of the guest's high
	 * fds, minus our slots. Flags ride along on every segment. */
	cr_n_seen = 0;
	test_int_eq(th_sys(TAWC_SYS_close_range, hi + 1, ~0U, 4 /*CLOEXEC*/,
			   0, 0, 0), 0);
	test_int_eq((long)cr_n_seen, 1);
	test_int_eq(cr_seen[0][0], hi + 1);
	test_int_eq(cr_seen[0][1], ~0U);
	test_int_eq(cr_seen[0][2], 4);

	/* A range that is nothing but reserved fds issues no syscall. */
	cr_n_seen = 0;
	test_int_eq(th_sys(TAWC_SYS_close_range, lo, lo, 0, 0, 0, 0), 0);
	test_int_eq((long)cr_n_seen, 0);

	tawcroot_test_raw_hook = NULL;
	th_teardown(&v);
}

/* The reserved fds themselves survive a guest sweep, and translation
 * still works afterwards — the property the trim used to provide. */
test(hosted_close_range_keeps_reserved_fds_alive)
{
	th_view v;
	th_setup(&v, "fd-crange2");

	/* A guest fd above the base must be closed by the sweep; the
	 * rootfs fd must not. Both are high, so only exact membership can
	 * tell them apart. */
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	long guest_hi = th_sys(TAWC_SYS_fcntl, fd, F_DUPFD,
			       TAWCROOT_RESERVED_FD_BASE + 64, 0, 0, 0);
	test_true(guest_hi >= TAWCROOT_RESERVED_FD_BASE + 64);
	test_int_eq(close((int)fd), 0);

	test_int_eq(th_sys(TAWC_SYS_close_range, TAWCROOT_RESERVED_FD_BASE,
			   ~0U, 0, 0, 0, 0), 0);

	struct stat st;
	test_int_eq(fstat((int)guest_hi, &st), -1);        /* guest's: closed */
	test_int_eq(fstat(tawcroot_rootfs_fd, &st), 0);    /* ours: alive */
	long probe = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe",
			    O_RDONLY, 0, 0, 0);
	test_true(probe >= 0);
	test_int_eq(close((int)probe), 0);

	th_teardown(&v);
}

/* --- getdents64 /proc/self/fd filter vs real kernel output -------------- */

test(hosted_getdents64_hides_reserved_fds_in_proc_self_fd)
{
	th_view v;
	th_setup(&v, "fd-dents");
	th_add_bind(&v, "/mnt/host");  /* a second reserved fd */

	int pfd = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
	test_true(pfd >= 0);

	/* Drive the handler exactly as the guest's closefrom would. */
	char names[4096];
	size_t names_n = 0;
	unsigned char buf[512];  /* small buffer to force several batches */
	for (;;) {
		long n = th_sys(TAWC_SYS_getdents64, pfd, buf, sizeof buf,
				0, 0, 0);
		test_true(n >= 0);
		if (n == 0) break;
		long i = 0;
		while (i < n) {
			uint16_t reclen;
			memcpy(&reclen, buf + i + 16, 2);
			const char *name = (const char *)(buf + i + 19);
			size_t len = strlen(name);
			test_true(names_n + len + 2 < sizeof names);
			memcpy(names + names_n, name, len);
			names[names_n + len] = '\n';
			names_n += len + 1;
			i += reclen;
		}
	}
	names[names_n] = 0;

	/* Every reserved fd's number must be absent; the dir fd itself
	 * (a low guest-range fd) must be present. */
	for (size_t i = 0; i < tawcroot_n_reserved_fds; i++) {
		char needle[16];
		snprintf(needle, sizeof needle, "%d\n",
			 tawcroot_reserved_fds[i]);
		test_null(strstr(names, needle));
	}
	char self_needle[16];
	snprintf(self_needle, sizeof self_needle, "%d\n", pfd);
	test_nonnull(strstr(names, self_needle));

	test_int_eq(close(pfd), 0);
	th_teardown(&v);
}

test(hosted_getdents64_non_proc_dir_unfiltered)
{
	th_view v;
	th_setup(&v, "fd-dents2");

	/* A dir inside the rootfs containing a file literally named
	 * "1000" (the reserved base) must NOT have it hidden. */
	long mr = th_sys(TAWC_SYS_mkdirat, AT_FDCWD, "/run/d", 0755, 0, 0, 0);
	test_int_eq(mr, 0);
	long cfd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/run/d/1000",
			  O_WRONLY | O_CREAT, 0644, 0, 0);
	test_true(cfd >= 0);
	test_int_eq(close((int)cfd), 0);

	long dfd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/run/d",
			  O_RDONLY | O_DIRECTORY, 0, 0, 0);
	test_true(dfd >= 0);

	unsigned char buf[1024];
	bool saw_1000 = false;
	for (;;) {
		long n = th_sys(TAWC_SYS_getdents64, dfd, buf, sizeof buf,
				0, 0, 0);
		test_true(n >= 0);
		if (n == 0) break;
		long i = 0;
		while (i < n) {
			uint16_t reclen;
			memcpy(&reclen, buf + i + 16, 2);
			if (strcmp((const char *)(buf + i + 19), "1000") == 0)
				saw_1000 = true;
			i += reclen;
		}
	}
	test_true(saw_1000);

	test_int_eq(close((int)dfd), 0);
	th_teardown(&v);
}

#if defined(__x86_64__)
/* Legacy getdents(2) must apply the same reserved-fd filter AND hand
 * back legacy-layout records (d_name at 18, d_type in the record's
 * last byte). Regression for the untrapped-NR-78 gap: raw kernel
 * dirents leaked reserved fds to legacy callers. x86_64-only — the NR
 * doesn't exist on aarch64. */
test(hosted_legacy_getdents_hides_reserved_fds_and_repacks)
{
	th_view v;
	th_setup(&v, "fd-ldents");
	th_add_bind(&v, "/mnt/host");  /* a second reserved fd */

	int pfd = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
	test_true(pfd >= 0);

	char names[4096];
	size_t names_n = 0;
	unsigned char buf[512];
	for (;;) {
		long n = th_sys(TAWC_SYS_getdents, pfd, buf, sizeof buf,
				0, 0, 0);
		test_true(n >= 0);
		if (n == 0) break;
		long i = 0;
		while (i < n) {
			uint16_t reclen;
			memcpy(&reclen, buf + i + 16, 2);
			test_true(reclen > 18 && i + reclen <= n);
			const char *name = (const char *)(buf + i + 18);
			/* /proc/<pid>/fd entries are symlinks → legacy
			 * d_type (last byte) must say DT_LNK, proving the
			 * repack moved it there ("." and ".." are DT_DIR). */
			size_t len = strlen(name);
			if (name[0] != '.')
				test_int_eq(buf[i + reclen - 1], DT_LNK);
			test_true(names_n + len + 2 < sizeof names);
			memcpy(names + names_n, name, len);
			names[names_n + len] = '\n';
			names_n += len + 1;
			i += reclen;
		}
	}
	names[names_n] = 0;

	for (size_t i = 0; i < tawcroot_n_reserved_fds; i++) {
		char needle[16];
		snprintf(needle, sizeof needle, "%d\n",
			 tawcroot_reserved_fds[i]);
		test_null(strstr(names, needle));
	}
	char self_needle[16];
	snprintf(self_needle, sizeof self_needle, "%d\n", pfd);
	test_nonnull(strstr(names, self_needle));

	test_int_eq(close(pfd), 0);
	th_teardown(&v);
}

/* --- legacy fd/poll-family → modern-sibling redirects ------------------ */
/* Each legacy NR is RET_TRAPped by the real emulator filter
 * (empirical audit: notes/tawcroot/status.md); the handler routes
 * it to the flags-taking modern syscall. th_sys drives the handler,
 * which issues the modern call against the real host kernel — so a
 * sane result (not -ENOSYS) proves the redirect. Every created fd is
 * closed so th_teardown's no-leak assert holds. */

test(hosted_legacy_select_routes_to_pselect6)
{
	th_view v;
	th_setup(&v, "fd-select");
	/* nfds=0 + zero timeout returns 0 immediately (no fds ready). */
	struct { long tv_sec; long tv_usec; } tv = { 0, 0 };
	test_int_eq(th_sys(TAWC_SYS_select, 0, 0, 0, 0, (long)&tv, 0), 0);
	/* Kernel select semantics survive the pselect6 redirect: an
	 * overflowing tv_usec is normalized into seconds (pselect6 alone
	 * would EINVAL the raw nsec), negative fields are EINVAL. A ready
	 * pipe keeps the normalized 1s timeout from actually elapsing. */
	int pfds[2] = { -1, -1 };
	test_int_eq(pipe(pfds), 0);
	test_int_eq(write(pfds[1], "x", 1), 1);
	fd_set rd;
	FD_ZERO(&rd);
	FD_SET(pfds[0], &rd);
	tv.tv_sec = 0; tv.tv_usec = 1000000;  /* normalizes to 1s */
	test_int_eq(th_sys(TAWC_SYS_select, pfds[0] + 1, (long)&rd, 0, 0,
			   (long)&tv, 0), 1);
	tv.tv_sec = 0; tv.tv_usec = -1;
	test_int_eq(th_sys(TAWC_SYS_select, 0, 0, 0, 0, (long)&tv, 0),
		    TAWC_EINVAL);
	test_int_eq(close(pfds[0]), 0);
	test_int_eq(close(pfds[1]), 0);
	th_teardown(&v);
}

test(hosted_legacy_pipe_routes_to_pipe2)
{
	th_view v;
	th_setup(&v, "fd-pipe");
	int fds[2] = { -1, -1 };
	test_int_eq(th_sys(TAWC_SYS_pipe, (long)fds, 0, 0, 0, 0, 0), 0);
	test_true(fds[0] >= 0 && fds[1] >= 0);
	test_int_eq(close(fds[0]), 0);
	test_int_eq(close(fds[1]), 0);
	th_teardown(&v);
}

test(hosted_legacy_eventfd_routes_to_eventfd2)
{
	th_view v;
	th_setup(&v, "fd-eventfd");
	long fd = th_sys(TAWC_SYS_eventfd, 0, 0, 0, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);
	th_teardown(&v);
}

test(hosted_legacy_signalfd_routes_to_signalfd4)
{
	th_view v;
	th_setup(&v, "fd-signalfd");
	unsigned long mask = 0;  /* empty sigset; sizemask = 8 (kernel) */
	long fd = th_sys(TAWC_SYS_signalfd, -1, (long)&mask, 8, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);
	th_teardown(&v);
}

test(hosted_legacy_epoll_create_routes_to_epoll_create1)
{
	th_view v;
	th_setup(&v, "fd-epcreate");
	/* Legacy size hint (1) is ignored by the modern call, but the
	 * kernel's size <= 0 validation must survive the redirect. */
	long fd = th_sys(TAWC_SYS_epoll_create, 1, 0, 0, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);
	test_int_eq(th_sys(TAWC_SYS_epoll_create, 0, 0, 0, 0, 0, 0),
		    TAWC_EINVAL);
	th_teardown(&v);
}

test(hosted_legacy_inotify_init_routes_to_inotify_init1)
{
	th_view v;
	th_setup(&v, "fd-inotify");
	long fd = th_sys(TAWC_SYS_inotify_init, 0, 0, 0, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);
	th_teardown(&v);
}
#endif
