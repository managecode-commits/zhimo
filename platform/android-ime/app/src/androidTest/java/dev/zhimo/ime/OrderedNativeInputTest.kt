// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class OrderedNativeInputTest {
    private fun burst(code: String, eraseLast: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directories = RimeAssets.prepare(context)
        val storage = File(context.cacheDir, "ordered-input-${System.nanoTime()}").apply { mkdirs() }
        val handle = NativeIme.create(storage.path, directories.shared.path, directories.user.path)
        assertTrue(handle != 0L)
        NativeIme.setPrivacy(handle, false, false)
        assertEquals(0, NativeIme.switchEngine(handle, "pinyin.reference"))
        val worker = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val queue = OrderedInputQueue({ worker.execute(it) }, { main.post(it) })
        val done = CountDownLatch(1)
        val responsive = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val committed = StringBuilder()
        try {
            val started = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                fun edit(operation: () -> Int) {
                    queue.dispatch { queue.compute({
                        check(Looper.myLooper() != Looper.getMainLooper())
                        check(operation() == 0)
                        InputActionDecoder.decode(NativeIme.actions(handle))
                    }) { result ->
                        result.onSuccess { batch ->
                            assertEquals(Looper.getMainLooper(), Looper.myLooper())
                            batch.filterIsInstance<InputAction.Commit>().forEach { committed.append(it.text) }
                        }.onFailure { failure.set(it) }
                    } }
                }
                repeat(20) {
                    code.forEach { letter -> edit { NativeIme.feed(handle, letter.toString()) } }
                    if (eraseLast) edit { NativeIme.command(handle, 0) }
                    edit { NativeIme.command(handle, 2) }
                }
                queue.dispatch { done.countDown() }
                main.post { responsive.countDown() }
            }
            assertTrue("main loop must remain responsive during native work", responsive.await(5, TimeUnit.SECONDS))
            assertTrue("burst did not drain", done.await(90, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Native burst failed", it) }
            assertEquals("你好".repeat(20), committed.toString())
            android.util.Log.i("ZhimoInputTest", "mode=${if (code.first().isDigit()) "t9" else "qwerty"} " +
                "backspace=$eraseLast operations=${20*(code.length+1+if (eraseLast) 1 else 0)} " +
                "elapsedMs=${SystemClock.elapsedRealtime()-started}")
        } finally {
            // Keep destruction behind all outstanding work, including on timeout.
            instrumentation.runOnMainSync {
                queue.invalidate()
                queue.dispatch { NativeIme.destroy(handle); worker.shutdown() }
            }
        }
    }

    @Test fun qwertyBurstKeepsEveryCommit() = burst("nihao", false)
    @Test fun nineKeyBurstKeepsEveryCommit() = burst("64426", false)
    @Test fun backspaceBetweenRapidKeysKeepsOrder() = burst("nihaoo", true)
}
