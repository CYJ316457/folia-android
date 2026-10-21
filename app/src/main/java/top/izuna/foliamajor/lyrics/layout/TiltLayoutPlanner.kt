package top.izuna.foliamajor.lyrics.layout

import kotlin.math.ln
import kotlin.math.sin

/**
 * Layout planning for the `tilt` visualizer, ported from folia-major's `VisualizerTilt.tsx`.
 *
 * A line is broken into 1–4 fragments, one of which may be rendered in the "tilt" style (large
 * italic with alternating up/down character offsets). Both the fragment count and which fragment
 * tilts are **deterministic** from the line's start time, so a line looks the same every time it
 * plays and across devices — the desktop app relies on that, and randomness here would make the
 * visualizer feel unstable.
 */

/** One rendered fragment. */
data class TiltSegment(
    val text: String,
    val isTilt: Boolean,
    /** Index of the fragment's first character in the line's full text, for timing lookup. */
    val charOffset: Int,
    /**
     * True for a short final fragment that reads better slightly larger.
     *
     * A one- or two-character tail ("吗", "。") left over from splitting looks like an accident at
     * the same size as the rest of the line.
     */
    val isShortLastLine: Boolean = false,
)

data class TiltLayout(
    val segments: List<TiltSegment>,
    /** Shrinks the whole block when the widest fragment would overflow the screen. */
    val scaleMultiplier: Float,
)

/** Tunables mirrored from the desktop's `TiltTuning`. */
data class TiltOptions(
    /** How eagerly a line splits into more fragments. */
    val splitProbability: Float = 1f,
    /** Probability that any given fragment is chosen for the tilt style. */
    val tiltStyleProbability: Float = 0.5f,
)

object TiltLayoutPlanner {

    /** The character count at which a line is "long" for the purpose of the split decision. */
    private const val CHAR_REF_LENGTH = 20.0
    private const val LOG_OFFSET = 4.0

    /** Score thresholds between 1, 2, 3 and 4 fragments. */
    private val LINE_THRESHOLDS = floatArrayOf(0.45f, 1.05f, 1.7f)

    private const val RESPLIT_THRESHOLD = 1.6f
    private const val SCALE_FLOOR_NORMAL = 0.55f
    private const val SCALE_FLOOR_TILT = 0.5f

    /**
     * Deterministic pseudo-random in [0, 1).
     *
     * `sin` of a large multiple of the seed, which is the same trick the desktop app uses. It is not
     * a good RNG — it only needs to look scattered and be reproducible from a line's start time.
     */
    fun seededRandom(seed: Double, offset: Int): Float {
        val x = sin(seed * 1000 + offset) * 10000
        return (x - kotlin.math.floor(x)).toFloat()
    }

    /**
     * How many fragments a line should become.
     *
     * Logarithmic in the character count so a 40-character line does not become four times as
     * fragmented as a 10-character one; the jitter keeps consecutive lines from looking identical.
     */
    fun determineLineCount(charCount: Int, seed: Double, splitProbability: Float): Int {
        val normalized = ln(charCount + LOG_OFFSET) / ln(CHAR_REF_LENGTH + LOG_OFFSET)
        val jitter = seededRandom(seed, 1) * 0.6f + 0.7f
        val score = (normalized * jitter * splitProbability).toFloat()

        return when {
            score < LINE_THRESHOLDS[0] -> 1
            score < LINE_THRESHOLDS[1] -> 2
            score < LINE_THRESHOLDS[2] -> 3
            else -> 4
        }
    }

    /**
     * Builds the layout.
     *
     * [measureWidth] reports the rendered width of a fragment at a given pixel size; the planner
     * uses it to decide whether the block needs to shrink. Passing a stub in tests keeps this pure.
     */
    fun plan(
        fullText: String,
        lineSeed: Double,
        options: TiltOptions,
        availableWidth: Float,
        baseFontPx: Float,
        measureWidth: (text: String, pxSize: Float, italic: Boolean) -> Float,
    ): TiltLayout {
        val trimmedText = fullText.trim()

        // An instrumental marker ("......") is one unit: splitting it produces meaningless shards.
        if (Regex("""^[\s.…·。]+$""").matches(trimmedText)) {
            return TiltLayout(
                segments = listOf(TiltSegment(fullText, isTilt = false, charOffset = 0)),
                scaleMultiplier = 1f,
            )
        }

        val charCount = trimmedText.length
        val targetCount = determineLineCount(charCount, lineSeed, options.splitProbability)
        val fragments = SentenceSplitter.split(fullText, targetCount)

        // Which fragment gets the tilt treatment. Candidates are drawn from all fragments, then one
        // is picked — so a line can end up with no tilt fragment at all, which is intended.
        val candidates = fragments.indices.filter { index ->
            seededRandom(lineSeed, 100 + index) < options.tiltStyleProbability
        }
        val tiltIndex = if (candidates.isEmpty()) {
            -1
        } else {
            val pick = (seededRandom(lineSeed, 200) * candidates.size).toInt()
            candidates[pick.coerceIn(0, candidates.lastIndex)]
        }

        var offset = 0
        val segments = fragments.mapIndexed { index, fragment ->
            val leadingSpaces = fragment.length - fragment.trimStart().length
            val segment = TiltSegment(
                text = fragment.trim(),
                isTilt = index == tiltIndex,
                charOffset = offset + leadingSpaces,
            )
            offset += fragment.length
            segment
        }

        val marked = markShortLastLine(segments)

        // Shrink when the widest fragment overflows. Re-splitting first is preferred: a smaller font
        // is more disruptive to the composition than one more line break.
        val widestTilt = marked.filter { it.isTilt }
            .maxOfOrNull { measureWidth(it.text, baseFontPx, true) } ?: 0f
        val widestNormal = marked.filterNot { it.isTilt }
            .maxOfOrNull { measureWidth(it.text, baseFontPx, false) } ?: 0f

        val tiltOverflow = if (widestTilt > 0) widestTilt / availableWidth else 0f
        val normalOverflow = if (widestNormal > 0) widestNormal / availableWidth else 0f
        val maxOverflow = maxOf(tiltOverflow, normalOverflow)

        if (maxOverflow <= 1f) {
            return TiltLayout(marked, 1f)
        }

        // A modest overflow is handled by shrinking; a severe one is handled by splitting further.
        if (maxOverflow >= RESPLIT_THRESHOLD) {
            val resplitCount = minOf(4, maxOf(marked.size + 1, targetCount + 1))
            val resplit = SentenceSplitter.split(fullText, resplitCount)

            if (resplit.size > marked.size) {
                var resplitOffset = 0
                val resplitSegments = resplit.map { fragment ->
                    val leadingSpaces = fragment.length - fragment.trimStart().length
                    val segment = TiltSegment(
                        text = fragment.trim(),
                        isTilt = false,
                        charOffset = resplitOffset + leadingSpaces,
                    )
                    resplitOffset += fragment.length
                    segment
                }.toMutableList()

                if (seededRandom(lineSeed, 300) < options.tiltStyleProbability && resplitSegments.isNotEmpty()) {
                    val pick = (seededRandom(lineSeed, 301) * resplitSegments.size).toInt()
                    val safeIndex = pick.coerceIn(0, resplitSegments.lastIndex)
                    resplitSegments[safeIndex] = resplitSegments[safeIndex].copy(isTilt = true)
                }

                val scale = scaleFor(resplitSegments, availableWidth, baseFontPx, measureWidth)
                return TiltLayout(markShortLastLine(resplitSegments), scale)
            }
        }

        val scale = if (tiltOverflow >= normalOverflow && widestTilt > 0) {
            maxOf(SCALE_FLOOR_TILT, availableWidth / widestTilt)
        } else if (widestNormal > 0) {
            maxOf(SCALE_FLOOR_NORMAL, availableWidth / widestNormal)
        } else {
            1f
        }

        return TiltLayout(marked, scale)
    }

    private fun scaleFor(
        segments: List<TiltSegment>,
        availableWidth: Float,
        baseFontPx: Float,
        measureWidth: (String, Float, Boolean) -> Float,
    ): Float {
        val tilt = segments.firstOrNull { it.isTilt }
        if (tilt != null) {
            val width = measureWidth(tilt.text, baseFontPx, true)
            if (width > availableWidth) {
                return maxOf(SCALE_FLOOR_TILT, availableWidth / width)
            }
        }

        val normalWidth = segments.filterNot { it.isTilt }
            .maxOfOrNull { measureWidth(it.text, baseFontPx, false) } ?: 0f
        if (normalWidth > availableWidth) {
            return maxOf(SCALE_FLOOR_NORMAL, availableWidth / normalWidth)
        }
        return 1f
    }

    /**
     * Marks a very short final fragment so it renders slightly larger.
     *
     * The rule requires the tail to be at most half the length of the fragment before it, which is
     * what distinguishes a stray "吗" from a genuinely short closing line.
     */
    private fun markShortLastLine(segments: List<TiltSegment>): List<TiltSegment> {
        if (segments.size < 2) return segments

        val last = segments.last()
        if (last.isTilt || last.text.trim().length > 2) return segments

        val previous = segments[segments.size - 2]
        if (last.text.trim().length * 2 > previous.text.length) return segments

        return segments.dropLast(1) + last.copy(isShortLastLine = true)
    }
}
