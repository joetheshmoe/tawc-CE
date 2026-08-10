# Bound the launcher icon cache

`IconLoader.cache` is a plain `HashMap<String, Bitmap>` with no eviction
(`launcher/IconLoader.kt`). Every distinct icon path decoded during a
launcher session is retained for the loader's lifetime.

Scope of the problem: the loader instance is owned by
`LauncherActivity`, so the map dies with the activity — this is growth
within one session, not a leak across the app's life. But the bound is
"number of `.desktop` entries with a PNG icon in the rootfs", which on a
full desktop distro is easily several hundred. Each is decoded to
roughly `sizePx²` at ARGB_8888 (`inSampleSize` lands it between 1× and
2× the target), so a few hundred rows is single-digit MB — enough to
matter on a low-memory phone, and it grows with the distro rather than
with anything the app controls.

## Change

Swap the map for `android.util.LruCache<String, Bitmap>` with a
byte-based bound:

- `sizeOf(key, value) = value.allocationByteCount`
- budget derived from the process heap rather than hardcoded — e.g. a
  fraction of `ActivityManager.memoryClass`, the standard idiom — with a
  floor generous enough that a normal screenful never thrashes.

`LruCache` is internally synchronized, which is harmless; today all
`cache` access is on the main thread (the coroutine resumes there after
the `Dispatchers.IO` decode), so no correctness change either way.

## Leave alone

- `EntryShortcuts.pinBitmap` calls the static `IconLoader.decode`
  directly and bypasses the cache. That is correct — pin icons are
  one-shot, at a different size, and shouldn't evict launcher rows.
- The `ImageView.tag` staleness check in `load()`. Unrelated and already
  right.

## Verification

Open the launcher on a distro with a large `applications` dir, scroll
the full list a few times, and confirm heap stays flat via
`adb shell dumpsys meminfo me.phie.tawc` — today it climbs once per
newly-seen icon and never comes back down.
