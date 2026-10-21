package top.izuna.foliamajor.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.fontWeightOrDefault
import top.izuna.foliamajor.theme.resolveFontFamily
import top.izuna.foliamajor.theme.resolveWordColor
import top.izuna.foliamajor.theme.toThemeColor
import kotlin.math.sin

/**
 * Word-level lyric renderer on a Compose Canvas.
 *
 * Drawn on a Canvas rather than composed as Text widgets because the desktop visualizer animates
 * each word independently at 60fps, and a recomposition per word per frame would be the wrong tool:
 * here the whole frame is one draw pass over pre-measured text.
 *
 * This ports the desktop `classic` mode's core idea — every word is in one of three states
 * (waiting / active / passed) and each state has its own color, scale and glow — but not its layout
 * randomization. That mode scatters words with a deterministic RNG seeded on the line start time;
 * on a phone-width screen that reads as broken text rather than as a stylistic choice, so words are
 * laid out on a stable centered grid and only the per-word motion is kept.
 */
@Composable
fun LyricCanvas(
    line: Line?,
    positionSec: Double,
    theme: Theme,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val baseColor = theme.primaryColor.toThemeColor()
    val accentColor = theme.accentColor.toThemeColor()
    val dimColor = theme.secondaryColor.toThemeColor()

    Canvas(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        if (line == null || line.words.isEmpty()) return@Canvas

        val style = TextStyle(
            color = baseColor,
            fontSize = fontSizeSp.sp,
            fontFamily = theme.resolveFontFamily(),
            fontWeight = theme.fontWeightOrDefault(700),
        )

        // Measured once per line; the per-frame work is only the color and offset math below.
        //
        // A word can be wider than the whole row — a long unspaced Latin token, or a line the parser
        // could not split. Rather than let it run off the screen, the type is scaled down until the
        // widest word fits. Rows are then laid out at the normal size in the common case, because
        // fitScale returns 1 when everything already fits.
        var measured = line.words.map { word -> MeasuredWord(word, measurer.measureWord(word.text, style)) }
        val widest = measured.maxOfOrNull { it.layout.size.width.toFloat() } ?: 0f
        val fitScale = RowLayout.fitScale(widest, size.width)
        if (fitScale < 1f) {
            val fitted = style.copy(fontSize = (fontSizeSp * fitScale).sp)
            measured = line.words.map { word -> MeasuredWord(word, measurer.measureWord(word.text, fitted)) }
        }
        val boxes = layoutWords(measured, size.width)
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        for ((measuredWord, box) in boxes) {
            drawWord(
                measured = measuredWord,
                box = box,
                centerX = centerX,
                centerY = centerY,
                positionSec = positionSec,
                theme = theme,
                baseColor = baseColor,
                activeColor = resolveWordColor(measuredWord.word.text, theme.wordColors, accentColor),
                dimColor = dimColor,
            )
        }
    }
}

private data class MeasuredWord(val word: Word, val layout: TextLayoutResult)

/** One word's measured geometry, in canvas pixels. */
private data class WordBox(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Flows words into centered rows.
 *
 * Measuring happens through [TextMeasurer] because word widths decide the wrap points, and a
 * character-count estimate mis-wraps CJK badly (the desktop mode has the same problem and solves it
 * with a canvas 2D context).
 */
private fun layoutWords(measured: List<MeasuredWord>, maxWidth: Float): List<Pair<MeasuredWord, WordBox>> {
    if (measured.isEmpty()) return emptyList()

    val gap = 4f
    val lineHeight = measured.maxOf { it.layout.size.height }.toFloat() * 1.35f

    val flow = RowLayout.flow(
        items = measured.map { RowLayout.Item(it, it.layout.size.width.toFloat()) },
        maxWidth = maxWidth,
        gap = gap,
    )

    // Each row is centred on the canvas origin, so a row's x offsets are shifted by half its width.
    val rowWidths = flow.rows.associate { it.index to it.width }
    val totalHeight = flow.rows.size * lineHeight
    val startY = -totalHeight / 2f + lineHeight / 2f

    return flow.placements.map { placement ->
        val entry = placement.key as MeasuredWord
        val rowWidth = rowWidths[placement.row] ?: 0f
        entry to WordBox(
            x = -rowWidth / 2f + placement.x,
            y = startY + placement.row * lineHeight,
            width = placement.width,
            height = entry.layout.size.height.toFloat(),
        )
    }
}

/**
 * Draws one word in its current state.
 *
 * The three states and their timing come from the desktop visualizer:
 *  - waiting: dimmed and slightly smaller, so the eye lands on the active word
 *  - active: full color, scaled up, with a soft glow that grows with the sung fraction
 *  - passed: full color, no scale, back at rest
 *
 * The sung fraction is per-word rather than per-line, which is what makes the highlight track the
 * vocals instead of jumping once per line.
 */
private fun DrawScope.drawWord(
    measured: MeasuredWord,
    box: WordBox,
    centerX: Float,
    centerY: Float,
    positionSec: Double,
    theme: Theme,
    baseColor: Color,
    activeColor: Color,
    dimColor: Color,
) {
    val word = measured.word
    val isActive = positionSec >= word.startTime && positionSec <= word.endTime
    val isPassed = positionSec > word.endTime

    val progress = when {
        isActive -> ((positionSec - word.startTime) / word.duration.coerceAtLeast(0.001)).toFloat()
        isPassed -> 1f
        else -> 0f
    }.coerceIn(0f, 1f)

    val color = when {
        isActive -> lerpColor(baseColor, activeColor, progress)
        isPassed -> baseColor
        else -> dimColor.copy(alpha = 0.45f)
    }

    // Calm themes keep the motion restrained; chaotic ones let the word overshoot and drift.
    val intensity = when (theme.animationIntensity) {
        Theme.AnimationIntensity.CALM -> 0.4f
        Theme.AnimationIntensity.NORMAL -> 1f
        Theme.AnimationIntensity.CHAOTIC -> 1.8f
    }

    val wordScale = when {
        isActive -> 1f + 0.06f * intensity * sin(progress * Math.PI.toFloat())
        isPassed -> 1f
        else -> 0.97f
    }

    // A small idle float keeps a long line from looking frozen between word events.
    val float = if (isActive) sin((positionSec * 2.2).toFloat()) * 2.5f * intensity else 0f

    // The layout is centered on the canvas origin, so each word is drawn relative to it.
    translate(
        left = centerX + box.x + box.width / 2f,
        top = centerY + box.y + box.height / 2f + float,
    ) {
        val textWidth = measured.layout.size.width.toFloat()
        val textHeight = measured.layout.size.height.toFloat()

        if (isActive) {
            // The glow is a scaled copy underneath rather than a text shadow: Compose's shadow
            // renders per glyph and washes out CJK strokes at these sizes.
            val glowAlpha = (0.35f * progress * intensity).coerceAtMost(0.6f)
            if (glowAlpha > 0.01f) {
                scale(1.18f * wordScale, 1.18f * wordScale, Offset.Zero) {
                    drawText(
                        textLayoutResult = measured.layout,
                        color = activeColor.copy(alpha = glowAlpha),
                        topLeft = Offset(-textWidth / 2f, -textHeight / 2f),
                    )
                }
            }
        }

        scale(wordScale, wordScale, Offset.Zero) {
            drawText(
                textLayoutResult = measured.layout,
                color = color,
                topLeft = Offset(-textWidth / 2f, -textHeight / 2f),
            )
        }
    }
}

/** Linear RGB interpolation; matches how the desktop mode blends base into the active word color. */
private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = from.alpha + (to.alpha - from.alpha) * f,
    )
}

private fun TextMeasurer.measureWord(text: String, style: TextStyle): TextLayoutResult =
    measure(text = text, style = style, constraints = Constraints())
