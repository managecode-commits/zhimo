// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class KeyboardFeedbackPolicyTest {
    @Test fun feedbackDefaultsOffButExplicitOptInStillWorks() {
        assertFalse(KeyboardFeedbackPolicy.DEFAULT_ENABLED)
        assertEquals(0, KeyboardFeedbackPolicy.strength(KeyboardFeedbackPolicy.DEFAULT_ENABLED, 35))
        assertFalse(KeyboardFeedbackPolicy.soundAllowed(KeyboardFeedbackPolicy.DEFAULT_ENABLED, false, true, true, true, true))
        assertEquals(35, KeyboardFeedbackPolicy.strength(true, 35))
        assertTrue(KeyboardFeedbackPolicy.soundAllowed(true, false, true, true, true, true))
    }
    @Test fun amplitudeDevicesDoNotRequireClickPrimitive() {
        assertEquals(HapticBackend.AMPLITUDE, KeyboardFeedbackPolicy.backend(true, true, false))
        assertEquals(HapticBackend.AMPLITUDE, KeyboardFeedbackPolicy.backend(true, true, true))
        assertEquals(HapticBackend.PRIMITIVE, KeyboardFeedbackPolicy.backend(true, false, true))
        assertEquals(HapticBackend.SYSTEM, KeyboardFeedbackPolicy.backend(true, false, false))
        assertEquals(HapticBackend.NONE, KeyboardFeedbackPolicy.backend(false, true, true))
    }
    @Test fun amplitudeIsStrictlyIncreasingAndZeroReallyOff() {
        assertEquals(0, KeyboardFeedbackPolicy.amplitude(0))
        assertEquals(0, KeyboardFeedbackPolicy.amplitude(-100))
        assertEquals(255, KeyboardFeedbackPolicy.amplitude(100))
        assertEquals(255, KeyboardFeedbackPolicy.amplitude(101))
        for (value in 0..99) assertTrue(KeyboardFeedbackPolicy.amplitude(value) < KeyboardFeedbackPolicy.amplitude(value + 1))
    }
    @Test fun zeroIsOffAndStrengthIsBoundedAndMonotonic() {
        assertEquals(0, KeyboardFeedbackPolicy.strength(false, 100))
        assertEquals(0, KeyboardFeedbackPolicy.strength(true, -1))
        assertEquals(100, KeyboardFeedbackPolicy.strength(true, 101))
        assertNull(KeyboardFeedbackPolicy.primitiveScale(0))
        assertNull(KeyboardFeedbackPolicy.primitiveScale(-1))
        assertEquals(1f, KeyboardFeedbackPolicy.primitiveScale(101)!!, 0f)
        for (value in 1..99) assertTrue(KeyboardFeedbackPolicy.primitiveScale(value)!! < KeyboardFeedbackPolicy.primitiveScale(value + 1)!!)
    }
    @Test fun all64PolicyCombinationsFailClosed() {
        for (mask in 0 until 64) {
            fun bit(index: Int) = mask and (1 shl index) != 0
            val actual = KeyboardFeedbackPolicy.soundAllowed(bit(0), bit(1), bit(2), bit(3), bit(4), bit(5))
            assertEquals("policy mask=$mask", mask == 61, actual)
        }
    }
    @Test fun repeatRateLimitHasNoQueuedCatchup() {
        val limit = FeedbackRateLimit(130)
        assertTrue(limit.accept(0))
        assertFalse(limit.accept(65))
        assertTrue(limit.accept(130))
        assertFalse(limit.accept(131))
        assertTrue(limit.accept(10_000))
        assertFalse(limit.accept(10_001))
    }
    @Test fun soundRateLimitAllowsFastTypingButNotDuplicateEvents() {
        val limit = FeedbackRateLimit(35)
        assertTrue(limit.accept(0))
        assertFalse(limit.accept(0))
        assertFalse(limit.accept(34))
        assertTrue(limit.accept(35))
        assertTrue(limit.accept(70))
    }
}
