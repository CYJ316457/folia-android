package top.izuna.foliamajor.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.getLineRenderEndTime
import top.izuna.foliamajor.lyrics.layout.FumeArticleLayout
import top.izuna.foliamajor.lyrics.layout.FumeArticlePlanner
import top.izuna.foliamajor.lyrics.layout.FumeBlock
import top.izuna.foliamajor.lyrics.layout.FumeCamera
import top.izuna.foliamajor.lyrics.layout.FumeCameraPoint
import top.izuna.foliamajor.lyrics.layout.FumeCameraState
import top.izuna.foliamajor.lyrics.layout.FumeGlyphMeasurer
import top.izuna.foliamajor.lyrics.layout.FumeMath
import top.izuna.foliamajor.lyrics.layout.FumeReveal
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.fontWeightOrDefault
import top.izuna.foliamajor.theme.resolveFontFamily
import top.izuna.foliamajor.theme.toThemeColor
import kotlin.math.max

/**
 * `fume`: the whole lyric as a typeset article, with a camera that follows the printing.
 *
 * The composition comes from [FumeArticlePlanner] and the motion from [FumeCamera]; this file only
 * draws. Three things make the visual distinct from the other modes:
 *
 *  - The page exists independently of any one line. Lines that have not been reached are visible as
 *    faint text, so the song reads as a document rather than a slideshow.
 *  - Text prints character by character in the current line, and once printed it stays.
 *  - The camera is inside the page at a readable zoom, not looking at the whole page.
 *
 * Text is measured once per line at the size the planner will use, and the measured advances are fed
 * back into the planner. That is the reverse of the usual direction and it is deliberate: the planner
 * needs advances to wrap, and measuring is the only expensive step, so it is cached per (line, font
 * size) rather than explored inside the layout search.
 */
@Composable
fun FumeLyricCanvas(
    lines: List<Line>,
    positionSec: Double,
    theme: Theme,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 16f,
) {
    if (lines.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val primary = theme.primaryColor.toThemeColor()
    val accent = theme.accentColor.toThemeColor()
    val secondary = theme.secondaryColor.toThemeColor()
    val background = theme.backgroundColor.toThemeColor()

    // Canvas size in pixels, needed by the layout before the first draw. Measured through
    // onSizeChanged rather than written during draw: mutating state inside a draw pass schedules a
    // recomposition from inside the frame that is being drawn, which Compose reports as an
    // "state read during draw" violation and can loop.
    var canvasWidth by remember { mutableFloatStateOf(1080f) }
    var canvasHeight by remember { mutableFloatStateOf(700f) }

    // Measured advances, keyed by line identity and the size the planner asked for.
    val measurementCache = remember { mutableMapOf<String, FloatArray>() }

    // The planner works in the same pixel space the Canvas draws in. Text sizes therefore have to
    // be converted with the ambient font scale once, not with `.sp` — `.sp` on a raw Float multiplies
    // by density *and* font scale, which made every block render at roughly three times its layout
    // size and overlap its neighbours.
    val density = LocalDensity.current
    val fontScale = LocalDensity.current.fontScale

    fun pxToTextUnit(px: Float): TextUnit =
        (px / (density.density * fontScale)).sp

    val measurer = remember(textMeasurer, theme, fontSizeSp, density, fontScale) {
        val baseStyle = TextStyle(
            fontFamily = theme.resolveFontFamily(),
            fontWeight = theme.fontWeightOrDefault(400),
        )
        FumeGlyphMeasurer { text, fontPx ->
            val key = "$fontPx|$text"
            measurementCache.getOrPut(key) {
                val graphemes = FumeMath.splitGraphemes(text)
                val measured = textMeasurer.measure(
                    text = text,
                    style = baseStyle.copy(fontSize = pxToTextUnit(fontPx)),
                )
                if (graphemes.isEmpty()) {
                    FloatArray(0)
                } else {
                    // The whole-line width divided proportionally per grapheme is an approximation,
                    // but it preserves the one property wrapping depends on: the sum equals the
                    // measured width, so lines break where the real text would.
                    val perGrapheme = measured.size.width.toFloat() / graphemes.size
                    FloatArray(graphemes.size) { perGrapheme }
                }
            }
        }
    }

    val article = remember(lines, canvasWidth, canvasHeight, theme.name, fontSizeSp) {
        FumeArticlePlanner.plan(
            lines = lines,
            viewportWidth = canvasWidth,
            viewportHeight = canvasHeight,
            measurer = measurer,
        )
    }

    var cameraState by remember(article) {
        mutableStateOf(FumeCamera.syncToArticle(FumeCameraState(), article))
    }
    var cameraPoint by remember(article) {
        mutableStateOf(FumeCamera.initialPoint(article))
    }
    var lastFrameNanos by remember { mutableLongStateOf(0L) }

    // The loop reads the position from a snapshot-backed holder instead of keying on it: keying the
    // effect on `positionSec` cancelled and restarted the loop on every playback tick (four times a
    // second), and each restart reset `lastFrameNanos`, so `dt` was never a real frame time.
    val latestPosition by rememberUpdatedState(positionSec)

    // A frame loop rather than a `withFrameNanos`-per-draw: the camera is stateful, so it has to be
    // stepped exactly once per frame even when nothing recomposes.
    LaunchedEffect(article) {
        while (true) {
            withFrameNanos { now ->
                val previous = lastFrameNanos
                lastFrameNanos = now
                val dt = if (previous == 0L) 1f / 60f else ((now - previous) / 1_000_000_000.0).toFloat()

                val current = article ?: return@withFrameNanos
                val time = latestPosition
                val activeIndex = activeLineIndexFor(lines, time)
                val (next, point) = FumeCamera.step(
                    state = cameraState,
                    article = current,
                    currentTime = time,
                    nowMillis = now / 1_000_000,
                    dt = dt,
                    currentLineIndex = activeIndex,
                    viewportWidth = canvasWidth,
                    viewportHeight = canvasHeight,
                    showOverview = FumeCamera.shouldShowOverview(current, time),
                )
                // The camera itself clamps to the article; nothing to correct here.
                cameraState = next
                cameraPoint = point
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0 &&
                    (size.width != canvasWidth.toInt() || size.height != canvasHeight.toInt())
                ) {
                    canvasWidth = size.width.toFloat()
                    canvasHeight = size.height.toFloat()
                }
            },
    ) {
        val currentArticle = article ?: return@Canvas

        // Paper: a slightly lifted surface so the article reads as a sheet on the theme background.
        drawRect(color = background)
        drawRect(
            color = primary.copy(alpha = 0.035f),
            topLeft = Offset(currentArticle.paperLeft, currentArticle.paperTop),
            size = androidx.compose.ui.geometry.Size(
                currentArticle.paperRight - currentArticle.paperLeft,
                currentArticle.paperBottom - currentArticle.paperTop,
            ),
        )

        drawArticle(
            article = currentArticle,
            camera = cameraPoint,
            positionSec = positionSec,
            measurer = textMeasurer,
            theme = theme,
            primary = primary,
            accent = accent,
            secondary = secondary,
            activeLineIndex = activeLineIndexFor(lines, positionSec),
            pxToTextUnit = { px -> pxToTextUnit(px) },
        )
    }
}

/**
 * The camera transform, then every block.
 *
 * The transform is applied to the whole page rather than to each block so the paper rectangle, the
 * blocks and any future decoration share one coordinate system.
 */
private fun DrawScope.drawArticle(
    article: FumeArticleLayout,
    camera: FumeCameraPoint,
    positionSec: Double,
    measurer: TextMeasurer,
    theme: Theme,
    primary: Color,
    accent: Color,
    secondary: Color,
    activeLineIndex: Int,
    pxToTextUnit: (Float) -> TextUnit,
) {
    clipRect {
        withTransform({
            translate(
                left = size.width * 0.5f - camera.x * camera.scale,
                top = size.height * 0.5f - camera.y * camera.scale,
            )
            scale(camera.scale, camera.scale, Offset.Zero)
        }) {
            for (block in article.blocks) {
                drawBlock(
                    block = block,
                    positionSec = positionSec,
                    isActive = block.sourceLineIndex == activeLineIndex,
                    measurer = measurer,
                    theme = theme,
                    primary = primary,
                    accent = accent,
                    secondary = secondary,
                    pxToTextUnit = pxToTextUnit,
                )
            }
        }
    }
}

/**
 * One block: all of its lines, with the printed prefix in the active colour.
 *
 * Drawing per render line rather than per grapheme is what makes this affordable: a whole line is one
 * `drawText` call, and the printed prefix is a second call over a clipped region. Per-grapheme drawing
 * on a page with 40 blocks would be thousands of draw calls per frame.
 */
private fun DrawScope.drawBlock(
    block: FumeBlock,
    positionSec: Double,
    isActive: Boolean,
    measurer: TextMeasurer,
    theme: Theme,
    primary: Color,
    accent: Color,
    secondary: Color,
    pxToTextUnit: (Float) -> TextUnit,
) {
    val printed = FumeReveal.printedGraphemeCount(
        line = block.line,
        wordRanges = block.wordRanges,
        graphemeCount = block.graphemes.size,
        currentTime = positionSec,
    )

    val finished = positionSec >= block.line.endTime
    val notStarted = positionSec < block.line.startTime
    val hasBeenPrinted = printed > 0

    // Unreached text is faint but present: that is what makes the page read as a document. Text
    // already sung stays readable at full strength, so the page accumulates the song.
    val baseColor = when {
        isActive -> primary
        hasBeenPrinted || finished -> primary.copy(alpha = 0.72f)
        notStarted -> secondary.copy(alpha = 0.22f)
        else -> primary
    }

    val style = TextStyle(
        color = baseColor,
        fontSize = pxToTextUnit(block.fontPx),
        fontFamily = theme.resolveFontFamily(),
        fontWeight = theme.fontWeightOrDefault(if (block.isHero) 700 else 400),
        lineHeight = pxToTextUnit(block.lineHeight),
    )

    for (renderLine in block.renderLines) {
        val layout = measurer.measure(renderLine.text, style)
        val position = Offset(
            x = block.x + renderLine.left,
            y = block.y + renderLine.top + (block.lineHeight - layout.size.height) / 2f,
        )

        // Every line is drawn in its base colour first; the printed part is then overdrawn in the
        // accent. One extra draw per visible line, and no per-character measurement.
        drawText(layout, topLeft = position)

        if (isActive && printed > renderLine.start) {
            val printedInLine = (printed - renderLine.start).coerceIn(0, renderLine.end - renderLine.start)
            if (printedInLine > 0) {
                val charFraction = if (renderLine.end > renderLine.start) {
                    printedInLine.toFloat() / (renderLine.end - renderLine.start)
                } else {
                    0f
                }
                val clipWidth = layout.size.width * charFraction

                clipRect(
                    left = position.x,
                    top = position.y,
                    right = position.x + clipWidth,
                    bottom = position.y + layout.size.height,
                ) {
                    drawText(
                        layout,
                        topLeft = position,
                        color = accent,
                    )
                }
            }
        }
    }
}

/**
 * The line the camera should consider active.
 *
 * Uses [`getLineRenderEndTime`] so a line stays current through its exit animation, then falls back to
 * the latest line that has started — a backwards seek must land on a line rather than on nothing.
 */
private fun activeLineIndexFor(lines: List<Line>, positionSec: Double): Int {
    for (index in lines.indices.reversed()) {
        val line = lines[index]
        if (positionSec >= line.startTime && positionSec <= getLineRenderEndTime(line)) return index
    }
    return -1
}
