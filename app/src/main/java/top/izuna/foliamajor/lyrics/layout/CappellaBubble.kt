package top.izuna.foliamajor.lyrics.layout

/**
 * Bubble geometry for `cappella`.
 *
 * Ported from `src/components/visualizer/cappella/cappellaBubbleMetrics.ts` and the sizing helpers in
 * `cappellaMessageLayout.ts`. The desktop version measures every character prefix with
 * `@chenglou/pretext` and caches the table; here the measuring is injected so the arithmetic stays
 * testable and the renderer can feed it real text metrics.
 *
 * A bubble's size at time *t* depends on how many characters are visible at *t*, which is why this
 * produces a whole table rather than a single size: playback then does an O(1) lookup per frame
 * instead of re-measuring.
 */
data class BubbleSize(val width: Float, val height: Float)

/** The per-line bubble table. `sizes[n]` is the bubble with the first `n` characters visible. */
data class BubbleMetrics(
    val characters: List<String>,
    val sizes: List<BubbleSize>,
    val revealTimes: List<Double>,
    /** Reveal times pulled earlier, for the width animation. */
    val bubbleTargetTimes: List<Double>,
    val timestampReadyTime: Double,
)

/**
 * Measures text for bubble sizing.
 *
 * Returns the width of the widest wrapped line and the number of wrapped lines, given a maximum text
 * width and a fixed line height. The renderer implements this with Compose's `TextMeasurer`; tests
 * use a fixed-advance fake.
 */
fun interface BubbleTextMeasurer {
    fun measure(text: String, fontSize: Float, lineHeightPx: Float, maxTextWidth: Float): Pair<Float, Int>
}

/**
 * A measurer that assumes every grapheme is square-ish, used when no text engine is available.
 *
 * The renderer always supplies a real one; this exists so the geometry can be exercised without one.
 */
object EstimatedBubbleMeasurer : BubbleTextMeasurer {
    override fun measure(text: String, fontSize: Float, lineHeightPx: Float, maxTextWidth: Float): Pair<Float, Int> {
        val graphemes = FumeMath.splitGraphemes(text)
        if (graphemes.isEmpty()) return fontSize to 1

        val perGrapheme = graphemes.sumOf { grapheme ->
            when {
                FumeMath.isCjk(grapheme) -> fontSize.toDouble()
                grapheme.isBlank() -> fontSize * 0.28
                else -> fontSize * 0.55
            }
        }.toFloat()

        if (perGrapheme <= maxTextWidth) return perGrapheme to 1
        val lines = kotlin.math.ceil(perGrapheme / maxTextWidth).toInt()
        return maxTextWidth to lines
    }
}

object CappellaBubble {

    /**
     * Builds the size table for one line.
     *
     * Measuring every prefix is O(n²) in the character count, which sounds worse than it is: lyric
     * lines are tens of characters, and the table is built once per line and cached by the caller.
     * The alternative — measuring only at reveal time — would do a text layout per frame.
     */
    fun metrics(
        line: top.izuna.foliamajor.lyrics.Line,
        fontSize: Float,
        lineHeightPx: Float,
        maxTextWidth: Float,
        paddingX: Float,
        paddingY: Float,
        measurer: BubbleTextMeasurer,
    ): BubbleMetrics {
        val plan = CappellaReveal.plan(line)
        val sizes = ArrayList<BubbleSize>(plan.characters.size + 1)

        // One entry per prefix, including the empty one: a bubble that has not started printing still
        // occupies its padding, and animating from a zero-size box looks like a flicker.
        for (visibleCount in 0..plan.characters.size) {
            val prefix = plan.characters.take(visibleCount).joinToString("")
            sizes.add(sizeFor(prefix, fontSize, lineHeightPx, maxTextWidth, paddingX, paddingY, measurer))
        }

        return BubbleMetrics(
            characters = plan.characters,
            sizes = sizes,
            revealTimes = plan.revealTimes,
            bubbleTargetTimes = plan.revealTimes.map { time ->
                maxOf(line.startTime, time - CappellaReveal.WIDTH_LOOKAHEAD_SECONDS)
            },
            timestampReadyTime = plan.timestampReadyTime,
        )
    }

    /** Size for a fixed piece of text (a title, or the completed bubble). */
    fun sizeFor(
        text: String,
        fontSize: Float,
        lineHeightPx: Float,
        maxTextWidth: Float,
        paddingX: Float,
        paddingY: Float,
        measurer: BubbleTextMeasurer,
    ): BubbleSize {
        val border = 1f
        val safeText = text.ifBlank { " " }
        val (textWidth, lineCount) = measurer.measure(safeText, fontSize, lineHeightPx, maxTextWidth)

        // A bubble is never narrower than one em: a single narrow character would otherwise produce a
        // pill that reads as a reaction rather than a message.
        val contentWidth = minOf(textWidth, maxTextWidth).coerceAtLeast(fontSize)
        val contentHeight = lineCount.coerceAtLeast(1) * lineHeightPx

        return BubbleSize(
            width = kotlin.math.ceil(contentWidth + paddingX * 2 + border * 2),
            height = kotlin.math.ceil(contentHeight + paddingY * 2 + border * 2),
        )
    }

    /** The bubble size at [currentTime], using the width timeline so the growth leads the text. */
    fun sizeAtTime(metrics: BubbleMetrics, currentTime: Double): BubbleSize {
        val count = CappellaReveal.visibleCount(metrics.bubbleTargetTimes, currentTime)
        return metrics.sizes[count.coerceIn(0, metrics.sizes.lastIndex)]
    }

    /** How many characters have started fading in at [currentTime]. */
    fun visibleCharactersAt(metrics: BubbleMetrics, currentTime: Double): Int =
        CappellaReveal.visibleCount(metrics.revealTimes, currentTime)

    /**
     * Estimated row height for the visible-window calculation.
     *
     * The chat has to drop old messages to fit its viewport, and it needs an answer before laying
     * anything out. Measuring the real bubble here would be the accurate approach, but it happens for
     * every message on every frame; the estimate only needs to be close enough that the floor of the
     * list lands off-screen rather than mid-bubble.
     */
    fun estimatedRowHeight(
        isActive: Boolean,
        motion: CappellaMotion,
        fontSize: Float,
        lineHeightPx: Float,
        bubbleHeight: Float,
    ): Float {
        val paddingY = if (isActive) motion.activePaddingY else motion.inactivePaddingY
        val minHeight = if (isActive) motion.activeMinHeight else motion.inactiveMinHeight
        val rendered = maxOf(bubbleHeight, minHeight, lineHeightPx + paddingY * 2)
        val scaleOverflow = if (isActive && motion.activeScale > 1f) {
            kotlin.math.ceil(rendered * (motion.activeScale - 1f))
        } else {
            0f
        }
        // The 12dp gap between rows is part of the row's footprint for budgeting purposes.
        return rendered + scaleOverflow + 12f
    }
}
