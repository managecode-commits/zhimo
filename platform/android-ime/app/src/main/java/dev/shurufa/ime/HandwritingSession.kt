package dev.shurufa.ime

/** Platform-independent ink values; never persisted or logged. Coordinates are canvas pixels. */
data class InkPoint(val x: Float, val y: Float, val timeMillis: Long)
data class InkRequest(val revision: Long, val strokes: List<List<InkPoint>>, val width: Float, val height: Float,
    val boundaries: List<Float>? = null)

/** One instance per visible writing surface. A revision invalidates both results and candidate clicks. */
class HandwritingSession {
    private val strokes = mutableListOf<List<InkPoint>>()
    var revision = 0L
        private set
    var active = true
        private set
    var choices: List<String> = emptyList()
        private set

    fun invalidate() { revision++; choices = emptyList() }
    fun add(stroke: List<InkPoint>): Boolean {
        if (!active || stroke.isEmpty() || strokes.size >= 128 || stroke.size > 4096 ||
            stroke.any { !it.x.isFinite() || !it.y.isFinite() || it.x < 0 || it.y < 0 || it.timeMillis < 0 } ||
            stroke.zipWithNext().any { (a, b) -> b.timeMillis < a.timeMillis }) return false
        strokes.add(stroke.toList()); invalidate(); return true
    }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); invalidate() }
    fun clear() { strokes.clear(); invalidate() }
    fun close() { clear(); active = false }
    fun snapshot(width: Float, height: Float): InkRequest? =
        if (active && strokes.isNotEmpty() && width.isFinite() && height.isFinite() && width > 0 && height > 0)
            InkRequest(revision, strokes.map { it.toList() }, width, height) else null
    fun accept(version: Long, values: List<String>): Boolean {
        if (!active || version != revision) return false
        choices = values.filter { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
            .distinct().take(100)
        return true
    }
    fun candidate(version: Long, index: Int): String? =
        if (active && version == revision) choices.getOrNull(index) else null
}
