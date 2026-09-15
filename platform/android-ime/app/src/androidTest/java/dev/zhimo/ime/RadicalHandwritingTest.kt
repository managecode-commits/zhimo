// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Manually reconstructed geometry inspired by screenshots, NOT original touch recordings. */
class RadicalHandwritingTest {
    private fun stroke(vararg xy: Int) = xy.toList().chunked(2).mapIndexed { i, p ->
        InkPoint(p[0].toFloat(), p[1].toFloat(), i.toLong())
    }
    private fun samples(): Map<String, InkRequest> = linkedMapOf(
        "比" to InkRequest(4, listOf(
            stroke(60,55, 58,130, 50,225, 50,285, 60,305, 82,308, 145,280, 185,255, 200,235),
            stroke(55,150, 95,110, 145,55),
            stroke(330,40, 310,100, 290,180, 275,240, 278,258, 293,266, 320,269, 355,263, 390,246, 420,216),
            stroke(270,145, 310,110, 355,80, 420,50)
        ), 500f, 350f, characterMode = HandwritingCharacterMode.MIXED),
        "观" to InkRequest(1, listOf(
            stroke(80,70, 160,30, 175,30, 170,55, 125,125, 50,215),
            stroke(70,85, 95,125, 160,190),
            stroke(230,35, 225,110, 215,155),
            stroke(245,45, 280,20, 310,15, 325,35, 330,100, 320,170),
            stroke(290,110, 270,160, 225,215),
            stroke(235,205, 265,195, 290,210, 310,240, 350,245, 400,240, 430,225, 435,175, 430,105)
        ), 500f, 300f, characterMode = HandwritingCharacterMode.MIXED),
        "动" to InkRequest(5, listOf(
            stroke(40,65, 170,55), stroke(25,120, 190,105),
            stroke(100,115, 40,230, 170,215), stroke(140,175, 190,235),
            stroke(245,115, 410,90, 395,210, 375,265, 345,245),
            stroke(325,45, 310,150, 270,235, 215,280)
        ), 500f, 350f, characterMode = HandwritingCharacterMode.MIXED),
        "从" to InkRequest(6, listOf(
            stroke(140,45, 90,160, 35,260), stroke(100,155, 205,260),
            stroke(335,45, 290,150, 225,275), stroke(315,155, 360,220, 430,260)
        ), 500f, 350f, characterMode = HandwritingCharacterMode.MIXED),
        "响" to InkRequest(7, listOf(
            stroke(40,140, 40,220), stroke(40,145, 105,135, 105,220), stroke(40,220, 105,220),
            stroke(300,35, 265,85), stroke(205,95, 205,280),
            stroke(205,95, 415,85, 415,280, 385,260),
            stroke(265,155, 265,225), stroke(265,155, 345,150, 345,225), stroke(265,225, 345,225)
        ), 500f, 350f, characterMode = HandwritingCharacterMode.MIXED),
        "北" to InkRequest(2, listOf(
            stroke(55,125, 110,95, 170,75), stroke(40,210, 105,160, 175,130),
            stroke(210,15, 210,110, 195,230),
            stroke(315,55, 300,130, 290,180, 305,195, 355,205, 400,205, 435,195, 440,185, 430,170),
            stroke(400,85, 350,130, 330,175)
        ), 500f, 280f, characterMode = HandwritingCharacterMode.MIXED),
        "物" to InkRequest(3, listOf(
            stroke(110,45, 85,100, 40,165), stroke(90,110, 145,100, 215,95),
            stroke(55,230, 105,195, 180,185, 205,170, 205,158),
            stroke(160,20, 150,95, 145,300),
            stroke(275,5, 270,70, 255,140),
            stroke(275,115, 330,95, 385,90, 400,100, 400,145, 380,240, 345,300, 330,290),
            stroke(290,155, 260,230), stroke(345,175, 290,230, 255,275)
        ), 450f, 350f, characterMode = HandwritingCharacterMode.MIXED)
    )

    @Test fun wholeGlyphAlternativesAreNeverBuriedBehindRadicalCombinations() {
        val alternatives = LineHandwritingProvider.rankHypotheses(listOf(
            listOf("人左", "人右", "从左"), listOf("从"),
            listOf("人广左", "人广右"), listOf("人一广左")), 2)
        assertTrue(alternatives.take(4).contains("从左"))
        assertTrue(alternatives.indexOf("人广左") > alternatives.indexOf("从左"))
        for ((character, request) in samples()) {
            val plans = HandwritingSegmentation.plans(request)
            assertTrue(plans.any { it.groups.size == 1 && it.groups.single().size == request.strokes.size })
            val clutter = (0 until 100).map { "牛" + (0x4e00 + it).toChar() }
            assertTrue(LineHandwritingProvider.rankHypotheses(listOf(clutter, listOf(character)), 2).take(2).contains(character))
            plans.forEach { assertEquals(request.strokes.indices.toList(), it.groups.flatten().sorted()) }
            val manual = HandwritingSegmentation.plans(request.copy(boundaries = emptyList()))
            assertEquals(1, manual.size); assertEquals(1, manual.first().groups.size)
        }
    }

    @Test fun offlineImageReconstructedRadicalsHaveWholeCharacterCandidates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var provider: LineHandwritingProvider
        instrumentation.runOnMainSync { provider = LineHandwritingProvider(FusedHandwritingProvider(ImageHandwritingProvider(instrumentation.targetContext), BundledHandwritingProvider(instrumentation.targetContext))) }
        try {
            val loaded = CountDownLatch(1)
            var ready = false
            instrumentation.runOnMainSync { provider.available { ready = it; loaded.countDown() } }
            assertTrue(loaded.await(30, TimeUnit.SECONDS)); assertTrue(ready)
            val samples = samples()
            val guan = samples.getValue("观")
            val joined = guan.copy(strokes = listOf((guan.strokes[0] + guan.strokes[1]).mapIndexed { index, p -> p.copy(timeMillis = index.toLong()) }) + guan.strokes.drop(2))
            for ((character, request) in samples.toList() + ("观" to joined)) {
                val done = CountDownLatch(1)
                var result: Result<List<String>>? = null
                instrumentation.runOnMainSync { provider.recognize(request) { result = it; done.countDown() } }
                assertTrue(done.await(45, TimeUnit.SECONDS))
                val choices = result!!.getOrThrow()
                android.util.Log.i("ZhimoAccuracy", "Reconstructed $character top20=${choices.take(20)}")
                assertTrue("$character missing from top8: ${choices.take(20)}", character in choices.take(8))
                if (character == "比") assertEquals("reconstructed 比 should lead: ${choices.take(10)}", character, choices.first())
                assertEquals(1, provider.choicesFor(character).size)
            }
        } finally { instrumentation.runOnMainSync { provider.close() } }
    }
}
