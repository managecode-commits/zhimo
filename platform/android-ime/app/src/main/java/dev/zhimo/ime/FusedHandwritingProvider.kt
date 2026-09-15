// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

/** Local rank fusion, not calibrated confidence. Either engine may fail independently. */
class FusedHandwritingProvider(
    private val image: HandwritingProvider,
    private val trajectory: HandwritingProvider,
) : HandwritingProvider {
    private var closed = false
    private var imageReady = false
    private var trajectoryReady = false

    override fun available(done: (Boolean) -> Unit) {
        if (closed) return
        image.available { first ->
            if (closed) return@available
            imageReady = first
            trajectory.available { second ->
                if (!closed) { trajectoryReady = second; done(first || second) }
            }
        }
    }

    override fun recognize(request: InkRequest, done: (Result<List<String>>) -> Unit) {
        if (closed) return
        val han = request.characterMode == HandwritingCharacterMode.CHINESE ||
            request.characterMode == HandwritingCharacterMode.MIXED
        fun finish(first: Result<List<String>>) {
            if (closed) return
            if (!han || !trajectoryReady) { done(first); return }
            trajectory.recognize(request.copy(characterMode = HandwritingCharacterMode.CHINESE)) { second ->
                if (closed) return@recognize
                if (first.isFailure && second.isFailure) done(first)
                else done(Result.success(merge(first.getOrDefault(emptyList()), second.getOrDefault(emptyList()), request.characterMode)))
            }
        }
        if (imageReady) image.recognize(request, ::finish)
        else finish(Result.failure(IllegalStateException("Image handwriting model unavailable")))
    }

    override fun close() {
        if (closed) return
        closed = true
        image.close(); trajectory.close()
    }

    companion object {
        fun merge(image: List<String>, trajectory: List<String>, mode: HandwritingCharacterMode): List<String> {
            if (mode == HandwritingCharacterMode.DIGITS || mode == HandwritingCharacterMode.LETTERS)
                return image.filter(mode::accepts).distinct().take(100)
            val first = image.filter(HandwritingCharacterMode.CHINESE::accepts).distinct()
            val second = trajectory.filter(HandwritingCharacterMode.CHINESE::accepts).distinct()
            // Reciprocal ranks let agreement contribute without comparing incompatible model scores.
            val scores = linkedMapOf<String, Double>()
            listOf(first, second).forEach { values -> values.forEachIndexed { rank, value ->
                scores[value] = (scores[value] ?: 0.0) + 1.0 / (8 + rank)
            } }
            val han = scores.keys.sortedByDescending { scores.getValue(it) }.take(100)
            return if (mode == HandwritingCharacterMode.CHINESE) han else
                ImageHandwritingProvider.balanceScripts(han, image.filter { mode.accepts(it) && !HandwritingCharacterMode.CHINESE.accepts(it) }.distinct())
        }
    }
}
