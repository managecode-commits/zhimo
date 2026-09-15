// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class GeographyRuntimeTest {
    @Test fun geographicOverlayLoadsInNativeRimeAndNineKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rime = RimeAssets.prepare(context)
        assertTrue(File(rime.shared, "zhimo_geography.dict.yaml").isFile)
        assertEquals("5", File(rime.shared, "version.txt").readText().trim())
        val data = File(context.cacheDir, "geography-runtime-test").apply { mkdirs() }
        val handle = NativeIme.create(data.absolutePath, rime.shared.absolutePath, rime.user.absolutePath)
        assertTrue(handle != 0L)
        try {
            assertTrue(NativeIme.capabilities() and NativeIme.CAP_NATIVE_LIBRIME != 0L)
            for (engine in listOf("rime", "pinyin.reference")) {
                assertEquals(0, NativeIme.switchEngine(handle, engine))
                for ((reading, word) in listOf(
                    "hebeisheng" to "河北省", "shijiazhuangshi" to "石家庄市",
                    "hunchunshi" to "珲春市", "shennongjialinqu" to "神农架林区",
                    "baiyangshi" to "白杨市", "jinmenxian" to "金门县",
                )) {
                    val code = if (engine == "rime") reading else reading.map { ch ->
                        when (ch) {
                            in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
                            in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
                            in 't'..'v' -> '8'; else -> '9'
                        }
                    }.joinToString("")
                    assertEquals(0, NativeIme.command(handle, 3))
                    assertEquals(0, NativeIme.feed(handle, code))
                    val actions = JSONArray(NativeIme.actions(handle))
                    var id: String? = null
                    for (index in 0 until actions.length()) {
                        val action = actions.optJSONObject(index) ?: continue
                        assertFalse("input must not commit before selection", action.has("CommitText"))
                        val candidates = action.optJSONArray("ShowCandidates") ?: continue
                        for (candidate in 0 until candidates.length()) {
                            val value = candidates.getJSONObject(candidate)
                            if (value.getString("commit_text") == word) id = value.getString("id")
                        }
                    }
                    assertNotNull("$engine $code must offer $word", id)
                    assertEquals(0, NativeIme.select(handle, checkNotNull(id)))
                    assertTrue(NativeIme.actions(handle).contains("\"CommitText\":\"$word\""))
                }
            }
        } finally {
            NativeIme.destroy(handle)
        }
    }
}
