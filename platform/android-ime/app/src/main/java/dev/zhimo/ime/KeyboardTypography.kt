// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

enum class KeyboardTextSize(val scale: Float) {
    STANDARD(1f), LARGE(1.15f), EXTRA_LARGE(1.3f);
    fun next(): KeyboardTextSize = entries[(ordinal + 1) % entries.size]
    companion object {
        fun fromStored(value: String?): KeyboardTextSize = entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

/** A shared type scale for every keyboard page, independent of keyboard height. */
object KeyboardTypography {
    const val LETTER = 26f
    const val NUMBER = 26f
    const val SYMBOL = 22f
    const val T9 = 25f
    const val FUNCTION = 15f
    const val CANDIDATE = 22f
    const val ANNOTATION = 14f
    const val HINT = 12f
    fun expandedColumns(widthDp: Float, textSize: KeyboardTextSize): Int =
        (widthDp / (88f * textSize.scale)).toInt().coerceIn(2, 5)
}
