// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** PCM stays in memory; this library has no network or external-process backend. */
internal object OfflineSpeechNative {
    init { System.loadLibrary("zhimo_speech") }
    external fun create(): Long
    external fun transcribe(id: Long, model: String, pcm: FloatArray, language: String,
        prompt: String = "", vadModel: String = ""): ByteArray
    external fun trimCache(): Boolean
    external fun cancel(id: Long)
    external fun release(id: Long)
}
