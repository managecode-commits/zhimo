// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HandwritingImageModelTest {
    @Test fun mixedModeKeepsHanBeforeConfusableAsciiWithoutRemovingLetters() {
        val labels = listOf("", "t", "七", "比", "北", "b", "林", "从")
        val scores = arrayOf(floatArrayOf(.01f, .60f, .12f, .10f, .07f, .05f, .03f, .02f))
        val mixed = ImageHandwritingProvider.decode(scores, labels, HandwritingCharacterMode.MIXED)
        assertEquals(listOf("七", "比", "北", "t"), mixed.take(4))
        assertTrue(mixed.containsAll(listOf("b", "林", "从")))
        assertEquals(listOf("t", "b"), ImageHandwritingProvider.decode(scores, labels, HandwritingCharacterMode.LETTERS))
        assertEquals(listOf("七", "比", "北", "林", "从"), ImageHandwritingProvider.decode(scores, labels, HandwritingCharacterMode.CHINESE))
        val protected = LineHandwritingProvider.protectWholeGlyph(listOf("七", "匕", "比", "t", "北", "林"), listOf(listOf("匕", "乙"), listOf("七", "乙")))
        assertEquals(listOf("比", "北", "林", "t", "七", "匕"), protected)
    }

    @Test fun alphabetsAreFilteredBeforeTopKWithoutCaseConversion() {
        val labels = listOf("", "中", "0", "1", "A", "a", "O", "I", "l", " ", "!", "é", "Ａ", "ab", "\n", "𠮷")
        val frames = arrayOf(FloatArray(labels.size) { 1f / labels.size })
        fun decode(mode: HandwritingCharacterMode) = ImageHandwritingProvider.decode(frames, labels, mode).toSet()
        assertEquals(setOf("中", "𠮷"), decode(HandwritingCharacterMode.CHINESE))
        assertEquals(setOf("0", "1"), decode(HandwritingCharacterMode.DIGITS))
        assertEquals(setOf("A", "a", "O", "I", "l"), decode(HandwritingCharacterMode.LETTERS))
        assertEquals(setOf("中", "𠮷", "0", "1", "A", "a", "O", "I", "l"), decode(HandwritingCharacterMode.MIXED))
        val ascii = ('0'..'9').map(Char::toString) + ('A'..'Z').map(Char::toString) + ('a'..'z').map(Char::toString)
        assertEquals(ascii.toSet(), ImageHandwritingProvider.decode(arrayOf(FloatArray(63) { 1f/63 }), listOf("") + ascii, HandwritingCharacterMode.MIXED).toSet())
        assertEquals(listOf("中A0a"), LineHandwritingProvider.combine(listOf(listOf("中"),listOf("A"),listOf("0"),listOf("a")), HandwritingCharacterMode.MIXED))
        assertTrue(LineHandwritingProvider.combine(listOf(listOf("A")), HandwritingCharacterMode.CHINESE).isEmpty())
        assertEquals(listOf("01"), LineHandwritingProvider.combine(listOf(listOf("O", "0"),listOf("I", "1")), HandwritingCharacterMode.DIGITS))
    }

    @Test fun offlineImageRecognizesSyntheticDigitAndLatinCases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var provider: ImageHandwritingProvider
        instrumentation.runOnMainSync { provider = ImageHandwritingProvider(instrumentation.targetContext) }
        fun stroke(vararg p: Float) = p.toList().chunked(2).mapIndexed { i, xy -> InkPoint(xy[0],xy[1],i.toLong()) }
        val circle = (0..48).map { i ->
            val angle = i * 2 * Math.PI / 48
            InkPoint(100f + 45f * kotlin.math.cos(angle).toFloat(), 100f + 70f * kotlin.math.sin(angle).toFloat(), i.toLong())
        }
        val cases = listOf(
            Triple("0", HandwritingCharacterMode.DIGITS, listOf(circle)),
            Triple("A", HandwritingCharacterMode.LETTERS, listOf(stroke(35f,170f,100f,30f,165f,170f),stroke(65f,110f,135f,110f))),
            Triple("a", HandwritingCharacterMode.LETTERS, listOf(circle,stroke(145f,35f,145f,170f,160f,165f)))
        )
        try {
            val prepared = CountDownLatch(1); var ready = false
            instrumentation.runOnMainSync { provider.available { ready = it; prepared.countDown() } }
            assertTrue(prepared.await(30, TimeUnit.SECONDS)); assertTrue(ready)
            for ((expected, mode, strokes) in cases) {
                val done = CountDownLatch(1); var result: Result<List<String>>? = null
                instrumentation.runOnMainSync { provider.recognize(InkRequest(1,strokes,200f,200f,characterMode=mode)) { result=it; done.countDown() } }
                assertTrue(done.await(30,TimeUnit.SECONDS))
                val words = result!!.getOrThrow()
                android.util.Log.i("ZhimoAlphabet", "synthetic $expected: ${words.take(10)}")
                assertTrue("$expected missing: ${words.take(10)}", words.take(10).contains(expected))
                assertTrue(words.all(mode::accepts))
            }
        } finally { instrumentation.runOnMainSync { provider.close() } }
    }
    @Test fun offlineImageProviderRecognizesSingleStroke() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var provider: HandwritingProvider
        instrumentation.runOnMainSync { provider = ImageHandwritingProvider(instrumentation.targetContext) }
        try {
            val prepared = CountDownLatch(1)
            var ready = false
            instrumentation.runOnMainSync { provider.available { ready = it; prepared.countDown() } }
            assertTrue(prepared.await(30, TimeUnit.SECONDS)); assertTrue("image model unavailable", ready)
            val points = (0..20).map { InkPoint(30f + it * 10, 100f, it.toLong()) }
            val tensor = ImageHandwritingProvider.render(InkRequest(1, listOf(points), 280f, 200f))
            assertTrue("thin ink vanished during downsampling", (0 until 48).any { y ->
                (0 until 48).any { x -> tensor[y * 320 + x] < 0.8f }
            })
            val recognized = CountDownLatch(1)
            var result: Result<List<String>>? = null
            instrumentation.runOnMainSync {
                provider.recognize(InkRequest(1, listOf(points), 280f, 200f)) { result = it; recognized.countDown() }
            }
            assertTrue(recognized.await(30, TimeUnit.SECONDS))
            assertTrue("single-stroke candidates: ${result!!.getOrThrow().take(10)}", result!!.getOrThrow().take(5).contains("一"))
        } finally { instrumentation.runOnMainSync { provider.close() } }
    }

    @Test fun rendererPreservesShapeAcrossScalePositionAndStrokeOrder() {
        val original = listOf(listOf(InkPoint(100f, 200f, 0), InkPoint(800f, 200f, 1)),
            listOf(InkPoint(50f, 600f, 2), InkPoint(900f, 600f, 3)))
        val changed = original.reversed().map { s -> s.map { InkPoint(it.x * .25f + 700, it.y * .25f + 50, it.timeMillis) } }
        assertArrayEquals(ImageHandwritingProvider.render(InkRequest(1, original, 1000f, 1000f)),
            ImageHandwritingProvider.render(InkRequest(2, changed, 1200f, 300f)), 0f)
        val decoded = ImageHandwritingProvider.decode(arrayOf(floatArrayOf(.1f, .8f, .1f)), listOf("", "制", "清"))
        assertEquals(listOf("制", "清"), decoded)
        assertEquals(listOf("一"), ImageHandwritingProvider.decode(arrayOf(floatArrayOf(.01f, .8f, .19f)), listOf("", "—", "一")))
    }
}
