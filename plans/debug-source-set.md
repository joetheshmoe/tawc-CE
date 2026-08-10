# Keep dev/test code out of the release APK

Today every dev-only class ships in the release artifact. `R8` is off
(deliberately — see the release block in `app/build.gradle.kts`), so
`ExecBroker`, `ExecBrokerSession`, `ActionRegistry`, `InputActions`,
`SettingsActions`, `InstallActions`, `LauncherActions` and friends are
all in the release DEX. Nothing starts them: the single call site is the
`if (BuildConfig.DEBUG)` block in `TawcApplication.onCreate`. The
guarantee is therefore "one guarded branch", not "not in the artifact".

Goal: make it structural. A release APK should not contain the exec
broker or any broker action at all.

## Mechanics

AGP build-type source sets (`src/debug/java`, `src/release/java`) merge
with `src/main`. A class may live in a build-type set only if `main`
doesn't also define it. Debug/release source can see `main`; `main`
cannot see either. That gives the shape:

1. Add `me.phie.tawc.dev.DevHooks` **twice** — same FQCN, one in
   `src/debug/java`, one in `src/release/java`:

   ```kotlin
   internal object DevHooks { fun start(app: Application) { … } }   // debug: real
   internal object DevHooks { fun start(app: Application) {} }      // release: empty
   ```

   The debug body does what the `BuildConfig.DEBUG` block does now:
   `registerActivityLifecycleCallbacks(DevActivityTracker)`,
   `ExecBroker.start(app)`, then the four `registerAll()` calls.

2. `TawcApplication.onCreate` calls `DevHooks.start(this)`
   unconditionally and drops the `BuildConfig.DEBUG` branch and the
   `me.phie.tawc.dev.*` imports.

3. Move to `src/debug/java`, packages unchanged:
   - the whole `me.phie.tawc.dev` package — `ExecBroker`,
     `ExecBrokerSession`, `BrokerAction`/`ActionContext`,
     `ActionRegistry`, `InputActions`, `SettingsActions`,
     `DevActivityTracker`, `BrokerOpMirror`;
   - `install/InstallActions.kt` and `launcher/LauncherActions.kt` —
     they implement `BrokerAction` (a debug type) but call production
     install/launcher code, which is fine in that direction. Their only
     `main` referent today is the `registerAll()` calls being moved in
     step 1.

## Hooks that must stay in `main`

These read or mutate private state of production classes, so they can't
move. They become dead-by-construction once their callers are gone, but
they stay in the release DEX:

- `Settings.enterTestMode()`
- `InstallationStore.setAndoOverride` / `clearAndoOverride` /
  `clearAndoOverrides`
- `NativeBridge.serviceRefForDev()`, `NativeBridge.imeOutput`,
  `NativeBridge.nativeCloseAllClientsForTest()`
- `ClipboardBridge.setTextFromDevAction` / `getTextForDevAction`

Decide per-hook whether to leave them documented as test-only (the
`setAndoOverride` KDoc already says so) or tighten visibility. One that
probably *can* move: `RecordingImeOutput` in `compositor/ImeOutput.kt` is
a standalone implementation of the `ImeOutput` interface referenced only
from `InputActions` (plus a KDoc mention in `NativeBridge`) — check and
move it to `src/debug/java` alongside the actions, keeping `ImeOutput`
and `RealImeOutput` in `main`.

## Verification

The point of the change is an artifact-level property, so check the
artifact, not the source tree:

```
./gradlew :app:assembleRelease
apkanalyzer dex packages app/build/outputs/apk/release/app-release.apk | grep 'me\.phie\.tawc\.dev'
```

should print nothing. Worth wiring as a Gradle `check` task next to
`checkInputConnectionAudit` so it can't regress — that task is the
existing pattern for "assert a property of the build".

Also confirm `./gradlew :app:assembleDebug` still binds the broker
(`adb logcat -s tawc-exec` shows the "Listening on @me.phie.tawc.exec"
line) and that `scripts/run-integration-tests.sh` passes end to end.

## Risks / things to check first

- `src/test` unit tests compile against the debug variant
  (`testDebugUnitTest`), so moved classes stay visible to them. No
  current test references a `dev` type, but re-check before moving.
- The integration crate drives the broker over adb against debug builds
  only — unaffected.
- If a *release-type* build ever needs the broker (e.g. profiling a
  release APK on device), this closes that door; it would need a third
  build type or a product flavor. Note it in notes/exec-broker.md so the
  tradeoff is discoverable.
- Do **not** bundle a flip of `isMinifyEnabled` into this. The
  unminified release is a deliberate call documented in
  `app/build.gradle.kts`; this plan should hold regardless of it.

## Follow-ups this makes cheap

Once the broker is absent from release, notes/exec-broker.md's security
model can state "not present in release builds" instead of "never
started in release builds", which is the claim that actually matters.
