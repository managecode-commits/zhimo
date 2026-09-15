// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.os.Build
import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun preservesNumbersAndEnglishBoundaries() {
        assertEquals("使用 Open AI 100 元", SpeechText.normalize("  使用 Open  AI 100 元  ", "zh", false))
        assertEquals("一百元", SpeechText.normalize("一百元", "zh", false))
    }
    @Test fun simplifiedIsExplicitAndVersionGated() {
        assertEquals("中國", SpeechText.normalize("中國", "zh", false))
        if (Build.VERSION.SDK_INT >= 29) assertEquals("中国", SpeechText.normalize("中國", "zh", true))
    }
    @Test fun hintsAreBoundedAndDeduplicated() {
        assertEquals("知墨，履带，物联网", SpeechText.prompt("知墨,履带；物联网\n知墨"))
        assertTrue(SpeechText.prompt((1..50).joinToString(",") { "词".repeat(40) + it }).toByteArray().size <= 2048)
    }
}
