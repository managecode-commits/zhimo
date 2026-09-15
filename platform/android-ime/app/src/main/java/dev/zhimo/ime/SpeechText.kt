// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.os.Build

internal object SpeechText {
    fun normalize(text: String, language: String, simplified: Boolean): String {
        val compact = text.trim().replace(Regex("\\s+"), " ")
        // Never remove English word boundaries or guess numbers/names from homophones.
        return if (language == "zh" && simplified && Build.VERSION.SDK_INT >= 29)
            android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate(compact)
        else compact
    }

    fun prompt(raw: String): String = raw.split(Regex("[,，;；\\n]+"))
        .map { it.trim().filter { char -> char.isLetterOrDigit() || char in " -'." }.take(24) }
        .filter { it.isNotEmpty() }.distinct().take(16).joinToString("，")
}
