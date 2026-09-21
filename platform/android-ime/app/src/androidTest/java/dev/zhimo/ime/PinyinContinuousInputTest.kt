// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PinyinContinuousInputTest {
    private fun exercise(engine: String, cases: List<Pair<String, String>>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = RimeAssets.prepare(context)
        val directory = File(context.cacheDir, "continuous-pinyin-${System.nanoTime()}").apply { mkdirs() }
        val user = File(directory, "rime").apply { mkdirs() }
        val handle = NativeIme.create(directory.path, assets.shared.path, user.path)
        assertTrue(handle != 0L)
        try {
            assertEquals(0, NativeIme.switchEngine(handle, engine))
            for ((reading, word) in cases) {
                assertEquals(0, NativeIme.command(handle, 3))
                reading.forEach { assertEquals(0, NativeIme.feed(handle, it.toString())) }
                var selected: String? = null
                for (page in 0 until 100) {
                    val actions = InputActionDecoder.decode(NativeIme.actions(handle))
                    val values = actions.filterIsInstance<InputAction.Candidates>().last().values
                    for (index in 0 until values.length()) {
                        val candidate = values.getJSONObject(index)
                        if (candidate.getString("commit_text") == word) {
                            selected = candidate.getString("id")
                            assertTrue("missing pronunciation: $reading", candidate.optString("annotation").isNotBlank())
                            break
                        }
                    }
                    if (selected != null || actions.filterIsInstance<InputAction.Page>().none { it.hasNext }) break
                    assertEquals(0, NativeIme.command(handle, 7))
                }
                assertNotNull("$engine: $reading must offer $word", selected)
                assertEquals(0, NativeIme.select(handle, selected!!))
                val result = InputActionDecoder.decode(NativeIme.actions(handle))
                assertEquals(word, result.filterIsInstance<InputAction.Commit>().joinToString("") { it.text })
                assertTrue(result.contains(InputAction.Close))
            }
        } finally { NativeIme.destroy(handle) }
    }

    @Test fun nineKeyWordsSentencesAndAbbreviations() = exercise("pinyin.reference", listOf(
        "58324" to "履带", "9854269264" to "物联网", "6442694664486" to "你好中国",
        "64" to "你好", "96" to "我们", "54'26" to "吉安",
    ))

    @Test fun qwertyRimeWordsSentencesAndAbbreviations() = exercise("rime", listOf(
        "lvdai" to "履带", "wulianwang" to "物联网", "nihaozhongguo" to "你好中国",
        "nh" to "你好", "nhao" to "你好", "ji'an" to "吉安",
    ))
}
