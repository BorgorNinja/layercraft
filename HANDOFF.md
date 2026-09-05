# HANDOFF.md

Read this first. It's written so another LLM (or a human) can pick up this
project cold, in one pass, without re-deriving decisions already made.

## What this project is

Android photo editor to compete with Snapseed but with real layers —
non-destructive edit stack, drag-to-reorder layers, blend modes, masks.
Snapseed's gap is the motivation: no layers, no drag-and-drop, limited
adjustment set.

**Not a GIMP port.** A GIMP-for-Android port was scoped first and rejected
(see "Rejected approach" below). This project reuses only GIMP's image
processing engine (GEGL + babl), not GIMP's UI, plug-in system, or app
architecture.

## Repo

`github.com/BorgorNinja/layercraft` — Kotlin + Jetpack Compose, Android
`minSdk 26`, `compileSdk 35`. Owner's other Android/web/game projects are
under the same `BorgorNinja` GitHub account (see that account's other repos
for house style: Kotlin/Compose for Android, PHP/MySQL for web, Phaser for
games — this project follows the Android conventions).

## Architecture (current + planned)

| Layer | Tech | Status |
|---|---|---|
| UI shell | Kotlin + Jetpack Compose | scaffolded, builds |
| Canvas/gestures | Compose `PointerInput` + `Canvas` | placeholder only — no image rendering wired up |
| Edit-stack model | Kotlin data classes (`model/Layer.kt`) | scaffolded |
| Image processing engine | GEGL + babl via JNI | **written but never compiled or run** |
| Native dependency chain | glib → json-glib → libjpeg-turbo → zlib → libpng → babl → gegl (meson + CMake), cross-compiled for Android | **in active iteration, not green yet** |
| GPU preview | not started | — |
| File I/O / project format | not started | — |

## Why GEGL/babl

GEGL is a node-graph, non-destructive image processing library — it
already models "layers as an adjustable stack," which is the core feature
gap vs. Snapseed. It and babl (pixel format conversion) are portable C
with no GTK dependency, unlike the rest of GIMP. This is why they're
extractable even though a full GIMP port isn't feasible.

## Rejected approach: full GIMP-for-Android port

Studied by forking `GNOME/gimp` (mirrored to `BorgorNinja/gimp`). Findings:

- 1.27M LOC, GTK+3-bound UI (423 files call `gtk_*` directly) — no Android
  GTK backend exists, and there's no incremental path off GTK.
- 37 plug-ins ship as separate executable binaries, communicating over
  pipes via GIMP's PDB (procedure database) IPC. Conflicts with Android's
  process sandboxing model.
- Verdict: not portable. GEGL/babl (the processing core) *are* portable
  and became the basis for this project instead.

Don't re-litigate this — it's a closed question. If asked "why not just
port GIMP," point here.

## The hard constraint that shapes everything below

**The sandbox this was built in cannot reach:**
- `dl.google.com` (Android NDK download)
- `gitlab.gnome.org` / `download.gnome.org` (glib/babl/gegl canonical source)

It *can* reach `github.com`/`api.github.com`/`codeload.github.com`, and
GNOME mirrors its repos there (`GNOME/glib`, `GNOME/babl`, `GNOME/gegl`,
`GNOME/json-glib` all confirmed to exist as read-only GitHub mirrors).

**Consequence: nothing involving the NDK or actually compiling the native
chain has been run or validated.** It was all written correctly per the
respective tools' documented APIs/flags, but "written correctly" and
"compiles" are different claims. GitHub Actions runners have full internet
+ a preinstalled NDK (`ANDROID_NDK_LATEST_HOME` env var), so that's where
this gets tested — not in whatever sandbox is reading this file, most
likely, unless that sandbox's network access has changed.

**If you're an LLM picking this up: check your own network access before
assuming you're equally constrained.** If you can reach `gitlab.gnome.org`
directly, or have an NDK available locally, you may be able to validate
things this session couldn't.

## Known risk areas (ranked by likelihood of breaking first)

1. **glib's Android meson flags** (`scripts/build-native-deps.sh`):
   `-Dlibmount=disabled -Dselinux=disabled -Dxattr=false -Dnls=disabled`
   are an educated guess for bionic libc, not verified against a real
   build log.
2. ~~**iconv on API 26**~~ — found & fixed on first real CI run: glib's
   meson build failed with `Dependency "iconv" not found`. Fixed by
   bumping `minSdk`/build API to 28 (bionic's native iconv floor), not yet
   confirmed by a follow-up run.
3. **GEGL optional deps**: `-Dcairo=disabled` in the build script is a
   guess that core raster ops don't need Cairo. Not checked against
   GEGL's actual `meson_options.txt` for hard requirements.
4. **`applyOp`'s param marshalling** (`native-engine.cpp`): every GEGL op
   property is set as `gdouble` via `gegl_node_set`. Works for
   float-valued props (blur radius, most curve/level params). Will
   silently fail or crash for enum/string/array-typed `GParamSpec`s —
   no type introspection is done. Needs per-op typed paths as more ops
   get added.
5. **Node/graph lifecycle**: `releaseNode` only erases a handle-table
   entry, doesn't do real `GeglNode`/graph refcount teardown. Will leak
   across a real session. Needs a proper "release whole graph from root"
   call before this goes past alpha.
6. **APK signing**: alpha builds use Gradle's auto-generated debug
   keystore. Sideload-only. Not Play-Store-eligible as-is.

## File map (what to read, in order, to get oriented)

1. `README.md` — architecture table, roadmap, same risk list as above (kept
   in sync — if you update one, update both).
2. `app/src/main/java/.../model/Layer.kt` — the data model. Start here to
   understand what a "layer" and "edit stack" mean in this codebase.
3. `app/src/main/java/.../ui/EditorScreen.kt` — Compose UI. Canvas is a
   placeholder; layer panel has real drag-to-reorder logic.
4. `app/src/main/java/.../engine/NativeEngine.kt` — the Kotlin↔JNI
   contract. Four functions: `createImageNode`, `applyOp`,
   `renderToBuffer`, `releaseNode`. This is the entire native API surface
   by design — don't expand it by binding more of GEGL's API directly
   into Kotlin; keep the graph-building logic on the native side.
5. `app/src/main/cpp/native-engine.cpp` — JNI impl. Two build modes via
   `#ifdef LAYERCRAFT_HAVE_GEGL`: real GEGL calls, or a stub that lets the
   Compose UI build/run standalone without the native chain built.
6. `app/src/main/cpp/CMakeLists.txt` — links against
   `-DNATIVE_DEPS_PREFIX=<path>` if provided (CI passes this), falls back
   to stub-only build otherwise.
7. `scripts/gen-cross-file.sh` + `scripts/build-native-deps.sh` — the
   cross-compile pipeline. Read the comments at the top of each; they
   state explicitly what's untested and why.
8. `.github/workflows/native-libs.yml` — manual-trigger standalone
   validator for the native build. **Run this first** before trusting
   `release-alpha.yml` to produce a real GEGL-linked APK.
9. `.github/workflows/release-alpha.yml` — full pipeline: native build →
   APK → GitHub prerelease. Degrades gracefully: if native build fails, it
   still ships a stub-engine (UI-only) APK rather than blocking release.

## CI run log (append new entries here, most recent first)

- **`v0.1.3-alpha` crashed on device** (person report, no logs available):
  screen showed "Running engine diagnostic..." then the app crashed --
  meaning the crash happened somewhere inside the
  `createImageNode`/`applyOp`/`renderToBuffer` round trip, not before it
  (that text only appears after `NativeEngine` loads and `initEngine()`
  already succeeded, per the `v0.1.2-alpha` confirmation). A Kotlin-side
  `runCatching` can't intercept a native crash (SIGSEGV), so this is
  consistent with a crash inside the JNI/GEGL call chain itself, not a
  JVM exception. No logcat access available to see a native backtrace.
  Two changes made in response, both unconfirmed:
  1. Moved the diagnostic off `Dispatchers.Default` (background thread
     pool) onto the main thread (matching the thread `gegl_init()`
     already ran on successfully in `MainActivity.onCreate`) -- a
     plausible but unconfirmed hypothesis, not a diagnosed root cause.
  2. Restructured into checkpoints: update the on-screen text (with a
     forced `withFrameNanos` + short `delay` to make sure it actually
     renders) *before* each risky native call, not just once at the end.
     This means if it crashes again, whatever text is last visible on
     screen pins down which specific call (`engineStatus`,
     `createImageNode`, `applyOp`, or `renderToBuffer`) crashed --
     narrows the search without needing logcat, though logcat would
     still be far more informative (an actual backtrace) if ever
     available.
- **First real device confirmation** (person installed `v0.1.2-alpha` and
  reported back, no CI run — this is the actual milestone this whole CI
  loop was building toward): app **launches successfully** and renders
  the full Compose UI (layer panel, drag handles, opacity sliders,
  visibility toggles) with no crash. This is a strong signal, not just a
  "looks fine" — `NativeEngine`'s `init` block runs
  `System.loadLibrary("layercraft_engine")`, and `MainActivity.onCreate`
  calls `NativeEngine.initEngine(...)` before anything else renders.
  Android resolves every `DT_NEEDED` shared-library dependency at
  `dlopen` time, not lazily — if even one of the 69 bundled `.so` files
  were missing or ABI-mismatched, this would have crashed immediately
  with `UnsatisfiedLinkError` instead of rendering. It didn't, which
  means: the jniLibs packaging fixes (both of them) worked at the actual
  OS level, and `initEngine()`'s `setenv()` + `gegl_init()` calls ran
  without crashing. **What this does NOT confirm**: whether
  `GEGL_PATH` scanning actually registered any operations, since nothing
  in the current UI calls `engineStatus()`/`createImageNode`/`applyOp` —
  the canvas is still the static placeholder from the original scaffold.
  Follow-up: added a temporary diagnostic to `CanvasPreview` that runs a
  real `createImageNode` → `applyOp("gegl:gaussian-blur")` →
  `renderToBuffer` round trip and displays the result text directly on
  the canvas (chosen over asking for `adb logcat` output, since the
  person may not have that readily available — this way it's just
  visible on screen after reinstalling). Not yet released or confirmed.
- **`v0.1.2-alpha` confirms the recursive-staging half of the fix**
  (run `33962618497`): unzipped the released APK — 69 `.so` files now
  present (up from 20 in `v0.1.1-alpha`), including all the previously
  missing GEGL operation bundles: `gegl-core.so`, `gegl-common.so`,
  `gegl-common-cxx.so`, `gegl-common-gpl3.so`, `gegl-generated.so`,
  `gegl-fixups.so` (no `lib` prefix on any of these — matches GEGL's
  `name_prefix: ''` build config, confirming they really did come from
  the `gegl-0.4/` subdirectory the recursive `find` was meant to catch,
  not just re-finding the same flat-libdir files as before). APK grew
  from ~57MB to 68.8MB, consistent with the added bundles.
  **What this does NOT confirm**: whether `GEGL_PATH`/`BABL_PATH` are
  actually picked up correctly at runtime, whether `gegl_init()` finds
  and successfully loads these bundles via `dlopen`, or whether mixing
  prefixed (`libgegl-0.4.so`) and non-prefixed (`gegl-core.so`) names in
  one flat directory confuses GEGL's own module-discovery logic in any
  way. Static inspection has done everything it usefully can here —
  confirming the actual point of this fix requires a device or emulator.
- **GEGL_PATH/BABL_PATH fix pushed** (commit `9561977`, no CI run yet):
  after confirming `v0.1.1-alpha` fixed the first packaging gap (below),
  read `gegl/operations/core/meson.build` directly and found a second,
  deeper problem: GEGL installs its actual operations (crop, blend modes,
  blur, etc.) as `shared_module` bundles under `$libdir/gegl-0.4/` — a
  subdirectory the `-maxdepth 1` jniLibs staging never scanned. Even with
  that fixed, Android's APK format has no subdirectory structure under
  `lib/<abi>/`, so GEGL's runtime plugin loader (confirmed via GEGL's own
  `docs/environment.adoc`: `GEGL_PATH` is "the directory where GEGL looks
  (recursively) for dynamically loadable operation libraries"; `BABL_PATH`
  is babl's equivalent) would have nothing to point at even with the
  files present. Fixed: recursive staging in `build-native-deps.sh`, plus
  a new explicit `NativeEngine.initEngine(nativeLibDir)` that sets both
  env vars via `setenv()` before `gegl_init()` runs, called once from
  `MainActivity.onCreate` with `applicationInfo.nativeLibraryDir`.
  **Genuinely can't be confirmed by static APK inspection alone this
  time** — whether `GEGL_PATH` scanning actually registers operations at
  runtime is real device/emulator behavior, not something `unzip -l` can
  show. Next CI run should at least confirm the plugin `.so` files are
  now present in the APK; confirming they're *found* needs a device.
- **`v0.1.1-alpha` fix confirmed** (run `33933122938`): the jniLibs
  staging + Gradle `sourceSets` wiring from the previous entry worked —
  unzipped the resulting APK and found 20 `.so` files in `lib/arm64-v8a/`,
  including everything previously missing (glib, gegl, babl, json-glib,
  libjpeg, libpng, zlib, gio, gobject, gmodule, gthread, girepository,
  libffi, libintl, plus `libgegl-npd-0.4.so`/`libgegl-sc-0.4.so` — two
  GEGL plugins that happened to install to the flat libdir rather than
  the `gegl-0.4/` subdir most operations use, which is what led to
  checking that subdirectory question in the entry above).
- **Post-run-4 static analysis** (no new CI run, `v0.1.0-alpha` APK
  inspected directly): downloaded the released APK and unzipped it —
  `lib/arm64-v8a/` contained only `liblayercraft_engine.so` (what CMake
  itself builds) plus `libc++_shared.so` and an unrelated androidx lib.
  **None of glib/gegl/babl/json-glib/libjpeg-turbo/zlib/libpng were
  present.** Root cause: Gradle's CMake integration packages what its own
  build produces, not the external shared libraries it happened to link
  against from `NATIVE_DEPS_PREFIX` — those were resolved at link time
  from a path outside the project and never copied into the APK. This
  would have failed at runtime with `UnsatisfiedLinkError` on missing
  `DT_NEEDED` entries the moment `System.loadLibrary("layercraft_engine")`
  ran, without needing a device to discover it. Fixed by: (1)
  `build-native-deps.sh` now stages every `*.so*` (symlinks dereferenced
  via `cp -L`, since meson/libtool installs are a symlink chain like
  `libfoo.so -> libfoo.so.0 -> libfoo.so.0.0.0` and Android's linker needs
  real files under each name) into `$PREFIX/jniLibs/$ABI/`; (2)
  `app/build.gradle.kts` now points `sourceSets.main.jniLibs.srcDirs` at
  that directory when `nativeDepsPrefix` is set, so Gradle bundles them
  into the APK alongside the CMake-built engine lib. **Not yet confirmed
  by a CI run** — this needs a fresh `release-alpha.yml` dispatch and
  another APK-contents inspection to verify all the expected `.so` files
  actually show up in `lib/arm64-v8a/` this time.
- **release-alpha run 4** (`33849565342`): **SUCCESS, both jobs.**
  Compose Compiler plugin fix cleared the Gradle configuration error.
  `native-engine.cpp`'s real GEGL JNI code compiled and linked against the
  cross-compiled prefix with no further changes needed. APK built
  (57.4MB, `arm64-v8a` only, debug-signed) and published:
  [`v0.1.0-alpha`](https://github.com/BorgorNinja/layercraft/releases/tag/v0.1.0-alpha).
  **This is "linked" not "verified"** — nobody has installed this APK on
  a device or emulator yet. `createImageNode`/`applyOp`/`renderToBuffer`
  compiling successfully says nothing about whether they produce correct
  output, crash on first call, or work at all at runtime — that's
  genuinely unknown until someone runs it. See "Immediate next step."
- **release-alpha run 3** (`33849222581`): native-deps job succeeded again
  (chain is reproducible). `build-apk` job reached the actual Gradle
  build for the first time and failed -- but on something unrelated to
  the native chain entirely: `org.jetbrains.kotlin.plugin.compose` Gradle
  plugin is required as of Kotlin 2.0+ when Compose is enabled (it used to
  be bundled into the Kotlin Android plugin; that changed and this
  scaffold predated the change). Fixed by adding the plugin to both
  `build.gradle.kts` (root, `apply false`) and `app/build.gradle.kts`, and
  removing the now-superseded `composeOptions { kotlinCompilerExtensionVersion }`
  block. Not yet confirmed by a follow-up run. Two earlier release-alpha
  attempts (runs 1-2, IDs `33848253492`/`33848787148`) failed before
  reaching this point on Gradle-wrapper-bootstrap issues -- see below.
- **release-alpha run 2** (`33848787148`): `gradle wrapper` step still
  failed even after adding `gradle/actions/setup-gradle@v4` to provision a
  `gradle` binary -- but the failure wasn't captured by the log-commit
  step, because that step ran *before* the log-capture step existed in
  the pipeline (wrapper-bootstrap was a separate, unwrapped step). Fixed
  by merging wrapper-bootstrap into the same logged/`continue-on-error`
  block as the actual Gradle build, so any failure in either is captured.
- **release-alpha run 1** (`33848253492`): `Bootstrap Gradle wrapper`
  step failed (`gradle: command not found` -- ubuntu-latest runners don't
  guarantee a system `gradle` binary). Also found a real bug while
  debugging: the `mark` step used `${{ job.status == 'success' }}`, but
  `job.status` isn't a valid context mid-job -- it always evaluated to
  `succeeded=false` regardless of the native build's actual result, which
  would have made every release ship the stub-engine APK even after the
  native chain went green. Fixed by referencing
  `steps.build_native.outcome` instead (a real, valid context).
- **Run 6** (`33825293060`, api_level=28): **SUCCESS.** Full chain (glib →
  json-glib → libjpeg-turbo → zlib → libpng → babl → gegl) cross-compiled
  cleanly for `arm64-v8a`. `native-prefix-arm64-v8a` artifact uploaded.
  This is the first fully green native build — six runs to get here (see
  runs 1-5 below for the debugging arc). Not yet confirmed: whether
  `native-engine.cpp`'s actual GEGL JNI calls compile/link against this
  prefix, or whether the resulting `.so` actually loads and runs on a
  device — those are the next checks, via `release-alpha.yml`.
- **Run 5** (`33824890039`, api_level=28): confirmed run 4's iconv fix —
  glib and json-glib now build and link cleanly (found via pkg-config).
  New failure: `gegl/meson.build:387` hard-requires `libjpeg`/
  `libjpeg-turbo` — confirmed by reading the file directly (no
  `required: false`, no wrap `fallback:`, unlike e.g. `poly2tri-c`/
  `libnsgif` a few lines below which do self-provide via wrap). Fix:
  added libjpeg-turbo + libpng to the chain via CMake (NDK's own
  toolchain file). Re-run hit a second issue in the same area: libpng's
  generated `.pc` requires `zlib`, and while bionic/NDK ships `libz.so` +
  `zlib.h`, there's no `zlib.pc` for pkg-config to resolve it. Fix: added
  `madler/zlib` to the chain too (built via CMake, same prefix) rather
  than hand-writing a `.pc` pointing at NDK sysroot paths. **Not yet
  confirmed** — next run tests both the jpeg/png addition and the zlib
  fix together.
- **Run 4** (`33824560762`, api_level=28): confirmed the iconv fix works
  (`Run-time dependency iconv found: YES`). Failed later at
  `gegl/meson.build:387` — see run 5 above, this is where libjpeg-turbo
  was first identified as missing.
- **Run 3** (`33753889868`, api_level=28): failed, but not on iconv or
  anything in the actual build — the workflow run used a version of
  `native-libs.yml` missing the log-capture steps entirely (confirmed via
  step list: no "Commit build log" step present). Root cause: the commit
  that applied the API-28 fix read `native-libs.yml` via
  `raw.githubusercontent.com` immediately after the prior commit, got a
  stale CDN-cached pre-log-capture copy, edited that, and pushed it —
  silently reverting the log-capture steps while keeping the API-28
  change. **No new information about whether the iconv fix actually
  works** — this run never got far enough to tell us, its log commit step
  didn't exist to capture anything anyway. Fixed by re-reading via the
  Contents API (uncached) and reapplying the log-capture steps on top of
  the correct current content. See "Conventions worth preserving" below
  for the rule this violates.
- **Run 2** (`33736861080`, api_level=26): glib meson build reached actual
  compilation, failed with `Dependency "iconv" not found (tried builtin
  and system)` at `glib/meson.build:2248`. Root cause: bionic's native
  iconv symbols only exist from API 28+. Fix: bumped `minSdk` and the
  workflows' default `api_level` from 26 to 28 (`app/build.gradle.kts`,
  both workflow YAML files). **Not yet confirmed** — next run is the
  check. Other errors earlier in the same log (`pthread_attr_setinheritsched`,
  `pthread_cond_timedwait_relative_np`, `pthread_getaffinity_np`,
  `winsock2.h`) are meson's normal feature-probe failures, not blockers —
  glib disables the corresponding optional code paths when a probe fails,
  it doesn't abort the build. Only the explicit `ERROR:` line ends the run.
- **Run 1** (`33736614984`): failed before reaching glib's own build --
  Actions log storage (`productionresultssa17.blob.core.windows.net`) is
  outside this environment's network allowlist, so the failure reason
  wasn't directly visible. Fix: added a step to `native-libs.yml` that
  tees build output to `build.log` and commits it to an orphan `ci-logs`
  branch on failure, readable via `raw.githubusercontent.com`. This is
  the log-retrieval path for all subsequent runs too.

**How to read a new failure**: fetch
`https://raw.githubusercontent.com/BorgorNinja/layercraft/ci-logs/ci-logs/native-libs-<run_id>-<abi>.log`
(the run ID is in the workflow run URL). `grep -n -iE "error|ERROR:"` it —
the meson build produces a lot of noisy non-fatal probe failures; the
actual blocker is almost always the last `ERROR:`-prefixed line near a
`meson.build:LINE:COL:` reference, right before the build aborts.

## Immediate next step

`v0.1.3-alpha` crashed (see CI run log entry above) somewhere inside the
`createImageNode`/`applyOp`/`renderToBuffer` chain, with no logcat access
to pin down exactly where or why. Released `v0.1.4-alpha` with two
changes: main-thread execution instead of a background thread pool, and
checkpointed on-screen progress text so the last-visible line narrows
down which call crashed even without logs. Have the person install it
and report exactly what text is on screen when/if it crashes (or if it
doesn't crash, what the final result says).

**If logcat ever becomes available** (person has/gets access to `adb`,
even wirelessly), that would be far more valuable than any further
guess-and-release cycle — a native SIGSEGV backtrace would show the exact
function and likely the exact cause, rather than needing another
multi-minute CI round trip to test each hypothesis blind.

Three possible outcomes to watch for once that report comes back:
- **"FULL ROUND TRIP OK... returned 64 bytes"** — GEGL_PATH worked,
  gaussian-blur was found and ran. This would be the real green light to
  start roadmap item 3 (wire the actual canvas to render buffer output).
- **"applyOp(gegl:gaussian-blur) FAILED"** — operation wasn't found;
  GEGL_PATH scanning didn't work as expected, needs its own debugging
  round (possible causes: `nativeLibraryDir` path wrong/inaccessible,
  GEGL's module loader expecting a naming convention the flattened
  bundle files don't match, `dlopen` restrictions on the directory).
- **"ENGINE DIAGNOSTIC CRASHED/THREW"** — something segfaults or throws;
  worth getting logcat output at that point since a Kotlin-side
  `runCatching` can't catch a native segfault (only JVM exceptions), so
  this outcome might actually still crash the app rather than showing
  text — if so, that itself is the signal, and logcat becomes necessary
  since Compose can't display text after a native crash.

## Longer-term roadmap (after native build is green)

See `README.md` roadmap section — it's kept current there, not duplicated
here to avoid drift. Summary of what's *not* started at all yet: canvas
rendering of GEGL output into Compose, layer-model-to-GEGL-graph wiring
(`BlendMode.toGeglOpName()` exists but nothing calls it), file I/O / project
format, and the full feature-parity pass (selections, masks, healing,
perspective, text layers).

## Conventions worth preserving

- Atomic multi-file commits via the Git Trees API (not sequential Contents
  API PUTs) — used for every commit in this repo's history so far.
- GitHub PATs used in this account are ephemeral (rotated ~per session) —
  don't assume a token from an earlier conversation still works; validate
  with `GET /user` before relying on it.
- File SHAs for Contents API writes go stale fast — refetch immediately
  before each write if not using the Trees API.
- **`raw.githubusercontent.com` is CDN-cached (observed ~minutes of lag)
  — don't use it to read "current" file content immediately after a
  commit, you can silently get a stale copy and then commit an edit on
  top of it, reverting the prior change.** This actually happened once in
  this repo's history (see CI run log below: the API-28 fix was applied
  on top of a stale copy and briefly reverted the log-capture steps).
  Use the Contents API (`GET /repos/{repo}/contents/{path}?ref=main`,
  base64-decode) instead when you need to read back what's actually on
  the branch right now.
- When bulk-renaming identifiers (not currently relevant here, but a
  pattern used elsewhere in this account's repos): sort replacements
  longest-first to avoid partial-match corruption.
