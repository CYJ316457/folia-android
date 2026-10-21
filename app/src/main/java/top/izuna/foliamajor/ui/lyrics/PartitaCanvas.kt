package top.izuna.foliamajor.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.layout.PartitaLayoutPlanner
import top.izuna.foliamajor.lyrics.layout.PartitaOptions
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.fontWeightOrDefault
import top.izuna.foliamajor.theme.resolveFontFamily
import top.izuna.foliamajor.theme.resolveWordColor
import top.izuna.foliamajor.theme.toThemeColor

/**
 * `partita`: the line cut into uneven chunks, each chunk a staggered row.
 *
 * A chunk that has not been reached yet is dimmed and scaled down; the active one is full size with
 * a guide line beside it. The unevenness and the left/right alternation are what make the block read
 * as a composed score rather than a list of rows.
 */
@Composable
fun PartitaLyricCanvas(
    line: Line?,
    positionSec: Double,
    theme: Theme,
    fontSizeSp: Float,
    options: PartitaOptions,
    modifier: Modifier = Modifier,
) {
    if (line == null) return

    val measurer = rememberTextMeasurer()
    val primary = theme.primaryColor.toThemeColor()
    val accent = theme.accentColor.toThemeColor()
    val secondary = theme.secondaryColor.toThemeColor()

    // The row count depends on the available height, which the layout planner needs up front. The
    // canvas height is not known until draw time, so a nominal value is used for planning and the
    // block is centred by the actual row count afterwards.
    val layout = remember(line.startTime, line.fullText, options, fontSizeSp) {
        PartitaLayoutPlanner.plan(
            line = line,
            theme = theme,
            availableHeight = 600f,
            options = options,
        )
    }

    val activeIndex = remember(layout, positionSec) {
        PartitaLayoutPlanner.activeChunkIndex(layout, positionSec)
    }

    Canvas(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (layout.chunks.isEmpty()) return@Canvas

        // Rows are distributed over the available height rather than packed at a fixed row height.
        // The canvas is the authority on how much room exists; deriving the spacing from the font
        // size instead makes rows collide whenever the container is taller than expected.
        val centerX = size.width / 2f
        val rowCount = layout.chunks.size
        val bandHeight = size.height / rowCount
        var y = bandHeight / 2f

        layout.chunks.forEachIndexed { index, chunk ->
            val isActive = index == activeIndex
            val isPassed = index < activeIndex
            val isWaiting = index > activeIndex

            val style = TextStyle(
                color = when {
                    isActive -> primary
                    isPassed -> primary.copy(alpha = 0.55f)
                    else -> secondary.copy(alpha = 0.28f)
                },
                fontSize = (fontSizeSp * 0.72f).sp,
                fontFamily = theme.resolveFontFamily(),
                fontWeight = theme.fontWeightOrDefault(if (isActive) 700 else 500),
            )

            // Waiting chunks sit slightly smaller, which is what gives the block its sense of
            // assembling as the line is sung.
            val chunkScale = if (isWaiting) 0.88f else 1f
            val chunkCenterX = centerX + chunk.x
            val chunkCenterY = y + chunk.y

            rotate(chunk.rotate, Offset(chunkCenterX, chunkCenterY)) {
                scale(chunkScale, chunkScale, Offset(chunkCenterX, chunkCenterY)) {
                    drawPartitaChunk(
                        measurer = measurer,
                        words = chunk.words,
                        style = style,
                        centerX = chunkCenterX,
                        centerY = chunkCenterY,
                        maxWidth = size.width * 0.86f,
                        activeColor = accent,
                        isActive = isActive,
                        positionSec = positionSec,
                    )
                }
            }

            // The guide rule beside each row: a thin line whose length follows the chunk's width.
            if (isActive || isPassed) {
                val guideLength = fontSizeSp * 2.2f
                val guideY = chunkCenterY + fontSizeSp * 0.62f
                drawLine(
                    color = if (isActive) accent.copy(alpha = 0.55f) else secondary.copy(alpha = 0.2f),
                    start = Offset(chunkCenterX - guideLength / 2f, guideY),
                    end = Offset(chunkCenterX + guideLength / 2f, guideY),
                    strokeWidth = 1.5f,
                )
            }

            y += bandHeight
        }
    }
}

/**
 * Draws one chunk's words on a single line, highlighting the word currently being sung.
 *
 * The words are laid out by measured advance rather than by `TextLayoutResult` per word, so the
 * active word's color change does not shift the rest of the row.
 */
private fun DrawScope.drawPartitaChunk(
    measurer: TextMeasurer,
    words: List<top.izuna.foliamajor.lyrics.layout.PartitaWord>,
    style: TextStyle,
    centerX: Float,
    centerY: Float,
    maxWidth: Float,
    activeColor: Color,
    isActive: Boolean,
    positionSec: Double,
) {
    val measured = words.map { word ->
        measurer.measure(
            text = word.text,
            style = style,
            constraints = Constraints(),
        )
    }

    val gap = style.fontSize.value * 0.2f
    val totalWidth = measured.sumOf { it.size.width.toDouble() }.toFloat() + gap * (words.size - 1).coerceAtLeast(0)
    val available = maxWidth.coerceAtLeast(1f)

    // A chunk that overflows is drawn smaller rather than wrapped: partita's rows are meant to stay
    // single-line, and wrapping one would break the staggered composition.
    val fitScale = if (totalWidth > available) available / totalWidth else 1f

    var x = centerX - (totalWidth * fitScale) / 2f

    words.forEachIndexed { index, word ->
        val slot = measured[index]
        val width = slot.size.width * fitScale
        val isWordActive = isActive && positionSec >= word.startTime && positionSec <= word.endTime

        val color = if (isWordActive) {
            resolveWordColor(word.text, emptyList(), activeColor)
        } else {
            style.color
        }

        drawText(
            textLayoutResult = slot,
            topLeft = Offset(x, centerY - slot.size.height / 2f),
            color = color,
        )

        x += width + gap
    }
}
