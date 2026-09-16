// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

enum class KeyFeedback { TAP, LONG_PRESS, REPEAT, ADJUST }

/** One owner per keyboard/settings window; no audio focus or volume changes. */
class KeyboardFeedbackController(private val context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences("zhimo", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    val hapticBackend: HapticBackend by lazy {
        runCatching {
            KeyboardFeedbackPolicy.backend(vibrator?.hasVibrator() == true,
                vibrator?.hasAmplitudeControl() == true,
                Build.VERSION.SDK_INT >= 30 && vibrator?.arePrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK)?.firstOrNull() == true)
        }.getOrDefault(HapticBackend.NONE)
    }
    val adjustableHaptics get() = hapticBackend == HapticBackend.AMPLITUDE || hapticBackend == HapticBackend.PRIMITIVE
    private var lastHapticError: String? = null
    private var lastSoundError: String? = null
    private var pool: SoundPool? = null
    private var sample = 0
    private var ready = false
    private var closed = false
    private val soundLimit = FeedbackRateLimit(35)
    private val repeatLimit = FeedbackRateLimit(130)
    private val adjustmentLimit = FeedbackRateLimit(80)
    var muted = false
        set(value) {
            field = value
            if (value) { pool?.autoPause(); runCatching { vibrator?.cancel() } }
        }

    fun prepare() {
        if (closed || !preferences.getBoolean("key_sound_enabled", true)) return
        if (preferences.getString("key_sound_style", "soft") == "system") {
            runCatching { audio?.loadSoundEffects() }
            return
        }
        if (pool != null) return
        runCatching {
            val soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            pool = soundPool
            soundPool.setOnLoadCompleteListener { _, _, status ->
                ready = !closed && status == 0
                if (status != 0) lastSoundError = "轻敲音加载失败，请重新打开设置或键盘"
            }
            sample = soundPool.load(context, R.raw.key_soft, 1)
        }.onFailure { pool?.release(); pool = null; ready = false; lastSoundError = "音效初始化失败" }
    }

    private fun systemTouchEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    fun hapticHint(): String = runCatching {
        when {
            hapticBackend == HapticBackend.NONE -> "未检测到振动马达，无法提供触觉反馈。"
            !systemTouchEnabled() -> "系统触觉反馈已关闭，请在系统声音与振动设置中开启。"
            notifications?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL -> "勿扰模式正在限制触觉预览。"
            lastHapticError != null -> lastHapticError!!
            hapticBackend == HapticBackend.AMPLITUDE -> "支持振幅调节：拖动试用，无 → 弱 → 中 → 强 → 最强。"
            hapticBackend == HapticBackend.PRIMITIVE -> "支持短触觉效果调节；实际强弱取决于手机马达。"
            else -> "本机仅支持系统振动开/关，不显示无效的强度滑条。"
        }
    }.getOrDefault("无法读取系统振动状态，请检查系统设置。")

    fun soundHint(): String? = runCatching {
        when {
            !preferences.getBoolean("key_sound_enabled", true) -> "按键音效已关闭。"
            muted -> "录音或转录期间暂停反馈。"
            audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL -> "手机处于静音或振动模式，按键音已暂停。"
            audio?.mode != AudioManager.MODE_NORMAL -> "通话或通信模式下暂停按键音。"
            notifications?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL -> "勿扰模式下暂停按键音。"
            (audio?.getStreamVolume(AudioManager.STREAM_SYSTEM) ?: 0) == 0 -> "系统音效音量为零，请在系统声音设置中调整。"
            preferences.getString("key_sound_style", "soft") == "system" -> {
                if (Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) == 0)
                    "系统触摸提示音未开启；请在系统设置中开启，或选择内置轻敲音。"
                else "系统音依赖手机厂商音效资源；若仍无声，请选择内置轻敲音。"
            }
            lastSoundError != null -> lastSoundError
            !ready -> "轻敲音准备中，请稍后点试听。"
            else -> null
        }
    }.getOrDefault("无法读取系统音效状态，已暂停播放。")

    private fun vibrate(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) vibrator?.vibrate(effect,
            android.os.VibrationAttributes.Builder().setUsage(android.os.VibrationAttributes.USAGE_TOUCH).build())
        else vibrator?.vibrate(effect, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
    }

    fun emit(view: View, event: KeyFeedback = KeyFeedback.TAP) {
        if (closed || muted || !view.isEnabled) return
        val now = SystemClock.uptimeMillis()
        if (event == KeyFeedback.ADJUST && !adjustmentLimit.accept(now)) return
        if (event == KeyFeedback.REPEAT) {
            if (!repeatLimit.accept(now)) return
        }
        val strength = KeyboardFeedbackPolicy.strength(preferences.getBoolean("haptic_enabled", true),
            preferences.getInt("haptic_strength", 35))
        if (strength == 0 && event == KeyFeedback.ADJUST) runCatching { vibrator?.cancel() }
        if (strength > 0) {
            // Key views disable framework long-click haptics to avoid duplication.
            // The window root remains system-policy-aware; no IGNORE flags used.
            runCatching {
                if (adjustableHaptics) {
                    if (systemTouchEnabled() &&
                        notifications?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                        if (hapticBackend == HapticBackend.AMPLITUDE) {
                            val amplitude = KeyboardFeedbackPolicy.amplitude(strength)
                            // Fixed 9ms envelope: adjust amplitude, never extend the buzz.
                            vibrate(VibrationEffect.createWaveform(longArrayOf(1, 2, 3, 2, 1),
                                intArrayOf(0, amplitude / 2, amplitude, amplitude / 2, 0), -1))
                        } else if (Build.VERSION.SDK_INT >= 30) {
                            val base = KeyboardFeedbackPolicy.primitiveScale(strength) ?: return
                            val scale = if (event == KeyFeedback.LONG_PRESS) (base * 1.2f).coerceAtMost(1f) else base
                            vibrate(VibrationEffect.startComposition()
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale).compose())
                        }
                    }
                } else view.rootView.performHapticFeedback(if (event == KeyFeedback.LONG_PRESS)
                    HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
            }.onFailure { lastHapticError = "振动调用失败：${it.javaClass.simpleName}，请反馈机型与系统版本。" }
        }
        if (event == KeyFeedback.ADJUST) return // Drag preview is tactile only.
        val allowed = runCatching {
            KeyboardFeedbackPolicy.soundAllowed(preferences.getBoolean("key_sound_enabled", true), muted,
                audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL,
                audio?.mode == AudioManager.MODE_NORMAL,
                notifications?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL,
                (audio?.getStreamVolume(AudioManager.STREAM_SYSTEM) ?: 0) > 0)
        }.getOrDefault(false)
        if (!allowed || !soundLimit.accept(now)) return
        runCatching {
            if (preferences.getString("key_sound_style", "soft") == "system") {
                if (Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) != 0)
                    audio?.playSoundEffect(AudioManager.FX_KEY_CLICK)
            } else if (ready) {
                pool?.play(sample, .35f, .35f, 1, 0, 1f)
            }
        }.onFailure { lastSoundError = "音效调用失败：${it.javaClass.simpleName}" }
    }

    override fun close() {
        closed = true
        ready = false
        pool?.release()
        pool = null
        runCatching { vibrator?.cancel() }
    }
}
