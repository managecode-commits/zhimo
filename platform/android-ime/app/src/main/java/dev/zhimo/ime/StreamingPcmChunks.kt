// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** AudioRecord nonblocking reads may return only 10 ms: aggregate before queueing. */
internal class StreamingPcmChunks {
    val buffer = ShortArray(1600)
    var filled = 0
        private set
    val remaining: Int get() = buffer.size - filled

    fun readCompleted(count: Int): FloatArray? {
        require(count in 0..remaining)
        filled += count
        return if (filled == buffer.size) flush() else null
    }

    fun flush(): FloatArray? {
        if (filled == 0) return null
        val result = FloatArray(filled) { buffer[it] / 32768f }
        filled = 0
        return result
    }
}
