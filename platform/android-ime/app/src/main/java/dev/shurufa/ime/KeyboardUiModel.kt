package dev.shurufa.ime

import android.content.res.Configuration
import android.text.InputType
import kotlin.math.roundToInt

enum class KeyboardPage {
    TEXT,
    NUMBER,
    SYMBOL,
    EMOJI,
    HANDWRITING,
}

enum class OneHandMode(val storedValue: String) {
    CENTER("center"),
    LEFT("left"),
    RIGHT("right");

    fun next(): OneHandMode = when (this) {
        CENTER -> LEFT
        LEFT -> RIGHT
        RIGHT -> CENTER
    }

    companion object {
        fun fromStored(value: String?): OneHandMode = entries.firstOrNull {
            it.storedValue == value
        } ?: CENTER
    }
}

enum class KeyboardSize(val storedValue: String, val scale: Float) {
    COMPACT("compact", 0.88f),
    STANDARD("standard", 1f),
    TALL("tall", 1.14f);

    fun next(): KeyboardSize = when (this) {
        COMPACT -> STANDARD
        STANDARD -> TALL
        TALL -> COMPACT
    }

    companion object {
        fun fromStored(value: String?): KeyboardSize = entries.firstOrNull {
            it.storedValue == value
        } ?: STANDARD
    }
}

object KeyboardUiModel {
    private val baseNumberRows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "-"),
    )

    fun numberRows(inputType: Int): List<List<String>> {
        val finalRow = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE -> listOf("*", "0", "#")
            InputType.TYPE_CLASS_DATETIME -> listOf("/", "0", ":")
            InputType.TYPE_CLASS_NUMBER -> {
                val flags = inputType and InputType.TYPE_MASK_FLAGS
                listOf(
                    if (flags and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0) "." else ",",
                    "0",
                    if (flags and InputType.TYPE_NUMBER_FLAG_SIGNED != 0) "-" else "+",
                )
            }
            else -> baseNumberRows.last()
        }
        return baseNumberRows.dropLast(1) + listOf(finalRow)
    }

    val chineseSymbolPages = listOf(
        listOf(
            listOf("，", "。", "？", "！", "：", "；"),
            listOf("“", "”", "‘", "’", "（", "）"),
            listOf("《", "》", "【", "】", "…", "—"),
        ),
        listOf(
            listOf("、", "·", "～", "￥", "％", "＃"),
            listOf("〈", "〉", "「", "」", "『", "』"),
            listOf("〔", "〕", "［", "］", "｛", "｝"),
        ),
    )

    val englishSymbolPages = listOf(
        listOf(
            listOf("~", "!", "@", "#", "\$", "%"),
            listOf("^", "&", "*", "(", ")", "_"),
            listOf("+", "-", "=", "/", "\\", ":", ";"),
        ),
        listOf(
            listOf("[", "]", "{", "}", "<", ">"),
            listOf("`", "|", "\"", "'", "?", "."),
            listOf("€", "£", "¥", "¢", "©", "®", "™"),
        ),
    )

    val emojiRows = listOf(
        listOf("😀", "😂", "😊", "😍", "🥰", "😎", "😭", "😡"),
        listOf("👍", "👎", "👏", "🙏", "💪", "🤝", "👌", "✌️"),
        listOf("❤️", "💔", "🔥", "✨", "🎉", "✅", "❌", "💯"),
        listOf("🌞", "🌙", "⭐", "🌈", "🍎", "☕", "🎁", "🚀"),
    )

    private val longPressValues = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "@", 's' to "#", 'd' to "\$", 'f' to "%", 'g' to "&",
        'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
        'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":",
        'b' to ";", 'n' to "!", 'm' to "?",
    )

    fun longPressValue(character: Char): String? = longPressValues[character.lowercaseChar()]

    fun keyHeightDp(orientation: Int, size: KeyboardSize, fontScale: Float): Int {
        // Keys use 3dp vertical margins in the renderer, so a 54dp row preserves a
        // 48dp interactive surface even in compact landscape mode.
        val base = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 54 else 60
        val scaled = (base * size.scale).roundToInt()
        val accessibleMinimum = (54f * fontScale.coerceAtMost(1.35f)).roundToInt()
        return maxOf(scaled, accessibleMinimum)
    }

    fun keyboardWidthFraction(mode: OneHandMode, smallestWidthDp: Int): Float = when {
        mode == OneHandMode.CENTER -> 1f
        smallestWidthDp >= 600 -> 0.62f
        else -> 0.82f
    }
}
