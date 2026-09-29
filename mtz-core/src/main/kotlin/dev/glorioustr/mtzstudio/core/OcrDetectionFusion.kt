package dev.glorioustr.mtzstudio.core

/**
 * Resolves duplicate text detections emitted by different OCR engines and image variants.
 *
 * OCR engines frequently disagree by a character while locating the same glyphs. Keeping both
 * detections would redraw the same label twice, so each overlapping group is reduced to its
 * strongest candidate while agreement increases that candidate's confidence.
 */
object OcrDetectionFusion {
    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        init {
            require(right > left) { "right must be greater than left" }
            require(bottom > top) { "bottom must be greater than top" }
        }

        private val area: Long
            get() = (right - left).toLong() * (bottom - top)

        fun overlapsSameLabel(other: Bounds): Boolean {
            val overlapWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
            val overlapHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
            val intersection = overlapWidth.toLong() * overlapHeight
            if (intersection == 0L) return false
            val union = area + other.area - intersection
            return intersection.toDouble() / union >= MIN_IOU
        }
    }

    data class Detection(
        val text: String,
        val bounds: Bounds,
        val confidence: Float,
    )

    fun fuse(vararg detectorResults: List<Detection>): List<Detection> =
        fuse(detectorResults.asList())

    fun fuse(detectorResults: List<List<Detection>>): List<Detection> {
        val candidates = detectorResults.flatten()
            .filter { it.text.isNotBlank() && it.confidence.isFinite() }
            .sortedWith(
                compareByDescending<Detection> { it.confidence }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left },
            )
        val groups = mutableListOf<MutableList<Detection>>()
        candidates.forEach { candidate ->
            groups.firstOrNull { group ->
                group.any { it.bounds.overlapsSameLabel(candidate.bounds) }
            }?.add(candidate) ?: groups.add(mutableListOf(candidate))
        }
        return groups.map(::selectBest)
            .sortedWith(compareBy<Detection> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private fun selectBest(group: List<Detection>): Detection {
        val normalizedCounts = group.groupingBy { normalize(it.text) }.eachCount()
        val best = group.maxWithOrNull(
            compareBy<Detection> {
                it.confidence + (normalizedCounts.getValue(normalize(it.text)) - 1) * AGREEMENT_BONUS
            }.thenBy { it.text.count(Char::isLetterOrDigit) },
        ) ?: error("OCR detection group cannot be empty")
        val agreement = normalizedCounts.getValue(normalize(best.text)) - 1
        return best.copy(confidence = (best.confidence + agreement * AGREEMENT_BONUS).coerceAtMost(1f))
    }

    private fun normalize(text: String): String =
        text.filterNot(Char::isWhitespace)

    private const val MIN_IOU = .50
    private const val AGREEMENT_BONUS = .05f
}
