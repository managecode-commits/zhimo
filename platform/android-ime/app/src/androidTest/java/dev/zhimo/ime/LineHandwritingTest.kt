// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LineHandwritingTest {
    @Test fun mixedLineRetainsLatinAndDigitsForPerCharacterCorrection() {
        val provider = LineHandwritingProvider(object : HandwritingProvider {
            override fun available(done: (Boolean) -> Unit) = done(true)
            override fun close() {}
            override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
                assertEquals(HandwritingCharacterMode.MIXED,request.characterMode)
                done(Result.success(if(request.strokes.size>1) listOf("中") else if(request.strokes[0][0].x<100) listOf("A","a") else listOf("0","O")))
            }
        })
        provider.recognize(ink().copy(characterMode=HandwritingCharacterMode.MIXED)) {
            assertEquals("A0",it.getOrThrow().first())
            assertEquals(listOf(listOf("A","a"),listOf("0","O")),provider.characterChoices)
        }
        provider.close()
    }
    @Test fun differentCharacterCountsCompeteInFirstTwoSlots() {
        val ranked = LineHandwritingProvider.rankHypotheses(listOf(listOf("人左", "从左", "入左"), listOf("从", "人")), 2)
        assertEquals(listOf("人左", "从", "从左", "人", "入左"), ranked)
        assertEquals(listOf("从"), LineHandwritingProvider.rankHypotheses(listOf(emptyList(), listOf("从")), 2))
        assertEquals(listOf("𠮷左", "从"), LineHandwritingProvider.rankHypotheses(listOf(listOf("𠮷左"), listOf("从")), 2))
    }

    private fun congZuoInk(): InkRequest {
        fun s(vararg xy: Float) = xy.toList().chunked(2).mapIndexed { i, p -> InkPoint(p[0], p[1], i.toLong()) }
        // Manually constructed strokes inspired by the screenshot; not the original touch recording.
        return InkRequest(42, listOf(s(70f,20f, 30f,140f), s(45f,85f, 90f,145f),
            s(125f,20f, 75f,145f), s(115f,80f, 155f,125f),
            s(185f,45f, 280f,32f), s(250f,10f, 190f,135f),
            s(207f,90f, 265f,70f), s(247f,85f, 233f,145f), s(195f,150f, 285f,145f)), 320f, 180f)
    }
    @Test fun congZuoGeometryPreservesBothRadicalsAndExposesPerCharacterChoices() {
        val ink = congZuoInk()
        val plan = HandwritingSegmentation.plans(ink).first()
        assertEquals(listOf(listOf(0,1,2,3), listOf(4,5,6,7,8)), plan.groups)
        val single = object : HandwritingProvider {
            override fun available(done: (Boolean) -> Unit) = done(true)
            override fun close() {}
            override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
                done(Result.success(when (request.strokes.size) { 4 -> listOf("人", "从"); 5 -> listOf("左", "右"); else -> listOf("从") }))
            }
        }
        val provider = LineHandwritingProvider(single)
        provider.recognize(ink) {
            val result = it.getOrThrow()
            assertTrue(result.take(4).contains("从左"))
            assertTrue(result.take(2).contains("从"))
            assertEquals(1, provider.choicesFor("从").size)
            assertEquals(2, provider.choicesFor("从左").size)
            assertEquals(42L, provider.characterRevision)
            assertEquals(listOf("人", "从"), provider.characterChoices[0])
            assertEquals(listOf("左", "右"), provider.characterChoices[1])
        }
        provider.close(); assertTrue(provider.characterChoices.isEmpty())
    }
    @Test fun offlineImageCongZuoHasCorrectableCharacters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var provider: LineHandwritingProvider
        instrumentation.runOnMainSync { provider = LineHandwritingProvider(FusedHandwritingProvider(ImageHandwritingProvider(instrumentation.targetContext), BundledHandwritingProvider(instrumentation.targetContext))) }
        try {
            val prepared = CountDownLatch(1)
            var ready = false
            instrumentation.runOnMainSync { provider.available { ready = it; prepared.countDown() } }
            assertTrue(prepared.await(30, TimeUnit.SECONDS)); assertTrue(ready)
            val recognized = CountDownLatch(1)
            var result: Result<List<String>>? = null
            instrumentation.runOnMainSync { provider.recognize(congZuoInk()) { result = it; recognized.countDown() } }
            assertTrue(recognized.await(30, TimeUnit.SECONDS))
            val words = result!!.getOrThrow()
            android.util.Log.i("ZhimoAccuracy", "synthetic congzuo top10=${words.take(10)} parts=${provider.characterChoices.map { it.take(10) }}")
            assertTrue("cong missing: ${provider.characterChoices[0].take(20)}", provider.characterChoices[0].take(20).contains("从"))
            assertTrue("zuo missing: ${provider.characterChoices[1].take(20)}", provider.characterChoices[1].take(20).contains("左"))
            assertTrue("complete phrase missing from top10: ${words.take(20)}", words.take(10).contains("从左"))
        } finally { instrumentation.runOnMainSync { provider.close() } }
    }
    @Test fun newInstallDefaultsToImageLineButExplicitOptOutIsRespected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("zhimo", 0)
        val existed = prefs.contains("handwriting_image_experimental")
        val old = prefs.getBoolean("handwriting_image_experimental", true)
        val oldMode = prefs.getString("handwriting_character_mode", null)
        fun language(): String {
            var selected = ""
            instrumentation.runOnMainSync {
                val panel = HandwritingPanel(context, android.graphics.Color.BLACK, android.graphics.Color.WHITE, 1f,
                    android.widget.LinearLayout(context), commit = { false }, providerFactory = { value ->
                        selected = value
                        object : HandwritingProvider {
                            override fun available(done: (Boolean) -> Unit) = done(true)
                            override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) = done(Result.success(emptyList()))
                            override fun close() {}
                        }
                    })
                panel.dispose()
            }
            return selected
        }
        try {
            prefs.edit().remove("handwriting_image_experimental").commit()
            prefs.edit().remove("handwriting_character_mode").commit()
            assertEquals("mixed-line", language())
            prefs.edit().putBoolean("handwriting_image_experimental", false).commit()
            assertEquals("zh-CN", language())
        } finally {
            prefs.edit().apply { if (existed) putBoolean("handwriting_image_experimental", old) else remove("handwriting_image_experimental") }.commit()
            prefs.edit().putString("handwriting_character_mode", oldMode).commit()
        }
    }
    private fun ink() = InkRequest(7, listOf(
        listOf(InkPoint(120f, 100f, 0), InkPoint(180f, 100f, 1)),
        listOf(InkPoint(20f, 100f, 2), InkPoint(80f, 100f, 3))), 300f, 200f)

    @Test fun spatialOrderDoesNotDependOnTimingOrStrokeOrder() {
        val cells = LineHandwritingProvider.split(ink())
        assertEquals(2, cells.size)
        assertEquals(20f, cells[0].strokes[0][0].x, 0f)
        assertEquals(2L, cells[0].strokes[0][0].timeMillis)
        assertEquals(0L, cells[1].strokes[0][0].timeMillis)
        assertTrue(cells.all { it.width == 300f && it.revision == 7L })
        val sentence = LineHandwritingProvider.combine(listOf(listOf("你", "他"), listOf("好", "子")))
        assertEquals("你好", sentence.first())
        assertTrue(sentence.contains("你子")); assertTrue(sentence.contains("他好"))
        assertEquals(listOf("𠮷一"), LineHandwritingProvider.combine(listOf(listOf("𠮷"), listOf("一"))))
        assertTrue(LineHandwritingProvider.combine(listOf(listOf("你"), emptyList())).isEmpty())
    }

    @Test fun crossingOldGridIsAcceptedAndChoicesBounded() {
        val bad = ink().copy(strokes = listOf(listOf(InkPoint(80f, 100f, 0), InkPoint(120f, 100f, 1))))
        assertEquals(bad.strokes, LineHandwritingProvider.split(bad).single().strokes)
        assertTrue(runCatching { LineHandwritingProvider.split(ink().copy(width = Float.NaN)) }.isFailure)
        val choices = (0 until 100).map { (0x4e00 + it).toChar().toString() }
        assertEquals(50, LineHandwritingProvider.combine(List(3) { choices }).size)
        assertEquals(100, LineHandwritingProvider.combine(listOf(choices)).size)
    }

    @Test fun screenshotLikeWideCharactersStayWholeAndManualMergeIsAvailable() {
        fun stroke(left: Float, right: Float, y: Float) = listOf(InkPoint(left,y,0), InkPoint(right,y,1))
        // Synthetic geometry only: two wide glyph groups crossing the former 1/3 and 2/3 grid lines.
        val request = InkRequest(1, listOf(stroke(10f, 165f, 50f), stroke(30f, 150f, 130f),
            stroke(185f, 295f, 60f), stroke(190f, 285f, 150f)), 300f, 200f)
        val plans = HandwritingSegmentation.plans(request)
        assertEquals(listOf(listOf(0,1), listOf(2,3)), plans.first().groups)
        assertTrue(plans.any { it.groups.size == 1 }) // left/right radicals can also form one glyph
        assertTrue(plans.size <= 4)
        plans.forEach { plan -> assertEquals(listOf(0,1,2,3), plan.groups.flatten().sorted()) }
        assertEquals(1, HandwritingSegmentation.plans(request.copy(boundaries = emptyList())).single().groups.size)
        assertEquals(2, HandwritingSegmentation.plans(request.copy(boundaries = listOf(.6f))).single().groups.size)
        assertTrue(runCatching { HandwritingSegmentation.plans(request.copy(boundaries = listOf(Float.NaN))) }.isFailure)
        assertTrue(runCatching { HandwritingSegmentation.plans(request.copy(boundaries = listOf(.8f,.2f))) }.isFailure)
    }

    @Test fun alternateHypothesisSurvivesFailureAndCloseDropsLateResult() {
        var pending: ((Result<List<String>>) -> Unit)? = null
        val single = object : HandwritingProvider {
            override fun available(done: (Boolean) -> Unit) = done(true)
            override fun close() {}
            override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) { pending = done }
        }
        val provider = LineHandwritingProvider(single)
        var result: Result<List<String>>? = null
        provider.recognize(ink()) { result = it }
        pending!!(Result.success(listOf("一")))
        pending!!(Result.success(listOf("一")))
        pending!!(Result.failure(IllegalStateException("synthetic merged-hypothesis failure")))
        assertEquals(listOf("一一"), result!!.getOrThrow())
        result = null
        provider.recognize(ink()) { result = it }
        provider.close(); pending!!(Result.success(listOf("旧")))
        assertNull(result)
    }

    @Test fun offlineImageLineRecognizesTwoCharactersWithoutEarlyCommit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var provider: HandwritingProvider
        instrumentation.runOnMainSync { provider = LineHandwritingProvider(ImageHandwritingProvider(instrumentation.targetContext)) }
        try {
            val prepared = CountDownLatch(1)
            var ready = false
            instrumentation.runOnMainSync { provider.available { ready = it; prepared.countDown() } }
            assertTrue(prepared.await(30, TimeUnit.SECONDS)); assertTrue(ready)
            val recognized = CountDownLatch(1)
            var result: Result<List<String>>? = null
            instrumentation.runOnMainSync { provider.recognize(ink()) { result = it; recognized.countDown() } }
            assertTrue(recognized.await(30, TimeUnit.SECONDS))
            assertTrue("line choices: ${result!!.getOrThrow().take(10)}", result!!.getOrThrow().take(5).contains("一一"))
        } finally { instrumentation.runOnMainSync { provider.close() } }
    }
}
