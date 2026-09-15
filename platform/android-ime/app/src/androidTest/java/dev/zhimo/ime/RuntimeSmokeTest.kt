// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

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
    fun testLearnedPhraseAndFrequencySurviveRuntimeRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directories = RimeAssets.prepare(context)
        val storage = File(context.cacheDir, "pinyin-learning-${System.nanoTime()}").apply { mkdirs() }
        var handle = 0L
        fun choices(batch: String): List<org.json.JSONObject> {
            val actions = org.json.JSONArray(batch)
            return (0 until actions.length()).flatMap { index ->
                val values = actions.optJSONObject(index)?.optJSONArray("ShowCandidates")
                if (values == null) emptyList() else (0 until values.length()).map { values.getJSONObject(it) }
            }
        }
        fun open(): Long = NativeIme.create(storage.absolutePath, directories.shared.absolutePath, directories.user.absolutePath).also {
            assertTrue(it != 0L)
            assertEquals(0, NativeIme.switchEngine(it, "pinyin.reference"))
        }
        try {
            handle = open()
            NativeIme.feed(handle, "maolvbei")
            var menu = choices(NativeIme.actions(handle))
            for (word in listOf("猫", "驴", "杯")) {
                val selected = menu.first { it.getString("display_text") == word }
                assertEquals(0, NativeIme.select(handle, selected.getString("id")))
                menu = choices(NativeIme.actions(handle))
            }
            for (word in listOf("泥", "泥", "你")) {
                NativeIme.feed(handle, "ni")
                val selected = choices(NativeIme.actions(handle)).first { it.getString("display_text") == word }
                assertEquals(0, NativeIme.select(handle, selected.getString("id")))
                NativeIme.actions(handle)
            }
            assertEquals(0, NativeIme.flush(handle))
            NativeIme.destroy(handle)
            handle = 0L
            assertTrue(File(storage, "learning-v1.sqlite3").isFile)
            handle = open()
            for ((code, word) in listOf("maolvbei" to "猫驴杯", "62658234" to "猫驴杯", "ni" to "泥", "64" to "泥")) {
                NativeIme.command(handle, 3)
                NativeIme.actions(handle)
                NativeIme.feed(handle, code)
                assertEquals("persisted ranking: $code", word, choices(NativeIme.actions(handle)).first().getString("display_text"))
            }
        } finally {
            if (handle != 0L) NativeIme.destroy(handle)
            storage.deleteRecursively()
        }
    }

    @Test
    fun testUnknownPhraseSupportsThreeSuccessiveCharacterSelections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directories = RimeAssets.prepare(context)
        val handle = NativeIme.create(context.filesDir.absolutePath, directories.shared.absolutePath, directories.user.absolutePath)
        try {
            for ((engine, input) in listOf("rime" to "maolvbei", "pinyin.reference" to "maolvbei", "pinyin.reference" to "62658234")) {
                if (engine == "rime" && NativeIme.switchEngine(handle, engine) != 0) {
                    assertFalse(InstrumentationRegistry.getArguments().getString("requireNativeRime") == "true")
                    continue
                }
                assertEquals(0, NativeIme.switchEngine(handle, engine))
                assertEquals(0, NativeIme.command(handle, 3))
                NativeIme.actions(handle)
                assertEquals(0, NativeIme.feed(handle, input))
                var actions = org.json.JSONArray(NativeIme.actions(handle))
                var committed = ""
                for ((position, word) in listOf("猫", "驴", "杯").withIndex()) {
                    var candidateId: String? = null
                    for (page in 0 until 20) {
                        for (index in 0 until actions.length()) {
                            val choices = actions.optJSONObject(index)?.optJSONArray("ShowCandidates") ?: continue
                            for (choice in 0 until choices.length()) {
                                val candidate = choices.getJSONObject(choice)
                                if (candidate.getString("display_text") == word) candidateId = candidate.getString("id")
                            }
                        }
                        if (candidateId != null || engine != "rime") break
                        val hasNext = (0 until actions.length()).any { actions.optJSONObject(it)?.optJSONObject("CandidatePage")?.optBoolean("has_next") == true }
                        if (!hasNext) break
                        assertEquals(0, NativeIme.command(handle, 7))
                        actions = org.json.JSONArray(NativeIme.actions(handle))
                    }
                    assertTrue("$engine $input position $position needs $word", candidateId != null)
                    assertEquals(0, NativeIme.select(handle, candidateId!!))
                    actions = org.json.JSONArray(NativeIme.actions(handle))
                    for (index in 0 until actions.length()) {
                        val action = actions.optJSONObject(index) ?: continue
                        if (action.has("CommitText")) committed += action.getString("CommitText")
                    }
                    if (position < 2) assertTrue("remaining input must survive", (0 until actions.length()).any {
                        actions.optJSONObject(it)?.optJSONObject("UpdateComposition")?.optJSONArray("segments")?.length()?.let { size -> size > 0 } == true
                    })
                }
                assertEquals("$engine $input", "猫驴杯", committed)
            }
        } finally { NativeIme.destroy(handle) }
    }

    @Test
    fun testContinuousPinyinSupplementInNativeAndNineKeyEngines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rime = RimeAssets.prepare(context)
        val handle = NativeIme.create(context.filesDir.absolutePath, rime.shared.absolutePath, rime.user.absolutePath)
        try {
            for (engine in listOf("rime", "pinyin.reference")) {
                if (engine == "rime" && NativeIme.switchEngine(handle, engine) != 0) {
                    assertFalse(InstrumentationRegistry.getArguments().getString("requireNativeRime") == "true")
                    continue
                }
                assertEquals("engine $engine", 0, NativeIme.switchEngine(handle, engine))
                val examples = mutableListOf("lvdai" to "履带", "lv'dai" to "履带", "wulianwang" to "物联网")
                if (engine == "pinyin.reference") examples.addAll(listOf("58324" to "履带", "9854269264" to "物联网"))
                for ((input, expected) in examples) {
                    assertEquals(0, NativeIme.command(handle, 3))
                    NativeIme.actions(handle)
                    assertEquals(0, NativeIme.feed(handle, input))
                    val actions = NativeIme.actions(handle)
                    assertTrue("$engine must recall $expected for $input", actions.contains(expected))
                    val batch = org.json.JSONArray(actions)
                    var selectedId: String? = null
                    for (index in 0 until batch.length()) {
                        val choices = batch.optJSONObject(index)?.optJSONArray("ShowCandidates") ?: continue
                        for (choice in 0 until choices.length()) {
                            val candidate = choices.getJSONObject(choice)
                            if (candidate.getString("display_text") == expected) selectedId = candidate.getString("id")
                        }
                    }
                    assertTrue("$expected must be selectable", selectedId != null)
                    assertEquals(0, NativeIme.select(handle, selectedId!!))
                    assertTrue(NativeIme.actions(handle).contains("\"CommitText\":\"$expected\""))
                }
            }
        } finally { NativeIme.destroy(handle) }
    }

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
        assertTrue(defaultConfig.readText().contains("zhimo_pinyin"))
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
                "shu'ru'fa" to "输入法",
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
