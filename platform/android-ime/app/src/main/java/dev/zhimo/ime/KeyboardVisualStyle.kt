// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

enum class KeyboardKeyRole { CHARACTER, FUNCTION, TAB, EMOJI, PRIMARY }

object KeyboardVisualStyle {
    fun emojiColumns(widthDp: Float, textScale: Float): Int =
        (widthDp / (52f * textScale.coerceAtLeast(1f))).toInt().coerceIn(4, 8)
    fun textReturnLabel(pinyin: Boolean) = if (pinyin) "拼音" else "ABC"
}
