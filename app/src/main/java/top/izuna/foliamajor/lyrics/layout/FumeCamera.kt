package top.izuna.foliamajor.lyrics.layout

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the `fume` camera looks, and how it gets there.
 *
 * Ported from `src/components/visualizer/fume/fumeCamera.ts` and `fumeCameraStep.ts`. The desktop
 * version is split across two files because it also owns a renderer; here the whole thing is pure
 * state so the motion can be unit-tested by stepping it with synthetic frames.
 *
 * The camera has three targets:
 *
 *  - a block, tracked character by character as it prints;
 *  - the article overview, shown when the song has no active line (intro, instrumental break);
 *  - the page centre, the resting shot.
 *
 * Movement is a critically-damped spring rather than a lerp: a lerp to a moving target lags
 * visibly, while a spring with the right stiffness settles fast without overshooting on the small
 * per-character moves and still glides on the big ones.
 */
data class FumeCameraPoint(val x: Float, val y: Float, val scale: Float)

/**
 * Spring state for one axis.
 *
 * [velocity] is carried across frames — that is what makes the motion continuous when the target
 * jumps, and it is why this cannot be a pure function of time.
 */
data class FumeSpring(
    val value: Float,
    val velocity: Float = 0f,
) {
    /**
     * Advances toward [target] by [dt] with the given stiffness and damping.
     *
     * Integrated semi-implicitly (velocity first, then position) because explicit Euler with a stiff
     * spring oscillates or diverges at the frame times a phone actually delivers.
     */
    fun step(target: Float, dt: Float, stiffness: Float, damping: Float): FumeSpring {
        val clampedDt = dt.coerceIn(0f, 0.05f)
        val acceleration = (target - value) * stiffness - velocity * damping
        val nextVelocity = velocity + acceleration * clampedDt
        return FumeSpring(value + nextVelocity * clampedDt, nextVelocity)
    }
}

/** Per-block arrival animation state, so a block can slide in as its line starts. */
data class FumeBlockArrival(
    val startTime: Double,
    val progress: Float,
)

/** The camera's mutable state across frames. */
data class FumeCameraState(
    val x: FumeSpring = FumeSpring(0f),
    val y: FumeSpring = FumeSpring(0f),
    val scale: FumeSpring = FumeSpring(1f),
    /** Source line index the camera is currently chasing; -1 for none, -2 for the overview shot. */
    val targetLineIndex: Int = -1,
    val initialized: Boolean = false,
)

object FumeCamera {

    const val SCALE_MIN = 0.22f
    const val SCALE_MAX = 2.24f
    const val OVERVIEW_SOURCE = -2

    /** Stiffness and damping for block tracking; tuned so a character-sized move settles in ~120ms. */
    private const val TRACK_STIFFNESS = 190f
    private const val TRACK_DAMPING = 26f

    /** Softer response for the overview, so the camera does not snap when the song pauses. */
    private const val OVERVIEW_STIFFNESS = 90f
    private const val OVERVIEW_DAMPING = 18f

    /** Idle float: a slow drift so a held shot is not perfectly static. */
    private const val IDLE_AMPLITUDE = 4.5f
    private const val IDLE_SPEED = 0.00022f

    /**
     * Places a fresh camera on a new article.
     *
     * Starting at the page centre at a slightly zoomed-in scale means the first line's move is a
     * short one; starting at scale 1 would show the whole page and then lurch inward.
     */
    fun syncToArticle(state: FumeCameraState, article: FumeArticleLayout?): FumeCameraState {
        if (article == null) return state.copy(initialized = false)

        return if (!state.initialized) {
            state.copy(
                x = FumeSpring(article.width * 0.5f),
                y = FumeSpring(article.height * 0.5f),
                scale = FumeSpring(1.18f),
                initialized = true,
            )
        } else {
            state.copy(
                x = FumeSpring(state.x.value.coerceIn(0f, article.width), state.x.velocity),
                y = FumeSpring(state.y.value.coerceIn(0f, article.height), state.y.velocity),
                scale = FumeSpring(
                    state.scale.value.coerceIn(SCALE_MIN, SCALE_MAX),
                    state.scale.velocity,
                ),
            )
        }
    }

    /**
     * Picks the block the camera should watch.
     *
     * The active line wins; then, after the song's last line has been read, the last block; then the
     * most recently printed block (which covers the gap between two lines). Falling back to the
     * page centre would swing the camera away and back on every line break.
     */
    fun resolveFocusBlock(
        article: FumeArticleLayout,
        currentLineIndex: Int,
        currentTime: Double,
    ): FumeBlock? {
        if (currentLineIndex >= 0) {
            article.blocks.firstOrNull { it.sourceLineIndex == currentLineIndex }?.let { return it }
        }

        val lastBlock = article.chronologicalBlocks.lastOrNull()
        if (lastBlock != null && currentTime >= article.lastChronologicalRenderEndTime) {
            return lastBlock
        }

        for (index in article.chronologicalBlocks.indices.reversed()) {
            val block = article.chronologicalBlocks[index]
            val printed = FumeReveal.printedGraphemeCount(
                block.line,
                block.wordRanges,
                block.graphemes.size,
                currentTime,
            )
            if (printed > 0) return block
        }

        return article.chronologicalBlocks.firstOrNull()
    }

    /** The first line's left edge: where the eye should land when a block becomes active. */
    fun blockEntryFocusPoint(block: FumeBlock): FumeCameraPoint {
        val firstLine = block.renderLines.firstOrNull()
            ?: return FumeCameraPoint(
                x = block.x + block.width * 0.5f,
                y = block.y + block.height * 0.5f,
                scale = 1f,
            )

        return FumeCameraPoint(
            x = block.x + firstLine.left,
            y = block.y + firstLine.top + block.lineHeight * 0.5f,
            scale = 1f,
        )
    }

    /**
     * Focus point tracking the printed position inside a block.
     *
     * [printedProgress] is fractional so the camera slides within a character. The x coordinate is
     * clamped to the current render line, so a line that wraps does not send the camera off the block.
     */
    fun steppingFocusPoint(block: FumeBlock, printedProgress: Float): FumeCameraPoint {
        if (block.renderLines.isEmpty()) {
            return FumeCameraPoint(
                x = block.x + block.width * 0.5f,
                y = block.y + block.height * 0.5f,
                scale = 1f,
            )
        }

        val offset = printedProgress.coerceIn(0f, block.graphemes.size.toFloat())
        val line = block.renderLines.firstOrNull { offset <= it.end }
            ?: block.renderLines.last()
        val localOffset = offset.coerceIn(line.start.toFloat(), line.end.toFloat())

        val width = widthBetween(block, line.start, localOffset)
        val minX = block.x + line.left

        return FumeCameraPoint(
            x = (minX + width).coerceIn(minX, minX + line.width),
            y = block.y + line.top + block.lineHeight * 0.5f,
            scale = 1f,
        )
    }

    /** Sum of advances from [startOffset] up to (not including) [endOffset], interpolating the last. */
    private fun widthBetween(block: FumeBlock, startOffset: Int, endOffset: Float): Float {
        if (endOffset <= startOffset) return 0f

        val whole = endOffset.toInt().coerceIn(startOffset, block.graphemes.size)
        var width = 0f
        for (index in startOffset until whole) {
            width += block.advances.getOrElse(index) { 0f }
        }
        val fraction = endOffset - whole
        if (fraction > 0f) {
            width += block.advances.getOrElse(whole) { 0f } * fraction
        }
        return width
    }

    /**
     * How close to put the camera on a block.
     *
     * Aims for a line height of about 11.5% of the viewport's short side, clamped to a readable range
     * and to the camera's own zoom limits. Proportional rather than fixed, so the same block is framed
     * the same way on a phone and a tablet.
     */
    fun scaleForBlock(block: FumeBlock, viewportWidth: Float, viewportHeight: Float): Float {
        val minSide = max(min(viewportWidth, viewportHeight), 1f)
        val targetLineHeight = FumeMath.clamp(minSide * 0.115f, 64f, 124f)
        val byLineHeight = FumeMath.clamp(targetLineHeight / max(block.lineHeight, 1f), 0.88f, 2.2f)

        // Never zoom in past the point where the block fits across the viewport. The line-height
        // rule alone aims for a fixed on-screen size, which on a phone makes a wide block several
        // screens across — the camera then tracks the printing cursor and pushes the start of every
        // line off the left edge. Fitting the block keeps whole words visible while still scaling
        // up small blocks for readability.
        val byWidth = viewportWidth / max(block.width, 1f) * 0.92f
        return max(min(byLineHeight, byWidth), SCALE_MIN)
    }

    /**
     * The overview shot: everything on the page, framed with a margin.
     */
    fun overviewCamera(article: FumeArticleLayout, viewportWidth: Float, viewportHeight: Float): FumeCameraPoint {
        if (article.blocks.isEmpty()) {
            val fitScale = min(
                viewportWidth / max(article.width, 1f),
                viewportHeight / max(article.height, 1f),
            )
            return FumeCameraPoint(
                x = article.width * 0.5f,
                y = article.height * 0.5f,
                scale = FumeMath.clamp(fitScale * 0.92f, SCALE_MIN, 0.72f),
            )
        }

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (block in article.blocks) {
            minX = min(minX, block.x)
            minY = min(minY, block.y)
            maxX = max(maxX, block.x + block.width)
            maxY = max(maxY, block.y + block.height)
        }

        val paddingX = FumeMath.clamp(viewportWidth * 0.2f, 120f, 280f)
        val paddingY = FumeMath.clamp(viewportHeight * 0.2f, 96f, 220f)
        val framedWidth = max(maxX - minX + paddingX * 2f, 1f)
        val framedHeight = max(maxY - minY + paddingY * 2f, 1f)
        val fitScale = min(viewportWidth / framedWidth, viewportHeight / framedHeight)

        return FumeCameraPoint(
            x = (minX + maxX) * 0.5f,
            y = (minY + maxY) * 0.5f,
            scale = FumeMath.clamp(fitScale, SCALE_MIN, 0.72f),
        )
    }

    /**
     * One frame.
     *
     * The idle float is added to the *target* rather than to the camera position: adding it to the
     * position would be fought by the spring every frame, producing a jitter instead of a drift.
     */
    fun step(
        state: FumeCameraState,
        article: FumeArticleLayout,
        currentTime: Double,
        nowMillis: Long,
        dt: Float,
        currentLineIndex: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        showOverview: Boolean,
    ): Pair<FumeCameraState, FumeCameraPoint> {
        val focusBlock = resolveFocusBlock(article, currentLineIndex, currentTime)

        val target: FumeCameraPoint
        val stiffness: Float
        val damping: Float

        if (showOverview || focusBlock == null) {
            target = overviewCamera(article, viewportWidth, viewportHeight)
            stiffness = OVERVIEW_STIFFNESS
            damping = OVERVIEW_DAMPING
        } else {
            val printed = FumeReveal.printedGraphemeProgress(
                focusBlock.line,
                focusBlock.wordRanges,
                focusBlock.graphemes.size,
                currentTime,
            )
            val cursor = steppingFocusPoint(focusBlock, printed)
            val scale = scaleForBlock(focusBlock, viewportWidth, viewportHeight)

            // Blend the printing cursor toward the block's horizontal centre. Pure cursor tracking
            // parks the block's left edge at the screen edge and leaves the right half empty, because
            // a freshly started line has printed nothing to the right of its first character. Half
            // the block's width keeps the text on screen while still leaning toward what is being
            // sung — the desktop app tracks the cursor alone, which works on a wide monitor and not
            // on a phone-width viewport.
            val blockCentreX = focusBlock.x + focusBlock.width * 0.5f
            val blendedX = FumeMath.mix(cursor.x, blockCentreX, if (focusBlock.isHero) 0.75f else 0.45f)

            target = FumeCameraPoint(
                x = blendedX,
                y = cursor.y,
                scale = scale,
            ).let { p ->
                // Clamp so the focus block stays inside the viewport, not so the whole page does.
                // Clamping to the page bounds let the camera sit on the page's left margin while the
                // block was further left, which is what pushed every block against the screen edge.
                val halfW = viewportWidth / (2f * p.scale)
                val halfH = viewportHeight / (2f * p.scale)
                val blockLeft = focusBlock.x + 16f
                val blockRight = focusBlock.x + focusBlock.width - 16f
                val blockTop = focusBlock.y
                val blockBottom = focusBlock.y + max(focusBlock.height, 1f)

                // A block narrower than the viewport has a negative clamp range; coerceIn throws on
                // that. In that case the block fits, so centring it is the only sensible answer.
                val xRange = (blockLeft + halfW)..(blockRight - halfW)
                val yRange = (blockTop + halfH)..(blockBottom - halfH)
                p.copy(
                    x = if (xRange.isEmpty()) (blockLeft + blockRight) * 0.5f else p.x.coerceIn(xRange),
                    y = if (yRange.isEmpty()) (blockTop + blockBottom) * 0.5f else p.y.coerceIn(yRange),
                )
            }
            stiffness = TRACK_STIFFNESS
            damping = TRACK_DAMPING
        }

        val idlePhase = nowMillis.toDouble() % 100000.0
        val idleX = (kotlin.math.sin(idlePhase * IDLE_SPEED) * IDLE_AMPLITUDE).toFloat()
        val idleY = (kotlin.math.cos(idlePhase * IDLE_SPEED * 0.83) * IDLE_AMPLITUDE * 0.7).toFloat()

        val nextX = state.x.step(target.x + idleX, dt, stiffness, damping)
        val nextY = state.y.step(target.y + idleY, dt, stiffness, damping)
        val nextScale = state.scale.step(
            target.scale.coerceIn(SCALE_MIN, SCALE_MAX),
            dt,
            stiffness,
            damping,
        )

        return state.copy(
            x = nextX,
            y = nextY,
            scale = nextScale,
            targetLineIndex = when {
                showOverview -> OVERVIEW_SOURCE
                focusBlock != null -> focusBlock.sourceLineIndex
                else -> -1
            },
        ) to FumeCameraPoint(nextX.value, nextY.value, nextScale.value)
    }

    /**
     * Whether the camera should be on the overview shot.
     *
     * Only during a sustained gap: a half-second rest between lines is part of the song's phrasing
     * and pulling back for it would make the page breathe on every line break. Three seconds is long
     * enough to mean an intro, a break, or the end.
     */
    fun shouldShowOverview(article: FumeArticleLayout, currentTime: Double): Boolean =
        currentTime < article.firstRenderableStartTime - OVERVIEW_LEAD_IN_SECONDS ||
            currentTime > article.lastChronologicalRenderEndTime + OVERVIEW_HOLD_SECONDS

    /** How long before the first line the overview starts, so the page is established early. */
    const val OVERVIEW_LEAD_IN_SECONDS = 6.0

    /** How long after the last line the overview returns. */
    const val OVERVIEW_HOLD_SECONDS = 4.0

    /** A stable initial camera before the first frame has run. */
    fun initialPoint(article: FumeArticleLayout?): FumeCameraPoint =
        if (article == null) {
            FumeCameraPoint(0f, 0f, 1f)
        } else {
            FumeCameraPoint(article.width * 0.5f, article.height * 0.5f, 1.18f)
        }
}
