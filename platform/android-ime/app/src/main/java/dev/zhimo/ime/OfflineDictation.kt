// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded recording/inference job; all callbacks are main-thread and generation-checked. */
internal class OfflineDictation(context: Context) {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private class Job(val id: Long) {
        val cancelled = AtomicBoolean(false)
        val finish = AtomicBoolean(false)
    }
    private var current: Job? = null
    private var closed = false
    private val releaseCache = Runnable { if (!active) worker.execute { OfflineSpeechNative.trimCache() } }
    val active: Boolean get() = current != null

    fun start(language: String, state: (Boolean) -> Unit, result: (String?, String?) -> Unit,
        prompt: String = "", useVad: Boolean = true, progress: (Int, Float) -> Unit = { _, _ -> }) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || current != null) return
        main.removeCallbacks(releaseCache)
        val job = Job(OfflineSpeechNative.create())
        if (job.id == 0L) { result(null, "无法创建语音任务，请稍后重试"); return }
        current = job
        worker.execute {
            fun emit(block: () -> Unit) { main.post { if (current === job && !job.cancelled.get()) block() } }
            var recorder: AudioRecord? = null
            try {
                val model = OfflineSpeechModel.install(context)
                val vad = if (useVad) SpeechVadModel.install(context).absolutePath else ""
                if (job.cancelled.get()) return@execute
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "请先授予麦克风权限"
                }
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "设备不支持 16kHz 录音" }
                val recording = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
                recorder = recording
                check(recording.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                if (job.cancelled.get()) return@execute
                recording.startRecording()
                check(recording.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风启动失败" }
                emit { state(true) }
                val samples = FloatArray(16000 * 60)
                val buffer = ShortArray(1600)
                var size = 0
                var lastProgress = 0
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (!job.finish.get() && !job.cancelled.get() && size < samples.size && SystemClock.elapsedRealtime() < deadline) {
                    val n = recording.read(buffer, 0, minOf(buffer.size, samples.size - size), AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0) { "录音中断，请检查麦克风权限" }
                    if (n == 0) { SystemClock.sleep(10); continue }
                    for (i in 0 until n) samples[size++] = buffer[i] / 32768f
                    if (size - lastProgress >= 4000) {
                        lastProgress = size
                        val seconds = size / 16000
                        val level = kotlin.math.sqrt((0 until n).sumOf { val x = buffer[it] / 32768.0; x * x } / n).toFloat()
                        emit { progress(seconds, level) }
                    }
                }
                recording.stop()
                recording.release()
                recorder = null
                if (job.cancelled.get()) return@execute
                check(size >= 1600) { "录音太短，请重试" }
                emit { state(false) }
                val text = OfflineSpeechNative.transcribe(job.id, model.absolutePath, samples.copyOf(size), language, prompt, vad)
                    .toString(Charsets.UTF_8).trim()
                emit { current = null; result(text, null) }
            } catch (error: Exception) {
                emit { current = null; result(null, error.message ?: "离线识别失败") }
            } catch (_: LinkageError) {
                emit { current = null; result(null, "语音引擎版本不匹配，请安装完整包") }
            } catch (_: OutOfMemoryError) {
                emit { current = null; result(null, "可用内存不足，请缩短录音或关闭其他应用") }
            } finally {
                recorder?.let { runCatching { it.stop() }; it.release() }
                OfflineSpeechNative.release(job.id)
                main.post { if (!closed) main.postDelayed(releaseCache, 30_000) }
            }
        }
    }

    fun finish() { current?.finish?.set(true) }
    fun cancel() {
        current?.let { it.cancelled.set(true); OfflineSpeechNative.cancel(it.id) }
        current = null
    }
    fun trimMemory() { if (!closed) worker.execute { OfflineSpeechNative.trimCache() } }
    fun close() {
        closed = true; cancel(); main.removeCallbacks(releaseCache)
        worker.execute { OfflineSpeechNative.trimCache() }
        worker.shutdown()
    }
}
