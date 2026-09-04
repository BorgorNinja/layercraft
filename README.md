# LayerCraft

> **Picking this up fresh, or handing it to another LLM/session?** Read
> [`HANDOFF.md`](./HANDOFF.md) first — it has the full context, decisions
> already made, and what's untested vs. verified. This README covers the
> same ground but assumes less context.

Non-destructive, layer-based photo editor for Android. Built to cover what
Snapseed lacks: real layers, drag-and-drop reordering, blend modes, masks,
and a non-destructive edit graph.

## Architecture

| Layer | Tech | Status |
|---|---|---|
| UI shell | Kotlin + Jetpack Compose | scaffolded |
| Canvas / gestures (drag-drop, pinch, reorder) | Compose `PointerInput` + custom `Canvas` | scaffolded |
| Edit-stack model | Kotlin data classes (`model/`) | scaffolded |
| Image processing engine | GEGL + babl via JNI (NDK cross-compiled) | **compiles + links (`v0.1.0-alpha`) — runtime unverified** |
| GPU preview | `RenderEffect` (API 31+) fallback, GLES later | not started |
| File I/O | Android `BitmapFactory` / `ImageDecoder` now; libjpeg-turbo/skia-codec later | not started |

## Current state (this commit)

This is a **scaffold with a working native build, not a working editor**:

- Compose UI shell: layer panel (drag-to-reorder via `LazyColumn`), canvas
  placeholder, basic edit-stack model (`Layer`, `LayerType`, `BlendMode`).
- JNI bridge (`NativeEngine.kt` + `native-engine.cpp`) now links against a
  real cross-compiled GEGL/babl (see "Native dependency chain" below) —
  confirmed by [`v0.1.0-alpha`](https://github.com/BorgorNinja/layercraft/releases/tag/v0.1.0-alpha)
  building successfully. **This means it compiles and links, not that it
  works** — no one has run the APK yet, so whether `System.loadLibrary`
  succeeds at runtime or the JNI calls produce correct output is unknown.
- No image loading/compositing pipeline wired up yet — the Compose canvas
  doesn't call into the native engine at all (see roadmap item 3).

## Why GEGL/babl and not a from-scratch engine

GEGL already models a non-destructive node graph (crop, curves, levels, blur,
etc. as composable nodes) — this is structurally what "layers + adjustable
stack" requires, so we don't want to reinvent it. Both are portable C with no
GTK dependency, unlike the rest of GIMP. See commit history / project notes
for the feasibility study this was based on (GIMP itself is GTK+3-bound and
not portable to Android; GEGL+babl are the extractable, portable core).

## Roadmap

1. ~~**NDK toolchain for GEGL/babl**~~ — **done**: the full chain (glib →
   json-glib → libjpeg-turbo → zlib → libpng → babl → gegl) cross-compiles
   cleanly for `arm64-v8a`/API 28, confirmed reproducible across 3 CI
   runs. Took 6 CI iterations (see HANDOFF.md CI run log for the full
   debugging arc: log-storage access, iconv/API-level, gegl's undeclared
   hard deps on libjpeg-turbo/libpng, libpng's zlib.pc gap).
2. ~~**JNI surface**~~ — `createImageNode`, `applyOp`, `renderToBuffer`,
   `releaseNode` implemented and **confirmed compiling + linking**
   against the real GEGL prefix as of `v0.1.0-alpha`. **Not yet verified
   at runtime** — nobody has installed the APK and confirmed
   `System.loadLibrary` succeeds or that any JNI call produces correct
   output. Typed param handling beyond `gdouble` still needed for ops
   with non-numeric properties (see risk areas).
3. **Compose canvas wired to native preview buffer** — render GEGL output
   into a `Bitmap`/`SurfaceTexture` per edit-stack change. Not started —
   `CanvasPreview` in `EditorScreen.kt` is still a placeholder.
4. **Layer model → GEGL graph mapping** — `BlendMode.toGeglOpName()` exists
   but nothing calls it yet; the Compose layer stack and the native graph
   aren't connected.
5. **File formats** — import via standard Android decoders; export flattened
   JPEG/PNG; own `.lcraft` project format for layer state (JSON manifest +
   per-layer raster/adjustment data). Not started.
6. **Feature parity pass** — selections, masks, healing, perspective, text
   layers, curves/levels UI. Not started.
7. ~~**Alpha APK release**~~ — **done**: pipeline (`release-alpha.yml`)
   produced [`v0.1.0-alpha`](https://github.com/BorgorNinja/layercraft/releases/tag/v0.1.0-alpha),
   57.4MB, `arm64-v8a`, debug-signed, GEGL linked. Took 4 iterations on
   top of item 1 (invalid `job.status` CI context, missing system Gradle
   binary, Kotlin 2.0 Compose Compiler plugin requirement — see
   HANDOFF.md CI run log). Degrades to a stub-engine (UI-only) APK if the
   native-deps job fails, so future releases aren't blocked on the native
   chain compiling cleanly every time.

## Native dependency chain (GEGL/babl for Android)

`scripts/build-native-deps.sh` cross-compiles, in order: **glib** (which
pulls libffi + pcre2 as meson wrap subprojects) → **json-glib** →
**libjpeg-turbo** → **zlib** → **libpng** → **babl** → **gegl**, targeting
a single Android ABI via a generated meson cross-file
(`scripts/gen-cross-file.sh`) for the meson-based projects, and the NDK's
own CMake toolchain file for libjpeg-turbo/zlib/libpng (all three build via
CMake upstream, not meson).

libjpeg-turbo and libpng were added after confirming, by reading
gegl/meson.build directly, that gegl hard-requires them (`dependency(...)`
calls with no `required: false` and no meson-wrap `fallback:`, unlike e.g.
`poly2tri-c`/`libnsgif` which self-provide via wrap subprojects). zlib was
added after that: libpng's own generated `.pc` file requires `zlib`, and
while the NDK sysroot ships `libz.so`/`zlib.h`, it has no `zlib.pc` for
pkg-config to resolve — cross-compiling zlib into the same prefix was more
robust than hand-writing a `.pc` pointing at NDK-sysroot paths that could
drift across NDK versions.

This cannot run in a sandbox without the Android NDK and without network
access to `gitlab.gnome.org`/GitHub mirrors — both are unavailable in the
environment this was developed in. It runs in CI instead:

- `.github/workflows/native-libs.yml` — standalone validator, manual
  trigger, just builds and uploads the native prefix as an artifact. Use
  this to iterate on cross-compile flags without cutting a release.
- `.github/workflows/release-alpha.yml` — full pipeline: native build →
  APK build (linked against GEGL if the native step succeeded, stub
  engine otherwise) → GitHub Release (prerelease, debug-signed APK
  attached).

### Known risk areas (untested — first CI run will likely surface issues here)

- **glib flags for bionic**: `-Dlibmount=disabled -Dselinux=disabled
  -Dxattr=false -Dnls=disabled` are a starting guess for what Android's
  libc doesn't support. May need adjustment.
- ~~**iconv on API 26**~~ — **found & fixed**: glib's meson build failed
  with `Dependency "iconv" not found (tried builtin and system)` on the
  first real CI run at API 26. Bionic only gained native iconv symbols at
  API 28. Fixed by bumping `minSdk`/build API to 28 rather than pulling in
  a libiconv wrap (simpler, and 28 is a low enough floor to not matter for
  a new app). Unverified until the next CI run confirms it clears this
  specific error — other bionic feature-probe failures further down the
  same log (`pthread_attr_setinheritsched`, `pthread_cond_timedwait_relative_np`,
  `pthread_getaffinity_np`) look like normal non-fatal feature detection,
  not blockers, but that's not confirmed yet either.
- **GEGL optional deps**: `-Dcairo=disabled` skips ops needing Cairo
  (e.g. some text/vector rendering); core raster ops shouldn't need it,
  but this hasn't been verified against GEGL's actual `meson_options.txt`.
- **`applyOp`'s generic param path** (`native-engine.cpp`): every op
  property is set as a `gdouble`. This works for most adjustment ops
  (blur radius, curves control points as floats) but will fail for ops
  with enum/string/array-typed `GParamSpec`s. Expect to add typed
  overloads per-op as the feature set grows.
- **GEGL node lifecycle**: `releaseNode` currently only drops the handle
  bookkeeping entry, not proper `GeglNode`/graph refcount teardown. Fine
  for a short-lived preview session, will leak across a real editing
  session — needs a real graph-teardown pass before this ships past
  alpha.
- **APK signing**: alpha releases are Gradle's auto-generated debug
  keystore. Fine for sideloading/testing, **not** suitable for Play
  Store or any signed-release channel.

## Build

Standard Android Gradle project. Open in Android Studio or:

```
./gradlew assembleDebug
```

Native module (`app/src/main/cpp`) currently builds a stub `.so` with no
GEGL dependency — CMake will fail once GEGL linking is added until the NDK
cross-compile step (roadmap #1) is done.

## Cutting an alpha release

Either push a tag matching `v*-alpha*` (e.g. `git tag v0.1.0-alpha && git
push origin v0.1.0-alpha`) or trigger `release-alpha.yml` manually from the
Actions tab with a version string. Produces a GitHub prerelease with
`layercraft-alpha-<version>.apk` attached — debug-signed, sideload-only.

## License

TBD — GEGL/babl are LGPL, which constrains distribution/linking choices for
this project. Decide before first public release.
