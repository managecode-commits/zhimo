// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class OrderedInputQueueTest {
    private val worker = ArrayDeque<() -> Unit>()
    private val main = ArrayDeque<() -> Unit>()
    private val queue = OrderedInputQueue({ worker.addLast(it) }, { main.addLast(it) })
    private fun finish() { worker.removeFirst()(); main.removeFirst()() }

    @Test fun rapidKeysAndModeBarrierStayOrdered() {
        val results = mutableListOf<Int>()
        repeat(100) { n -> queue.dispatch { queue.compute({ n }) { results.add(it.getOrThrow()) } } }
        queue.dispatch { results.add(100) }
        assertTrue(results.isEmpty())
        repeat(100) { assertEquals(1, worker.size); finish() }
        assertEquals((0..100).toList(), results)
        assertFalse(queue.pending)
    }

    @Test fun editorChangeDropsOldResultsAndQueuedKeysBeforeReset() {
        val results = mutableListOf<String>()
        queue.dispatch { queue.compute({ "old" }) { results.add(it.getOrThrow()) } }
        queue.dispatch { results.add("stale key") }
        queue.invalidate()
        queue.dispatch { results.add("new editor reset") }
        finish()
        assertEquals(listOf("new editor reset"), results)
    }

    @Test fun nativeFailureDoesNotStallLaterKeys() {
        var failed = false
        var next = false
        queue.dispatch { queue.compute({ error("native failure") }) { failed = it.isFailure } }
        queue.dispatch { next = true }
        finish()
        assertTrue(failed && next)
    }

    @Test fun reentrantActionWaitsUntilResultWasApplied() {
        val results = mutableListOf<Int>()
        queue.dispatch { queue.compute({ 1 }) {
            queue.dispatch { results.add(2) }
            results.add(it.getOrThrow())
        } }
        finish()
        assertEquals(listOf(1, 2), results)
    }
}
