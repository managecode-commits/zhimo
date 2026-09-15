// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Multiple spatial hypotheses, never time-based segmentation. Reuses one offline model. */
class LineHandwritingProvider(private val single: HandwritingProvider) : HandwritingProvider {
    private var closed = false
    var characterRevision: Long = -1
        private set
    var characterChoices: List<List<String>> = emptyList()
        private set
    private var candidateParts: Map<String, List<List<String>>> = emptyMap()
    fun choicesFor(value: String): List<List<String>> = candidateParts[value].orEmpty()
    var suggestedBoundaries: List<Float> = emptyList()
        private set
    override fun available(done: (Boolean) -> Unit) = single.available(done)
    override fun close() { closed = true; characterChoices = emptyList(); candidateParts = emptyMap(); single.close() }
    override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
        characterRevision = -1; characterChoices = emptyList(); candidateParts = emptyMap()
        val plans = runCatching { HandwritingSegmentation.plans(request) }
        if (plans.isFailure) { done(Result.failure(plans.exceptionOrNull()!!)); return }
        val hypotheses = plans.getOrThrow()
        suggestedBoundaries = hypotheses.first().cuts
        val groups = hypotheses.flatMap { it.groups }.distinct()
        val cache = mutableMapOf<List<Int>, List<String>>()
        var lastFailure: Throwable? = null
        fun next(index: Int) {
            if (closed) return
            if (index == groups.size) {
                val points = request.strokes.flatten()
                val inkHeight = (points.maxOf { it.y } - points.minOf { it.y }).coerceAtLeast(1f)
                val inkWidth = points.maxOf { it.x } - points.minOf { it.x }
                val compactHan = request.boundaries == null && inkWidth / inkHeight <= 1.6f &&
                    request.characterMode in listOf(HandwritingCharacterMode.CHINESE, HandwritingCharacterMode.MIXED)
                val bilateral = if (compactHan) hypotheses.firstOrNull { plan ->
                    plan.groups.size == 2 && plan.groups.all { group ->
                        val ink = group.flatMap { request.strokes[it] }
                        group.size >= 2 && (ink.maxOf { it.y } - ink.minOf { it.y }) >= inkHeight * .55f
                    }
                } else null
                val lists = hypotheses.map { hypothesis ->
                    val values = combine(hypothesis.groups.map { cache[it].orEmpty() }, request.characterMode)
                    if (hypothesis.groups.size == 1 && bilateral != null)
                        protectWholeGlyph(values, bilateral.groups.map { cache[it].orEmpty() }) else values
                }
                characterRevision = request.revision
                characterChoices = hypotheses.first().groups.map { cache[it].orEmpty().filter(request.characterMode::accepts).distinct().take(100) }
                // The same compact bilateral evidence governs both label ranking
                // and the leading character-count interpretation.
                val preferredCount = if (bilateral != null) 1 else hypotheses.first().groups.size
                val choices = rankHypotheses(lists, preferredCount)
                candidateParts = buildMap {
                    hypotheses.forEachIndexed { index, hypothesis ->
                        val parts = hypothesis.groups.map { cache[it].orEmpty().filter(request.characterMode::accepts).distinct().take(100) }
                        lists[index].filter { it in choices }.forEach { value -> if (!containsKey(value)) put(value, parts) }
                    }
                }
                if (choices.isEmpty() && lastFailure != null) done(Result.failure(lastFailure!!))
                else done(Result.success(choices))
                return
            }
            single.recognize(HandwritingSegmentation.part(request, groups[index])) { result ->
                if (closed) return@recognize
                result.fold(onSuccess = { candidates ->
                    cache[groups[index]] = HandwritingShapeRanking.rank(HandwritingSegmentation.part(request, groups[index]), candidates)
                    next(index + 1)
                }, onFailure = { lastFailure = it; cache[groups[index]] = emptyList(); next(index + 1) })
            }
        }
        next(0)
    }
    companion object {
        /** A whole glyph should not lose its first positions to labels that only
         * explain one substantial side. Preserve all labels and ASCII slots. */
        fun protectWholeGlyph(whole: List<String>, parts: List<List<String>>): List<String> {
            val fragments = parts.flatMap { it.filter(HandwritingCharacterMode.CHINESE::accepts).take(2) }.toSet()
            val han = whole.filter(HandwritingCharacterMode.CHINESE::accepts)
            val complete = han.filter { it !in fragments }
            val ordered = complete.take(3) + han.filter { it in fragments } + complete.drop(3)
            var index = 0
            return whole.map { if (HandwritingCharacterMode.CHINESE.accepts(it)) ordered[index++] else it }
        }

        /** Geometry is only a hypothesis: reserve the second slot for another
         * character count, then fairly interleave ranks. Never bury whole glyphs
         * behind dozens of radical combinations. These are ranks, not confidence.
         */
        fun rankHypotheses(lists: List<List<String>>, preferredCount: Int): List<String> {
            val all = (0 until 100).flatMap { rank -> lists.mapNotNull { it.getOrNull(rank) } }.distinct()
            val preferred = all.filter { it.codePointCount(0, it.length) == preferredCount }
            val alternateCount = if (preferredCount != 1) 1 else
                all.map { it.codePointCount(0, it.length) }.filter { it > 1 }.minOrNull()
            val alternate = all.filter { it.codePointCount(0, it.length) == alternateCount }
            val main = (0 until 100).flatMap { rank -> listOfNotNull(preferred.getOrNull(rank), alternate.getOrNull(rank)) }
            val remaining = all.filter { it !in main }
            // Give whole-glyph and primary interpretations a small shared front
            // section. Additional 3/4-way splits must not crowd out either one.
            return (main.take(10) + (0 until 100).flatMap { rank ->
                listOfNotNull(remaining.getOrNull(rank), main.getOrNull(rank + 10))
            }).distinct().take(100)
        }
        fun split(request: InkRequest): List<InkRequest> {
            return HandwritingSegmentation.plans(request).first().groups.map { HandwritingSegmentation.part(request, it) }
        }
        /** Rank sum, not a calibrated confidence or a language model. */
        fun combine(parts: List<List<String>>, mode: HandwritingCharacterMode = HandwritingCharacterMode.CHINESE): List<String> {
            val perCharacter = if (parts.size == 1) 100 else 20
            val resultLimit = if (parts.size == 1) 100 else 50
            var beam = listOf("" to 0)
            for (part in parts) {
                val choices = part.filter(mode::accepts).distinct().take(perCharacter)
                if (choices.isEmpty()) return emptyList()
                beam = beam.flatMap { (prefix, score) -> choices.mapIndexed { rank, word -> prefix + word to score + rank } }
                    .sortedBy { it.second }.distinctBy { it.first }.take(resultLimit)
            }
            return beam.map { it.first }.filter { it.isNotBlank() }
        }
    }
}
