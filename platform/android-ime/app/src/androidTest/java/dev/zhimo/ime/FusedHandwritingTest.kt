// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class FusedHandwritingTest {
    private class Fake(private val values: Result<List<String>>, private val ready: Boolean = true) : HandwritingProvider {
        var calls = 0
        var closes = 0
        override fun available(done: (Boolean) -> Unit) = done(ready)
        override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) { calls++; done(values) }
        override fun close() { closes++ }
    }
    private val request = InkRequest(1, listOf(listOf(InkPoint(1f, 1f, 1))), 10f, 10f)

    @Test fun consensusAndIndependentCandidatesAreRetained() {
        val values = FusedHandwritingProvider.merge(listOf("向", "响", "t"), listOf("响", "口", "向"), HandwritingCharacterMode.MIXED)
        assertEquals("响", values.first())
        assertTrue(values.containsAll(listOf("向", "口", "t")))
        assertEquals(values.distinct(), values)
        assertEquals(listOf("t"), FusedHandwritingProvider.merge(listOf("向", "t"), listOf("响"), HandwritingCharacterMode.LETTERS))
    }

    @Test fun imageFailureFallsBackToTrajectory() {
        val image = Fake(Result.failure(IllegalStateException("unavailable")), false)
        val stroke = Fake(Result.success(listOf("观", "见")))
        val provider = FusedHandwritingProvider(image, stroke)
        provider.available { assertTrue(it) }
        provider.recognize(request) { assertEquals(listOf("观", "见"), it.getOrThrow()) }
        assertEquals(0, image.calls); assertEquals(1, stroke.calls)
        provider.close(); provider.close()
        assertEquals(1, image.closes); assertEquals(1, stroke.closes)
        provider.recognize(request) { fail("closed provider delivered a result") }
    }

    @Test fun trajectoryFailurePreservesImageAndLettersBypassTrajectory() {
        val image = Fake(Result.success(listOf("比", "t")))
        val stroke = Fake(Result.failure(IllegalStateException("failure")))
        val provider = FusedHandwritingProvider(image, stroke)
        provider.available { assertTrue(it) }
        provider.recognize(request.copy(characterMode = HandwritingCharacterMode.MIXED)) { assertEquals(listOf("比", "t"), it.getOrThrow()) }
        provider.recognize(request.copy(characterMode = HandwritingCharacterMode.LETTERS)) { assertTrue(it.isSuccess) }
        assertEquals(1, stroke.calls)
        provider.close()
    }
}
