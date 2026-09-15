// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OfflineSpeechTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun bundledModelInstallsAndRepairsCorruption() {
        val model = OfflineSpeechModel.install(context)
        assertEquals(OfflineSpeechModel.SIZE, model.length())
        model.outputStream().use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(OfflineSpeechModel.SIZE, OfflineSpeechModel.install(context).length())
        assertTrue(model.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/"))
        assertFalse(model.parentFile!!.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun vadModelInstallsAndRepairsCorruption() {
        val model = SpeechVadModel.install(context)
        assertEquals(885098L, model.length())
        model.outputStream().use { it.write(byteArrayOf(1)) }
        assertEquals(885098L, SpeechVadModel.install(context).length())
    }

    @Test fun neuralVadRejectsGeneratedTone() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runOfflineSpeech") == "true")
        withSession { id ->
            val tone = FloatArray(32000) { (0.036 * kotlin.math.sin(2 * Math.PI * 440 * it / 16000)).toFloat() }
            val text = OfflineSpeechNative.transcribe(id, OfflineSpeechModel.install(context).path, tone, "zh",
                vadModel = SpeechVadModel.install(context).path).toString(Charsets.UTF_8).trim()
            assertEquals("", text)
        }
    }

    private fun withSession(block: (Long) -> Unit) {
        val id = OfflineSpeechNative.create()
        try { block(id) } finally { OfflineSpeechNative.release(id) }
    }

    @Test fun silenceProducesNoInventedText() = withSession { id ->
        val result = OfflineSpeechNative.transcribe(id, OfflineSpeechModel.install(context).path, FloatArray(16000), "zh")
        assertEquals(0, result.size)
    }

    @Test fun invalidAudioAndCancelledSessionsAreRejected() = withSession { id ->
        val path = OfflineSpeechModel.install(context).path
        for (audio in listOf(FloatArray(0), FloatArray(1600) { Float.NaN }, FloatArray(1600) { 2f }, FloatArray(960001))) {
            assertThrows(IllegalStateException::class.java) { OfflineSpeechNative.transcribe(id, path, audio, "en") }
        }
        OfflineSpeechNative.cancel(id)
        assertThrows(IllegalStateException::class.java) { OfflineSpeechNative.transcribe(id, path, FloatArray(16000), "en") }
    }

    private fun fixture(name: String): FloatArray {
        val file = File(context.getExternalFilesDir(null), "speech-test/$name")
        check(file.isFile) { "Push public PCM16 mono 16kHz fixture first: $file" }
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF")
        check(bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE")
        var offset = 12
        var formatOk = false
        while (offset + 8 <= bytes.size) {
            val chunk = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val size = b.getInt(offset + 4)
            check(size >= 0 && size <= bytes.size - offset - 8)
            if (chunk == "fmt ") {
                check(size >= 16)
                formatOk = b.getShort(offset + 8).toInt() == 1 && b.getShort(offset + 10).toInt() == 1 &&
                    b.getInt(offset + 12) == 16000 && b.getShort(offset + 22).toInt() == 16
            }
            if (chunk == "data") {
                check(formatOk && size % 2 == 0)
                return FloatArray(size / 2) { b.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + size + size % 2
        }
        error("No PCM data")
    }

    @Test fun realBundledModelTranscribesEnglishAndChinese() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runOfflineSpeech") == "true")
        val model = OfflineSpeechModel.install(context)
        for ((file, language) in listOf("en.wav" to "en", "zh.wav" to "zh")) withSession { id ->
            val start = SystemClock.elapsedRealtime()
            val text = OfflineSpeechNative.transcribe(id, model.path, fixture(file), language).toString(Charsets.UTF_8)
            android.util.Log.i("ZhimoSpeechTest", "Public fixture $language (${SystemClock.elapsedRealtime() - start} ms): $text")
            if (language == "en") assertTrue(text.lowercase().contains("country"))
            else assertTrue(text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        }
    }

    @Test fun activeNativeInferenceCanBeCancelled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runOfflineSpeech") == "true")
        val model = OfflineSpeechModel.install(context)
        val audio = fixture("en.wav")
        withSession { id ->
            val done = CountDownLatch(1)
            var rejected = false
            val thread = Thread {
                try { OfflineSpeechNative.transcribe(id, model.path, audio, "en") }
                catch (_: IllegalStateException) { rejected = true }
                finally { done.countDown() }
            }
            thread.start()
            SystemClock.sleep(500)
            OfflineSpeechNative.cancel(id)
            assertTrue("Cancelled work terminates", done.await(30, TimeUnit.SECONDS))
            assertTrue(rejected)
        }
    }

    @Test fun microphoneFinishAndCancelLifecycle() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runOfflineMic") == "true")
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        assertTrue("Grant RECORD_AUDIO on the test device before the opt-in microphone test", granted)
        val activity = instrumentation.startActivitySync(Intent(context, KeyboardTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var dictation: OfflineDictation
        instrumentation.runOnMainSync { dictation = OfflineDictation(context) }
        try {
            val listening = CountDownLatch(1)
            val done = CountDownLatch(1)
            var error: String? = null
            instrumentation.runOnMainSync {
                dictation.start("zh", { if (it) listening.countDown() }, { _, e -> error = e; done.countDown() })
            }
            assertTrue("Microphone starts", listening.await(30, TimeUnit.SECONDS))
            SystemClock.sleep(400)
            instrumentation.runOnMainSync { dictation.finish() }
            assertTrue("Finishing produces a callback", done.await(130, TimeUnit.SECONDS))
            assertNull(error)
            val listeningAgain = CountDownLatch(1)
            var staleCallback = false
            instrumentation.runOnMainSync {
                dictation.start("en", { if (it) listeningAgain.countDown() }, { _, _ -> staleCallback = true })
            }
            assertTrue(listeningAgain.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { dictation.cancel(); assertFalse(dictation.active) }
            SystemClock.sleep(500)
            instrumentation.runOnMainSync { assertFalse(staleCallback) }
        } finally {
            instrumentation.runOnMainSync { dictation.close() }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
