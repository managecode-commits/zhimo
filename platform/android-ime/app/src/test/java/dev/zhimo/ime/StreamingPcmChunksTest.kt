// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class StreamingPcmChunksTest {
    @Test fun smallReadsProduceOneHundredMillisecondChunks() {
        val chunks = StreamingPcmChunks()
        repeat(9) {
            chunks.buffer.fill(16384, chunks.filled, chunks.filled + 160)
            assertNull(chunks.readCompleted(160))
        }
        chunks.buffer.fill(-16384, chunks.filled, chunks.filled + 160)
        val output = chunks.readCompleted(160)!!
        assertEquals(1600, output.size)
        assertEquals(0.5f, output.first(), 0f)
        assertEquals(-0.5f, output.last(), 0f)
        assertEquals(0, chunks.filled)
        assertEquals(1600, chunks.remaining)
    }

    @Test fun partialTailIsFlushedExactlyOnce() {
        val chunks = StreamingPcmChunks()
        chunks.buffer[0] = Short.MIN_VALUE
        assertNull(chunks.readCompleted(37))
        val output = chunks.flush()!!
        assertEquals(37, output.size)
        assertEquals(-1f, output.first(), 0f)
        assertNull(chunks.flush())
    }

    @Test fun invalidReadCannotOverflowBuffer() {
        val chunks = StreamingPcmChunks()
        assertThrows(IllegalArgumentException::class.java) { chunks.readCompleted(-1) }
        chunks.readCompleted(1500)
        assertThrows(IllegalArgumentException::class.java) { chunks.readCompleted(101) }
        assertEquals(1500, chunks.filled)
    }
}
