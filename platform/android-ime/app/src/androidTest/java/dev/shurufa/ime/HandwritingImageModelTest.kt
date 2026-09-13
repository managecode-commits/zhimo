package dev.shurufa.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HandwritingImageModelTest {
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
