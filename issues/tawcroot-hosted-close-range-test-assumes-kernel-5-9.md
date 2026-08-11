# `hosted_close_range_keeps_reserved_fds_alive` fails on kernels without close_range

`tawcroot/test.sh --device` fails one test on the OnePlus 9 (Android 14,
kernel 5.4):

    hosted_close_range_keeps_reserved_fds_alive .......... oh no :(
      tawcroot/tests/hosted/test_fd_handlers.c:212:
        th_sys_impl(..., 436 /* close_range */, 1000, ~0U, ...)
        expected: 0   actual: -38   (ENOSYS)
      tawcroot/tests/hosted/test_fd_handlers.c:215: fstat(guest_hi, &st)
        expected: -1  actual: 0
      tawcroot/tests/hosted/hosted.c:159: count_fds()
        expected: v->fds_before   actual: 7

`close_range(2)` landed in Linux 5.9; this device is older, so the raw
syscall the hosted shim issues returns `ENOSYS`, nothing is closed, and
the leak check at teardown trips too. The `androidfilter` layer's
close_range cases already tolerate this — they print
`(kernel <5.9: close_range not available)` and pass — so the fix is
presumably the same skip/tolerate treatment in the hosted test (or an
`ENOSYS`-aware assertion).

Reproducible in isolation:

    tawcroot/test.sh --device --no-build '.*close_range.*'
    → 14 tests passed, 1 test failed

Not a regression: found while validating the `/linkerconfig` copy change
(2026-08-10), which touches nothing in this area. Host-mode runs are
unaffected (dev box kernel is new enough).
