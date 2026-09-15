// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Recognition alphabet, applied before top-k truncation and again during line composition. */
enum class HandwritingCharacterMode(val label: String, val providerLanguage: String) {
    MIXED("混合", "mixed"), CHINESE("中文", "zh-CN"), DIGITS("数字", "digits"), LETTERS("字母", "letters");

    fun accepts(value: String): Boolean {
        if (value.codePointCount(0, value.length) != 1) return false
        val cp = value.codePointAt(0)
        val han = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
        val digit = cp in '0'.code..'9'.code
        val letter = cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code
        return when (this) {
            MIXED -> han || digit || letter
            CHINESE -> han
            DIGITS -> digit
            LETTERS -> letter
        }
    }
}
