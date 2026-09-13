package dev.shurufa.ime

/** Multiple spatial hypotheses, never time-based segmentation. Reuses one offline model. */
class LineHandwritingProvider(private val single: HandwritingProvider) : HandwritingProvider {
    private var closed = false
    var characterRevision: Long = -1
        private set
    var characterChoices: List<List<String>> = emptyList()
        private set
    var suggestedBoundaries: List<Float> = emptyList()
        private set
    override fun available(done: (Boolean) -> Unit) = single.available(done)
    override fun close() { closed = true; characterChoices = emptyList(); single.close() }
    override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
        characterRevision = -1; characterChoices = emptyList()
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
                val lists = hypotheses.map { hypothesis -> combine(hypothesis.groups.map { cache[it].orEmpty() }) }
                characterRevision = request.revision
                characterChoices = hypotheses.first().groups.map { cache[it].orEmpty().filter(::isHan).distinct().take(100) }
                val choices = rankHypotheses(lists, hypotheses.first().groups.size)
                if (choices.isEmpty() && lastFailure != null) done(Result.failure(lastFailure!!))
                else done(Result.success(choices))
                return
            }
            single.recognize(HandwritingSegmentation.part(request, groups[index])) { result ->
                if (closed) return@recognize
                result.fold(onSuccess = { candidates ->
                    cache[groups[index]] = candidates; next(index + 1)
                }, onFailure = { lastFailure = it; cache[groups[index]] = emptyList(); next(index + 1) })
            }
        }
        next(0)
    }
    companion object {
        private fun isHan(value: String) = value.codePointCount(0, value.length) == 1 &&
            Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN

        /** Prefer the geometric word count, without claiming calibrated model confidence.
         * Keep alternate counts accessible, but never interleave them into the first two slots.
         */
        fun rankHypotheses(lists: List<List<String>>, preferredCount: Int): List<String> {
            val all = (0 until 100).flatMap { rank -> lists.mapNotNull { it.getOrNull(rank) } }.distinct()
            val preferred = all.filter { it.codePointCount(0, it.length) == preferredCount }
            val alternate = all.filter { it.codePointCount(0, it.length) != preferredCount }
            // Reserve a bounded alternate section even when the preferred beam is full.
            return (preferred.take(80) + alternate.take(20) + preferred.drop(80) + alternate.drop(20)).distinct().take(100)
        }
        fun split(request: InkRequest): List<InkRequest> {
            return HandwritingSegmentation.plans(request).first().groups.map { HandwritingSegmentation.part(request, it) }
        }
        /** Rank sum, not a calibrated confidence or a language model. */
        fun combine(parts: List<List<String>>): List<String> {
            val perCharacter = if (parts.size == 1) 100 else 20
            val resultLimit = if (parts.size == 1) 100 else 50
            var beam = listOf("" to 0)
            for (part in parts) {
                val choices = part.filter { it.codePointCount(0, it.length) == 1 &&
                    Character.UnicodeScript.of(it.codePointAt(0)) == Character.UnicodeScript.HAN }.distinct().take(perCharacter)
                if (choices.isEmpty()) return emptyList()
                beam = beam.flatMap { (prefix, score) -> choices.mapIndexed { rank, word -> prefix + word to score + rank } }
                    .sortedBy { it.second }.distinctBy { it.first }.take(resultLimit)
            }
            return beam.map { it.first }.filter { it.isNotBlank() }
        }
    }
}
