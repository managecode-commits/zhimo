// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class KeyboardFeedbackTest {
    @Test fun soundPolicyRespectsEverySuppressionCondition() {
        assertTrue(KeyboardFeedbackPolicy.soundAllowed(true, false, true, true, true, true))
        assertFalse(KeyboardFeedbackPolicy.soundAllowed(false, false, true, true, true, true))
        assertFalse(KeyboardFeedbackPolicy.soundAllowed(true, true, true, true, true, true))
        assertFalse(KeyboardFeedbackPolicy.soundAllowed(true, false, false, true, true, true))
        assertFalse(KeyboardFeedbackPolicy.soundAllowed(true, false, true, false, true, true))
        assertFalse(KeyboardFeedbackPolicy.soundAllowed(true, false, true, true, false, true))
        assertFalse(KeyboardFeedbackPolicy.soundAllowed(true, false, true, true, true, false))
    }

    @Test fun repeatingFeedbackIsRateLimitedNotQueued() {
        val limit = FeedbackRateLimit(130)
        assertTrue(limit.accept(0))
        assertFalse(limit.accept(65))
        assertTrue(limit.accept(130))
        assertFalse(limit.accept(131))
        assertTrue(limit.accept(260))
    }

    @Test fun touchHasOneFeedbackAndCancelledGestureNeverClicks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(
            instrumentation.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var key: KeyboardKeyView
        var clicks = 0
        var feedback = 0
        instrumentation.runOnMainSync {
            key = KeyboardKeyView(activity)
            key.feedback = { feedback++ }
            key.setOnClickListener { clicks++ }
            activity.setContentView(key, android.view.ViewGroup.LayoutParams(100, 100))
        }
        instrumentation.waitForIdleSync()
        fun send(action: Int, x: Float = 50f) {
            instrumentation.runOnMainSync {
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, action, x, 50f, 0)
                key.dispatchTouchEvent(event)
                event.recycle()
            }
            instrumentation.waitForIdleSync()
        }
        try {
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_UP)
            assertEquals(1, clicks)
            assertEquals(1, feedback)
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_MOVE, 120f)
            send(MotionEvent.ACTION_MOVE, 50f)
            send(MotionEvent.ACTION_UP)
            assertEquals(1, clicks)
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_CANCEL)
            send(MotionEvent.ACTION_UP)
            assertEquals(1, clicks)
            instrumentation.runOnMainSync { key.performClick() } // Accessibility activation.
            assertEquals(2, clicks)
            assertFalse(key.isSoundEffectsEnabled)
            assertFalse(key.isHapticFeedbackEnabled)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
