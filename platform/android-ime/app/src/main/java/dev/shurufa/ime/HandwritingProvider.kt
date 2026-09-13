package dev.shurufa.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Main-thread API and callbacks, background model preparation/inference. No networking. */
interface HandwritingProvider : AutoCloseable {
    fun available(done: (Boolean) -> Unit)
    fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit)
}

class BundledHandwritingProvider(context: Context) : HandwritingProvider {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var handle = 0L // accessed only by worker

    private fun modelFile(): File {
        val target = File(app.noBackupFilesDir, "handwriting-zh-cn-v03.model")
        fun valid(file: File): Boolean {
            if (!file.isFile || file.length() != MODEL_SIZE) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256
        }
        if (valid(target)) return target
        val temporary = File.createTempFile("handwriting-", ".tmp", app.noBackupFilesDir)
        try {
            app.assets.open(MODEL_ASSET).use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(valid(temporary)) { "Bundled handwriting model is damaged" }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
        return target
    }

    override fun available(done: (Boolean) -> Unit) {
        if (closed.get()) return
        worker.execute {
            val ready = runCatching {
                if (handle == 0L) handle = NativeIme.handwritingOpen(modelFile().absolutePath)
                handle != 0L
            }.getOrDefault(false)
            main.post { if (!closed.get()) done(ready) }
        }
    }

    override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
        if (closed.get()) return
        worker.execute {
            val result = runCatching {
                check(handle != 0L) { "Handwriting model is not ready" }
                val strokes = JSONArray()
                request.strokes.forEach { stroke ->
                    strokes.put(JSONArray().apply { stroke.forEach { p ->
                        put(JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("time_ms", p.timeMillis))
                    } })
                }
                val ink = JSONObject().put("width", request.width.toDouble())
                    .put("height", request.height.toDouble()).put("strokes", strokes)
                val bytes = checkNotNull(NativeIme.handwritingRecognize(handle, ink.toString())) { "Invalid ink or unavailable model" }
                val candidates = JSONArray(bytes.toString(Charsets.UTF_8))
                (0 until candidates.length()).map { candidates.getJSONObject(it).getString("text") }
            }
            main.post { if (!closed.get()) done(result) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute { if (handle != 0L) NativeIme.handwritingClose(handle); handle = 0L }
        worker.shutdown()
    }

    companion object {
        const val MODEL_ASSET = "handwriting/zh-cn/handwriting-zh_CN.model"
        const val MODEL_SIZE = 26834816L
        const val MODEL_SHA256 = "e16153d1ff267cd479aea260d6f71a3edda8b4ba06db2d121513adda65a4449e"
    }
}
