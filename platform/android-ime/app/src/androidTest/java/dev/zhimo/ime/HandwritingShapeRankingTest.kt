// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class HandwritingShapeRankingTest {
    @Test fun bundledModelsRecognizeScreenshotApproximation() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("runRealInk") == "true")
        val finished = java.util.concurrent.CountDownLatch(1)
        var output: Result<List<String>>? = null
        lateinit var provider: LineHandwritingProvider
        instrumentation.runOnMainSync {
            provider = LineHandwritingProvider(FusedHandwritingProvider(
                ImageHandwritingProvider(instrumentation.targetContext), BundledHandwritingProvider(instrumentation.targetContext)))
            provider.available { ready ->
                if (!ready) { output = Result.failure(IllegalStateException("Bundled handwriting unavailable")); finished.countDown() }
                else provider.recognize(ink()) { output = it; finished.countDown() }
            }
        }
        try {
            assertTrue("Real handwriting timed out", finished.await(120, java.util.concurrent.TimeUnit.SECONDS))
            val choices = checkNotNull(output).getOrThrow()
            assertEquals("Screenshot-derived approximation: ${choices.take(10)}", "天", choices.firstOrNull())
        } finally { instrumentation.runOnMainSync { provider.close() } }
    }
    private fun ink(startY: Float = 575f): InkRequest {
        fun s(vararg xy: Float) = xy.toList().chunked(2).mapIndexed { i, p -> InkPoint(p[0], p[1], i.toLong()) }
        // Approximation from the supplied screenshot, not recovered touch events.
        return InkRequest(1, listOf(s(377f,563f, 500f,542f, 595f,530f, 646f,516f),
            s(252f,737f, 475f,704f, 675f,677f),
            s(476f,startY, 474f,620f, 445f,705f, 395f,805f, 341f,897f),
            s(499f,749f, 550f,790f, 650f,819f, 740f,826f, 791f,822f)), 1000f, 1000f)
    }
    @Test fun screenshotTianMovesAheadOfFuWithoutDroppingAlternatives() {
        assertEquals(listOf("天", "夫", "大", "夭"), HandwritingShapeRanking.rank(ink(), listOf("夫", "大", "天", "夭")))
        assertEquals(listOf("天", "夫", "大"), HandwritingShapeRanking.rank(ink(), listOf("夫", "大")))
    }
    @Test fun protrudingStrokeRetainsFu() {
        assertEquals(listOf("夫", "天", "大"), HandwritingShapeRanking.rank(ink(450f), listOf("天", "夫", "大")))
    }
    @Test fun uncertainAndUnrelatedShapesAreNotRewritten() {
        val choices = listOf("夫", "天", "大")
        assertEquals(choices, HandwritingShapeRanking.rank(ink(548f), choices))
        assertEquals(choices, HandwritingShapeRanking.rank(ink().copy(strokes=ink().strokes.take(3)), choices))
        assertEquals(listOf("未", "末"), HandwritingShapeRanking.rank(ink(), listOf("未", "末")))
        assertEquals(choices, HandwritingShapeRanking.rank(ink().copy(characterMode=HandwritingCharacterMode.LETTERS), choices))
    }
    @Test fun scaleAndTranslationDoNotChangeDecision() {
        val transformed = ink().copy(strokes=ink().strokes.map { stroke -> stroke.map {
            it.copy(x=it.x*.3f+10f, y=it.y*.3f+15f)
        } })
        assertEquals("天", HandwritingShapeRanking.rank(transformed, listOf("夫", "天")).first())
    }
}
