#include "supertonic_engine.hpp"
#include "kokoro_engine.hpp"
#include <jni.h>

namespace {
using EnglishReader::SupertonicEngine;

void reportError(JNIEnv* env, const char* message) {
    auto type = env->FindClass("java/lang/IllegalStateException");
    if (type)
        env->ThrowNew(type, message);
}

std::vector<int> ints(JNIEnv* env, jintArray array) {
    if (!array)
        return {};
    const auto size = env->GetArrayLength(array);
    if (size > 65536)
        return {};
    std::vector<int> values(size);
    env->GetIntArrayRegion(array, 0, size, values.data());
    return values;
}

std::vector<float> floats(JNIEnv* env, jfloatArray array) {
    if (!array)
        return {};
    const auto size = env->GetArrayLength(array);
    if (size > 262144)
        return {};
    std::vector<float> values(size);
    env->GetFloatArrayRegion(array, 0, size, values.data());
    return values;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_alibaba_mnn_english_SupertonicNative_create(JNIEnv* env, jclass,
                                                                                        jstring path, jint sampleRate,
                                                                                        jint chunkSize, jint compress,
                                                                                        jint latentDim) {
    if (!path) {
        reportError(env, "Missing model path");
        return 0;
    }
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (!utf)
        return 0;
    const std::string directory(utf);
    env->ReleaseStringUTFChars(path, utf);
    std::unique_ptr<SupertonicEngine> engine(new SupertonicEngine(sampleRate, chunkSize, compress, latentDim));
    if (!engine->load(directory)) {
        reportError(env, engine->error().c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT jshortArray JNICALL Java_com_alibaba_mnn_english_SupertonicNative_synthesize(
    JNIEnv* env, jclass, jlong handle, jintArray tokens, jfloatArray ttl, jintArray ttlShape, jfloatArray dp,
    jintArray dpShape, jfloat speed) {
    auto engine = reinterpret_cast<SupertonicEngine*>(handle);
    if (!engine) {
        reportError(env, "Model not initialized");
        return nullptr;
    }
    auto ids = ints(env, tokens);
    auto ttlData = floats(env, ttl);
    auto ttlDims = ints(env, ttlShape);
    auto dpData = floats(env, dp);
    auto dpDims = ints(env, dpShape);
    if (env->ExceptionCheck())
        return nullptr;
    std::vector<int16_t> pcm;
    if (!engine->synthesize(ids, ttlData, ttlDims, dpData, dpDims, speed, pcm)) {
        reportError(env, engine->error().c_str());
        return nullptr;
    }
    auto result = env->NewShortArray(static_cast<jsize>(pcm.size()));
    if (result)
        env->SetShortArrayRegion(result, 0, static_cast<jsize>(pcm.size()), pcm.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_alibaba_mnn_english_SupertonicNative_cancel(JNIEnv*, jclass, jlong handle) {
    if (handle)
        reinterpret_cast<SupertonicEngine*>(handle)->cancel();
}

extern "C" JNIEXPORT void JNICALL Java_com_alibaba_mnn_english_SupertonicNative_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<SupertonicEngine*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_alibaba_mnn_english_KokoroNative_create(JNIEnv* env, jclass, jstring path) {
    if (!path) {
        reportError(env, "Missing Kokoro model path");
        return 0;
    }
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (!utf)
        return 0;
    const std::string model(utf);
    env->ReleaseStringUTFChars(path, utf);
    std::unique_ptr<EnglishReader::KokoroEngine> engine(new EnglishReader::KokoroEngine());
    if (!engine->load(model)) {
        reportError(env, engine->error().c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT jshortArray JNICALL Java_com_alibaba_mnn_english_KokoroNative_synthesize(
    JNIEnv* env, jclass, jlong handle, jintArray tokens, jfloatArray style, jfloat speed) {
    auto engine = reinterpret_cast<EnglishReader::KokoroEngine*>(handle);
    if (!engine) {
        reportError(env, "Kokoro not initialized");
        return nullptr;
    }
    auto ids = ints(env, tokens);
    auto voice = floats(env, style);
    if (env->ExceptionCheck())
        return nullptr;
    std::vector<int16_t> pcm;
    if (!engine->synthesize(ids, voice, speed, pcm)) {
        reportError(env, engine->error().c_str());
        return nullptr;
    }
    auto result = env->NewShortArray(static_cast<jsize>(pcm.size()));
    if (result)
        env->SetShortArrayRegion(result, 0, static_cast<jsize>(pcm.size()), pcm.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_alibaba_mnn_english_KokoroNative_cancel(JNIEnv*, jclass, jlong handle) {
    if (handle)
        reinterpret_cast<EnglishReader::KokoroEngine*>(handle)->cancel();
}

extern "C" JNIEXPORT void JNICALL Java_com_alibaba_mnn_english_KokoroNative_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<EnglishReader::KokoroEngine*>(handle);
}
