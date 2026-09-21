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
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Recording never waits for inference; overflow is reported, never silently discarded. */
internal class StreamingDictation(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val capture = Executors.newSingleThreadExecutor()
    private class Job {
        val cancelled = AtomicBoolean(false)
        val finish = AtomicBoolean(false)
        val audio = ArrayBlockingQueue<FloatArray>(64) // <= 6.4 seconds / 400 KiB
        @Volatile var captureDone = false
        @Volatile var failure: String? = null
        @Volatile var count = 0
        @Volatile var stoppedAt = 0L
    }
    private var current: Job? = null // main thread only
    private var closed = false
    private var recognizer: OnlineRecognizer? = null // decoder thread only
    private val trim = Runnable { if (!closed && !active) decoder.execute { releaseModel() } }
    val active: Boolean get() = current != null

    fun start(state: (Boolean) -> Unit, result: (String?, String?) -> Unit,
        progress: (Int, Float) -> Unit, partial: (String) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || active) return
        main.removeCallbacks(trim)
        val job = Job()
        current = job
        fun emit(block: () -> Unit) { main.post { if (current === job && !job.cancelled.get()) block() } }
        decoder.execute {
            var stream: OnlineStream? = null
            val startedAt = SystemClock.elapsedRealtime()
            var loadedAt = startedAt
            var firstTextAt = 0L
            try {
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "请先授予麦克风权限"
                }
                val model = recognizer ?: run {
                    val dir = StreamingSpeechModel.install(context) { job.cancelled.get() }
                    check(!job.cancelled.get()) { "语音已取消" }
                    OnlineRecognizer(config = OnlineRecognizerConfig(
                        modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                            encoder = dir.resolve("encoder-epoch-99-avg-1.int8.onnx").path,
                            decoder = dir.resolve("decoder-epoch-99-avg-1.onnx").path,
                            joiner = dir.resolve("joiner-epoch-99-avg-1.onnx").path),
                            tokens = dir.resolve("tokens.txt").path, numThreads = 2,
                            provider = "cpu", modelType = "zipformer"),
                        enableEndpoint = true, decodingMethod = "greedy_search"
                    )).also { recognizer = it }
                }
                loadedAt = SystemClock.elapsedRealtime()
                if (job.cancelled.get()) return@execute
                val input = model.createStream()
                stream = input
                val transcript = StreamingTranscript()
                capture.execute {
                    record(job, { emit { state(true) } }, { seconds, level -> emit { progress(seconds, level) } })
                    emit { state(false) }
                }
                var lastPreview = ""
                var lastPreviewAt = 0L
                fun decodeReady() {
                    while (model.isReady(input)) {
                        check(!job.cancelled.get()) { "语音已取消" }
                        check(SystemClock.elapsedRealtime() - loadedAt < 180_000) { "流式识别超时，请缩短录音" }
                        model.decode(input)
                    }
                }
                while (!job.cancelled.get()) {
                    job.failure?.let { error(it) }
                    val chunk = job.audio.poll(100, TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        input.acceptWaveform(chunk, 16000)
                        decodeReady()
                        val text = model.getResult(input).text.trim()
                        val now = SystemClock.elapsedRealtime()
                        if (text.isNotEmpty() && firstTextAt == 0L) firstTextAt = now
                        val preview = transcript.preview(text)
                        if (preview != lastPreview && now - lastPreviewAt >= 250) {
                            lastPreview = preview; lastPreviewAt = now
                            emit { partial(preview) }
                        }
                        if (model.isEndpoint(input)) {
                            transcript.endpoint(text)
                            model.reset(input)
                        }
                    } else if (job.captureDone) break
                }
                if (job.cancelled.get()) return@execute
                job.failure?.let { error(it) }
                check(job.count >= 1600) { "录音太短，请重试" }
                emit { state(false) }
                // Tail padding is for model context, not a new recording or another utterance.
                input.acceptWaveform(FloatArray(8000), 16000)
                input.inputFinished()
                decodeReady()
                val finalText = transcript.preview(model.getResult(input).text)
                emit { current = null; result(finalText, null) }
                val finishedAt = SystemClock.elapsedRealtime()
                android.util.Log.i("ZhimoSpeechTiming", "engine=zipformer prepareMs=${loadedAt-startedAt} " +
                    "audioMs=${job.count/16} firstTextAfterPrepareMs=${if (firstTextAt == 0L) -1 else firstTextAt-loadedAt} " +
                    "tailMs=${finishedAt-job.stoppedAt}") // No audio or recognized text.
            } catch (_: OutOfMemoryError) {
                releaseModelAfterStream = true
                emit { current = null; result(null, "内存不足，请切回标准语音或关闭其他应用") }
            } catch (_: LinkageError) {
                emit { current = null; result(null, "流式运行库不可用，请在设置中切回标准语音") }
            } catch (error: Exception) {
                emit { current = null; result(null, error.message ?: "流式识别失败，请切回标准语音") }
            } finally {
                job.finish.set(true)
                stream?.release()
                if (releaseModelAfterStream) { releaseModel(); releaseModelAfterStream = false }
                job.audio.clear()
                main.post { if (!closed) { main.removeCallbacks(trim); main.postDelayed(trim, 120_000) } }
            }
        }
    }

    private var releaseModelAfterStream = false
    private fun record(job: Job, ready: () -> Unit, progress: (Int, Float) -> Unit) {
        var recorder: AudioRecord? = null
        try {
            if (job.cancelled.get()) return
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "麦克风权限已撤回"
            }
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持 16kHz 录音" }
            val recording = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
            recorder = recording
            check(recording.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            if (job.cancelled.get()) return
            recording.startRecording()
            check(recording.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风启动失败" }
            ready()
            val chunks = StreamingPcmChunks()
            val deadline = SystemClock.elapsedRealtime() + 60_000
            var lastProgress = 0
            fun enqueue(chunk: FloatArray) {
                check(job.audio.offer(chunk)) { "设备识别速度跟不上录音，请缩短录音或切回标准语音" }
                if (job.count-lastProgress >= 4000) {
                    lastProgress = job.count
                    val level = kotlin.math.sqrt(chunk.sumOf { it.toDouble()*it }/chunk.size).toFloat()
                    progress(job.count/16000, level)
                }
            }
            while (!job.cancelled.get() && !job.finish.get() && job.count < 960000 && SystemClock.elapsedRealtime() < deadline) {
                val n = recording.read(chunks.buffer, chunks.filled, minOf(chunks.remaining, 960000-job.count), AudioRecord.READ_NON_BLOCKING)
                check(n >= 0) { "录音中断，请检查麦克风权限" }
                if (n == 0) { SystemClock.sleep(10); continue }
                job.count += n
                chunks.readCompleted(n)?.let { enqueue(it) }
            }
            if (!job.cancelled.get()) chunks.flush()?.let { enqueue(it) }
        } catch (error: Exception) {
            job.failure = error.message ?: "录音失败"
        } catch (_: OutOfMemoryError) {
            job.failure = "录音内存不足，请切回标准语音"
        } finally {
            recorder?.let { runCatching { it.stop() }; it.release() }
            job.stoppedAt = SystemClock.elapsedRealtime()
            job.captureDone = true
        }
    }

    fun finish() { current?.finish?.set(true) }
    fun cancel() { current?.cancelled?.set(true); current = null }
    fun trimMemory() { if (!closed) decoder.execute { releaseModel() } }
    private fun releaseModel() { recognizer?.release(); recognizer = null }
    fun close() {
        if (closed) return
        closed = true; cancel(); main.removeCallbacks(trim)
        decoder.execute { releaseModel() }
        decoder.shutdown(); capture.shutdown()
    }
}
