package com.borgorninja.layercraft.engine

/**
 * JNI bridge to the native image-processing engine.
 *
 * STATUS: compiles and links against real GEGL/babl as of `v0.1.1-alpha`
 * -- see README.md roadmap item 2 and HANDOFF.md's CI run log for the
 * packaging history (this took several rounds to get the actual .so
 * files bundled correctly). Runtime behavior is still unconfirmed on a
 * real device.
 *
 * Design intent: expose a minimal C API rather than binding all of
 * GEGL's API surface into Kotlin. Kotlin owns the EditDocument/Layer
 * model; this bridge only needs to (a) build/update a GEGL graph from
 * that model and (b) render it to a buffer Compose can display.
 *
 * IMPORTANT: call [initEngine] once, before any other method on this
 * object, and before `gegl_init()` would otherwise run implicitly. GEGL
 * discovers its operation plugins (crop, blur, blend modes, etc.) at
 * init time by scanning the `GEGL_PATH` environment variable (babl has
 * the equivalent `BABL_PATH`) -- on desktop Linux this is a compiled-in
 * default; on Android there is no such default, and GEGL's operations
 * are bundled as flat .so files alongside every other native lib in
 * `ApplicationInfo.nativeLibraryDir`, not any particular fixed path.
 * Without this call, `gegl_init()` still succeeds, but GEGL registers
 * zero operations, so every `applyOp()` call fails to find whatever op
 * name it's given.
 */
object NativeEngine {

    init {
        System.loadLibrary("layercraft_engine")
    }

    /**
     * Sets GEGL_PATH/BABL_PATH to [nativeLibDir] and runs gegl_init().
     * Must be called exactly once, before any other method here.
     * [nativeLibDir] should be `context.applicationInfo.nativeLibraryDir`.
     */
    external fun initEngine(nativeLibDir: String)

    /** Returns a version/status string from the native side. Useful as a
     *  smoke test that the JNI bridge itself is wired correctly, independent
     *  of whether GEGL is linked yet. */
    external fun engineStatus(): String

    /**
     * Loads image bytes (already decoded to raw RGBA8 by Android's own
     * decoders on the Kotlin side) into a new native graph node.
     * Returns an opaque native handle, or -1 if unimplemented/failed.
     */
    external fun createImageNode(rgbaBytes: ByteArray, width: Int, height: Int): Long

    /**
     * Applies a named op (e.g. "gegl:gaussian-blur") with float params to
     * the given node handle, returning a new node handle representing the
     * op applied. Params passed as flattened [key1, val1, key2, val2, ...]
     * is avoided in favor of parallel arrays for JNI simplicity.
     */
    external fun applyOp(
        nodeHandle: Long,
        opName: String,
        paramKeys: Array<String>,
        paramValues: FloatArray,
    ): Long

    /**
     * Renders the given node graph to an RGBA8 buffer at the requested
     * size. Returns null if unimplemented/failed.
     */
    external fun renderToBuffer(nodeHandle: Long, width: Int, height: Int): ByteArray?

    /** Releases a native node handle. No-op until real graph nodes exist. */
    external fun releaseNode(nodeHandle: Long)
}
