// Copyright © 2026 立方田 <managecode@gmail.com>
#include <jni.h>
#include "zhimo_speech.h"
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {
void fail(JNIEnv *env, const char *message) {
    auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}
std::string utf(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_zhimo_ime_OfflineSpeechNative_create(JNIEnv *, jobject) {
    return static_cast<jlong>(zhimo_speech_create());
}
extern "C" JNIEXPORT void JNICALL
Java_dev_zhimo_ime_OfflineSpeechNative_cancel(JNIEnv *, jobject, jlong id) {
    zhimo_speech_cancel(static_cast<uint64_t>(id));
}
extern "C" JNIEXPORT void JNICALL
Java_dev_zhimo_ime_OfflineSpeechNative_release(JNIEnv *, jobject, jlong id) {
    zhimo_speech_release(static_cast<uint64_t>(id));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_zhimo_ime_OfflineSpeechNative_transcribe(
        JNIEnv *env, jobject, jlong id, jstring model, jfloatArray pcm, jstring language,
        jstring prompt, jstring vadModel) try {
    if (!model || !pcm || !language || !prompt || !vadModel) { fail(env, "语音参数无效"); return nullptr; }
    const auto count = env->GetArrayLength(pcm);
    if (count < 1600 || count > 960000) { fail(env, "Audio must be 0.1 to 60 seconds"); return nullptr; }
    std::vector<float> samples(count);
    env->GetFloatArrayRegion(pcm, 0, count, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    const auto path = utf(env, model);
    const auto lang = utf(env, language);
    const auto hint = utf(env, prompt);
    const auto vad = utf(env, vadModel);
    if (env->ExceptionCheck()) return nullptr;
    char *raw = nullptr;
    const int status = zhimo_speech_transcribe_options(static_cast<uint64_t>(id), path.c_str(),
        samples.data(), samples.size(), lang.c_str(), hint.c_str(), vad.c_str(), &raw);
    std::unique_ptr<char, decltype(&zhimo_speech_text_free)> text(raw, zhimo_speech_text_free);
    if (status != ZHIMO_SPEECH_OK || !text) {
        const char *message = "离线语音识别失败，请重试";
        switch (status) {
            case ZHIMO_SPEECH_INVALID: message = "音频格式或语音参数无效"; break;
            case ZHIMO_SPEECH_BUSY: message = "语音引擎正忙，请稍后重试"; break;
            case ZHIMO_SPEECH_CANCELLED: message = "语音已取消"; break;
            case ZHIMO_SPEECH_TIMEOUT: message = "语音识别超时，请缩短录音后重试"; break;
            case ZHIMO_SPEECH_MODEL_ERROR: message = "语音模型加载失败，请检查可用内存或重新安装"; break;
            case ZHIMO_SPEECH_RESOURCE_ERROR: message = "语音资源不足，请关闭其他应用后重试"; break;
        }
        fail(env, message);
        return nullptr;
    }
    const auto length = static_cast<jsize>(std::strlen(text.get()));
    auto result = env->NewByteArray(length);
    if (result) env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte *>(text.get()));
    return result;
} catch (...) {
    fail(env, "语音资源不足，请稍后重试");
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zhimo_ime_OfflineSpeechNative_trimCache(JNIEnv *, jobject) {
    return zhimo_speech_trim_cache() != 0;
}
