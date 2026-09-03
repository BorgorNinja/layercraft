# LayerCraft

Non-destructive, layer-based photo editor for Android. Built to cover what
Snapseed lacks: real layers, drag-and-drop reordering, blend modes, masks,
and a non-destructive edit graph.

## Architecture

| Layer | Tech | Status |
|---|---|---|
| UI shell | Kotlin + Jetpack Compose | scaffolded |
| Canvas / gestures (drag-drop, pinch, reorder) | Compose `PointerInput` + custom `Canvas` | scaffolded |
| Edit-stack model | Kotlin data classes (`model/`) | scaffolded |
| Image processing engine | GEGL + babl via JNI (NDK cross-compiled) | **not yet built — stub JNI only** |
| GPU preview | `RenderEffect` (API 31+) fallback, GLES later | not started |
| File I/O | Android `BitmapFactory` / `ImageDecoder` now; libjpeg-turbo/skia-codec later | not started |

## Current state (this commit)

This is a **scaffold**, not a working editor:

- Compose UI shell: layer panel (drag-to-reorder via `LazyColumn`), canvas
  placeholder, basic edit-stack model (`Layer`, `LayerType`, `BlendMode`).
- JNI bridge (`NativeEngine.kt` + `native-engine.cpp`) exists but the native
  side is a **stub** — it does not link GEGL/babl yet. Calls return
  unimplemented placeholders.
- No image loading/compositing pipeline wired up yet.

## Why GEGL/babl and not a from-scratch engine

GEGL already models a non-destructive node graph (crop, curves, levels, blur,
etc. as composable nodes) — this is structurally what "layers + adjustable
stack" requires, so we don't want to reinvent it. Both are portable C with no
GTK dependency, unlike the rest of GIMP. See commit history / project notes
for the feasibility study this was based on (GIMP itself is GTK+3-bound and
not portable to Android; GEGL+babl are the extractable, portable core).

## Roadmap

1. ~~**NDK toolchain for GEGL/babl**~~ — meson cross-file generator +
   dependency-chain build script written (`scripts/`), wired into CI
   (`.github/workflows/native-libs.yml`, `release-alpha.yml`). **Not yet
   validated end-to-end** — see "Known risk areas" above; run
   `native-libs.yml` to find out what breaks first.
2. **JNI surface** — done for a first minimal cut (`createImageNode`,
   `applyOp`, `renderToBuffer`, `releaseNode` in `NativeEngine.kt` /
   `native-engine.cpp`), real GEGL calls written but unrun. Typed param
   handling beyond `gdouble` still needed (see risk areas).
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
7. ~~**Alpha APK release**~~ — pipeline written (`release-alpha.yml`,
   triggered by `workflow_dispatch` or a `v*-alpha*` tag push), produces a
   debug-signed prerelease GitHub Release. Ships a stub-engine (UI-only)
   APK if the native-deps job fails, so releases aren't blocked on GEGL
   compiling cleanly on the first try.

## Native dependency chain (GEGL/babl for Android)

`scripts/build-native-deps.sh` cross-compiles, in order: **glib** (which
pulls libffi + pcre2 as meson wrap subprojects) → **json-glib** → **babl**
→ **gegl**, targeting a single Android ABI via a generated meson cross-file
(`scripts/gen-cross-file.sh`).

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
- **iconv on API 26**: bionic gained native iconv symbols around API 28.
  `minSdk 26` may need `-Diconv=external` + a libiconv wrap, or bumping
  `minSdk` to 28.
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
