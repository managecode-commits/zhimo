// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Narrow geometric tie-break, not a recognizer. Never changes unrelated model labels. */
internal object HandwritingShapeRanking {
    fun rank(request: InkRequest, choices: List<String>): List<String> {
        if (request.characterMode !in listOf(HandwritingCharacterMode.CHINESE, HandwritingCharacterMode.MIXED) ||
            request.strokes.size != 4 || request.strokes.any { it.size < 2 } ||
            choices.take(3).none { it == "天" || it == "夫" }) return choices
        val points = request.strokes.flatten()
        val height = points.maxOf { it.y } - points.minOf { it.y }
        if (height <= 0f) return choices
        val bars = request.strokes.filter { stroke ->
            val width = stroke.maxOf { it.x } - stroke.minOf { it.x }
            val rise = stroke.maxOf { it.y } - stroke.minOf { it.y }
            width > height * .45f && rise < width * .24f
        }.sortedBy { it.map(InkPoint::y).average() }
        if (bars.size != 2) return choices
        val top = bars[0]; val bottom = bars[1]
        val topWidth = top.maxOf { it.x } - top.minOf { it.x }
        val bottomWidth = bottom.maxOf { it.x } - bottom.minOf { it.x }
        if (topWidth > bottomWidth * .95f) return choices
        fun barY(stroke: List<InkPoint>, x: Float): Float {
            val left = stroke.minBy { it.x }; val right = stroke.maxBy { it.x }
            return left.y + (right.y - left.y) * (x - left.x) / (right.x - left.x)
        }
        val legs = request.strokes.filter { it !in bars }
        val left = legs.singleOrNull { it.last().x < it.first().x - height * .18f &&
            it.last().y > it.first().y + height * .45f } ?: return choices
        val right = legs.first { it !== left }
        if (right.last().x < right.first().x + height * .25f ||
            right.last().y < right.first().y + height * .10f) return choices
        val start = left.first()
        if (start.x !in top.minOf { it.x }..top.maxOf { it.x }) return choices
        val topY = barY(top, start.x)
        val bottomY = barY(bottom, start.x)
        if (bottomY - topY < height * .2f || left.last().y < bottomY + height * .2f ||
            right.first().y < bottomY - height * .15f) return choices
        // Leave the touching/uncertain band unchanged rather than guessing.
        val preferred = when {
            start.y > topY + height * .025f && start.y < bottomY - height * .1f -> "天"
            start.y < topY - height * .10f -> "夫"
            else -> return choices
        }
        val other = if (preferred == "天") "夫" else "天"
        val anchor = choices.indexOfFirst { it == preferred || it == other }
        val rest = choices.filter { it != preferred }
        return (rest.take(anchor) + preferred + rest.drop(anchor)).distinct().take(100)
    }
}
