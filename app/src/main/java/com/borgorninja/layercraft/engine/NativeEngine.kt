package com.borgorninja.layercraft.engine

/**
 * JNI bridge to the native image-processing engine.
 *
 * STATUS: STUB. `native-engine.cpp` currently does not link GEGL/babl --
 * these calls compile and run but return placeholder values. See
 * README.md roadmap step 1-2 before relying on this for real rendering.
 *
 * Design intent once implemented: expose a minimal C API rather than
 * binding all of GEGL's API surface into Kotlin. Kotlin owns the
 * EditDocument/Layer model; this bridge only needs to (a) build/update a
 * GEGL graph from that model and (b) render it to a buffer Compose can
 * display.
 */
object NativeEngine {

    init {
        System.loadLibrary("layercraft_engine")
    }

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
