// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class HoldToTalkGestureTest {
    @Test fun shortTapRemainsSpace() {
        val g = HoldToTalkGesture()
        g.down()
        assertFalse(g.release())
        assertFalse(g.suppressTap)
    }
    @Test fun holdFinishesExactlyOnceAndNeverTypesSpace() {
        val g = HoldToTalkGesture()
        g.down()
        assertTrue(g.begin(g.longPress()!!))
        assertTrue(g.release())
        assertFalse(g.release())
        assertTrue(g.suppressTap)
    }
    @Test fun queuedStartCannotRecordAfterFingerUp() {
        val g = HoldToTalkGesture()
        g.down()
        val token = g.longPress()!!
        assertFalse(g.release())
        assertFalse(g.begin(token))
        assertTrue(g.suppressTap)
    }
    @Test fun oldQueuedStartCannotJoinNextTouch() {
        val g = HoldToTalkGesture()
        g.down()
        val token = g.longPress()!!
        g.release(); g.down()
        assertFalse(g.begin(token))
        assertFalse(g.suppressTap)
        assertTrue(g.begin(g.longPress()!!))
    }
    @Test fun slideOutOrDetachCancelsAndInvalidatesPendingStart() {
        val g = HoldToTalkGesture()
        g.down()
        val token = g.longPress()!!
        assertTrue(g.begin(token))
        assertTrue(g.cancel())
        assertFalse(g.release())
        assertFalse(g.begin(token))
    }
    @Test fun accessibilityLongClickHasNoTouchToken() {
        assertNull(HoldToTalkGesture().longPress())
    }
}
