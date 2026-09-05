package com.borgorninja.layercraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.borgorninja.layercraft.engine.NativeEngine
import com.borgorninja.layercraft.model.BlendMode
import com.borgorninja.layercraft.model.EditDocument
import com.borgorninja.layercraft.model.Layer
import com.borgorninja.layercraft.model.LayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Top-level editor screen: canvas preview (top) + layer panel (bottom,
 * drag-to-reorder). No native rendering wired up yet -- canvas shows a
 * placeholder; layer thumbnails are solid-color swatches for now.
 */
@Composable
fun EditorScreen() {
    val document = remember {
        mutableStateOf(
            EditDocument(
                title = "Untitled",
                widthPx = 1080,
                heightPx = 1350,
                layers = mutableListOf(
                    Layer(name = "Background", type = LayerType.RASTER),
                    Layer(name = "Exposure", type = LayerType.ADJUSTMENT, blendMode = BlendMode.NORMAL),
                    Layer(name = "Color Pop", type = LayerType.ADJUSTMENT, blendMode = BlendMode.OVERLAY),
                ),
            )
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CanvasPreview(modifier = Modifier.weight(1f))
        LayerPanel(
            document = document.value,
            onReorder = { from, to ->
                document.value.moveLayer(from, to)
                document.value = document.value.copy(layers = document.value.layers)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        )
    }
}

@Composable
private fun CanvasPreview(modifier: Modifier = Modifier) {
    // Temporary runtime diagnostic (not the real canvas -- see roadmap
    // item 3). Runs a minimal end-to-end GEGL round trip on first
    // composition and displays the result, since this is the one thing
    // static APK inspection could never confirm: whether GEGL_PATH
    // actually let gegl_init() discover its operation plugins at
    // runtime, not just whether the .so files are present in the APK.
    var diagnostic by remember { mutableStateOf("Running engine diagnostic...") }

    LaunchedEffect(Unit) {
        diagnostic = withContext(Dispatchers.Default) {
            runCatching {
                val status = NativeEngine.engineStatus()

                // 4x4 solid mid-gray RGBA8 test image.
                val w = 4
                val h = 4
                val pixels = ByteArray(w * h * 4) { i ->
                    if (i % 4 == 3) 0xFF.toByte() else 0x80.toByte()
                }

                val srcNode = NativeEngine.createImageNode(pixels, w, h)
                if (srcNode < 0) {
                    return@runCatching "engineStatus: $status\n\ncreateImageNode FAILED (returned -1)"
                }

                val blurredNode = NativeEngine.applyOp(
                    srcNode,
                    "gegl:gaussian-blur",
                    arrayOf("std-dev-x", "std-dev-y"),
                    floatArrayOf(1.0f, 1.0f),
                )
                if (blurredNode < 0) {
                    return@runCatching "engineStatus: $status\n\ncreateImageNode OK (handle $srcNode)\napplyOp(gegl:gaussian-blur) FAILED (returned -1)\n-- likely means GEGL_PATH didn't find this operation"
                }

                val out = NativeEngine.renderToBuffer(blurredNode, w, h)
                NativeEngine.releaseNode(srcNode)
                NativeEngine.releaseNode(blurredNode)

                if (out == null) {
                    "engineStatus: $status\n\ncreateImageNode OK\napplyOp OK (handle $blurredNode)\nrenderToBuffer FAILED (returned null)"
                } else {
                    "engineStatus: $status\n\nFULL ROUND TRIP OK:\ncreateImageNode -> applyOp(gegl:gaussian-blur) -> renderToBuffer\nreturned ${out.size} bytes (expected ${w * h * 4})"
                }
            }.getOrElse { e ->
                "ENGINE DIAGNOSTIC CRASHED/THREW:\n${e::class.simpleName}: ${e.message}"
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            diagnostic,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun LayerPanel(
    document: EditDocument,
    onReorder: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reversed for display: topmost layer (last in list) shown first, like
    // GIMP/Photoshop layer panels.
    val displayLayers = document.layers.asReversed()

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            "Layers",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(12.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(displayLayers.size) { displayIndex ->
                val actualIndex = document.layers.size - 1 - displayIndex
                LayerRow(
                    layer = document.layers[actualIndex],
                    onDragTo = { deltaDisplayIndex ->
                        val targetDisplay = (displayIndex + deltaDisplayIndex)
                            .coerceIn(0, document.layers.size - 1)
                        val targetActual = document.layers.size - 1 - targetDisplay
                        if (targetActual != actualIndex) {
                            onReorder(actualIndex, targetActual)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    onDragTo: (deltaDisplayIndex: Int) -> Unit,
) {
    var visible by remember { mutableStateOf(layer.visible) }
    var opacity by remember { mutableFloatStateOf(layer.opacity) }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = 56f // approximate, used for drag-threshold conversion

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .zIndex(0f),
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = "Drag to reorder",
            modifier = Modifier
                .padding(end = 8.dp)
                .pointerInput(layer.id) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumPx += dragAmount.y
                            val threshold = rowHeightPx * density
                            if (kotlin.math.abs(dragAccumPx) > threshold) {
                                val steps = (dragAccumPx / threshold).toInt()
                                onDragTo(-steps) // drag down = move toward bottom of stack
                                dragAccumPx -= steps * threshold
                            }
                        },
                        onDragEnd = { dragAccumPx = 0f },
                    )
                },
        )

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(layerSwatchColor(layer)),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(layer.name, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    layer.blendMode.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = opacity,
                    onValueChange = {
                        opacity = it
                        layer.opacity = it
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        IconButton(onClick = {
            visible = !visible
            layer.visible = visible
        }) {
            Icon(
                imageVector = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = if (visible) "Hide layer" else "Show layer",
            )
        }
    }
}

private fun layerSwatchColor(layer: Layer): Color = when (layer.type) {
    LayerType.RASTER -> Color(0xFF3A3A3C)
    LayerType.ADJUSTMENT -> Color(0xFF0A84FF)
    LayerType.MASK -> Color(0xFF8E8E93)
}
