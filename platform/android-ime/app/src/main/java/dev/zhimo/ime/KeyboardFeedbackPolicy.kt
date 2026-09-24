// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

enum class HapticBackend { NONE, AMPLITUDE, PRIMITIVE, SYSTEM }

object KeyboardFeedbackPolicy {
    const val DEFAULT_ENABLED = false
    fun backend(hasMotor: Boolean, amplitude: Boolean, primitive: Boolean): HapticBackend = when {
        !hasMotor -> HapticBackend.NONE
        amplitude -> HapticBackend.AMPLITUDE
        primitive -> HapticBackend.PRIMITIVE
        else -> HapticBackend.SYSTEM
    }
    fun amplitude(strength: Int): Int = if (strength <= 0) 0 else
        (24 + strength.coerceAtMost(100) * 231 / 100).coerceIn(1, 255)
    fun strength(enabled: Boolean, stored: Int): Int = if (enabled) stored.coerceIn(0, 100) else 0
    fun primitiveScale(strength: Int): Float? = strength.coerceIn(0, 100).takeIf { it > 0 }?.div(100f)
    fun soundAllowed(enabled: Boolean, muted: Boolean, normalRinger: Boolean,
        normalAudioMode: Boolean, unrestricted: Boolean, audible: Boolean): Boolean =
        enabled && !muted && normalRinger && normalAudioMode && unrestricted && audible
}

class FeedbackRateLimit(private val intervalMs: Long) {
    private var last: Long? = null
    fun accept(now: Long): Boolean {
        if (last?.let { now - it < intervalMs } == true) return false
        last = now
        return true
    }
}
