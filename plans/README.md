# Plans Index

Future work and speculative implementation plans live here. Keep current-state
design, build, and operational notes in [`../notes/`](../notes/).

- [usecase_tests/](usecase_tests/README.md) - one-shot agent-run usecase test plans; see that README for the procedure.
- [ando-am.md](ando-am.md) - termux-am-style in-app `am` behind broker interception of `ando am`, so unrooted guests can launch activities/broadcasts/services.
- [audio.md](audio.md) - planned PipeWire/PulseAudio bridge to Android audio.
- [bounded-icon-cache.md](bounded-icon-cache.md) - replace `IconLoader`'s unbounded bitmap map with a byte-bounded LruCache.
- [clipboard-paste-focus-gate.md](clipboard-paste-focus-gate.md) - only serve the Android clipboard to the focused client; today any X11 client on the shared Xwayland can read it.
- [compositor-output-at-startup.md](compositor-output-at-startup.md) - advertise `wl_output` at compositor start instead of at the first Activity surface, so clients that need a display at init (all of SDL, Xwayland's root window) don't die on a cold compositor.
- [debug-source-set.md](debug-source-set.md) - move the exec broker and broker actions into `src/debug/java` so they are absent from the release APK, not merely unstarted.
- [gfxstream-bridge-remaining-work.md](gfxstream-bridge-remaining-work.md) - remaining GL/GLES and x86_64 AVD work for the gfxstream bridge backend.
- [gl-on-gles-translator.md](gl-on-gles-translator.md) - possible in-house GL 3.3-core-on-ES 3.2 translator (glslang/SPIRV-Cross shader pipeline) for the modern-GL gap zink can't cover on Vulkan 1.1 devices.
- [tawcroot-default-binds-ro.md](tawcroot-default-binds-ro.md) - make tawcroot's built-in binds read-only where the guest never writes (system partitions; app-asset copy→bind revert).
- [tawcroot-landlock.md](tawcroot-landlock.md) - kernel-enforced path containment for tawcroot via Landlock (probe-and-enable, kernel 5.13+).
- [x86-box64.md](x86-box64.md) - per-program x86_64 emulation in existing arm64 distros via box64 (native-library wrapping; 16K-page hedge).
- [x86-fex-glibc.md](x86-fex-glibc.md) - FEX installed inside an arm64 glibc distro; x86_64 RootFS; tawcroot exec dispatch; whole-x86-distro stretch.
- [x86-fex-bionic.md](x86-fex-bionic.md) - speculative bionic-built FEX with no glibc layer, thunking straight to NDK graphics; gated on the glibc FEX plan.
