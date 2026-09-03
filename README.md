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

1. **NDK toolchain for GEGL/babl** — write meson cross-files targeting
   Android ABIs (arm64-v8a first), resolve GEGL's own deps (glib, gobject,
   json-glib at minimum; babl has none). This is the long pole.
2. **JNI surface** — expose a minimal C API (`load_image`, `apply_op`,
   `render_preview`, `export`) rather than binding all of GEGL's API into
   Kotlin directly.
3. **Compose canvas wired to native preview buffer** — render GEGL output
   into a `Bitmap`/`SurfaceTexture` per edit-stack change.
4. **Layer model → GEGL graph mapping** — each `Layer` + its ops compiles to
   a subgraph; blend modes map to GEGL's `gegl:*-blend` ops.
5. **File formats** — import via standard Android decoders; export flattened
   JPEG/PNG; own `.lcraft` project format for layer state (JSON manifest +
   per-layer raster/adjustment data).
6. **Feature parity pass** — selections, masks, healing, perspective, text
   layers, curves/levels UI.

## Build

Standard Android Gradle project. Open in Android Studio or:

```
./gradlew assembleDebug
```

Native module (`app/src/main/cpp`) currently builds a stub `.so` with no
GEGL dependency — CMake will fail once GEGL linking is added until the NDK
cross-compile step (roadmap #1) is done.

## License

TBD — GEGL/babl are LGPL, which constrains distribution/linking choices for
this project. Decide before first public release.
