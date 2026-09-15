// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Unicode text only: no sticker files, network access or account integration. */
object EmojiCatalog {
    val categories = linkedMapOf(
        "常用" to "😀 😂 🤣 😊 😍 🥰 😘 😎 😭 🥺 😅 🤔 👍 👏 🙏 🤝 💪 ❤️ 🎉 🌹 ✅ 💯 ☕ 🎁",
        "心情" to "😄 😁 😉 🙂 🤗 🤭 🤫 🥳 😋 😜 🤪 😏 😴 😪 😔 😢 😤 😡 🤯 😱 😳 🙄 😷 🤒",
        "手势" to "👍 👎 👏 🙌 👐 🤲 🙏 🤝 💪 👌 ✌️ 🤞 🤟 🤘 👋 🤚 ✋ 🖐️ 👊 ✊ 🤛 🤜 👈 👉",
        "生活" to "❤️ 💕 💖 💔 🌹 🌸 🌞 🌙 ⭐ 🌈 🔥 ✨ 🎉 🎂 🎁 🍎 🍉 🍰 ☕ 🍻 🏠 🚗 ✈️ 🚀",
    ).mapValues { (_, text) -> text.split(' ') }
    val all = categories.values.flatten().toSet()
    const val RECENT_LIMIT = 24
    fun recent(encoded: String): List<String> = encoded.split('|').filter { it in all }.distinct().take(RECENT_LIMIT)
    fun remember(encoded: String, value: String): String =
        (if (value in all) listOf(value) + recent(encoded) else recent(encoded)).distinct().take(RECENT_LIMIT).joinToString("|")
}
