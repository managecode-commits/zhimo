// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Main-thread gesture state, including a queued start which may outlive finger-up. */
internal class HoldToTalkGesture {
    private var generation = 0L
    private var down = false
    private var recording = false
    var suppressTap = false
        private set
    fun down() { generation++; down = true; recording = false; suppressTap = false }
    fun longPress(): Long? {
        if (!down) return null // Accessibility long-click uses explicit start/stop.
        suppressTap = true
        return generation
    }
    fun begin(token: Long): Boolean {
        if (!down || generation != token || recording) return false
        recording = true
        return true
    }
    fun release(): Boolean {
        val wasRecording = recording
        down = false; recording = false; generation++
        return wasRecording
    }
    fun cancel(): Boolean { suppressTap = true; return release() }
}
