// Two build modes, selected by CMakeLists.txt based on whether
// NATIVE_DEPS_PREFIX was supplied:
//
//   LAYERCRAFT_HAVE_GEGL defined   -> real GEGL graph calls below
//   LAYERCRAFT_HAVE_GEGL undefined -> stub bodies, JNI bridge still works
//
// The stub path lets the Compose UI build/run standalone without the
// native dependency chain built (useful for UI-only iteration). The real
// path has NOT been exercised end-to-end -- it was written against the
// GEGL/babl API but this sandbox cannot build or run it (see README
// "Known risk areas"). Expect debugging once CI actually compiles this.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cstdlib>

#define LOG_TAG "layercraft_engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef LAYERCRAFT_HAVE_GEGL
#include <gegl.h>
#include <babl/babl.h>

namespace {

std::once_flag gGeglInitFlag;
std::mutex gHandleMutex;
std::unordered_map<jlong, GeglNode *> gHandles;
std::atomic<jlong> gNextHandle{1};
std::atomic<bool> gExplicitlyInitialized{false};

// Real init path: must run BEFORE gegl_init(), since that's when GEGL
// scans GEGL_PATH to discover its operation plugins. On desktop Linux
// these env vars have a compiled-in default (a normal install prefix);
// on Android there is no such default and no directory structure to
// discover -- every .so (glib, gegl, its bundled operation plugins,
// babl's extensions) ends up flattened into the same
// ApplicationInfo.nativeLibraryDir alongside everything else. Confirmed
// via GEGL's own docs/environment.adoc (GEGL_PATH: "directory where GEGL
// looks (recursively) for dynamically loadable operation libraries";
// BABL_PATH is babl's equivalent) and operations/core/meson.build
// (operations installed as shared_module bundles to $libdir/gegl-0.4,
// which is exactly what gets flattened away in an Android APK).
void initEngineImpl(const char *nativeLibDir) {
    std::call_once(gGeglInitFlag, [nativeLibDir] {
        setenv("GEGL_PATH", nativeLibDir, 1);
        setenv("BABL_PATH", nativeLibDir, 1);
        int argc = 0;
        gegl_init(&argc, nullptr);
        LOGI("gegl_init done, GEGL_PATH/BABL_PATH=%s", nativeLibDir);
    });
    gExplicitlyInitialized.store(true);
}

// Fallback for any caller that reaches a GEGL entry point without having
// called initEngine first: still calls gegl_init() (so it doesn't crash
// outright), but without GEGL_PATH set, GEGL registers zero operations --
// every applyOp() will fail to find its op. Logs loudly so this is
// obvious in logcat rather than a silent, confusing failure.
void ensureGeglInit() {
    if (!gExplicitlyInitialized.load()) {
        LOGE("GEGL used before initEngine() was called -- GEGL_PATH/BABL_PATH "
             "not set, operations will not be found. Call NativeEngine.initEngine() "
             "with context.applicationInfo.nativeLibraryDir first.");
    }
    std::call_once(gGeglInitFlag, [] {
        int argc = 0;
        gegl_init(&argc, nullptr);
        LOGI("gegl_init done (fallback path, no GEGL_PATH set)");
    });
}

jlong storeHandle(GeglNode *node) {
    jlong h = gNextHandle.fetch_add(1);
    std::lock_guard<std::mutex> lock(gHandleMutex);
    gHandles[h] = node;
    return h;
}

GeglNode *lookupHandle(jlong h) {
    std::lock_guard<std::mutex> lock(gHandleMutex);
    auto it = gHandles.find(h);
    return it == gHandles.end() ? nullptr : it->second;
}

void eraseHandle(jlong h) {
    std::lock_guard<std::mutex> lock(gHandleMutex);
    gHandles.erase(h);
}

} // namespace
#endif // LAYERCRAFT_HAVE_GEGL

extern "C" JNIEXPORT void JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_initEngine(
        JNIEnv *env, jobject, jstring nativeLibDir) {
#ifdef LAYERCRAFT_HAVE_GEGL
    const char *dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    initEngineImpl(dir);
    env->ReleaseStringUTFChars(nativeLibDir, dir);
#else
    (void) env; (void) nativeLibDir;
    LOGI("initEngine: stub build, no-op");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_engineStatus(JNIEnv *env, jobject) {
#ifdef LAYERCRAFT_HAVE_GEGL
    bool initialized = gExplicitlyInitialized.load();
    return env->NewStringUTF(initialized
        ? "gegl-engine v0.2 (GEGL linked, initEngine() called, GEGL_PATH set -- runtime op availability still unverified)"
        : "gegl-engine v0.2 (GEGL linked, but initEngine() NOT called yet -- GEGL_PATH unset, operations will not be found)");
#else
    return env->NewStringUTF("stub-engine v0.1 (no GEGL/babl linked)");
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_createImageNode(
        JNIEnv *env, jobject, jbyteArray rgbaBytes, jint width, jint height) {
#ifdef LAYERCRAFT_HAVE_GEGL
    ensureGeglInit();

    jsize len = env->GetArrayLength(rgbaBytes);
    jsize expected = width * height * 4;
    if (len != expected) {
        LOGE("createImageNode: byte array len %d != expected %d", len, expected);
        return -1;
    }

    jbyte *bytes = env->GetByteArrayElements(rgbaBytes, nullptr);

    // GeglBuffer takes ownership of a copy via babl format "R'G'B'A u8"
    // (sRGB, non-linear, premultiplied-alpha variant would be "R'aG'aB'aA u8";
    // using straight alpha here since that's what Android Bitmap gives us).
    GeglRectangle extent = {0, 0, width, height};
    const Babl *format = babl_format("R'G'B'A u8");
    GeglBuffer *buffer = gegl_buffer_new(&extent, format);
    gegl_buffer_set(buffer, &extent, 0, format, bytes, GEGL_AUTO_ROWSTRIDE);

    env->ReleaseByteArrayElements(rgbaBytes, bytes, JNI_ABORT);

    GeglNode *graph = gegl_node_new();
    GeglNode *source = gegl_node_new_child(graph,
        "operation", "gegl:buffer-source",
        "buffer", buffer,
        nullptr);

    // buffer-source's GeglBuffer* ref is held by the node; drop our ref.
    g_object_unref(buffer);

    return storeHandle(source);
#else
    (void) env; (void) rgbaBytes; (void) width; (void) height;
    LOGI("createImageNode: stub build, no GEGL linked");
    return -1;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_applyOp(
        JNIEnv *env, jobject,
        jlong nodeHandle, jstring opName,
        jobjectArray paramKeys, jfloatArray paramValues) {
#ifdef LAYERCRAFT_HAVE_GEGL
    GeglNode *input = lookupHandle(nodeHandle);
    if (!input) {
        LOGE("applyOp: unknown handle %lld", (long long) nodeHandle);
        return -1;
    }

    const char *opNameChars = env->GetStringUTFChars(opName, nullptr);
    GeglNode *parent = gegl_node_get_parent(input);
    if (!parent) parent = input; // defensive; shouldn't happen

    GeglNode *op = gegl_node_new_child(parent, "operation", opNameChars, nullptr);

    jsize nParams = env->GetArrayLength(paramKeys);
    jfloat *values = env->GetFloatArrayElements(paramValues, nullptr);
    for (jsize i = 0; i < nParams; i++) {
        auto keyObj = (jstring) env->GetObjectArrayElement(paramKeys, i);
        const char *key = env->GetStringUTFChars(keyObj, nullptr);
        // GEGL op properties are typed (double/float/int/enum); this
        // assumes float-compatible properties, which covers most
        // adjustment ops (curves control points aside). Ops with
        // non-numeric properties will need a separate typed path later.
        gegl_node_set(op, key, static_cast<gdouble>(values[i]), nullptr);
        env->ReleaseStringUTFChars(keyObj, key);
        env->DeleteLocalRef(keyObj);
    }
    env->ReleaseFloatArrayElements(paramValues, values, JNI_ABORT);
    env->ReleaseStringUTFChars(opName, opNameChars);

    gegl_node_link(input, op);
    return storeHandle(op);
#else
    (void) env; (void) nodeHandle; (void) opName; (void) paramKeys; (void) paramValues;
    LOGI("applyOp: stub build, no GEGL linked");
    return -1;
#endif
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_renderToBuffer(
        JNIEnv *env, jobject, jlong nodeHandle, jint width, jint height) {
#ifdef LAYERCRAFT_HAVE_GEGL
    GeglNode *node = lookupHandle(nodeHandle);
    if (!node) {
        LOGE("renderToBuffer: unknown handle %lld", (long long) nodeHandle);
        return nullptr;
    }

    GeglRectangle roi = {0, 0, width, height};
    const Babl *format = babl_format("R'G'B'A u8");

    jsize outLen = width * height * 4;
    jbyteArray out = env->NewByteArray(outLen);
    jbyte *outBytes = env->GetByteArrayElements(out, nullptr);

    gegl_node_blit(node, 1.0, &roi, format, outBytes,
                    GEGL_AUTO_ROWSTRIDE, GEGL_BLIT_DEFAULT);

    env->ReleaseByteArrayElements(out, outBytes, 0);
    return out;
#else
    (void) env; (void) nodeHandle; (void) width; (void) height;
    LOGI("renderToBuffer: stub build, no GEGL linked");
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_releaseNode(
        JNIEnv *, jobject, jlong nodeHandle) {
#ifdef LAYERCRAFT_HAVE_GEGL
    GeglNode *node = lookupHandle(nodeHandle);
    if (node) {
        // Nodes are owned by their parent graph; unref-ing the graph root
        // tears down the whole chain. For a single node in a shared graph
        // this just drops our handle bookkeeping -- full graph teardown
        // needs a separate "releaseGraph(rootHandle)" call not yet exposed.
        eraseHandle(nodeHandle);
    }
#else
    (void) nodeHandle;
    LOGI("releaseNode: stub build, no-op");
#endif
}
