package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.getLineRenderEndTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The `fume` page composition: lyrics typeset as an article.
 *
 * Ported from `src/components/visualizer/fume/fumeArticleLayout.ts`. The desktop mode lays every
 * lyric line into a printed block on a wide paper sheet, sizes some blocks as "hero" headlines, and
 * packs the blocks into columns; a camera then flies between them as the song plays.
 *
 * Two adaptations:
 *
 *  - Measurement is injected ([FumeGlyphMeasurer]) rather than done through `@chenglou/pretext`. The
 *    planner can therefore run its column/density search without a text engine, and tests can supply
 *    a deterministic one.
 *  - The search keeps the same scoring and bracket, because that is what makes the resulting page
 *    look composed rather than like a hardcoded grid. Only the number of inner iterations is exposed
 *    so a slow device can trade fidelity for time.
 */
data class FumeBlock(
    val id: String,
    val sourceLineIndex: Int,
    val line: Line,
    val isHero: Boolean,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val innerWidth: Float,
    val fontPx: Float,
    val lineHeight: Float,
    val graphemes: List<String>,
    /** Advances at [fontPx], one per grapheme. */
    val advances: FloatArray,
    val renderLines: List<FumeRenderLine>,
    /** Word ranges as grapheme offsets, for printing and focus tracking. */
    val wordRanges: List<FumeWordRange>,
)

/** One wrapped visual line inside a block. Offsets are grapheme indices into [FumeBlock.graphemes]. */
data class FumeRenderLine(
    val text: String,
    val start: Int,
    val end: Int,
    val width: Float,
    val left: Float,
    val top: Float,
)

/** A word's slice of a block, in grapheme offsets and in time. */
data class FumeWordRange(
    val wordIndex: Int,
    val start: Int,
    val end: Int,
    val startTime: Double,
    val endTime: Double,
    /** Per-grapheme start times, or null when the word has no syllable timing. */
    val graphemeStarts: List<Double>?,
)

data class FumeArticleLayout(
    val width: Float,
    val height: Float,
    val viewportHeight: Float,
    val columns: Int,
    val gap: Float,
    val paperLeft: Float,
    val paperTop: Float,
    val paperRight: Float,
    val paperBottom: Float,
    val blocks: List<FumeBlock>,
    val chronologicalBlocks: List<FumeBlock>,
    val firstRenderableStartTime: Double,
    val lastChronologicalRenderEndTime: Double,
)

/** Just the size, for the measure pass of the search. */
data class FumeLayoutMetrics(
    val width: Float,
    val height: Float,
    val columns: Int,
    val gap: Float,
    val paperLeft: Float,
    val paperTop: Float,
    val paperRight: Float,
    val paperBottom: Float,
)

object FumeArticlePlanner {

    /** Inner iterations of the density bracket per column count. Matches the desktop loop. */
    const val DENSITY_ITERATIONS = 8

    /**
     * Room around the paper for the camera to move in.
     *
     * Much smaller than the desktop's 0.86×: that margin exists so the camera can drift past the
     * page edge on a large display, and on a phone it would triple the article width and force the
     * camera to zoom out. A modest margin still lets the camera breathe without leaving the paper.
     */
    private fun horizontalMargin(viewportWidth: Float) = max(viewportWidth * 0.18f, 80f)
    private fun verticalMargin(viewportHeight: Float) = max(viewportHeight * 0.35f, 120f)

    /**
     * The page is wider than the viewport, but only modestly.
     *
     * The desktop version uses at least 920px, which on a phone is 2.5 screens wide and forces the
     * camera either to crop every block or to zoom out until the text is unreadable. Capping the
     * multiplier keeps the multi-column feel while letting a block fit across the viewport, so the
     * camera can stay at a readable zoom.
     */
    private fun paperWidthFor(viewportWidth: Float): Float =
        FumeMath.clamp(
            viewportWidth * 1.9f,
            viewportWidth + 300f,
            1400f,
        )

    /** At most two columns: a phone-width page has no room to make four readable. */
    private fun maxColumnsFor(paperWidth: Float): Int = when {
        paperWidth >= 680f -> 2
        else -> 1
    }

    private fun gapFor(paperWidth: Float, columns: Int): Float {
        val ratio = when {
            columns >= 4 -> 0.0065f
            columns == 3 -> 0.0085f
            else -> 0.0115f
        }
        return FumeMath.clamp((paperWidth * ratio).roundToInt().toFloat(), 6f, 14f)
    }

    /**
     * Whether a line becomes a headline.
     *
     * Choruses that fit are always heroes; otherwise a short line near the middle of the song has a
     * periodic chance. The seed is the line text so the choice is stable across re-layouts.
     */
    internal fun chooseNaturalBlockVariant(
        line: Line,
        index: Int,
        total: Int,
        graphemeCount: Int,
    ): Boolean {
        if (graphemeCount == 0) return false
        if (line.isChorus && graphemeCount <= 22) return true

        val shortEnough = graphemeCount in 4..28
        val centered = abs(index - total / 2f) / max(total, 1).toFloat()
        val random = FumeMath.seeded("${line.fullText}:$index")
        return shortEnough && centered < 0.72f && ((index + 1) % 6 == 0 || random > 0.965f)
    }

    /**
     * Picks a line to force into a hero block when no line qualifies naturally.
     *
     * Without this a wall of long lines produces a page with no focal point, which reads as a bug
     * rather than a style. The score balances "near the middle", "a comfortable length" and "is a
     * chorus", with a little seeded jitter so two songs of the same shape do not pick the same slot.
     */
    internal fun chooseFallbackHeroIndex(
        entries: List<Pair<Line, Int>>,
        graphemeCounts: List<Int>,
    ): Int {
        if (entries.isEmpty()) return -1

        val hasNaturalHero = entries.indices.any { blockIndex ->
            chooseNaturalBlockVariant(
                entries[blockIndex].first,
                blockIndex,
                entries.size,
                graphemeCounts[blockIndex],
            )
        }
        if (hasNaturalHero) return -1

        var bestIndex = -1
        var bestScore = Float.NEGATIVE_INFINITY

        entries.indices.forEach { blockIndex ->
            val line = entries[blockIndex].first
            val graphemeCount = graphemeCounts[blockIndex]
            if (graphemeCount == 0) return@forEach

            val comfortable = graphemeCount in 4..28
            val acceptable = graphemeCount <= 36
            if (!comfortable && !acceptable) return@forEach

            val centered = abs(blockIndex - entries.size / 2f) / max(entries.size, 1).toFloat()
            val centerScore = 1f - centered
            val lengthScore = when {
                graphemeCount in 6..22 -> 1f
                graphemeCount <= 28 -> 0.72f
                else -> 0.36f
            }
            val chorusScore = if (line.isChorus) 0.28f else 0f
            val jitter = FumeMath.seeded("${line.fullText}:$blockIndex:fallback-hero") * 0.04f
            val score = centerScore * 0.62f + lengthScore * 0.34f + chorusScore + jitter

            if (score > bestScore) {
                bestScore = score
                bestIndex = blockIndex
            }
        }

        if (bestIndex >= 0) return bestIndex

        // Every line was either empty or longer than 36 graphemes: take the shortest non-empty one.
        var shortestIndex = -1
        var shortestCount = Int.MAX_VALUE
        entries.indices.forEach { blockIndex ->
            val count = graphemeCounts[blockIndex]
            if (count in 1 until shortestCount) {
                shortestCount = count
                shortestIndex = blockIndex
            }
        }
        return shortestIndex
    }

    /**
     * Font size for a block.
     *
     * Derived from the block width and the square root of its "density" (graphemes plus a per-word
     * weight), which is what makes a long line set smaller and a short one set larger — the same
     * relationship a typesetter would use. The clamps keep a two-word line from becoming a banner and
     * a 60-character line from becoming unreadable.
     */
    internal fun chooseFontPx(
        graphemeCount: Int,
        wordCount: Int,
        isHero: Boolean,
        blockWidth: Float,
        lyricsFontScale: Float,
        densityScale: Float,
        heroScale: Float,
    ): Float {
        val count = max(graphemeCount, 1)
        val density = count + wordCount * 1.4f
        val base = if (isHero) {
            blockWidth / max(sqrt(density) * 1.5f, 4.5f)
        } else {
            blockWidth / max(sqrt(density) * 2.25f, 7f)
        }

        val scaled = base * lyricsFontScale * densityScale * (if (isHero) heroScale else 1f)
        return if (isHero) FumeMath.clamp(scaled, 24f, 54f) else FumeMath.clamp(scaled, 14f, 28f)
    }

    /**
     * Wraps [graphemes] into lines no wider than [innerWidth].
     *
     * Prefers breaking at whitespace, and falls back to breaking anywhere once a run of non-space
     * text exceeds the width — CJK has no spaces, so a break-anywhere rule is the only option and a
     * break-at-space-only rule would overflow on every Chinese line.
     */
    internal fun wrap(
        graphemes: List<String>,
        advances: FloatArray,
        innerWidth: Float,
    ): List<FumeRenderLine> {
        if (graphemes.isEmpty()) return emptyList()

        val lines = mutableListOf<FumeRenderLine>()
        var lineStart = 0
        var cursor = 0
        var width = 0f

        fun advanceAt(index: Int): Float =
            if (index in advances.indices) advances[index] else 0f

        fun flush(endExclusive: Int) {
            if (endExclusive <= lineStart) return
            val text = buildString { for (i in lineStart until endExclusive) append(graphemes[i]) }
            lines.add(
                FumeRenderLine(
                    text = text,
                    start = lineStart,
                    end = endExclusive,
                    width = width,
                    left = 0f,
                    top = lines.size.toFloat(),
                )
            )
        }

        while (cursor < graphemes.size) {
            val advance = advanceAt(cursor)
            if (width + advance > innerWidth && cursor > lineStart) {
                // Try to break at the last space in this line; the space stays with the previous line
                // so the visible text keeps its word gap.
                var breakAt = -1
                for (index in cursor - 1 downTo lineStart) {
                    if (graphemes[index].isBlank()) {
                        breakAt = index + 1
                        break
                    }
                }
                val end = if (breakAt > lineStart) breakAt else cursor
                flush(end)
                lineStart = end
                width = 0f
                for (index in lineStart until cursor) width += advanceAt(index)
                // The current grapheme is re-measured below against the fresh width.
                if (cursor < lineStart) cursor = lineStart
            }

            width += advance
            cursor += 1
        }
        flush(graphemes.size)

        return lines
    }

    /**
     * Builds one candidate layout.
     *
     * [mode] controls whether the wrapped render lines are produced. The search calls this dozens of
     * times per song, so the measure pass stops as soon as the height is known.
     */
    internal fun attempt(
        lines: List<Line>,
        viewportWidth: Float,
        viewportHeight: Float,
        paperWidth: Float,
        columns: Int,
        gap: Float,
        densityScale: Float,
        seedKey: String,
        lyricsFontScale: Float,
        heroScale: Float,
        measurer: FumeGlyphMeasurer,
        buildRenderDetails: Boolean,
    ): FumeArticleLayout? {
        if (viewportWidth <= 0f || viewportHeight <= 0f || lines.isEmpty()) return null

        val hMargin = horizontalMargin(viewportWidth)
        val vMargin = verticalMargin(viewportHeight)
        val columnWidth = (paperWidth - gap * (columns - 1)) / columns

        // Empty lines carry no text on the page, and the shuffle makes the composition look designed
        // rather than chronological. The seed is stable per line so a re-layout is identical.
        val filtered = lines
            .mapIndexed { index, line -> line to index }
            .filter { it.first.fullText.trim().isNotEmpty() }
            .sortedBy { FumeMath.seeded("$seedKey:${it.second}:${it.first.fullText}") }

        if (filtered.isEmpty()) return null

        val graphemeCounts = filtered.map { FumeMath.splitGraphemes(it.first.fullText).size }
        val forcedHeroIndex = chooseFallbackHeroIndex(filtered, graphemeCounts)

        val blocks = mutableListOf<FumeBlock>()
        val columnHeights = FloatArray(columns) { vMargin }
        var bodyTieCursor = 0
        var heroTieCursor = 0

        filtered.forEachIndexed { blockIndex, (line, sourceIndex) ->
            val graphemes = FumeMath.splitGraphemes(line.fullText)
            val isHero = blockIndex == forcedHeroIndex ||
                chooseNaturalBlockVariant(line, blockIndex, filtered.size, graphemes.size)

            // A hero may span two columns when there is room; body blocks stay inside one so the page
            // still reads as columns.
            val heroSpanColumns = if (isHero) min(columns, if (columns <= 1) 1 else 2) else 1
            val heroSpanWidth = if (heroSpanColumns > 1) {
                columnWidth * heroSpanColumns + gap * (heroSpanColumns - 1)
            } else {
                paperWidth
            }
            // With one column there is no width to give a hero, so it distinguishes itself by type
            // size alone (chooseFontPx's hero branch). Claiming the full width in that case would
            // make it identical to every body block, which is the bug this branch fixes.
            val blockWidth = when {
                !isHero -> columnWidth
                columns == 1 -> columnWidth
                heroSpanColumns == 1 -> paperWidth
                columns == 2 -> columnWidth * 1.5f + gap * 0.5f
                else -> heroSpanWidth
            }
            val innerWidth = max(blockWidth, 120f)

            val fontPx = chooseFontPx(
                graphemeCount = graphemes.size,
                wordCount = line.words.size,
                isHero = isHero,
                blockWidth = blockWidth,
                lyricsFontScale = lyricsFontScale,
                densityScale = densityScale,
                heroScale = heroScale,
            )
            val lineHeight = (fontPx * (if (isHero) 1.02f else 1.06f)).roundToInt().toFloat()

            val advances = measurer.advances(line.fullText, fontPx)
            val wrapped = wrap(graphemes, advances, innerWidth)
            val renderedLineCount = max(wrapped.size, 1)

            val blockGap = if (isHero) {
                max((lineHeight * 0.2f).roundToInt().toFloat(), 6f)
            } else {
                max((lineHeight * 0.08f).roundToInt().toFloat(), 2f)
            }
            val blockHeight = renderedLineCount * lineHeight

            var x: Float
            var y: Float

            if (isHero) {
                if (heroSpanColumns == 1) {
                    y = columnHeights.max()
                    x = hMargin
                    columnHeights[0] = y + blockHeight + blockGap
                } else {
                    // Find the calmest multi-column slot: the one whose tallest column is lowest.
                    var bestHeight = Float.POSITIVE_INFINITY
                    val candidates = mutableListOf<Int>()
                    for (startColumn in 0..(columns - heroSpanColumns)) {
                        var covered = 0f
                        for (columnIndex in startColumn until startColumn + heroSpanColumns) {
                            covered = max(covered, columnHeights[columnIndex])
                        }
                        if (covered < bestHeight) {
                            bestHeight = covered
                            candidates.clear()
                            candidates.add(startColumn)
                        } else if (covered == bestHeight) {
                            candidates.add(startColumn)
                        }
                    }

                    val targetStart = if (candidates.isNotEmpty()) {
                        candidates[heroTieCursor % candidates.size]
                    } else {
                        0
                    }
                    heroTieCursor += 1
                    y = bestHeight
                    x = hMargin + targetStart * (columnWidth + gap) +
                        max((heroSpanWidth - blockWidth) * 0.5f, 0f)

                    for (columnIndex in targetStart until targetStart + heroSpanColumns) {
                        columnHeights[columnIndex] = y + blockHeight + blockGap
                    }
                }
            } else {
                var minHeight = columnHeights[0]
                val candidates = mutableListOf(0)
                for (columnIndex in 1 until columns) {
                    val height = columnHeights[columnIndex]
                    if (height < minHeight) {
                        minHeight = height
                        candidates.clear()
                        candidates.add(columnIndex)
                    } else if (height == minHeight) {
                        candidates.add(columnIndex)
                    }
                }

                val targetColumn = candidates[bodyTieCursor % candidates.size]
                bodyTieCursor += 1
                x = hMargin + targetColumn * (columnWidth + gap)
                y = columnHeights[targetColumn]
                columnHeights[targetColumn] = y + blockHeight + blockGap
            }

            val renderLines = if (buildRenderDetails) {
                wrapped.mapIndexed { index, wrappedLine ->
                    wrappedLine.copy(
                        // A hero line sits slightly in from its block edge, which is the visual cue
                        // that distinguishes it from body text on the same page.
                        left = if (isHero) max((blockWidth - wrappedLine.width) * 0.08f, 0f) else 0f,
                        top = index * lineHeight,
                    )
                }
            } else {
                emptyList()
            }

            blocks.add(
                FumeBlock(
                    id = "fume-${line.startTime}-$sourceIndex",
                    sourceLineIndex = sourceIndex,
                    line = line,
                    isHero = isHero,
                    x = x,
                    y = y,
                    width = blockWidth,
                    height = blockHeight,
                    innerWidth = innerWidth,
                    fontPx = fontPx,
                    lineHeight = lineHeight,
                    graphemes = graphemes,
                    advances = advances,
                    renderLines = renderLines,
                    wordRanges = buildWordRanges(line, graphemes),
                )
            )
        }

        val articleHeight = columnHeights.max() + vMargin
        val paperRight = hMargin + paperWidth
        val paperBottom = max(articleHeight - vMargin, vMargin)

        if (!buildRenderDetails) {
            return FumeArticleLayout(
                width = paperWidth + hMargin * 2,
                height = articleHeight,
                viewportHeight = viewportHeight,
                columns = columns,
                gap = gap,
                paperLeft = hMargin,
                paperTop = vMargin,
                paperRight = paperRight,
                paperBottom = paperBottom,
                blocks = emptyList(),
                chronologicalBlocks = emptyList(),
                firstRenderableStartTime = Double.POSITIVE_INFINITY,
                lastChronologicalRenderEndTime = Double.NEGATIVE_INFINITY,
            )
        }

        val chronological = blocks.sortedBy { it.sourceLineIndex }
        return FumeArticleLayout(
            width = paperWidth + hMargin * 2,
            height = articleHeight,
            viewportHeight = viewportHeight,
            columns = columns,
            gap = gap,
            paperLeft = hMargin,
            paperTop = vMargin,
            paperRight = paperRight,
            paperBottom = paperBottom,
            blocks = blocks,
            chronologicalBlocks = chronological,
            firstRenderableStartTime = chronological.firstOrNull()?.line?.startTime
                ?: Double.POSITIVE_INFINITY,
            lastChronologicalRenderEndTime = chronological.lastOrNull()?.line
                ?.let { getLineRenderEndTime(it) }
                ?: Double.NEGATIVE_INFINITY,
        )
    }

    /**
     * Maps each word onto grapheme offsets.
     *
     * Whitespace that the word text omits stays attached to the preceding word, so the printed text
     * remains contiguous: without this every later word would shift left and the reveal would print
     * the wrong characters at the line's end.
     */
    internal fun buildWordRanges(line: Line, graphemes: List<String>): List<FumeWordRange> {
        if (line.words.isEmpty() || graphemes.isEmpty()) return emptyList()

        val ranges = mutableListOf<FumeWordRange>()
        var cursor = 0

        line.words.forEachIndexed { wordIndex, word ->
            val wordGraphemes = FumeMath.splitGraphemes(word.text)
            if (wordGraphemes.isEmpty()) return@forEachIndexed

            val start = cursor.coerceIn(0, graphemes.size)
            var end = (start + wordGraphemes.size).coerceIn(start, graphemes.size)
            while (end < graphemes.size && graphemes[end].isBlank()) end += 1

            // Even spread across the word. The Android lyric model does not carry per-syllable
            // timings (the desktop `Word` does), so every word reveals at a constant rate — which is
            // also what the desktop falls back to for line-timed formats.
            val count = end - start
            val graphemeStarts = if (count <= 0) {
                emptyList()
            } else {
                List(count) { index ->
                    word.startTime + (word.endTime - word.startTime) * index / count
                }
            }

            ranges.add(
                FumeWordRange(
                    wordIndex = wordIndex,
                    start = start,
                    end = end,
                    startTime = word.startTime,
                    endTime = word.endTime,
                    graphemeStarts = graphemeStarts,
                )
            )
            cursor = end
        }

        return ranges
    }

    /**
     * Builds the article, choosing the column count and density that land closest to the target
     * height.
     *
     * This is the part that makes fume look composed: the density bracket widens the type until the
     * page is about 2.45 screens tall, and the column sweep picks whichever arrangement gets there
     * most evenly. Both scores are kept identical to the desktop version so the two clients compose
     * the same page for the same song.
     */
    fun plan(
        lines: List<Line>,
        viewportWidth: Float,
        viewportHeight: Float,
        lyricsFontScale: Float = 1f,
        heroScale: Float = 1f,
        measurer: FumeGlyphMeasurer = EstimatedFumeMeasurer,
        densityIterations: Int = DENSITY_ITERATIONS,
    ): FumeArticleLayout? {
        if (viewportWidth <= 0f || viewportHeight <= 0f || lines.isEmpty()) return null

        val paperWidth = paperWidthFor(viewportWidth)
        val viewportH = max(viewportHeight, 240f)
        val maxColumns = maxColumnsFor(paperWidth)
        val targetHeight = viewportH * 2.45f
        val seedKey = "fume"

        var best: FumeArticleLayout? = null
        var bestScore = Float.POSITIVE_INFINITY

        for (columns in maxColumns downTo 1) {
            val gap = gapFor(paperWidth, columns)
            var low = 0.82f
            var high = 1.42f

            repeat(densityIterations) {
                val densityScale = (low + high) / 2f
                val attempt = attempt(
                    lines = lines,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportH,
                    paperWidth = paperWidth,
                    columns = columns,
                    gap = gap,
                    densityScale = densityScale,
                    seedKey = "$seedKey:$columns:${paperWidth.roundToInt()}",
                    lyricsFontScale = lyricsFontScale,
                    heroScale = heroScale,
                    measurer = measurer,
                    buildRenderDetails = true,
                ) ?: return@repeat

                val coveragePenalty = abs(attempt.height - targetHeight)
                val overflowPenalty = if (attempt.height < targetHeight) {
                    0f
                } else {
                    (attempt.height - targetHeight) * 0.14f
                }
                val score = coveragePenalty + overflowPenalty

                if (score < bestScore) {
                    bestScore = score
                    best = attempt
                }

                if (attempt.height < targetHeight) low = densityScale else high = densityScale
            }
        }

        return best
    }
}
