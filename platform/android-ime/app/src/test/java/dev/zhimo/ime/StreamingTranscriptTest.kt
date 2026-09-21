// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTranscriptTest {
    @Test fun partialsReplaceRatherThanAppend() {
        val transcript = StreamingTranscript()
        assertEquals("你", transcript.preview("你"))
        assertEquals("你好", transcript.preview("你好"))
        assertEquals("您好", transcript.preview("您好"))
    }
    @Test fun chineseEndpointsAppendOnce() {
        val transcript = StreamingTranscript()
        transcript.endpoint("你好")
        assertEquals("你好世界", transcript.preview("世界"))
        assertEquals("你好", transcript.preview(""))
    }
    @Test fun englishBoundariesKeepSpaces() {
        val transcript = StreamingTranscript()
        transcript.endpoint("hello")
        assertEquals("hello world", transcript.preview("world"))
    }
    @Test fun repetitionsAreNotDeleted() {
        val transcript = StreamingTranscript()
        transcript.endpoint("你好")
        transcript.endpoint("你好")
        assertEquals("你好你好", transcript.preview(""))
    }
    @Test fun blankEndpointAddsNothing() {
        val transcript = StreamingTranscript()
        transcript.endpoint(" ")
        assertEquals("你好", transcript.preview(" 你好 "))
    }
}
