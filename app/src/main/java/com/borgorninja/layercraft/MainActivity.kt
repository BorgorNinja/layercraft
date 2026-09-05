package com.borgorninja.layercraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.borgorninja.layercraft.engine.NativeEngine
import com.borgorninja.layercraft.ui.EditorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must happen before any other NativeEngine call -- sets
        // GEGL_PATH/BABL_PATH to this app's own native library directory,
        // which is where GEGL's operation plugins and babl's extensions
        // actually ended up bundled (Android's APK format has no
        // subdirectory structure under lib/<abi>/, unlike a normal Linux
        // install). See NativeEngine.kt / native-engine.cpp for why.
        NativeEngine.initEngine(applicationInfo.nativeLibraryDir)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EditorScreen()
                }
            }
        }
    }
}
