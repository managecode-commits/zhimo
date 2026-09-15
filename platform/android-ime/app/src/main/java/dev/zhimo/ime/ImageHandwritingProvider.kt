// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.PriorityQueue

/** Default offline image route, still under quality evaluation. No network or ink persistence. */
class ImageHandwritingProvider(context: Context) : HandwritingProvider {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var session: OrtSession? = null // worker owned
    private var characters: List<String> = emptyList()

    override fun available(done: (Boolean) -> Unit) {
        if (closed.get()) return
        worker.execute {
            val ready = runCatching {
                if (session == null) {
                    val bytes = app.assets.open(ASSET).use { it.readBytes() }
                    check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == SHA256)
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                        session = OrtEnvironment.getEnvironment().createSession(bytes, options)
                    }
                    val dictionary = checkNotNull(session!!.metadata.customMetadata["character"])
                    characters = listOf("") + dictionary.trimEnd('\n', '\r').lines() + listOf(" ")
                }
                true
            }.getOrElse { session?.close(); session = null; false }
            main.post { if (!closed.get()) done(ready) }
        }
    }

    override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
        if (closed.get()) return
        worker.execute {
            val result = runCatching {
                val active = checkNotNull(session)
                val input = render(request)
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input), longArrayOf(1, 3, 48, 320)).use { tensor ->
                    active.run(mapOf(active.inputNames.first() to tensor)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val probabilities = (output[0].value as Array<Array<FloatArray>>)[0]
                        decode(probabilities, characters, request.characterMode)
                    }
                }
            }
            main.post { if (!closed.get()) done(result) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute { session?.close(); session = null; characters = emptyList() }
        worker.shutdown()
    }

    companion object {
        const val ASSET = "handwriting-image/pp-ocrv5-mobile-rec.onnx"
        const val SHA256 = "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5"

        fun render(request: InkRequest): FloatArray {
            require(request.width.isFinite() && request.height.isFinite() && request.width > 0 && request.height > 0)
            require(request.strokes.isNotEmpty() && request.strokes.size <= 64 && request.strokes.sumOf { it.size } <= 32768)
            require(request.strokes.all { stroke -> stroke.isNotEmpty() && stroke.size <= 4096 &&
                stroke.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..request.width && it.y in 0f..request.height } &&
                stroke.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis } })
            val points = request.strokes.flatten()
            val left = points.minOf { it.x }; val right = points.maxOf { it.x }
            val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
            val span = maxOf(right - left, bottom - top).coerceAtLeast(1f)
            val scale = 400f / span
            val cx = left + (right - left) / 2; val cy = top + (bottom - top) / 2
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; strokeWidth = 8f; style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                }
                for (stroke in request.strokes) {
                    val distinct = stroke.distinctBy { it.x to it.y }
                    if (distinct.size == 1) {
                        canvas.drawPoint((distinct[0].x - cx) * scale + 256, (distinct[0].y - cy) * scale + 256, pen)
                    } else {
                        val path = Path()
                        stroke.forEachIndexed { index, p ->
                            val x = (p.x - cx) * scale + 256; val y = (p.y - cy) * scale + 256
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        canvas.drawPath(path, pen)
                    }
                }
                // Area averaging, not sparse bilinear sampling: a subpixel horizontal
                // stroke can disappear entirely when shrinking 512 -> 48 with sampling.
                val pixels = IntArray(512 * 512); bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512)
                val input = FloatArray(3 * 48 * 320)
                for (y in 0 until 48) for (x in 0 until 48) {
                    val leftPixel = x * 512 / 48; val rightPixel = (x + 1) * 512 / 48
                    val topPixel = y * 512 / 48; val bottomPixel = (y + 1) * 512 / 48
                    var sum = 0
                    for (sy in topPixel until bottomPixel) for (sx in leftPixel until rightPixel)
                        sum += Color.red(pixels[sy * 512 + sx])
                    val value = sum.toFloat() / ((rightPixel - leftPixel) * (bottomPixel - topPixel)) / 127.5f - 1f
                    for (channel in 0..2) input[channel * 48 * 320 + y * 320 + x] = value
                }
                return input
            } finally { bitmap.recycle() }
        }

        /** Exact CTC paths yielding one label; scores are not combined with Zinnia margins. */
        fun decode(frames: Array<FloatArray>, labels: List<String>, mode: HandwritingCharacterMode = HandwritingCharacterMode.CHINESE): List<String> {
            require(frames.isNotEmpty() && labels.size > 1)
            val active = DoubleArray(labels.size - 1); val ended = DoubleArray(labels.size - 1)
            var before = 1.0
            for (frame in frames) {
                require(frame.size == labels.size && frame.all { it.isFinite() && it >= 0 && it <= 1 })
                for (i in active.indices) {
                    ended[i] = (ended[i] + active[i]) * frame[0]
                    active[i] = (active[i] + before) * frame[i + 1]
                }
                before *= frame[0]
            }
            // Apply the selected alphabet before truncation; preserve Latin case and digit identity.
            // Bound sorting work and allocation instead of sorting the entire vocabulary.
            val best = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
            val latin = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
            for (i in active.indices) {
                val label = labels[i + 1]
                if (!mode.accepts(label)) continue
                val score = active[i] + ended[i]
                val queue = if (mode == HandwritingCharacterMode.MIXED && !HandwritingCharacterMode.CHINESE.accepts(label)) latin else best
                if (score > 0 && (queue.size < 100 || score > (queue.peek()?.second ?: Double.NEGATIVE_INFINITY))) {
                    queue.add(label to score)
                    if (queue.size > 100) queue.poll()
                }
            }
            val chinese = best.sortedByDescending { it.second }.map { it.first }
            if (mode != HandwritingCharacterMode.MIXED) return chinese
            // Mixed handwriting remains Chinese-first, without discarding Latin
            // alternatives. Keep separate top-k pools so ASCII cannot evict Han
            // before ranking. These are rank quotas, not calibrated confidence.
            return balanceScripts(chinese, latin.sortedByDescending { it.second }.map { it.first })
        }

        fun balanceScripts(han: List<String>, latin: List<String>): List<String> =
            (0 until 100).flatMap { rank ->
                han.drop(rank * 3).take(3) + listOfNotNull(latin.getOrNull(rank))
            }.distinct().take(100)
    }
}
