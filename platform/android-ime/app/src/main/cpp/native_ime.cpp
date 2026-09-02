#include <jni.h>

#include "shurufa_ime.h"

#include <cstdint>
#include <string>

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
Java_dev_shurufa_ime_NativeIme_create(JNIEnv *env, jobject, jstring data_dir) {
  const std::string directory = utf8(env, data_dir);
  ImeHandle *value = ime_runtime_new_with_data_dir("bilingual", directory.c_str());
  return static_cast<jlong>(reinterpret_cast<intptr_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_shurufa_ime_NativeIme_destroy(JNIEnv *, jobject, jlong value) {
  ime_runtime_free(handle(value));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_feed(JNIEnv *env, jobject, jlong value, jstring text) {
  const std::string input = utf8(env, text);
  return ime_runtime_feed_utf8(handle(value), input.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_command(JNIEnv *, jobject, jlong value, jint command) {
  return ime_runtime_send_command(handle(value), static_cast<unsigned int>(command));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_switchEngine(JNIEnv *env, jobject, jlong value, jstring engine) {
  const std::string id = utf8(env, engine);
  return ime_runtime_switch_engine(handle(value), id.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_setScope(JNIEnv *, jobject, jlong value, jint scope) {
  return ime_runtime_set_input_scope(handle(value), static_cast<unsigned int>(scope));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_setPrivacy(JNIEnv *, jobject, jlong value,
                                          jboolean learning_allowed,
                                          jboolean network_allowed) {
  return ime_runtime_set_privacy_policy(handle(value), learning_allowed ? 1 : 0,
                                        network_allowed ? 1 : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_setApplicationId(JNIEnv *env, jobject, jlong value,
                                                jstring application_id) {
  const std::string id = utf8(env, application_id);
  return ime_runtime_set_application_id(handle(value), id.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_select(JNIEnv *env, jobject, jlong value, jstring candidate) {
  const std::string id = utf8(env, candidate);
  return ime_runtime_select_candidate(handle(value), id.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_shurufa_ime_NativeIme_actions(JNIEnv *env, jobject, jlong value) {
  const char *json = ime_runtime_last_actions_json(handle(value));
  return env->NewStringUTF(json ? json : "[]");
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_flush(JNIEnv *, jobject, jlong value) {
  return ime_runtime_flush(handle(value));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_shurufa_ime_NativeIme_speechResult(JNIEnv *env, jobject, jlong value, jstring text,
                                            jstring language, jfloat confidence,
                                            jboolean final_result) {
  const std::string transcript = utf8(env, text);
  const std::string locale = utf8(env, language);
  return ime_runtime_speech_result(handle(value), transcript.c_str(), locale.c_str(), confidence,
                                   final_result ? 1 : 0);
}
