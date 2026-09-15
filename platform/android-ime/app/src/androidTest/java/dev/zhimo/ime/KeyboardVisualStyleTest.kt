// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class KeyboardVisualStyleTest {
    @Test fun gridAdaptsToWidthAndFontScale() {
        assertEquals(6, KeyboardVisualStyle.emojiColumns(360f, 1f))
        assertEquals(4, KeyboardVisualStyle.emojiColumns(280f, 1.3f))
        assertEquals(8, KeyboardVisualStyle.emojiColumns(800f, 1f))
        assertTrue(KeyboardVisualStyle.emojiColumns(360f, 1.3f) < KeyboardVisualStyle.emojiColumns(360f, 1f))
    }
    @Test fun returnLabelMatchesLanguage() {
        assertEquals("拼音", KeyboardVisualStyle.textReturnLabel(true))
        assertEquals("ABC", KeyboardVisualStyle.textReturnLabel(false))
    }
}
