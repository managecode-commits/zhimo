// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.content.Context

/** Android's only bundled speech engine. There is no Whisper fallback. */
internal class OfflineDictation(context: Context) {
    private val streaming = StreamingDictation(context)
    val active: Boolean get() = streaming.active
    fun start(state: (Boolean) -> Unit, result: (String?, String?) -> Unit,
        progress: (Int, Float) -> Unit = { _, _ -> }, partial: (String) -> Unit = {}) =
        streaming.start(state, result, progress, partial)
    fun finish() = streaming.finish()
    fun cancel() = streaming.cancel()
    fun trimMemory() = streaming.trimMemory()
    fun close() = streaming.close()
}
