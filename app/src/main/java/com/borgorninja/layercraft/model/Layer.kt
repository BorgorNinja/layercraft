package com.borgorninja.layercraft.model

import java.util.UUID

/**
 * One entry in the non-destructive edit stack.
 *
 * This is the Kotlin-side mirror of what will eventually compile down to a
 * GEGL subgraph (see NativeEngine). For now it's pure state -- no rendering
 * is wired up yet.
 */
data class Layer(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var type: LayerType,
    var visible: Boolean = true,
    var opacity: Float = 1.0f, // 0f..1f
    var blendMode: BlendMode = BlendMode.NORMAL,
    var thumbnail: ByteArray? = null,
    var adjustments: List<Adjustment> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = other is Layer && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

enum class LayerType {
    RASTER,       // pixel data (imported photo, painted content)
    ADJUSTMENT,   // curves/levels/hsl-only layer, affects layers below
    MASK,         // grayscale mask attached to another layer
}

enum class BlendMode {
    NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN,
    COLOR_DODGE, COLOR_BURN, HARD_LIGHT, SOFT_LIGHT, DIFFERENCE;

    /** Maps to the corresponding `gegl:*` blend op name once the native
     *  engine is wired up (e.g. NORMAL -> "gegl:over", MULTIPLY ->
     *  "gegl:multiply"). Left unimplemented until NativeEngine lands. */
    fun toGeglOpName(): String = when (this) {
        NORMAL -> "gegl:over"
        MULTIPLY -> "gegl:multiply"
        SCREEN -> "gegl:screen"
        OVERLAY -> "gegl:overlay"
        DARKEN -> "gegl:darken"
        LIGHTEN -> "gegl:lighten"
        COLOR_DODGE -> "gegl:dodge"
        COLOR_BURN -> "gegl:burn"
        HARD_LIGHT -> "gegl:hard-light"
        SOFT_LIGHT -> "gegl:soft-light"
        DIFFERENCE -> "gegl:difference"
    }
}

/** A single non-destructive adjustment op within a layer (curves, blur, etc). */
data class Adjustment(
    val id: String = UUID.randomUUID().toString(),
    val opName: String,       // e.g. "gegl:gaussian-blur", "gegl:curves"
    val params: Map<String, Float> = emptyMap(),
    var enabled: Boolean = true,
)

/** The full document: an ordered stack of layers, bottom to top. */
data class EditDocument(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Untitled",
    var widthPx: Int,
    var heightPx: Int,
    var layers: MutableList<Layer> = mutableListOf(),
) {
    fun moveLayer(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val layer = layers.removeAt(fromIndex)
        layers.add(toIndex, layer)
    }
}
