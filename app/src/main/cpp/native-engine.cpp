// STATUS: stub. No GEGL/babl linked yet -- see CMakeLists.txt and
// /README.md roadmap. Every function here is a placeholder that proves the
// JNI bridge works, not real image processing.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LOG_TAG "layercraft_engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_engineStatus(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("stub-engine v0.1 (no GEGL/babl linked)");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_createImageNode(
        JNIEnv *env, jobject /* this */,
        jbyteArray /* rgbaBytes */, jint width, jint height) {
    LOGI("createImageNode called (%dx%d) -- stub, no GEGL graph built", width, height);
    // Once GEGL is linked: gegl_node_new(), gegl_node_new_child with
    // "gegl:load-buffer" or a GeglBuffer wrapping rgbaBytes, return an
    // opaque handle (e.g. cast GeglNode* to jlong via a handle table).
    return -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_applyOp(
        JNIEnv *env, jobject /* this */,
        jlong nodeHandle, jstring opName,
        jobjectArray /* paramKeys */, jfloatArray /* paramValues */) {
    const char *opNameChars = env->GetStringUTFChars(opName, nullptr);
    LOGI("applyOp('%s') on handle %lld -- stub, not applied", opNameChars, (long long) nodeHandle);
    env->ReleaseStringUTFChars(opName, opNameChars);
    return -1;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_renderToBuffer(
        JNIEnv *env, jobject /* this */,
        jlong nodeHandle, jint width, jint height) {
    LOGI("renderToBuffer on handle %lld (%dx%d) -- stub, returning null", (long long) nodeHandle, width, height);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_borgorninja_layercraft_engine_NativeEngine_releaseNode(
        JNIEnv * /* env */, jobject /* this */, jlong nodeHandle) {
    LOGI("releaseNode %lld -- stub, no-op", (long long) nodeHandle);
}
