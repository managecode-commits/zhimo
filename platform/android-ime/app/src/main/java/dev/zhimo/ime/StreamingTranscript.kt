// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Partial hypotheses replace each other; endpoint text is appended exactly once. */
internal class StreamingTranscript {
    private var completed = ""
    fun preview(partial: String): String = join(completed, partial.trim())
    fun endpoint(text: String) { completed = preview(text) }
    private fun join(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val space = left.last().isAsciiWord() && right.first().isAsciiWord()
        return left + (if (space) " " else "") + right
    }
    private fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
