// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.graphics.Bitmap
import java.io.File
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: temporarily selects this IME on a dedicated emulator, restoring it afterwards. */
class KeyboardInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
            // Native popup menus expose the text as a child of the clickable row.
            var target: AccessibilityNodeInfo? = node
            repeat(4) {
                if (target?.isClickable == true) return target
                target = target?.parent
            }
        }
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun hasPreedit(node: AccessibilityNodeInfo?, vararg readings: String): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser && readings.any { node.contentDescription?.toString() == "待选拼音：$it" }) return true
        return (0 until node.childCount).any { hasPreedit(node.getChild(it), *readings) }
    }

    private fun awaitPreedit(message: String, vararg readings: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (automation.windows.any { hasPreedit(it.root, *readings) }) return
            SystemClock.sleep(30)
        }
        capture("failure")
        assertTrue(message, automation.windows.any { hasPreedit(it.root, *readings) })
    }

    private fun click(label: String, longPress: Boolean = false) {
        fun revealToolbarEnd(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.contentDescription?.toString() == "顶栏工具" && node.isScrollable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            return (0 until node.childCount).any { revealToolbarEnd(node.getChild(it)) }
        }
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            for (window in automation.windows) {
                find(window.root, label)?.let {
                    assertTrue("click $label", it.performAction(if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(80)
                    return
                }
            }
            // Seven minimum-touch-width tools intentionally scroll on narrow screens.
            if (label == "切换单手键盘位置") automation.windows.any { revealToolbarEnd(it.root) }
            SystemClock.sleep(100)
        }
        capture("failure")
        error("keyboard key not found: $label")
    }

    private fun capture(stage: String) {
        if (stage != "failure") {
            val context = instrumentation.targetContext
            val removedTitles = setOf("手写 · 点选上屏",
                context.getString(R.string.mode_pinyin_nine_key),
                context.getString(R.string.mode_pinyin_full_keyboard),
                context.getString(R.string.mode_pinyin_fallback))
            fun hasModeTitle(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isVisibleToUser && node.text?.toString() in removedTitles) return true
                return (0 until node.childCount).any { hasModeTitle(node.getChild(it)) }
            }
            org.junit.Assert.assertFalse("redundant mode title at $stage",
                automation.windows.any { hasModeTitle(it.root) })
        }
        val profile = InstrumentationRegistry.getArguments().getString("visualProfile") ?: "standard"
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "keyboard-visual").apply { mkdirs() }
        SystemClock.sleep(250)
        automation.takeScreenshot()?.let { bitmap ->
            File(directory, "$profile-$stage.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun assertTopRow(showCandidates: Boolean) {
        fun visible(node: AccessibilityNodeInfo?, label: String): Boolean {
            if (node == null) return false
            if (node.isVisibleToUser && node.contentDescription?.toString() == label) return true
            return (0 until node.childCount).any { visible(node.getChild(it), label) }
        }
        assertEquals("candidate row visibility", showCandidates, automation.windows.any { visible(it.root, "顶栏候选") })
        assertEquals("toolbar visibility", !showCandidates, automation.windows.any { visible(it.root, "顶栏工具") })
    }

    private fun keyboardBodyBounds(): android.graphics.Rect {
        fun lookup(node: AccessibilityNodeInfo?): android.graphics.Rect? {
            if (node == null) return null
            if (node.isVisibleToUser && node.contentDescription?.toString() == "键盘按键区域") {
                return android.graphics.Rect().also { node.getBoundsInScreen(it) }
            }
            for (index in 0 until node.childCount) lookup(node.getChild(index))?.let { return it }
            return null
        }
        val bounds = automation.windows.firstNotNullOfOrNull { lookup(it.root) }
            ?: error("keyboard body not visible")
        assertTrue(bounds.height() > 0)
        return bounds
    }

    private fun assertFooterVisible(activity: KeyboardTestActivity) {
        val button = automation.windows.firstNotNullOfOrNull { find(it.root, "空格") ?: find(it.root, "选词") }
            ?: error("space key is not visible")
        val bounds = android.graphics.Rect().also { button.getBoundsInScreen(it) }
        assertTrue("footer touch height was clipped: $bounds", bounds.height() >= 48 * instrumentation.targetContext.resources.displayMetrics.density)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            var navigation = 0
            instrumentation.runOnMainSync {
                navigation = activity.window.decorView.rootWindowInsets?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
            }
            val screenshot = checkNotNull(automation.takeScreenshot())
            val screenHeight = screenshot.height
            screenshot.recycle()
            assertTrue("footer overlaps navigation: $bounds screen=$screenHeight nav=$navigation", bounds.bottom <= screenHeight - navigation)
        }
    }

    @Test
    fun pinyinPunctuationAndModeSwitchKeepComposedWord() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runKeyboardUi") == "true")
        val ime = "dev.zhimo.ime/.ZhimoInputMethodService"
        val previous = shell("settings get secure default_input_method")
        val wasEnabled = shell("ime list -s").lines().contains(ime)
        val preferences = instrumentation.targetContext.getSharedPreferences("zhimo", 0)
        val oldPinyin = preferences.getBoolean("default_pinyin", true)
        val oldNine = preferences.getBoolean("pinyin_nine_key", false)
        val oldSize = preferences.getString("keyboard_text_size", null)
        val oldContrast = preferences.getBoolean("high_contrast", false)
        val oldPosition = preferences.getString("one_hand_mode", null)
        val oldEmojiRecent = preferences.getString("emoji_recent", null)
        val oldLearning = preferences.getBoolean("learning_enabled", true)
        val oldHandwriting = preferences.getBoolean("handwriting_enabled", false)
        val oldInkLanguage = preferences.getString("handwriting_language", null)
        val realInk = InstrumentationRegistry.getArguments().getString("runRealInk") == "true"
        val hadImage = preferences.contains("handwriting_image_experimental")
        val oldImage = preferences.getBoolean("handwriting_image_experimental", true)
        val oldCharacterMode = preferences.getString("handwriting_character_mode", null)
        val hadOfflineSpeech = preferences.contains("bundled_offline_speech")
        val oldOfflineSpeech = preferences.getBoolean("bundled_offline_speech", true)
        var activity: KeyboardTestActivity? = null
        try {
            preferences.edit().remove("emoji_recent").putBoolean("learning_enabled", true).commit()
            preferences.edit().putString("handwriting_character_mode", "MIXED").commit()
            preferences.edit().putBoolean("handwriting_image_experimental",
                InstrumentationRegistry.getArguments().getString("runImageInk") == "true").commit()
            preferences.edit().putBoolean("handwriting_enabled", realInk).putString("handwriting_language", "zh-Hani-CN").commit()
            preferences.edit().putBoolean("default_pinyin", true).putBoolean("pinyin_nine_key", false).commit()
            val large = InstrumentationRegistry.getArguments().getString("visualProfile") == "large"
            preferences.edit().putString("keyboard_text_size", if (large) "EXTRA_LARGE" else "STANDARD")
                .putBoolean("high_contrast", large).putString("one_hand_mode", if (large) "left" else "center").commit()
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            shell("ime enable $ime")
            shell("ime set $ime")
            val active = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardTestActivity
            activity = active
            // Landscape IMEs may decline an implicit auto-show; a real editor
            // interaction requests the keyboard explicitly.
            click("键盘回归输入框")
            val readyDeadline = SystemClock.uptimeMillis() + 15000
            while (automation.windows.none { find(it.root, "空格") != null } &&
                SystemClock.uptimeMillis() < readyDeadline) SystemClock.sleep(100)
            assertTopRow(false)
            SystemClock.sleep(350) // Wait for IME insets animation before comparing heights.
            val keyboardBounds = keyboardBodyBounds()
            assertFooterVisible(active)
            click("表情键盘")
            click("表情分类：常用")
            click("输入表情：😀"); click("输入表情：😀")
            instrumentation.runOnMainSync { assertEquals("😀😀", active.editor.text.toString()) }
            SystemClock.sleep(350)
            val emojiBounds = keyboardBodyBounds()
            assertFooterVisible(active)
            assertEquals("表情与 26 键高度一致", keyboardBounds.height(), emojiBounds.height())
            capture("emoji")
            if (!large) {
                click("表情分类：最近")
                click("输入表情：😀")
                instrumentation.runOnMainSync { assertEquals("😀😀😀", active.editor.text.toString()) }
                click("清空最近表情")
                assertEquals(listOf("😀"), EmojiCatalog.recent(preferences.getString("emoji_recent", "").orEmpty()))
                click("确认清空最近表情")
                assertTrue(EmojiCatalog.recent(preferences.getString("emoji_recent", "").orEmpty()).isEmpty())
                click("删除，长按连续删除")
            }
            click("删除，长按连续删除"); click("删除，长按连续删除")
            instrumentation.runOnMainSync { assertEquals("", active.editor.text.toString()) }
            click("返回表情前的输入模式")
            val originalHand = preferences.getString("one_hand_mode", "center")
            repeat(3) { click("切换单手键盘位置") }
            assertEquals(originalHand, preferences.getString("one_hand_mode", "center"))
            assertTrue("空格必须支持长按语音", automation.windows.any { find(it.root, "空格")?.isLongClickable == true })
            click("j"); click("i")
            click("拼音手动分词")
            click("a"); click("n")
            instrumentation.runOnMainSync { assertEquals("分词不得提前上屏", "", active.editor.text.toString()) }
            capture("manual-segmentation")
            assertFooterVisible(active)
            click("候选词 吉安")
            instrumentation.runOnMainSync { assertEquals("吉安", active.editor.text.toString()) }
            click("删除，长按连续删除"); click("删除，长按连续删除")
            "nihao".forEach { click(it.toString()) }
            assertTopRow(true)
            instrumentation.runOnMainSync { assertEquals("未选拼音不得写入应用", "", active.editor.text.toString()) }
            awaitPreedit("26 键必须显示待选拼音", "ni hao", "nihao", "ni'hao")
            capture("letters")
            click("，")
            instrumentation.runOnMainSync { assertEquals("你好，", active.editor.text.toString()) }
            assertTopRow(false)
            "nihao".forEach { click(it.toString()) }
            click("键盘工具")
            click("123")
            instrumentation.runOnMainSync { assertEquals("你好，你好", active.editor.text.toString()) }
            click("5")
            instrumentation.runOnMainSync { assertEquals("你好，你好5", active.editor.text.toString()) }
            capture("numbers")
            click("拼音")
            click("9键")
            SystemClock.sleep(350)
            val nineBounds = keyboardBodyBounds()
            click("表情键盘")
            SystemClock.sleep(350)
            assertEquals("表情与 9 键高度一致", nineBounds.height(), keyboardBodyBounds().height())
            click("返回表情前的输入模式")
            for (label in listOf("5\nJKL", "4\nGHI", "2\nABC", "6\nMNO")) click(label)
            click("选择拼音：lian")
            instrumentation.runOnMainSync { assertEquals("选拼音不能上屏", "你好，你好5", active.editor.text.toString()) }
            capture("t9-reading-lian")
            assertFooterVisible(active)
            click("候选词 连")
            instrumentation.runOnMainSync { assertEquals("你好，你好5连", active.editor.text.toString()) }
            click("删除，长按连续删除")
            for (label in listOf("6\nMNO", "4\nGHI", "4\nGHI", "2\nABC", "6\nMNO")) click(label)
            instrumentation.runOnMainSync { assertEquals("9 键编码不得写入应用", "你好，你好5", active.editor.text.toString()) }
            awaitPreedit("9 键应显示输入对应的读音选项，不跟随首候选猜测", "mi / ng / ni / m · 连拼")
            capture("t9")
            assertTopRow(true)
            click("选词")
            instrumentation.runOnMainSync { assertEquals("你好，你好5你好", active.editor.text.toString()) }
            // Restore the same text through the nine-key action/return button.
            // This must choose Chinese, not leak the 64426 spelling code.
            click("删除，长按连续删除")
            click("删除，长按连续删除")
            for (label in listOf("6\nMNO", "4\nGHI", "4\nGHI", "2\nABC", "6\nMNO")) click(label)
            awaitPreedit("9 键回车前等待解码", "mi / ng / ni / m · 连拼")
            click("完成")
            val enterDeadline = SystemClock.uptimeMillis() + 10000
            var correctEnter = false
            while (!correctEnter && SystemClock.uptimeMillis() < enterDeadline) {
                instrumentation.runOnMainSync { correctEnter = active.editor.text.toString() == "你好，你好5你好" }
                if (!correctEnter) SystemClock.sleep(30)
            }
            assertTrue("9 键完成键必须选汉字，不能提交数字编码", correctEnter)
            click("6\nMNO"); click("4\nGHI")
            click("清空当前拼音")
            instrumentation.runOnMainSync { assertEquals("你好，你好5你好", active.editor.text.toString()) }
            click("？")
            instrumentation.runOnMainSync { assertEquals("你好，你好5你好？", active.editor.text.toString()) }
            click("26键")
            "ren".forEach { click(it.toString()) }
            click("展开全部候选")
            capture("candidates")
            click("返回键盘")
            click("选词")
            click("符号键盘")
            capture("symbols")
            click("拼音")
            click("手写输入")
            assertTopRow(false)
            click("表情键盘")
            click("返回表情前的输入模式")
            assertTrue("从表情返回手写", automation.windows.any {
                find(it.root, "切换单字或自由连写") != null
            })
            if (automation.windows.none { find(it.root, "手写区域，请一次书写一个汉字") != null }) click("切换单字或自由连写")
            capture("handwriting")
            if (realInk) {
                val label = "手写区域，请一次书写一个汉字"
                click(label)
                val node = automation.windows.firstNotNullOfOrNull { find(it.root, label) }!!
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val down = SystemClock.uptimeMillis()
                for (i in 0..20) {
                    val action = when (i) { 0 -> android.view.MotionEvent.ACTION_DOWN; 20 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        bounds.left + bounds.width() * (0.15f + i * 0.035f), bounds.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(automation.injectInputEvent(event, true)); event.recycle()
                    SystemClock.sleep(15)
                }
                val portrait = instrumentation.targetContext.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                var beforePunctuation = ""
                instrumentation.runOnMainSync { beforePunctuation = active.editor.text.toString() }
                if (portrait) {
                    click("手写标点：，")
                    instrumentation.runOnMainSync { assertEquals(beforePunctuation, active.editor.text.toString()) }
                }
                click("更多手写候选")
                assertTopRow(true)
                capture("handwriting-grid")
                click("更多手写候选")
                click("手写候选：一")
                instrumentation.runOnMainSync { assertTrue(active.editor.text.toString().endsWith("一")) }
                if (portrait) {
                    click("手写标点：，")
                    instrumentation.runOnMainSync { assertTrue(active.editor.text.toString().endsWith("一，")) }
                    click("手写删除：有笔迹撤笔，无笔迹删字")
                    instrumentation.runOnMainSync { assertTrue(active.editor.text.toString().endsWith("一")) }
                }
                capture("handwriting-committed")
                if (InstrumentationRegistry.getArguments().getString("runLine") == "true") {
                    if (automation.windows.none { find(it.root, "连写区域，从左到右书写，不限格") != null }) click("切换单字或自由连写")
                    var beforeDraft = ""
                    instrumentation.runOnMainSync { beforeDraft = active.editor.text.toString() }
                    repeat(2) { cell ->
                        val pad = automation.windows.firstNotNullOfOrNull { find(it.root, "连写区域，从左到右书写，不限格") }!!
                        val box = android.graphics.Rect(); pad.getBoundsInScreen(box)
                        val start = SystemClock.uptimeMillis()
                        for (i in 0..20) {
                            val action = when (i) { 0 -> android.view.MotionEvent.ACTION_DOWN; 20 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                            val event = android.view.MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                                box.left + box.width() * (if (cell == 0) .05f + i * .02f else .58f + i * .0185f), box.exactCenterY(), 0)
                            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            assertTrue(automation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(15)
                        }
                    }
                    instrumentation.runOnMainSync { assertEquals(beforeDraft, active.editor.text.toString()) }
                    capture("line-ink")
                    click("拼音") // must refuse to discard pending draft
                    click("更多手写候选")
                    capture("line-candidates")
                    click("更多手写候选")
                    click("调整分字")
                    capture("line-boundaries")
                    fun boundaryGesture(startX: Float, endX: Float) {
                        val pad = automation.windows.firstNotNullOfOrNull { find(it.root, "连写区域，从左到右书写，不限格") }!!
                        val box = android.graphics.Rect(); pad.getBoundsInScreen(box)
                        val start = SystemClock.uptimeMillis()
                        for ((index, fraction) in listOf(startX, endX, endX).withIndex()) {
                            val action = listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP)[index]
                            val event = android.view.MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                                box.left + box.width() * fraction, box.exactCenterY(), 0)
                            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            assertTrue(automation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(30)
                        }
                    }
                    boundaryGesture(.515f, .515f) // tap the suggested boundary to merge
                    click("整句上屏") // must not submit while editing boundaries
                    instrumentation.runOnMainSync { assertEquals(beforeDraft, active.editor.text.toString()) }
                    boundaryGesture(.5f, .5f) // add a boundary in the gap
                    boundaryGesture(.5f, .56f) // move it without modifying ink
                    click("完成分字")
                    click("更多手写候选")
                    val word = automation.windows.firstNotNullOfOrNull { find(it.root, "手写候选：一一") }!!
                    assertTrue(word.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                    click("第1字替换为：一")
                    click("纠正第2字：一")
                    click("第2字替换为：一")
                    instrumentation.runOnMainSync { assertEquals(beforeDraft, active.editor.text.toString()) }
                    capture("line-correction")
                    click("确认逐字纠正上屏")
                    instrumentation.runOnMainSync { assertEquals(beforeDraft + "一一", active.editor.text.toString()) }
                    capture("line-committed")
                    if (InstrumentationRegistry.getArguments().getString("runImageInk") == "true") {
                        fun selectRange(label: String) {
                            repeat(4) {
                                if (automation.windows.any { find(it.root, "$label ↻") != null }) return
                                click("切换手写识别范围")
                            }
                            error("range not selected: $label")
                        }
                        fun drawShape(strokes: List<List<Pair<Float,Float>>>) {
                            val pad=automation.windows.firstNotNullOfOrNull { find(it.root,"连写区域，从左到右书写，不限格") }!!
                            val box=android.graphics.Rect();pad.getBoundsInScreen(box)
                            for(stroke in strokes) {
                                val start=SystemClock.uptimeMillis()
                                stroke.forEachIndexed { i,p ->
                                    val action=when(i) { 0->android.view.MotionEvent.ACTION_DOWN;stroke.lastIndex->android.view.MotionEvent.ACTION_UP;else->android.view.MotionEvent.ACTION_MOVE }
                                    val event=android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,box.left+box.width()*p.first,box.top+box.height()*p.second,0)
                                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                                    assertTrue(automation.injectInputEvent(event,true));event.recycle();SystemClock.sleep(15)
                                }
                            }
                        }
                        selectRange("数字")
                        drawShape(listOf((0..48).map { i -> val angle=i*2*Math.PI/48; (.5f+.15f*kotlin.math.cos(angle).toFloat()) to (.5f+.3f*kotlin.math.sin(angle).toFloat()) }))
                        click("更多手写候选")
                        capture("digit-candidates")
                        click("手写候选：0")
                        selectRange("字母")
                        drawShape(listOf(listOf(.3f to .8f,.5f to .2f,.7f to .8f),listOf(.4f to .55f,.6f to .55f)))
                        click("更多手写候选")
                        capture("letter-candidates")
                        click("手写候选：A")
                        instrumentation.runOnMainSync { assertEquals(beforeDraft+"一一0A",active.editor.text.toString()) }
                        capture("alphabet-committed")
                    }
                }
            }
            click("撤一笔")
            click("清空")
            click("拼音")
            "nihao".forEach { click(it.toString()) }
            click("选词")
            instrumentation.runOnMainSync { assertTrue(active.editor.text.toString().endsWith("你好")) }
            if (InstrumentationRegistry.getArguments().getString("runOfflineMic") == "true") {
                preferences.edit().putBoolean("bundled_offline_speech", true).commit()
                assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                    instrumentation.targetContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
                fun awaitVoice(label: String) {
                    val deadline = SystemClock.uptimeMillis() + 90000
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (automation.windows.any { find(it.root, label) != null }) return
                        SystemClock.sleep(100)
                    }
                    error("Voice state not reached: $label")
                }
                val beforeVoice = active.editor.text.toString()
                var heldAt = 0L
                val heldBounds = android.graphics.Rect()
                fun touchSpace(action: Int) {
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        heldAt = SystemClock.uptimeMillis()
                        automation.windows.firstNotNullOfOrNull { find(it.root, "空格") }!!
                            .getBoundsInScreen(heldBounds)
                    }
                    val event = android.view.MotionEvent.obtain(heldAt, SystemClock.uptimeMillis(), action,
                        heldBounds.exactCenterX(), heldBounds.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                touchSpace(android.view.MotionEvent.ACTION_DOWN)
                awaitVoice("停止语音输入")
                capture("offline-voice-recording")
                SystemClock.sleep(400)
                touchSpace(android.view.MotionEvent.ACTION_UP)
                awaitVoice("空格")
                instrumentation.runOnMainSync { assertEquals(beforeVoice, active.editor.text.toString()) }
                touchSpace(android.view.MotionEvent.ACTION_DOWN)
                awaitVoice("停止语音输入")
                instrumentation.runOnMainSync { active.editor.setSelection(0) }
                awaitVoice("空格")
                touchSpace(android.view.MotionEvent.ACTION_CANCEL)
                capture("offline-voice-cancelled")
            }
        } finally {
            preferences.edit().putString("emoji_recent", oldEmojiRecent).putBoolean("learning_enabled", oldLearning).commit()
            preferences.edit().apply {
                if (hadOfflineSpeech) putBoolean("bundled_offline_speech", oldOfflineSpeech) else remove("bundled_offline_speech")
            }.commit()
            preferences.edit().putString("handwriting_character_mode", oldCharacterMode).commit()
            preferences.edit().apply {
                if (hadImage) putBoolean("handwriting_image_experimental", oldImage) else remove("handwriting_image_experimental")
            }.commit()
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            if (previous.matches(Regex("[A-Za-z0-9_./]+")) && previous != "null") shell("ime set $previous")
            if (!wasEnabled && previous != ime) shell("ime disable $ime")
            preferences.edit().putBoolean("default_pinyin", oldPinyin).putBoolean("pinyin_nine_key", oldNine).commit()
            preferences.edit().putString("keyboard_text_size", oldSize).putBoolean("high_contrast", oldContrast)
                .putString("one_hand_mode", oldPosition).commit()
            preferences.edit().putBoolean("handwriting_enabled", oldHandwriting).putString("handwriting_language", oldInkLanguage).commit()
        }
    }
}
