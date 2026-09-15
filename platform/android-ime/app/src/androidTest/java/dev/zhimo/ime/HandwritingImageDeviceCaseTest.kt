// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit local development fixtures, never packaged in either APK. */
class HandwritingImageDeviceCaseTest {
    @Test fun localScreenshotTensorsMatchDesktopHypotheses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            environment.createSession(context.assets.open(ImageHandwritingProvider.ASSET).use { it.readBytes() }, options).use { session ->
                val labels = listOf("") + session.metadata.customMetadata["character"]!!.trimEnd('\n', '\r').lines() + listOf(" ")
                for ((index, expected) in listOf("制", "清", "存").withIndex()) {
                    val bytes = File(context.getExternalFilesDir(null), "handwriting-eval/user-0${index+1}.f32").readBytes()
                    assertEquals(3 * 48 * 320 * 4, bytes.size)
                    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes)
                    buffer.rewind()
                    OnnxTensor.createTensor(environment, buffer.asFloatBuffer(), longArrayOf(1, 3, 48, 320)).use { tensor ->
                        val start = android.os.SystemClock.elapsedRealtime()
                        session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                            @Suppress("UNCHECKED_CAST")
                            val frames = (output[0].value as Array<Array<FloatArray>>)[0]
                            val candidates = ImageHandwritingProvider.decode(frames, labels)
                            assertEquals("case ${index+1}", expected, candidates.first())
                            println("image_case_${index+1}_inference_decode_ms=" + (android.os.SystemClock.elapsedRealtime() - start))
                            println("image_case_${index+1}_process_pss_kib=" + android.os.Debug.getPss())
                        }
                    }
                }
            }
        }
    }
}
