// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class EmojiCatalogTest {
    @Test fun categoriesFitGridAndContainNoEmptyEntries() {
        assertEquals(4, EmojiCatalog.categories.size)
        EmojiCatalog.categories.values.forEach { values ->
            assertEquals(24, values.size)
            assertEquals(24, values.distinct().size)
            assertTrue(values.all { it.isNotBlank() && '|' !in it })
        }
    }
    @Test fun recentPreservesSequencesAndMovesSelectedToFront() {
        var encoded = EmojiCatalog.remember("invalid|❤️|👍|❤️", "✌️")
        assertEquals(listOf("✌️", "❤️", "👍"), EmojiCatalog.recent(encoded))
        encoded = EmojiCatalog.remember(encoded, "❤️")
        assertEquals(listOf("❤️", "✌️", "👍"), EmojiCatalog.recent(encoded))
        assertEquals(encoded, EmojiCatalog.remember(encoded, "not an emoji"))
    }
    @Test fun recentIsBounded() {
        var encoded = ""
        EmojiCatalog.all.forEach { encoded = EmojiCatalog.remember(encoded, it) }
        assertEquals(24, EmojiCatalog.recent(encoded).size)
        assertEquals(EmojiCatalog.all.last(), EmojiCatalog.recent(encoded).first())
        assertTrue(EmojiCatalog.recent("").isEmpty())
    }
}
