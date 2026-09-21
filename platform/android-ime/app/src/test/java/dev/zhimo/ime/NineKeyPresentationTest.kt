// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class NineKeyPresentationTest {
    @Test fun showsAlternativesWithoutGuessingFromRankedWords() {
        assertEquals("jian / jiao / lian / liao", NineKeyPresentation.reading("5426", listOf("jian", "jiao", "lian", "liao"), null))
        assertEquals("lian", NineKeyPresentation.reading("5426", listOf("jian", "lian"), "lian"))
    }
    @Test fun neverShowsRawCodesInLongOrInvalidComposition() {
        assertEquals("ni · 后续待选", NineKeyPresentation.reading("64'426", listOf("mi", "ni"), "ni"))
        assertEquals("mi / ni · 连拼", NineKeyPresentation.reading("64426", listOf("mi", "ni"), null))
        assertEquals("请调整拼音", NineKeyPresentation.reading("2222222", emptyList(), null))
        assertEquals("", NineKeyPresentation.reading("", emptyList(), null))
    }
}
