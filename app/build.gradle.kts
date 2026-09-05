plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.borgorninja.layercraft"
    compileSdk = 35

    // Package the GEGL/babl/glib/etc. .so files staged by
    // scripts/build-native-deps.sh at $nativeDepsPrefix/jniLibs/<abi>/.
    // Without this, only liblayercraft_engine.so (what CMake itself
    // builds) ends up in the APK -- its external shared-library
    // dependencies are linked against at build time but never bundled,
    // which fails at runtime with UnsatisfiedLinkError. Confirmed by
    // inspecting v0.1.0-alpha's APK contents directly.
    val nativeDepsPrefixForJniLibs = project.findProperty("nativeDepsPrefix") as String?
    if (nativeDepsPrefixForJniLibs != null) {
        sourceSets {
            getByName("main") {
                jniLibs.srcDirs(file("$nativeDepsPrefixForJniLibs/jniLibs"))
            }
        }
    }

    defaultConfig {
        applicationId = "com.borgorninja.layercraft"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-scaffold"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a first; add armeabi-v7a/x86_64 once GEGL cross-build
            // is verified on the primary target.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                // Set via `-PnativeDepsPrefix=/path/to/prefix` (CI passes the
                // output of scripts/build-native-deps.sh). Unset locally ->
                // CMakeLists.txt falls back to the no-GEGL stub build.
                val nativeDepsPrefix = project.findProperty("nativeDepsPrefix") as String?
                if (nativeDepsPrefix != null) {
                    arguments += "-DNATIVE_DEPS_PREFIX=$nativeDepsPrefix"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Compose compiler version is now managed by the
    // org.jetbrains.kotlin.plugin.compose Gradle plugin (Kotlin 2.0+);
    // no composeOptions{kotlinCompilerExtensionVersion} needed/allowed.

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.02"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
