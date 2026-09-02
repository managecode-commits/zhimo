package dev.shurufa.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSmokeTest {
    @Test
    fun testPlatformPolicyRequiresExplicitNetworkConsentAndProtectsPasswords() {
        assertFalse(PlatformPolicy.mayStartSpeech(true, true, true, true))
        assertFalse(PlatformPolicy.mayStartSpeech(false, true, false, false))
        assertTrue(PlatformPolicy.mayStartSpeech(false, true, true, false))
        assertTrue(PlatformPolicy.mayStartSpeech(false, true, false, true))
        assertEquals("rime", PlatformPolicy.engine(true, true))
        assertEquals("pinyin.reference", PlatformPolicy.engine(true, false))
        assertEquals("latin", PlatformPolicy.engine(false, true))
    }

    @Test
    fun testBundledRimeOrReferenceFallbackCommitsChinese() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = File(context.filesDir, "instrumentation-learning").apply { mkdirs() }
        val rime = RimeAssets.prepare(context)
        val handle = NativeIme.create(data.absolutePath, rime.shared.absolutePath, rime.user.absolutePath)
        assertTrue("runtime handle", handle != 0L)
        try {
            val nativeRime = NativeIme.capabilities() and NativeIme.CAP_NATIVE_LIBRIME != 0L &&
                NativeIme.switchEngine(handle, "rime") == 0
            if (!nativeRime) {
                assertEquals(0, NativeIme.switchEngine(handle, "pinyin.reference"))
            }
            assertEquals(0, NativeIme.feed(handle, "nihao"))
            assertTrue(NativeIme.actions(handle).contains("你好"))
            assertEquals(0, NativeIme.command(handle, 2))
            assertTrue(NativeIme.actions(handle).contains("CommitText"))
        } finally {
            NativeIme.destroy(handle)
        }
    }

    @Test
    fun testPasswordScopeRejectsSpeechAndLearningCanBeDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rime = RimeAssets.prepare(context)
        val handle = NativeIme.create(context.filesDir.absolutePath, rime.shared.absolutePath, rime.user.absolutePath)
        assertTrue("runtime handle", handle != 0L)
        try {
            assertEquals(0, NativeIme.setPrivacy(handle, false, false))
            assertEquals(0, NativeIme.setScope(handle, 1))
            assertTrue(
                NativeIme.speechResult(handle, "sensitive", "en", 1.0f, true) < 0,
            )
        } finally {
            NativeIme.destroy(handle)
        }
    }
}
