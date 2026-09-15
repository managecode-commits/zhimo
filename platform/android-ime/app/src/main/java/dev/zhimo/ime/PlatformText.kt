// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.icu.text.BreakIterator

object PlatformText {
    fun previousGraphemeUtf16Length(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val previous = iterator.preceding(text.length)
        return if (previous == BreakIterator.DONE) text.length else text.length - previous
    }
}
