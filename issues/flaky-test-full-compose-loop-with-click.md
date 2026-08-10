# Flaky: text_input::test_full_compose_loop_with_click_in_middle

Failed once in a full integration run on the x86_64 rootless emulator
(2026-08-09, during proctitle-fixes validation), then passed in
isolation and in an immediate full-suite rerun (100/100). The failing
run's panic detail was lost to output truncation, so no diagnosis —
this file exists so a future failure is recognized as a repeat, not a
regression of whatever change is being tested at the time.

The test drives an IME compose loop with a mid-compose click
(commit 3031b63), so a timing race between synthetic input and
compositor state is the obvious suspect. If it fires again, capture
the panic message and the compositor logcat before rerunning.
