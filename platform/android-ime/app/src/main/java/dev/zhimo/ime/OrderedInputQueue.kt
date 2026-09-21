// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Main-thread coordinator. Only one native operation may run; editor effects stay on main. */
internal class OrderedInputQueue(
    private val execute: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
) {
    private val actions = ArrayDeque<() -> Unit>()
    private var draining = false
    var working = false
        private set
    private var generation = 0L
    val pending: Boolean get() = working || actions.isNotEmpty()

    fun dispatch(action: () -> Unit) { actions.addLast(action); drain() }
    fun invalidate() { generation++; actions.clear() }

    fun <T> compute(work: () -> T, apply: (Result<T>) -> Unit) {
        check(!working)
        working = true
        val epoch = generation
        execute {
            val result = runCatching(work)
            post {
                working = false
                draining = true
                try { if (epoch == generation) apply(result) }
                finally { draining = false; drain() }
            }
        }
    }

    private fun drain() {
        if (draining || working) return
        draining = true
        try { while (!working && actions.isNotEmpty()) actions.removeFirst().invoke() }
        finally { draining = false }
    }
}
