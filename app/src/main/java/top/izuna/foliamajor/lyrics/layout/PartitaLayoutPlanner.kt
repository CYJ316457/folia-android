package top.izuna.foliamajor.lyrics.layout

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.theme.Theme

/**
 * Column layout for the `partita` visualizer, ported from folia-major's `partitaLayout.ts`.
 *
 * The mode reads as a hand-written score: a lyric line is cut into uneven chunks, each chunk becomes
 * a row, and consecutive rows are offset left and right so the block looks staggered rather than
 * aligned. The unevenness is the point — evenly sized chunks looked mechanical, which is why the
 * chunk length is drawn from a seeded random around the average.
 *
 * Like `tilt`, every random choice is seeded on the line's start time so the same line always
 * composes the same way.
 */

/** One chunk: a run of words rendered as a row. */
data class PartitaChunk(
    val words: List<PartitaWord>,
    /** Row index within the block. */
    val rowIndex: Int,
    /** Horizontal offset; negative shifts left. */
    val x: Float,
    val y: Float,
    val rotate: Float,
    val scale: Float,
    /** When the chunk starts being sung, used to decide its reveal state. */
    val startTime: Double,
    val endTime: Double,
)

/** A word inside a chunk, with its own timing for the per-word highlight. */
data class PartitaWord(
    val text: String,
    val startTime: Double,
    val endTime: Double,
)

data class PartitaLayout(
    val chunks: List<PartitaChunk>,
    /** Total non-space characters, used by the caller to size the block. */
    val totalGraphemes: Int,
    /** How far the block leans; larger values read as more energetic. */
    val perspective: Float,
)

data class PartitaOptions(
    val staggerMin: Float = 12f,
    val staggerMax: Float = 72f,
    /** When true, chunks break at semantic boundaries instead of by count. */
    val useSemanticLayout: Boolean = true,
)

object PartitaLayoutPlanner {

    /** Target height of one row in pixels, used to decide how many rows fit. */
    private const val BASE_ROW_HEIGHT = 100f

    /**
     * Deterministic pseudo-random in [0, 1), seeded on the line's start time.
     *
     * The seed advances with each call, so a single plan draws a different value per chunk while
     * remaining reproducible.
     */
    class SeededSequence(private var seed: Double) {
        fun next(): Float {
            val x = sin(seed) * 10000
            seed += 1
            return (x - floor(x)).toFloat()
        }
    }

    /**
     * How many rows the line should occupy.
     *
     * Keyed on grapheme count rather than word count because a five-character line and a five-word
     * Latin line need very different amounts of horizontal room.
     */
    fun targetColumnCount(totalGraphemes: Int, wordCount: Int): Int = when {
        wordCount <= 2 || totalGraphemes <= 5 -> 1
        totalGraphemes <= 10 -> 2
        totalGraphemes <= 16 -> 3
        totalGraphemes <= 24 -> 4
        else -> 5
    }

    /**
     * Splits the line into chunks and places them.
     *
     * [availableHeight] is the vertical room the block has; the row count is capped by how many
     * rows of [BASE_ROW_HEIGHT] fit, so a short line does not spread over the whole screen.
     */
    fun plan(
        line: Line,
        theme: Theme,
        availableHeight: Float,
        options: PartitaOptions,
    ): PartitaLayout {
        val words = line.words.filter { it.text.isNotBlank() }
        if (words.isEmpty()) {
            return PartitaLayout(emptyList(), 0, 1000f)
        }

        val totalGraphemes = max(
            line.fullText.replace(Regex("""\s+"""), "").length,
            words.size,
        )

        val intensity = theme.animationIntensity
        val isChaotic = intensity == Theme.AnimationIntensity.CHAOTIC
        val isCalm = intensity == Theme.AnimationIntensity.CALM

        // The row count is bounded by both the line's length and the available height.
        val desiredRows = targetColumnCount(totalGraphemes, words.size)
        val rowsThatFit = max(1, floor(availableHeight * 0.65f / BASE_ROW_HEIGHT).toInt())
        val rowCount = min(words.size, min(desiredRows, rowsThatFit))

        val random = SeededSequence(line.startTime)

        // Chunk lengths are uneven on purpose: an even split reads as a table, not as handwriting.
        val chunkSizes = mutableListOf<Int>()
        var remainingUnits = words.size
        var remainingChunks = rowCount

        for (index in 0 until rowCount) {
            val isLast = index == rowCount - 1
            val size = if (isLast) {
                remainingUnits
            } else {
                val average = remainingUnits.toFloat() / remainingChunks
                val maxSize = ceil(average * 1.5f).toInt()
                val drawn = average + (random.next() - 0.5f) * average
                max(1, min(maxSize, Math.round(drawn)))
            }

            // Never leave a later chunk with nothing to hold.
            val bounded = max(1, min(size, remainingUnits - (remainingChunks - 1)))
            chunkSizes.add(bounded)
            remainingUnits -= bounded
            remainingChunks -= 1
        }

        val chunks = mutableListOf<PartitaChunk>()
        var wordIndex = 0

        chunkSizes.forEachIndexed { rowIndex, size ->
            val chunkWords = words.subList(wordIndex, min(wordIndex + size, words.size))
            wordIndex += size
            if (chunkWords.isEmpty()) return@forEachIndexed

            val isStaggeredLeft = rowIndex % 2 == 0
            val staggerMagnitude = options.staggerMin +
                random.next() * max(options.staggerMax - options.staggerMin, 0f)
            val staggerX = if (isStaggeredLeft) -staggerMagnitude else staggerMagnitude

            // Vertical bias pushes the outer rows away from the centre, which is what makes the
            // block read as a diagonal rather than a list.
            val rowBias = rowIndex - (rowCount - 1) / 2f
            val staggerScale = if (isCalm) 1f else 0.8f + random.next() * 0.9f

            val graphemeCount = chunkWords.sumOf { it.text.replace(Regex("""\s+"""), "").length }

            chunks.add(
                PartitaChunk(
                    words = chunkWords.map { PartitaWord(it.text, it.startTime, it.endTime) },
                    rowIndex = rowIndex,
                    x = staggerX,
                    y = if (isCalm) 0f else rowBias * 2.5f,
                    rotate = if (isChaotic) (if (isStaggeredLeft) -3f else 3f) else 0f,
                    scale = (if (isChaotic) 1f + min(graphemeCount * 0.01f, 0.05f) else 1f) * staggerScale,
                    startTime = chunkWords.first().startTime,
                    endTime = chunkWords.last().endTime,
                )
            )
        }

        return PartitaLayout(
            chunks = chunks,
            totalGraphemes = totalGraphemes,
            perspective = if (isChaotic) 720f else 1000f,
        )
    }

    /**
     * Which chunk is currently being sung.
     *
     * Returns -1 before the line starts. A chunk stays current until the next one begins, so the
     * highlight does not blink off between words.
     */
    fun activeChunkIndex(layout: PartitaLayout, positionSec: Double): Int {
        var current = -1
        layout.chunks.forEachIndexed { index, chunk ->
            if (positionSec >= chunk.startTime) current = index
        }
        return current
    }
}
