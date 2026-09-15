// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.json.JSONArray

/** Decode the whole batch before applying editor mutations. No UI or JNI ownership. */
sealed interface InputAction {
    data object Close : InputAction
    data class Commit(val text: String) : InputAction
    data class Composition(val text: String) : InputAction
    data class Candidates(val values: JSONArray) : InputAction
    data class Page(val index: Int, val hasNext: Boolean) : InputAction
    data class Readings(val values: List<String>, val selected: String?) : InputAction
}

object InputActionDecoder {
    fun decode(json: String): List<InputAction> = buildList {
        val actions = JSONArray(json)
        for (index in 0 until actions.length()) {
            val item = actions.get(index)
            if (item == "CloseComposition") { add(InputAction.Close); continue }
            val action = item as? org.json.JSONObject ?: continue
            when {
                action.has("CommitText") -> add(InputAction.Commit(action.getString("CommitText")))
                action.has("UpdateComposition") -> {
                    val segments = action.getJSONObject("UpdateComposition").getJSONArray("segments")
                    add(InputAction.Composition(buildString {
                        for (segment in 0 until segments.length()) append(segments.getJSONObject(segment).getString("text"))
                    }))
                }
                action.has("ShowCandidates") -> add(InputAction.Candidates(action.getJSONArray("ShowCandidates")))
                action.has("CandidatePage") -> {
                    val page = action.getJSONObject("CandidatePage")
                    add(InputAction.Page(page.getInt("index"), page.getBoolean("has_next")))
                }
                action.has("PinyinReadings") || action.has("ReadingOptions") -> {
                    val readings = action.optJSONObject("ReadingOptions") ?: action.getJSONObject("PinyinReadings")
                    val values = readings.getJSONArray("readings")
                    add(InputAction.Readings(List(values.length()) { values.getString(it) },
                        if (readings.isNull("selected")) null else readings.getString("selected")))
                }
            }
        }
    }
}
