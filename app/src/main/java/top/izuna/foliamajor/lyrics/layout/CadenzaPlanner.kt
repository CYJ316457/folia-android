package top.izuna.foliamajor.lyrics.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word
import top.izuna.foliamajor.theme.Theme

/**
 * Placement for the `cadenza` visualizer, ported from folia-major's `cadenzaPlacements.ts`.
 *
 * This is the most involved layout in the light modes: every word is a free-floating object placed
 * on a 2D field, and no two may overlap. Each word starts from a preferred position (derived from
 * its order in the line) and, when that position is taken, searches outward in expanding rings for
 * the nearest free spot.
 *
 * Two details make it work rather than merely run:
 *
 *  - **Collision uses an inflated box.** A word's collision rect is wider and taller than its glyphs
 *    (1.26–1.48× wide), because words are rotated and animated and their ink moves outside the
 *    text bounds during the animation.
 *  - **One "hero" word is enlarged.** The most semantically weighty word in the line gets a larger
 *    emphasis scale, which is what gives the composition a focal point instead of uniform noise.
 */

/** One placed word. */
data class CadenzaPlacement(
    val text: String,
    val wordIndex: Int,
    /** Centre position in the layout's coordinate space (0,0 at the line's centre). */
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotationDeg: Float,
    /** Drift applied once the word has been sung, in the direction away from the centre. */
    val passedDriftX: Float,
    val passedDriftY: Float,
    /** Word start time, for the waiting/active/passed state. */
    val startTime: Double,
    val endTime: Double,
    val isHero: Boolean,
)

data class CadenzaLayout(
    val placements: List<CadenzaPlacement>,
    /** Index of the emphasised word, or -1 when the line has no hero. */
    val heroWordIndex: Int,
)

data class CadenzaOptions(
    /** Horizontal room the composition may use. */
    val maxWidth: Float = 900f,
    /** Vertical room the composition may use. */
    val maxHeight: Float = 700f,
    val fontPx: Float = 34f,
    val lineHeight: Float = 46f,
)

object CadenzaPlanner {

    /** How far from the preferred point the search may travel. */
    private const val MAX_SEARCH_RADIUS_FRACTION = 0.62f

    /** Granularity of the expanding-ring search. */
    private const val SEARCH_STEP = 18f

    private class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Deterministic pseudo-random in [0, 1), seeded on the line's start time.
     *
     * Same technique as the other modes: `sin` of a large multiple of the seed. It only needs to
     * look scattered and be reproducible.
     */
    private class SeededSequence(private var seed: Double) {
        fun next(offset: Int = 0): Float {
            val x = sin(seed * 1000 + offset) * 10000
            seed += 1
            return (x - floor(x)).toFloat()
        }
    }

    fun plan(
        line: Line,
        theme: Theme,
        options: CadenzaOptions,
    ): CadenzaLayout {
        val words = line.words.filter { it.text.isNotBlank() }
        if (words.isEmpty()) return CadenzaLayout(emptyList(), -1)

        val random = SeededSequence(line.startTime)
        val intensity = theme.animationIntensity
        val isCalm = intensity == Theme.AnimationIntensity.CALM
        val isChaotic = intensity == Theme.AnimationIntensity.CHAOTIC

        val heroIndex = pickHero(words)
        val occupied = mutableListOf<Rect>()

        val maxRadius = min(options.maxWidth, options.maxHeight) * MAX_SEARCH_RADIUS_FRACTION
        val placements = mutableListOf<CadenzaPlacement>()

        words.forEachIndexed { index, word ->
            val isHero = index == heroIndex
            val emphasis = if (isHero) 1.4f else 1f

            val glyphCount = max(word.text.replace(Regex("""\s+"""), "").length, 1)
            // A rough width: CJK is one em per character, Latin roughly half.
            val isCjk = word.text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF }
            val width = options.fontPx * (if (isCjk) glyphCount.toFloat() else glyphCount * 0.58f) * emphasis

            // The collision box is larger than the ink: the word rotates and drifts, and its
            // rendered extent moves outside the measured bounds.
            val collisionWidth = width * (if (isHero) 1.48f else 1.26f)
            val collisionHeight = options.lineHeight * (if (isHero) 1.36f else 1.24f)
            val padding = if (isCalm) 4f else 6f

            // The preferred point comes from a grid cell, not a linear fraction.
            //
            // A linear spread puts consecutive words close together (they differ by one step along
            // a line), so the ring search has to resolve collisions constantly. A grid gives every
            // word a cell of its own up front, and the seeded jitter inside the cell is what keeps
            // the result from looking like a table.
            val cell = gridCell(index, words.size)
            val jitterX = (random.next(11) - 0.5f) * options.maxWidth * 0.06f
            val jitterY = (random.next(12) - 0.5f) * options.maxHeight * 0.05f
            val preferredX = cell.first * options.maxWidth * 0.40f + jitterX
            val preferredY = cell.second * options.maxHeight * 0.40f + jitterY

            val placed = findSpot(
                preferredX = preferredX,
                preferredY = preferredY,
                collisionWidth = collisionWidth,
                collisionHeight = collisionHeight,
                padding = padding,
                maxWidth = options.maxWidth,
                maxHeight = options.maxHeight,
                maxRadius = maxRadius,
                occupied = occupied,
                centerOnHero = heroIndex >= 0 && !isHero,
                heroX = placements.getOrNull(heroIndex)?.x ?: 0f,
                heroY = placements.getOrNull(heroIndex)?.y ?: 0f,
            )

            occupied.add(
                Rect(
                    left = placed.first - collisionWidth / 2f - padding,
                    top = placed.second - collisionHeight / 2f - padding,
                    right = placed.first + collisionWidth / 2f + padding,
                    bottom = placed.second + collisionHeight / 2f + padding,
                )
            )

            // Rotation leans away from the centre, which keeps the field from reading as a grid.
            val outwardAngle = atan2(placed.second, placed.first) * 180f / PI.toFloat()
            val rotation = when {
                isCalm -> 0f
                isChaotic -> outwardAngle * 0.12f + (random.next(3) - 0.5f) * 14f
                else -> outwardAngle * 0.06f + (random.next(3) - 0.5f) * 6f
            }

            // Passed words drift outward, away from the composition's centre.
            val outwardLength = max(hypot(placed.first, placed.second), 1f)
            val driftAmount = when {
                isCalm -> 4f
                isHero -> 6f + random.next(6) * 4f
                isChaotic -> 8f + random.next(6) * 9f
                else -> 5f + random.next(6) * 6f
            }

            placements.add(
                CadenzaPlacement(
                    text = word.text,
                    wordIndex = index,
                    x = placed.first,
                    y = placed.second,
                    scale = when {
                        isHero -> 1.46f
                        isChaotic -> 1f + random.next(4) * 0.12f
                        else -> 1f
                    },
                    rotationDeg = rotation,
                    passedDriftX = placed.first / outwardLength * driftAmount,
                    passedDriftY = placed.second / outwardLength * driftAmount * 0.72f,
                    startTime = word.startTime,
                    endTime = word.endTime,
                    isHero = isHero,
                )
            )
        }

        return CadenzaLayout(placements, heroIndex)
    }

    /**
     * Assigns [index] a cell in a roughly square grid, centred on the origin.
     *
     * Cells are visited in a spiral from the centre outward so the visually important words (which
     * come first) land near the middle and the composition stays balanced.
     */
    private fun gridCell(index: Int, total: Int): Pair<Float, Float> {
        if (total <= 1) return 0f to 0f

        val columns = kotlin.math.ceil(kotlin.math.sqrt(total.toFloat())).toInt().coerceAtLeast(1)
        val rows = kotlin.math.ceil(total.toFloat() / columns).toInt().coerceAtLeast(1)

        val column = index % columns
        val row = index / columns

        val x = (column - (columns - 1) / 2f) / max((columns - 1) / 2f, 1f)
        val y = (row - (rows - 1) / 2f) / max((rows - 1) / 2f, 1f)
        return x to y
    }

    /**
     * Chooses the word to emphasise.
     *
     * Longer Latin words and CJK words score higher, and words near the middle of the line get a
     * bonus — a hero at the very end would pull the composition's weight to one side.
     */
    private fun pickHero(words: List<Word>): Int {
        if (words.isEmpty()) return -1

        return words.indices.maxByOrNull { index ->
            val text = words[index].text
            val isCjk = text.any { it.code in 0x4E00..0x9FFF }
            val semanticWeight = if (isCjk) 0.18f else min(text.length * 0.08f, 0.36f)
            val centre = (words.size - 1) / 2f
            val centreBias = 1f - abs(index - centre) / max(words.size, 1)
            semanticWeight + centreBias * 0.18f
        } ?: -1
    }

    /**
     * Finds the nearest free position, searching outward in rings.
     *
     * Returns the preferred point itself when it is free, otherwise the first non-colliding point on
     * an expanding ellipse. When nothing is free within [maxRadius], the least-colliding candidate is
     * used — an overlapping word is still better than a dropped one.
     */
    private fun findSpot(
        preferredX: Float,
        preferredY: Float,
        collisionWidth: Float,
        collisionHeight: Float,
        padding: Float,
        maxWidth: Float,
        maxHeight: Float,
        maxRadius: Float,
        occupied: List<Rect>,
        centerOnHero: Boolean,
        heroX: Float,
        heroY: Float,
    ): Pair<Float, Float> {
        val horizontalMin = -maxWidth / 2f - 72f
        val horizontalMax = maxWidth / 2f + 72f
        val verticalMin = -maxHeight / 2f
        val verticalMax = maxHeight / 2f

        var bestX = preferredX
        var bestY = preferredY
        var bestScore = Float.MAX_VALUE
        var found = false

        // Other words lean toward the hero, which is what makes the composition converge instead of
        // scattering. The lean is capped so a word is never dragged off its own position entirely.
        val startX = if (centerOnHero && heroX != 0f) {
            preferredX + (heroX - preferredX) * 0.22f
        } else {
            preferredX
        }
        val startY = if (centerOnHero && heroY != 0f) {
            preferredY + (heroY - preferredY) * 0.22f
        } else {
            preferredY
        }

        var radius = 0f
        while (radius <= maxRadius && !found) {
            val samples = if (radius == 0f) {
                1
            } else {
                max(12, (2 * PI * radius / max(SEARCH_STEP * 1.1f, 10f)).toInt())
            }

            for (sample in 0 until samples) {
                val angle = if (samples == 1) 0f else (sample.toFloat() / samples) * 2f * PI.toFloat()
                val dx = if (radius == 0f) 0f else cos(angle) * radius
                val dy = if (radius == 0f) 0f else sin(angle) * radius * 0.92f

                val x = startX + dx
                val y = startY + dy

                val left = x - collisionWidth / 2f - padding
                val right = x + collisionWidth / 2f + padding
                val top = y - collisionHeight / 2f - padding
                val bottom = y + collisionHeight / 2f + padding

                if (left < horizontalMin || right > horizontalMax ||
                    top < verticalMin || bottom > verticalMax
                ) {
                    continue
                }

                val overlap = occupied.sumOf { rect ->
                    overlapArea(rect, Rect(left, top, right, bottom)).toDouble()
                }.toFloat()
                val travel = hypot(dx, dy)
                val score = overlap * 2.2f + travel

                if (score < bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }

                if (overlap <= 0f) {
                    found = true
                    break
                }
            }

            radius += SEARCH_STEP
        }

        return bestX to bestY
    }

    private fun overlapArea(a: Rect, b: Rect): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (width > 0 && height > 0) width * height else 0f
    }

    /**
     * Which word is being sung at [positionSec], or -1 before the line starts.
     *
     * Uses the same "latest started word wins" rule as the other modes, so the highlight does not
     * blink between words.
     */
    fun activeWordIndex(layout: CadenzaLayout, positionSec: Double): Int {
        return layout.placements
            .filter { positionSec >= it.startTime }
            .maxByOrNull { it.startTime }
            ?.wordIndex
            ?: -1
    }
}
