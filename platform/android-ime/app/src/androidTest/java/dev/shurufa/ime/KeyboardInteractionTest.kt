package dev.shurufa.ime

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

    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            for (window in automation.windows) {
                find(window.root, label)?.let {
                    assertTrue("click $label", it.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(80)
                    return
                }
            }
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

    @Test
    fun pinyinPunctuationAndModeSwitchKeepComposedWord() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runKeyboardUi") == "true")
        val ime = "dev.shurufa.ime/.ShurufaInputMethodService"
        val previous = shell("settings get secure default_input_method")
        val wasEnabled = shell("ime list -s").lines().contains(ime)
        val preferences = instrumentation.targetContext.getSharedPreferences("shurufa", 0)
        val oldPinyin = preferences.getBoolean("default_pinyin", true)
        val oldNine = preferences.getBoolean("pinyin_nine_key", false)
        val oldSize = preferences.getString("keyboard_text_size", null)
        val oldContrast = preferences.getBoolean("high_contrast", false)
        val oldPosition = preferences.getString("one_hand_mode", null)
        val oldHandwriting = preferences.getBoolean("handwriting_enabled", false)
        val oldInkLanguage = preferences.getString("handwriting_language", null)
        val realInk = InstrumentationRegistry.getArguments().getString("runRealInk") == "true"
        val hadImage = preferences.contains("handwriting_image_experimental")
        val oldImage = preferences.getBoolean("handwriting_image_experimental", true)
        var activity: KeyboardTestActivity? = null
        try {
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
            assertTopRow(false)
            "nihao".forEach { click(it.toString()) }
            assertTopRow(true)
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
            click("ABC")
            click("9键")
            for (label in listOf("6\nMNO", "4\nGHI", "4\nGHI", "2\nABC", "6\nMNO")) click(label)
            capture("t9")
            assertTopRow(true)
            click("选词")
            instrumentation.runOnMainSync { assertEquals("你好，你好5你好", active.editor.text.toString()) }
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
            click("ABC")
            click("手写输入")
            assertTopRow(false)
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
                }
            }
            click("撤一笔")
            click("清空")
            click("拼音")
            "nihao".forEach { click(it.toString()) }
            click("选词")
            instrumentation.runOnMainSync { assertTrue(active.editor.text.toString().endsWith("你好")) }
        } finally {
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
