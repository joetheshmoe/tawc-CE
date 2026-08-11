# RO asset binds not yet verified on a physical device

Shipped 2026-08-10: under tawcroot, `/usr/lib/{hybris,mesa-zink,gfxstream}`
are read-only binds of the `<filesDir>` asset extracts instead of
per-rootfs copies (`TawcrootMethod.assetBinds`, notes/installation.md
"Copy vs bind").

Verified on the x86_64 emulator (`.tawctarget=emulator` at the time):
manifest shrinks to ando + bashrc, stale copies wiped host-side, bound
dirs list correctly, guest writes get `EROFS`, `libvulkan_gfxstream.so`
and the mesa-zink soname chain dlopen out of the binds, `adb install -r`
→ immediate spawn re-extracts via the bind guard (no tawcroot exit 93),
full integration suite green.

Not verified: **libhybris**, which never runs on x86_64 — and it is the
production GPU path on real phones. When `.tawctarget` next points at
the phone, check:

- libhybris backend boots and GPU init works with `/usr/lib/hybris`
  bound RO (e.g. `scripts/rootfs-run.sh 'lxterminal'`), including the
  `/usr/lib/hybris-vulkan-only/libvulkan.so.1` symlink, which still
  ships as a manifest LINK and resolves *through* the bind.
- `/usr/share/glvnd/egl_vendor.d/00_libhybris.json` is still copied in
  (it must coexist with the distro's `50_mesa.json`) and EGL dispatch
  still picks libhybris.
- Writes into `/usr/lib/hybris` → `EROFS`; libhybris-zink smoke with
  `/usr/lib/mesa-zink` bound.
- Uninstall/reinstall and `adb install -r` upgrade cycles: no stale
  copies left under the bound dirs, bound trees track the APK.
