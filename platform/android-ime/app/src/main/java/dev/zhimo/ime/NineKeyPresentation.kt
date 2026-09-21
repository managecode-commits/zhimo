// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Reading choices derive from the typed prefix, not from a changing top-ranked word. */
object NineKeyPresentation {
    fun reading(raw: String, choices: List<String>, selected: String?): String {
        if (raw.isEmpty()) return ""
        val length = raw.count { it in '2'..'9' }
        if (selected != null) return selected + if (length > selected.length) " · 后续待选" else ""
        if (choices.isEmpty()) return "请调整拼音"
        val labels = choices.take(4).joinToString(" / ")
        val suffix = if (length > choices.maxOf { it.length }) " · 连拼" else if (choices.size > 4) " …" else ""
        return labels + suffix
    }
}
