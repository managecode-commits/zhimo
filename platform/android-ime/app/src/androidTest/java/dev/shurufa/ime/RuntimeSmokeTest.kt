package dev.shurufa.ime

import android.text.InputType
import android.content.res.Configuration
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
    fun testKeyboardTypeScaleAndResponsiveCandidates() {
        // Reference layout gives letters and numbers equal prominence.
        assertTrue(KeyboardTypography.NUMBER >= KeyboardTypography.LETTER)
        assertTrue(KeyboardTypography.LETTER >= 26f)
        assertTrue(KeyboardTypography.LETTER > KeyboardTypography.HINT)
        assertTrue(KeyboardTypography.CANDIDATE > KeyboardTypography.ANNOTATION)
        assertTrue(KeyboardTypography.T9 > KeyboardTypography.HINT)
        assertEquals(KeyboardTextSize.STANDARD, KeyboardTextSize.fromStored("invalid"))
        assertEquals(KeyboardTextSize.STANDARD, KeyboardTextSize.EXTRA_LARGE.next())
        assertEquals(4, KeyboardTypography.expandedColumns(360f, KeyboardTextSize.STANDARD))
        assertEquals(2, KeyboardTypography.expandedColumns(280f, KeyboardTextSize.EXTRA_LARGE))
    }

    @Test
    fun testNativePagingAndPrivacyFailClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rime = RimeAssets.prepare(context)
        val handle = NativeIme.create(context.filesDir.absolutePath, rime.shared.absolutePath, rime.user.absolutePath)
        try {
            if (NativeIme.switchEngine(handle, "rime") != 0) {
                assertFalse(InstrumentationRegistry.getArguments().getString("requireNativeRime") == "true")
                return
            }
            assertEquals(0, NativeIme.feed(handle, "ren"))
            val first = NativeIme.actions(handle)
            assertTrue(first.contains("\"has_next\":true"))
            assertEquals(0, NativeIme.command(handle, 7))
            assertTrue(NativeIme.actions(handle).contains("\"index\":1"))
            assertEquals(0, NativeIme.command(handle, 6))
            assertTrue(NativeIme.actions(handle).contains("\"index\":0"))
            assertEquals(0, NativeIme.command(handle, 3))
            assertEquals(0, NativeIme.setPrivacy(handle, false, false))
            assertTrue(NativeIme.feed(handle, "nihao") < 0)
            assertEquals(0, NativeIme.switchEngine(handle, "pinyin.reference"))
            assertEquals(0, NativeIme.feed(handle, "nihao"))
            assertEquals(0, NativeIme.command(handle, 2))
            assertTrue(NativeIme.actions(handle).contains("你好"))
        } finally {
            NativeIme.destroy(handle)
        }
    }

    @Test
    fun testBundledRimeInstallationRecoversFromCorruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = RimeAssets.prepare(context)
        val defaultConfig = File(first.shared, "default.yaml")
        assertTrue(defaultConfig.isFile)
        assertTrue(File(first.shared, "pinyin_simp.dict.yaml").length() > 1_000_000L)
        assertTrue(defaultConfig.readText().contains("shurufa_pinyin"))
        assertTrue(defaultConfig.delete())
        val recovered = RimeAssets.prepare(context)
        assertTrue(File(recovered.shared, "default.yaml").isFile)
    }

    @Test
    fun testPlatformPolicyRequiresExplicitNetworkConsentAndProtectsPasswords() {
        assertFalse(PlatformPolicy.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertTrue(PlatformPolicy.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(PlatformPolicy.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(PlatformPolicy.mayStartSpeech(true, true, true, true))
        assertFalse(PlatformPolicy.mayStartSpeech(false, true, false, false))
        assertTrue(PlatformPolicy.mayStartSpeech(false, true, true, false))
        assertTrue(PlatformPolicy.mayStartSpeech(false, true, false, true))
        assertEquals("rime", PlatformPolicy.engine(true, true))
        assertEquals("pinyin.reference", PlatformPolicy.engine(true, false))
        assertEquals("pinyin.reference", PlatformPolicy.engine(true, true, nineKeyPinyin = true))
        assertEquals("latin", PlatformPolicy.engine(false, true))
        assertTrue(PlatformPolicy.shouldInsertLiteralSpace(false, true))
        assertFalse(PlatformPolicy.shouldInsertLiteralSpace(true, true))
        assertTrue(PlatformPolicy.shouldInsertLiteralSpace(true, false))
        assertFalse(PlatformPolicy.shouldFallbackToEditor(true))
        assertTrue(PlatformPolicy.shouldFallbackToEditor(false))
        assertTrue(PlatformPolicy.shouldRouteEditingToEngine(KeyboardPage.TEXT))
        assertFalse(PlatformPolicy.shouldRouteEditingToEngine(KeyboardPage.NUMBER))
        assertFalse(PlatformPolicy.shouldRouteEditingToEngine(KeyboardPage.SYMBOL))
        assertFalse(PlatformPolicy.shouldRouteEditingToEngine(KeyboardPage.EMOJI))
        assertTrue(
            PlatformPolicy.shouldInitializeKeyboardState(
                restarting = false,
                stateInitialized = true,
                sameEditor = false,
            ),
        )
        assertTrue(
            PlatformPolicy.shouldInitializeKeyboardState(
                restarting = true,
                stateInitialized = false,
                sameEditor = true,
            ),
        )
        assertFalse(
            PlatformPolicy.shouldInitializeKeyboardState(
                restarting = true,
                stateInitialized = true,
                sameEditor = false,
            ),
        )
        assertFalse(
            PlatformPolicy.shouldInitializeKeyboardState(
                restarting = false,
                stateInitialized = true,
                sameEditor = true,
            ),
        )
        assertEquals(KeyboardPage.TEXT, PlatformPolicy.initialKeyboardPage(InputType.TYPE_CLASS_TEXT))
        assertEquals(KeyboardPage.NUMBER, PlatformPolicy.initialKeyboardPage(InputType.TYPE_CLASS_NUMBER))
        assertEquals(KeyboardPage.NUMBER, PlatformPolicy.initialKeyboardPage(InputType.TYPE_CLASS_PHONE))
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "-"),
            KeyboardUiModel.numberRows(InputType.TYPE_CLASS_TEXT).flatten(),
        )
        assertEquals(
            listOf("*", "0", "#"),
            KeyboardUiModel.numberRows(InputType.TYPE_CLASS_PHONE).last(),
        )
        assertEquals(
            listOf(".", "0", "-"),
            KeyboardUiModel.numberRows(
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED,
            ).last(),
        )
        assertEquals("1", KeyboardUiModel.longPressValue('q'))
        assertEquals("?", KeyboardUiModel.longPressValue('m'))
        assertEquals(OneHandMode.LEFT, OneHandMode.CENTER.next())
        assertEquals(OneHandMode.RIGHT, OneHandMode.LEFT.next())
        assertEquals(OneHandMode.CENTER, OneHandMode.RIGHT.next())
        assertEquals(0.82f, KeyboardUiModel.keyboardWidthFraction(OneHandMode.LEFT, 400))
        assertEquals(0.62f, KeyboardUiModel.keyboardWidthFraction(OneHandMode.RIGHT, 700))
        assertTrue(
            KeyboardUiModel.keyHeightDp(
                Configuration.ORIENTATION_PORTRAIT,
                KeyboardSize.COMPACT,
                1.3f,
            ) >= 62,
        )
        assertTrue(KeyboardUiModel.chineseSymbolPages.all { it.size == 3 })
        assertTrue(KeyboardUiModel.englishSymbolPages.all { it.size == 3 })
        assertEquals(32, KeyboardUiModel.emojiRows.flatten().size)
        assertEquals(1, PlatformText.previousGraphemeUtf16Length("a"))
        assertEquals(2, PlatformText.previousGraphemeUtf16Length("e\u0301"))
        assertEquals(11, PlatformText.previousGraphemeUtf16Length("👨‍👩‍👧‍👦"))
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
            val requireNativeRime = InstrumentationRegistry.getArguments()
                .getString("requireNativeRime") == "true"
            if (requireNativeRime) {
                assertTrue("native librime must initialize and select the rime engine", nativeRime)
            }
            if (!nativeRime) {
                assertEquals(0, NativeIme.switchEngine(handle, "pinyin.reference"))
            }
            assertEquals(0, NativeIme.feed(handle, "nihao"))
            assertTrue(NativeIme.actions(handle).contains("你好"))
            assertEquals(0, NativeIme.command(handle, 2))
            assertTrue(NativeIme.actions(handle).contains("CommitText"))
            if (nativeRime) {
                assertEquals(0, NativeIme.command(handle, 3))
                assertEquals(0, NativeIme.feed(handle, "putao"))
                assertTrue("production Rime dictionary must contain 葡萄", NativeIme.actions(handle).contains("葡萄"))
            }
        } finally {
            NativeIme.destroy(handle)
        }
    }

    @Test
    fun testReferencePinyinCoversCommonPhrasesAndRejectsInvalidSuffixes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = File(context.filesDir, "instrumentation-reference-lexicon").apply { mkdirs() }
        val handle = NativeIme.create(data.absolutePath, "", "")
        assertTrue("runtime handle", handle != 0L)
        try {
            assertEquals(0, NativeIme.switchEngine(handle, "pinyin.reference"))
            for ((pinyin, expected) in listOf(
                "women" to "我们",
                "zhongguo" to "中国",
                "beijing" to "北京",
                "jintian" to "今天",
                "tianqi" to "天气",
                "xiexie" to "谢谢",
                "zaijian" to "再见",
                "putao" to "葡萄",
                "dianshiju" to "电视剧",
                "shurufa" to "输入法",
                "rengongzhineng" to "人工智能",
            )) {
                assertEquals(0, NativeIme.feed(handle, pinyin))
                assertTrue("missing $expected for $pinyin", NativeIme.actions(handle).contains(expected))
                assertEquals(0, NativeIme.command(handle, 3))
            }
            assertEquals(0, NativeIme.feed(handle, "nihaox"))
            val invalid = org.json.JSONArray(NativeIme.actions(handle))
            val candidateAction = (0 until invalid.length())
                .mapNotNull { invalid.optJSONObject(it) }
                .first { it.has("ShowCandidates") }
            assertEquals(0, candidateAction.getJSONArray("ShowCandidates").length())
        } finally {
            NativeIme.destroy(handle)
        }
    }

    @Test
    fun testReferenceNineKeyCommitsCommonPhrase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val handle = NativeIme.create(context.filesDir.absolutePath, "", "")
        assertTrue("runtime handle", handle != 0L)
        try {
            assertEquals(0, NativeIme.switchEngine(handle, "pinyin.reference"))
            assertEquals(0, NativeIme.feed(handle, "94664486"))
            assertTrue(NativeIme.actions(handle).contains("中国"))
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
