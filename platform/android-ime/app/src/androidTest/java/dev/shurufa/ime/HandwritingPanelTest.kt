package dev.shurufa.ime

import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HandwritingPanelTest {
    @Test fun correctionReplacesOneCharacterWithoutEarlyCommitAndRejectsStaleButtons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var panel: HandwritingPanel
        lateinit var candidates: LinearLayout
        val committed = mutableListOf<String>()
        var allowed = false
        val recognized = CountDownLatch(1)
        val single = object : HandwritingProvider {
            override fun available(done: (Boolean) -> Unit) = done(true)
            override fun close() {}
            override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
                done(Result.success(if (request.strokes.size > 1) listOf("从") else if (request.strokes[0][0].x < 200) listOf("人", "从") else listOf("左", "右")))
                recognized.countDown()
            }
        }
        fun find(view: View, description: String): View? {
            if (view.contentDescription == description) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                find(view.getChildAt(i), description)?.let { return it }
            }
            return null
        }
        instrumentation.runOnMainSync {
            candidates = LinearLayout(instrumentation.targetContext)
            panel = HandwritingPanel(instrumentation.targetContext, Color.BLACK, Color.WHITE, 1f, candidates,
                commit = { if (allowed) { committed.add(it); true } else false },
                providerFactory = { LineHandwritingProvider(single) }, initialLineMode = true)
            panel.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
            panel.layout(0,0,panel.measuredWidth,panel.measuredHeight)
            val canvas = (panel.getChildAt(2) as android.widget.FrameLayout).getChildAt(0)
            for (x in listOf(50f, 300f)) {
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now + 20, action, x + action * 50, 80f, 0)
                    canvas.dispatchTouchEvent(event); event.recycle()
                }
            }
        }
        try {
            assertTrue(recognized.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals("人左", (candidates.getChildAt(0) as android.widget.Button).text.toString())
                find(candidates, "逐字纠正手写候选")!!.performClick()
                val staleReplace = find(panel, "第1字替换为：从")!!
                staleReplace.performClick()
                assertTrue(committed.isEmpty()); assertTrue(panel.hasUncommitted())
                assertNotNull(find(panel, "纠正第2字：左"))
                find(panel, "纠正第2字：左")!!.performClick()
                find(panel, "第2字替换为：右")!!.performClick()
                val staleConfirm = find(panel, "确认逐字纠正上屏")!!
                staleConfirm.performClick() // rejected editor preserves correction draft
                assertTrue(committed.isEmpty()); assertTrue(panel.hasUncommitted())
                allowed = true
                panel.confirmFirstOr { fail("must commit corrected draft, not space or old first choice") }
                assertEquals(listOf("从右"), committed)
                assertFalse(panel.hasUncommitted())
                staleReplace.performClick(); staleConfirm.performClick()
                assertEquals(listOf("从右"), committed)
                panel.dispose(); staleConfirm.performClick()
                assertEquals(listOf("从右"), committed)
            }
        } finally { instrumentation.runOnMainSync { panel.dispose() } }
    }
    @Test fun lineKeepsAllInkAcrossPausesAndCommitsOnlyOnConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val candidates = LinearLayout(context)
        val committed = mutableListOf<String>()
        var allowed = false
        lateinit var panel: HandwritingPanel
        lateinit var canvas: View
        val provider = object : HandwritingProvider {
            override fun available(done: (Boolean) -> Unit) = done(true)
            override fun close() {}
            override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
                done(Result.success(if (request.strokes.size == 1) listOf("你", "他") else listOf("你好", "你子")))
            }
        }
        fun draw() = instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + 20, action, 100f + action * 40, 100f, 0)
                canvas.dispatchTouchEvent(event); event.recycle()
            }
        }
        fun waitCandidates() {
            val deadline = SystemClock.uptimeMillis() + 5000
            var found = false
            while (!found && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { found = candidates.childCount > 0 }
                if (!found) SystemClock.sleep(50)
            }
            assertTrue("missing candidates", found)
        }
        instrumentation.runOnMainSync {
            panel = HandwritingPanel(context, Color.BLACK, Color.WHITE, 1f, candidates,
                commit = { if (allowed) { committed.add(it); true } else false }, providerFactory = { provider }, initialLineMode = true)
            panel.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            canvas = (panel.getChildAt(2) as android.widget.FrameLayout).getChildAt(0)
        }
        try {
            draw(); waitCandidates()
            SystemClock.sleep(1900)
            instrumentation.runOnMainSync { assertTrue(committed.isEmpty()) }
            draw(); waitCandidates()
            instrumentation.runOnMainSync {
                assertEquals("你好", (candidates.getChildAt(0) as android.widget.Button).text.toString())
                assertFalse(panel.canLeave())
                panel.confirmFirstOr { fail("must not insert space during draft confirmation") }
                assertTrue(committed.isEmpty()); assertTrue(panel.hasUncommitted())
                panel.undo()
            }
            waitCandidates()
            instrumentation.runOnMainSync { assertEquals("你", (candidates.getChildAt(0) as android.widget.Button).text.toString()) }
            draw(); waitCandidates()
            instrumentation.runOnMainSync {
                allowed = true
                panel.confirmFirstOr { fail("must commit draft") }
                assertEquals(listOf("你好"), committed)
                assertFalse(panel.hasUncommitted()); assertTrue(panel.canLeave())
                panel.confirmFirstOr { committed.add(" ") }
                assertEquals(listOf("你好", " "), committed)
                panel.dispose()
            }
        } finally { instrumentation.runOnMainSync { panel.dispose() } }
    }
    private class FakeProvider : HandwritingProvider {
        val requestArrived = CountDownLatch(1)
        var callback: ((Result<List<String>>) -> Unit)? = null
        var closed = false
        override fun available(done: (Boolean) -> Unit) = done(true)
        override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
            callback = done; requestArrived.countDown()
        }
        override fun close() { closed = true }
    }
    @Test fun candidateClicksCommitOnceAndLateCallbacksCannotCommit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("shurufa", 0)
        val enabled = prefs.getBoolean("handwriting_enabled", false)
        prefs.edit().putBoolean("handwriting_enabled", true).commit()
        val provider = FakeProvider()
        lateinit var panel: HandwritingPanel
        var created = false
        lateinit var candidates: LinearLayout
        val committed = mutableListOf<String>()
        var allowCommit = false
        try {
            instrumentation.runOnMainSync {
                candidates = LinearLayout(context)
                panel = HandwritingPanel(context, Color.BLACK, Color.WHITE, 1f, candidates,
                    commit = { if (allowCommit) { committed.add(it); true } else false }, providerFactory = { provider }, initialLineMode = false)
                created = true
                panel.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val canvas = (panel.getChildAt(2) as android.widget.FrameLayout).getChildAt(0)
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time + action * 20, action, 100f + action * 40, 100f, 0)
                    canvas.dispatchTouchEvent(event); event.recycle()
                }
            }
            assertTrue("ink request missing", provider.requestArrived.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val values = listOf("中", "文") + (0 until 98).map { (0x6000 + it).toChar().toString() }
                provider.callback!!(Result.success(values))
                assertEquals(6, candidates.childCount)
                val oldKey = candidates.getChildAt(0)
                oldKey.performClick()
                panel.confirmFirstOr { fail("unfinished ink must not trigger an editor/voice action") }
                assertTrue(committed.isEmpty())
                assertEquals(6, candidates.childCount)
                candidates.getChildAt(5).performClick()
                val area = panel.getChildAt(2) as android.widget.FrameLayout
                val grid = area.getChildAt(1) as android.widget.ScrollView
                assertEquals(View.VISIBLE, grid.visibility)
                assertEquals(View.INVISIBLE, area.getChildAt(0).visibility)
                val rows = grid.getChildAt(0) as LinearLayout
                val keys = (0 until rows.childCount).flatMap { rowIndex ->
                    val row = rows.getChildAt(rowIndex) as LinearLayout
                    (0 until row.childCount).mapNotNull { row.getChildAt(it) as? android.widget.Button }
                }
                assertEquals(100, keys.size)
                assertEquals(values.last(), keys.last().text.toString())
                allowCommit = true
                keys.last().performClick()
                var emptyAction = 0
                panel.confirmFirstOr { emptyAction++ }
                assertEquals(1, emptyAction)
                assertEquals(View.GONE, grid.visibility)
                assertEquals(View.VISIBLE, area.getChildAt(0).visibility)
                oldKey.performClick(); oldKey.performClick()
                assertEquals(listOf(values.last()), committed)
                panel.dispose()
                provider.callback!!(Result.success(listOf("旧")))
                assertTrue(provider.closed)
                assertEquals(listOf(values.last()), committed)
            }
        } finally {
            instrumentation.runOnMainSync { if (created) panel.dispose() }
            prefs.edit().putBoolean("handwriting_enabled", enabled).commit()
        }
    }
}
