// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit opt-in: loads the large model. Must run on each supported device ABI. */
class StreamingSpeechTest {
    @Test fun bundledModelLoadsAndSilenceDoesNotInventText() {
        assumeTrue(BuildConfig.STREAMING_SPEECH)
        assumeTrue(InstrumentationRegistry.getArguments().getString("runStreamingSpeech") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = StreamingSpeechModel.install(context) { false }
        assertTrue(dir.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/"))
        val model = OnlineRecognizer(config = OnlineRecognizerConfig(modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = dir.resolve("encoder-epoch-99-avg-1.int8.onnx").path,
                decoder = dir.resolve("decoder-epoch-99-avg-1.onnx").path,
                joiner = dir.resolve("joiner-epoch-99-avg-1.onnx").path),
            tokens = dir.resolve("tokens.txt").path, modelType = "zipformer", numThreads = 2)))
        try {
            repeat(2) { // Repeated stream creation/release on one native model.
                val stream = model.createStream()
                try {
                    repeat(20) {
                        stream.acceptWaveform(FloatArray(1600), 16000)
                        while (model.isReady(stream)) model.decode(stream)
                    }
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (model.isReady(stream)) model.decode(stream)
                    assertEquals("", model.getResult(stream).text.trim())
                } finally { stream.release() }
            }
            if (InstrumentationRegistry.getArguments().getString("runStreamingSpeechFixture") == "true") {
                // Optional, explicitly supplied public JFK fixture. Never record/read private audio.
                val fixture = File(context.getExternalFilesDir(null), "speech-test/jfk.pcm")
                check(fixture.isFile && fixture.length() in 3200L..1920000L && fixture.length() % 2 == 0L)
                val bytes = ByteBuffer.wrap(fixture.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val stream = model.createStream()
                try {
                    var sawPartial = false
                    while (bytes.hasRemaining()) {
                        val audio = FloatArray(minOf(1600, bytes.remaining()/2)) { bytes.short / 32768f }
                        stream.acceptWaveform(audio, 16000)
                        while (model.isReady(stream)) model.decode(stream)
                        if (model.getResult(stream).text.isNotBlank()) sawPartial = true
                    }
                    assertTrue("Expected a partial result before end of input", sawPartial)
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (model.isReady(stream)) model.decode(stream)
                    assertTrue(model.getResult(stream).text.uppercase().contains("AMERICANS"))
                } finally { stream.release() }
            }
        } finally { model.release() }
    }

    @Test fun cancelledModelInstallationStopsBeforeCopying() {
        assumeTrue(BuildConfig.STREAMING_SPEECH)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(IllegalStateException::class.java) { StreamingSpeechModel.install(context) { true } }
    }
}
