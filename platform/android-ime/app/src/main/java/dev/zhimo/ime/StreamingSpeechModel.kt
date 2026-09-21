// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

internal object StreamingSpeechModel {
    private val verified = mutableMapOf<String, Pair<Long, Long>>()
    private val names = setOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.onnx", "tokens.txt")

    @Synchronized fun install(context: Context, cancelled: () -> Boolean): File {
        check(!cancelled()) { "语音已取消" }
        LegacySpeechCache.remove(context.noBackupFilesDir)
        val manifest = context.assets.open("speech-streaming/manifest.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        check(manifest.getString("model") == "zipformer") { "流式模型类型不匹配" }
        val revision = manifest.getString("revision")
        check(revision.matches(Regex("[a-f0-9]{40}")))
        val directory = File(context.noBackupFilesDir, "speech-streaming/$revision")
        check(directory.isDirectory || directory.mkdirs())
        for (name in names) {
            check(!cancelled()) { "语音已取消" }
            val hash = manifest.getJSONObject("files").getString(name)
            check(hash.matches(Regex("[a-f0-9]{64}")))
            val destination = File(directory, name)
            val key = destination.path + hash
            val stamp = destination.length() to destination.lastModified()
            if (destination.isFile && verified[key] == stamp) continue
            fun valid(file: File): Boolean {
                if (!file.isFile) return false
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        check(!cancelled()) { "语音已取消" }
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) } == hash
            }
            if (!valid(destination)) {
                val temporary = File.createTempFile("model-", ".part", directory)
                try {
                    context.assets.open("speech-streaming/$name").use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                check(!cancelled()) { "语音已取消" }
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                            output.fd.sync()
                        }
                    }
                    check(valid(temporary)) { "流式模型校验失败，请重新安装完整测试包" }
                    check(temporary.renameTo(destination)) { "流式模型安装失败" }
                } finally { temporary.delete() }
            }
            verified[key] = destination.length() to destination.lastModified()
        }
        return directory
    }
}
