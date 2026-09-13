package dev.shurufa.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses APK assets only. Run after a fresh offline install; never downloads models. */
class HandwritingModelTest {
    @Test fun realChineseModelRecognizesHorizontalStroke() {
        recognizeHorizontalStroke()
    }

    @Test fun repairsCorruptCacheFromBundledAsset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.noBackupFilesDir, "handwriting-zh-cn-v03.model").writeText("corrupt test cache")
        recognizeHorizontalStroke()
        assertEquals(BundledHandwritingProvider.MODEL_SIZE,
            java.io.File(context.noBackupFilesDir, "handwriting-zh-cn-v03.model").length())
    }

    private fun recognizeHorizontalStroke() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var provider: HandwritingProvider
        instrumentation.runOnMainSync { provider = BundledHandwritingProvider(instrumentation.targetContext) }
        try {
            val prepared = CountDownLatch(1)
            var ready = false
            instrumentation.runOnMainSync {
                val done: (Boolean) -> Unit = { ok -> ready = ok; prepared.countDown() }
                provider.available(done)
            }
            assertTrue("bundled model preparation timed out", prepared.await(30, TimeUnit.SECONDS))
            assertTrue("bundled model preparation failed", ready)
            val recognized = CountDownLatch(1)
            var result: Result<List<String>>? = null
            val points = (0..20).map { InkPoint(30f + it * 10, 100f, 1000L + it * 15) }
            instrumentation.runOnMainSync {
                provider.recognize(InkRequest(1, listOf(points), 280f, 200f)) {
                    result = it; recognized.countDown()
                }
            }
            assertTrue("recognition timed out", recognized.await(30, TimeUnit.SECONDS))
            assertTrue("expected 一 in real model candidates", result!!.getOrThrow().contains("一"))
        } finally { instrumentation.runOnMainSync { provider.close() } }
    }
}
