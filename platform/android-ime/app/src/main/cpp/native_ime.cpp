// Copyright © 2026 立方田 <managecode@gmail.com>
#include <jni.h>

#include "zhimo_ime.h"

#include <cstdint>
#include <string>
#include <cstring>

namespace {
std::string utf8(JNIEnv *env, jstring value) {
  if (!value) return {};
  const char *bytes = env->GetStringUTFChars(value, nullptr);
  std::string result = bytes ? bytes : "";
  if (bytes) env->ReleaseStringUTFChars(value, bytes);
  return result;
}

ImeHandle *handle(jlong value) {
  return reinterpret_cast<ImeHandle *>(static_cast<intptr_t>(value));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_zhimo_ime_NativeIme_handwritingOpen(JNIEnv *env, jobject, jstring path) {
  const auto file = utf8(env, path);
  return static_cast<jlong>(reinterpret_cast<intptr_t>(ime_handwriting_new(file.c_str())));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zhimo_ime_NativeIme_handwritingClose(JNIEnv *, jobject, jlong value) {
  ime_handwriting_free(reinterpret_cast<ImeHandwritingHandle *>(static_cast<intptr_t>(value)));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_zhimo_ime_NativeIme_handwritingRecognize(JNIEnv *env, jobject, jlong value, jstring ink) {
  const auto request = utf8(env, ink);
  const char *result = ime_handwriting_recognize_json(
      reinterpret_cast<ImeHandwritingHandle *>(static_cast<intptr_t>(value)), request.c_str());
  if (!result) return nullptr;
  const auto length = static_cast<jsize>(std::strlen(result));
  jbyteArray bytes = env->NewByteArray(length);
  if (bytes) env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(result));
  return bytes;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_zhimo_ime_NativeIme_create(JNIEnv *env, jobject, jstring data_dir,
                                     jstring rime_shared_dir, jstring rime_user_dir) {
  const std::string directory = utf8(env, data_dir);
  const std::string shared = utf8(env, rime_shared_dir);
  const std::string user = utf8(env, rime_user_dir);
  ImeHandle *value = nullptr;
  if ((ime_runtime_capabilities() & ZHIMO_CAP_NATIVE_LIBRIME) != 0) {
    value = ime_runtime_new_with_rime("bilingual", directory.c_str(), shared.c_str(),
                                      user.c_str());
  }
  if (!value) value = ime_runtime_new_with_data_dir("bilingual", directory.c_str());
  return static_cast<jlong>(reinterpret_cast<intptr_t>(value));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_zhimo_ime_NativeIme_capabilities(JNIEnv *, jobject) {
  return static_cast<jlong>(ime_runtime_capabilities());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zhimo_ime_NativeIme_destroy(JNIEnv *, jobject, jlong value) {
  ime_runtime_free(handle(value));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_feed(JNIEnv *env, jobject, jlong value, jstring text) {
  const std::string input = utf8(env, text);
  return ime_runtime_feed_utf8(handle(value), input.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_command(JNIEnv *, jobject, jlong value, jint command) {
  return ime_runtime_send_command(handle(value), static_cast<unsigned int>(command));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_switchEngine(JNIEnv *env, jobject, jlong value, jstring engine) {
  const std::string id = utf8(env, engine);
  return ime_runtime_switch_engine(handle(value), id.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_setScope(JNIEnv *, jobject, jlong value, jint scope) {
  return ime_runtime_set_input_scope(handle(value), static_cast<unsigned int>(scope));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_setPrivacy(JNIEnv *, jobject, jlong value,
                                          jboolean learning_allowed,
                                          jboolean network_allowed) {
  return ime_runtime_set_privacy_policy(handle(value), learning_allowed ? 1 : 0,
                                        network_allowed ? 1 : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_setApplicationId(JNIEnv *env, jobject, jlong value,
                                                jstring application_id) {
  const std::string id = utf8(env, application_id);
  return ime_runtime_set_application_id(handle(value), id.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_select(JNIEnv *env, jobject, jlong value, jstring candidate) {
  const std::string id = utf8(env, candidate);
  return ime_runtime_select_candidate(handle(value), id.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zhimo_ime_NativeIme_actions(JNIEnv *env, jobject, jlong value) {
  const char *json = ime_runtime_last_actions_json(handle(value));
  return env->NewStringUTF(json ? json : "[]");
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_flush(JNIEnv *, jobject, jlong value) {
  return ime_runtime_flush(handle(value));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_scheduleFlush(JNIEnv *, jobject, jlong value) {
  return ime_runtime_schedule_flush(handle(value));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_learningStatus(JNIEnv *, jobject, jlong value) {
  return ime_runtime_learning_status(handle(value));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zhimo_ime_NativeIme_speechResult(JNIEnv *env, jobject, jlong value, jstring text,
                                            jstring language, jfloat confidence,
                                            jboolean final_result) {
  const std::string transcript = utf8(env, text);
  const std::string locale = utf8(env, language);
  return ime_runtime_speech_result(handle(value), transcript.c_str(), locale.c_str(), confidence,
                                   final_result ? 1 : 0);
}
