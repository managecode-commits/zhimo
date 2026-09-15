// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.content.Context
import java.io.File
import java.security.MessageDigest

internal object OfflineSpeechModel {
    private var verifiedStamp: Triple<String, Long, Long>? = null
    const val SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    const val SIZE = 59_707_625L
    private fun valid(file: File): Boolean {
        if (file.length() != SIZE) return false
        val stamp = Triple(file.absolutePath, file.length(), file.lastModified())
        if (verifiedStamp == stamp) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val valid = digest.digest().joinToString("") { "%02x".format(it) } == SHA256
        if (valid) verifiedStamp = stamp
        return valid
    }

    /** Called on a worker; validated, app-private, atomic installation. No audio files. */
    @Synchronized fun install(context: Context): File {
        val dir = File(context.noBackupFilesDir, "speech").apply { check(isDirectory || mkdirs()) }
        val model = File(dir, "base-q5_1-$SHA256.bin")
        if (valid(model)) return model
        val temporary = File.createTempFile("model-", ".part", dir)
        try {
            context.assets.open("speech/ggml-base-q5_1.bin").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            }
            check(valid(temporary)) { "内置语音模型校验失败，请重新安装" }
            check(temporary.renameTo(model)) { "无法安装内置语音模型" }
        } finally { temporary.delete() }
        return model
    }
}
