package dev.shurufa.ime

/** Bounded geometric hypotheses. Keeps every stroke whole; no grid and no timing threshold. */
object HandwritingSegmentation {
    data class Plan(val cuts: List<Float>, val groups: List<List<Int>>)
    private data class Box(val index: Int, val left: Float, val right: Float, val center: Float)
    fun plans(request: InkRequest): List<Plan> {
        require(request.width.isFinite() && request.width > 0 && request.height.isFinite() && request.height > 0)
        require(request.strokes.isNotEmpty() && request.strokes.size <= 64 && request.strokes.sumOf { it.size } <= 32768)
        val boxes = request.strokes.mapIndexed { index, stroke ->
            require(stroke.isNotEmpty() && stroke.size <= 4096)
            require(stroke.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..request.width && it.y in 0f..request.height && it.timeMillis >= 0 })
            require(stroke.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis })
            val left = stroke.minOf { it.x }; val right = stroke.maxOf { it.x }
            Box(index, left, right, (left + right) / 2)
        }
        fun plan(cuts: List<Float>): Plan {
            val groups = List(cuts.size + 1) { mutableListOf<Int>() }
            boxes.forEach { box -> groups[cuts.count { box.center / request.width > it }].add(box.index) }
            return Plan(cuts, groups.filter { it.isNotEmpty() }.map { it.toList() })
        }
        request.boundaries?.let { cuts ->
            require(cuts.size <= 3 && cuts.all { it.isFinite() && it > 0 && it < 1 } && cuts.zipWithNext().all { (a,b) -> a < b })
            return listOf(plan(cuts))
        }
        val span = (boxes.maxOf { it.right } - boxes.minOf { it.left }).coerceAtLeast(1f)
        val natural = mutableListOf<Pair<Float, Float>>()
        var right = boxes.minOf { it.left }
        for (box in boxes.sortedBy { it.left }) {
            val gap = box.left - right
            if (gap > span * .018f) natural.add((right + box.left) / 2 / request.width to gap)
            right = maxOf(right, box.right)
        }
        val preferred = natural.sortedByDescending { it.second }.take(3).map { it.first }.sorted()
        val centers = boxes.map { it.center }.distinct().sorted()
        val proposals = centers.zipWithNext().map { (a,b) ->
            val x = (a+b)/2
            val crossings = boxes.count { it.left < x && it.right > x }
            x / request.width to (crossings * 2f - (b-a)/span)
        }.sortedBy { it.second }.take(8)
        val alternatives = mutableListOf(plan(preferred))
        // Compare merged and differently split interpretations, rather than committing a guessed boundary.
        for (count in 0..3) {
            if (alternatives.size >= 4) break
            val cuts = proposals.take(count).map { it.first }.sorted()
            val candidate = plan(cuts)
            if (alternatives.none { it.groups == candidate.groups }) alternatives.add(candidate)
        }
        return alternatives
    }
    fun part(request: InkRequest, indices: List<Int>): InkRequest {
        // Retain original canvas coordinates and all points; the single-character provider normalizes them.
        return request.copy(strokes = indices.map { request.strokes[it] }, boundaries = null)
    }
}
