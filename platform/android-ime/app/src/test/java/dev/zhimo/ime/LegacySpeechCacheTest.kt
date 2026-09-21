// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LegacySpeechCacheTest {
    @Test fun removesOnlyKnownModelFiles() {
        val root = Files.createTempDirectory("zhimo-legacy-model-test").toFile()
        try {
            val speech = root.resolve("speech").apply { mkdir() }
            LegacySpeechCache.names.forEach { speech.resolve(it).writeText("old model") }
            speech.resolve("user.wav").writeText("preserve")
            root.resolve("learning.db").writeText("preserve")
            LegacySpeechCache.remove(root)
            LegacySpeechCache.names.forEach { assertFalse(speech.resolve(it).exists()) }
            assertTrue(speech.resolve("user.wav").isFile)
            assertTrue(root.resolve("learning.db").isFile)
        } finally { root.deleteRecursively() }
    }
}
