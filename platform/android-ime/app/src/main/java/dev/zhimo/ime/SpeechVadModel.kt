// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.content.Context
import java.io.File
import java.security.MessageDigest

internal object SpeechVadModel {
    const val HASH = "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"
    private var stamp: Triple<String, Long, Long>? = null
    @Synchronized fun install(context: Context): File {
        val directory = File(context.noBackupFilesDir, "speech").apply { check(isDirectory || mkdirs()) }
        val target = File(directory, "silero-$HASH.bin")
        fun valid(file: File): Boolean {
            if (file.length() != 885098L) return false
            val key = Triple(file.path, file.length(), file.lastModified())
            if (stamp == key) return true
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val ok = digest.digest().joinToString("") { "%02x".format(it) } == HASH
            if (ok) stamp = key
            return ok
        }
        if (valid(target)) return target
        val part = File.createTempFile("vad-", ".part", directory)
        try {
            context.assets.open("speech/ggml-silero-v5.1.2.bin").use { input ->
                part.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            }
            check(valid(part)) { "人声检测模型校验失败，请重新安装" }
            check(part.renameTo(target)) { "无法安装人声检测模型" }
        } finally { part.delete() }
        return target
    }
}
