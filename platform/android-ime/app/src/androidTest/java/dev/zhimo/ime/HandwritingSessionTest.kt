// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class HandwritingSessionTest {
    private fun stroke() = listOf(InkPoint(1f, 2f, 1), InkPoint(3f, 4f, 2))
    @Test fun staleResultsAndClicksAreRejected() {
        val session = HandwritingSession()
        assertTrue(session.add(stroke()))
        val first = session.snapshot(100f, 100f)!!
        assertTrue(session.accept(first.revision, listOf("中", "中", "", "文")))
        assertEquals(listOf("中", "文"), session.choices)
        session.invalidate() // next pointer DOWN, before completed stroke
        assertNull(session.candidate(first.revision, 0))
        assertFalse(session.accept(first.revision, listOf("旧")))
    }
    @Test fun undoClearAndCloseInvalidateRequests() {
        val session = HandwritingSession()
        session.add(stroke()); session.add(stroke())
        val revision = session.revision
        session.undo()
        assertEquals(1, session.snapshot(100f, 100f)!!.strokes.size)
        assertFalse(session.accept(revision, listOf("旧")))
        session.clear(); assertNull(session.snapshot(100f, 100f))
        session.close(); assertFalse(session.add(stroke()))
        assertFalse(session.accept(session.revision, listOf("旧")))
    }
    @Test fun rejectsInvalidAndUnboundedInk() {
        val session = HandwritingSession()
        assertFalse(session.add(emptyList()))
        assertFalse(session.add(listOf(InkPoint(Float.NaN, 2f, 1))))
        assertFalse(session.add(listOf(InkPoint(1f, 2f, 2), InkPoint(1f, 2f, 1))))
        repeat(128) { assertTrue(session.add(stroke())) }
        assertFalse(session.add(stroke()))
        assertNull(session.snapshot(0f, 100f))
    }
    @Test fun confirmationRetainsUnicodeAndRejectsControlText() {
        val session = HandwritingSession(); session.add(stroke())
        assertTrue(session.accept(session.revision, listOf("𠮷", "你好", "\n", "a\tb")))
        assertEquals(listOf("𠮷", "你好"), session.choices)
        val version = session.revision
        assertEquals("𠮷", session.candidate(version, 0))
        session.clear()
        assertNull(session.candidate(version, 0))
    }
}
