package top.izuna.foliamajor.lyrics

/**
 * Per-line animation timing, ported from folia-major's `src/utils/lyrics/renderHints.ts`.
 *
 * The desktop app derives every mode's enter/exit/reveal timing from these three classifications,
 * so porting them (rather than inventing native equivalents) is what keeps the mobile animation
 * feel matched to the desktop one. The thresholds are deliberately tiny fractions of a second:
 * they exist to catch lines that are only a few frames long, where a normal-length transition
 * would still be animating when the line is already over.
 */
enum class LineTimingClass { NORMAL, SHORT, MICRO }

enum class LineTransitionMode { NORMAL, FAST, NONE }

enum class WordRevealMode { NORMAL, FAST, INSTANT }

data class LineRenderHints(
    val rawDuration: Double,
    val timingClass: LineTimingClass,
    val renderEndTime: Double,
    val lineTransitionMode: LineTransitionMode,
    val wordRevealMode: WordRevealMode,
)

data class LineTransitionTiming(
    val enterDuration: Double,
    val exitDuration: Double,
    val linePassHold: Double,
)

private const val MICRO_LINE_DURATION_THRESHOLD = 0.10
private const val SHORT_LINE_DURATION_THRESHOLD = 0.18
private const val MICRO_LINE_RENDER_FLOOR = 0.067

private fun clamp(value: Double, min: Double, max: Double): Double =
    min.coerceAtLeast(max.coerceAtMost(value))

private fun timingClassOf(rawDuration: Double): LineTimingClass = when {
    rawDuration < MICRO_LINE_DURATION_THRESHOLD -> LineTimingClass.MICRO
    rawDuration < SHORT_LINE_DURATION_THRESHOLD -> LineTimingClass.SHORT
    else -> LineTimingClass.NORMAL
}

private fun transitionModeOf(timingClass: LineTimingClass): LineTransitionMode = when (timingClass) {
    LineTimingClass.MICRO -> LineTransitionMode.NONE
    LineTimingClass.SHORT -> LineTransitionMode.FAST
    LineTimingClass.NORMAL -> LineTransitionMode.NORMAL
}

private fun wordRevealModeOf(timingClass: LineTimingClass): WordRevealMode = when (timingClass) {
    LineTimingClass.MICRO -> WordRevealMode.INSTANT
    LineTimingClass.SHORT -> WordRevealMode.FAST
    LineTimingClass.NORMAL -> WordRevealMode.NORMAL
}

fun getLineTransitionTiming(
    rawDuration: Double,
    lineTransitionMode: LineTransitionMode,
    wordRevealMode: WordRevealMode,
): LineTransitionTiming = when (lineTransitionMode) {
    LineTransitionMode.NONE -> LineTransitionTiming(0.0, 0.0, 0.0)

    LineTransitionMode.FAST -> LineTransitionTiming(
        enterDuration = clamp(rawDuration * 0.45, 0.045, 0.06),
        exitDuration = clamp(rawDuration * 0.22, 0.03, 0.04),
        linePassHold = if (wordRevealMode == WordRevealMode.INSTANT) 0.0 else 0.03,
    )

    LineTransitionMode.NORMAL -> LineTransitionTiming(
        enterDuration = (0.42).coerceAtMost((0.22).coerceAtLeast(rawDuration.coerceAtLeast(0.12) * 0.34)),
        exitDuration = (0.32).coerceAtMost((0.18).coerceAtLeast(rawDuration.coerceAtLeast(0.12) * 0.18)),
        linePassHold = if (wordRevealMode == WordRevealMode.INSTANT) 0.0 else 0.06,
    )
}

private fun buildLineRenderEndTime(
    line: Line,
    lineTransitionMode: LineTransitionMode,
    wordRevealMode: WordRevealMode,
    rawDuration: Double,
): Double {
    // renderEndTime is the latest point a visualizer may keep this line on screen for active/pass/
    // exit polish after reveal has finished. It is not a guaranteed standalone timeline: a later
    // line may start earlier and force the current renderer to cut that extra window short.
    if (lineTransitionMode == LineTransitionMode.NONE) {
        return line.endTime.coerceAtLeast(line.startTime + MICRO_LINE_RENDER_FLOOR)
    }

    val transitionTiming = getLineTransitionTiming(rawDuration, lineTransitionMode, wordRevealMode)
    val lastWordEndTime = line.words.lastOrNull()?.endTime ?: line.endTime
    val linePassStart = lastWordEndTime.coerceAtLeast(line.startTime) + transitionTiming.linePassHold
    val exitStart = if (lineTransitionMode == LineTransitionMode.FAST) {
        maxOf(
            line.startTime + transitionTiming.enterDuration + 0.01,
            linePassStart,
            line.endTime - transitionTiming.exitDuration,
        )
    } else {
        linePassStart.coerceAtLeast(line.endTime - transitionTiming.exitDuration)
    }

    return line.endTime.coerceAtLeast(exitStart + transitionTiming.exitDuration)
}

fun buildLineRenderHints(line: Line): LineRenderHints {
    val rawDuration = (line.endTime - line.startTime).coerceAtLeast(0.0)
    val timingClass = timingClassOf(rawDuration)
    val lineTransitionMode = transitionModeOf(timingClass)
    val wordRevealMode = wordRevealModeOf(timingClass)

    return LineRenderHints(
        rawDuration = rawDuration,
        timingClass = timingClass,
        renderEndTime = buildLineRenderEndTime(line, lineTransitionMode, wordRevealMode, rawDuration),
        lineTransitionMode = lineTransitionMode,
        wordRevealMode = wordRevealMode,
    )
}

/** Attaches hints to every line that does not carry them yet. Applied once at parse time. */
fun annotateLyricLines(lines: List<Line>): List<Line> =
    lines.map { if (it.renderHints != null) it else it.copy(renderHints = buildLineRenderHints(it)) }

fun getLineRenderEndTime(line: Line?): Double =
    line?.let { it.renderHints?.renderEndTime ?: it.endTime } ?: Double.NEGATIVE_INFINITY
