package top.izuna.foliamajor.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.layout.CladdaghRingPlanner
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.fontWeightOrDefault
import top.izuna.foliamajor.theme.resolveFontFamily
import top.izuna.foliamajor.theme.toThemeColor
import kotlin.math.min

/**
 * `claddagh`: the line laid around a tilted ring, revealed character by character.
 *
 * The ellipse and the tangent rotation come from [CladdaghRingPlanner]; this composable only places
 * the glyphs and draws the axis line that anchors the ring.
 */
@Composable
fun CladdaghLyricCanvas(
    line: Line?,
    positionSec: Double,
    theme: Theme,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    if (line == null) return

    val measurer = rememberTextMeasurer()
    val primary = theme.primaryColor.toThemeColor()
    val accent = theme.accentColor.toThemeColor()
    val secondary = theme.secondaryColor.toThemeColor()

    // The radius the ring will actually be drawn at, resolved once from the measured width so the
    // planner distributes characters over the real arc instead of a nominal one.
    val canvasWidthPx = LocalDensity.current.run { 360.dp.toPx() }
    val plannedRadius = (canvasWidthPx * 0.42f).coerceAtLeast(fontSizeSp * 2f)

    // The font size the ring can actually carry.
    //
    // A line is laid along the arc of a fixed radius, so the number of characters that fit is
    // bounded by the arc length. A long line at a fixed size overflows the arc and the glyphs
    // overlap; shrinking the type is what keeps them legible, and it is what the desktop app does
    // through its arc-span cap.
    val charCount = line.fullText.count { !it.isWhitespace() }.coerceAtLeast(1)
    val maxArcLength = plannedRadius * 2.2f
    val maxGlyphAdvance = maxArcLength / charCount
    // Each glyph occupies about 1.75 em including the tracking the ring needs. Without that much
    // room the glyphs' bounding boxes overlap on the arc even when their centres are evenly spaced,
    // because each one is rotated by its own tangent angle.
    val fittedFontPx = min(fontSizeSp, maxGlyphAdvance / 1.75f).coerceAtLeast(fontSizeSp * 0.35f)

    val ring = remember(line.startTime, line.fullText, fittedFontPx, plannedRadius) {
        CladdaghRingPlanner.plan(
            text = line.fullText,
            fontPx = fittedFontPx,
            maxRadiusX = plannedRadius,
            measureWidth = { char, px -> char.length * px * 1.75f },
        )
    }

    // How many characters have been reached, derived from the line's elapsed fraction. Using the
    // line's own start/end keeps the reveal in step with the vocal rather than with wall time.
    val revealProgress = remember(line, positionSec) {
        val duration = (line.endTime - line.startTime).coerceAtLeast(0.1)
        ((positionSec - line.startTime) / duration).coerceIn(0.0, 1.0).toFloat()
    }

    Canvas(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        if (ring.chars.isEmpty()) return@Canvas

        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // The ring is capped by the canvas: a radius wider than the screen would push glyphs off
        // the edges, which is worse than a smaller ring.
        val radiusX = min(ring.radiusX, size.width * 0.42f)
        val radiusY = radiusX * (ring.radiusY / ring.radiusX)

        // Shrinking the radius shortens the arc, so the glyphs shrink by the same factor — the
        // angular spacing was planned for `ring.radiusX`.
        val radiusScale = radiusX / ring.radiusX

        // The axis line the ring sits on, drawn first so glyphs paint over it.
        drawLine(
            color = secondary.copy(alpha = 0.22f),
            start = Offset(centerX - radiusX * 1.15f, centerY),
            end = Offset(centerX + radiusX * 1.15f, centerY),
            strokeWidth = 1.5f,
        )

        val style = TextStyle(
            color = primary,
            fontSize = (fittedFontPx * radiusScale).sp,
            fontFamily = theme.resolveFontFamily(),
            fontWeight = theme.fontWeightOrDefault(600),
        )

        ring.chars.forEach { char ->
            val isSung = char.progress <= revealProgress
            val isCurrent = char.progress <= revealProgress && char.progress > revealProgress - 0.06f

            val (dx, dy) = CladdaghRingPlanner.positionOf(char, radiusX, radiusY)
            val x = centerX + dx
            val y = centerY + dy

            val measured = measurer.measure(
                text = char.char,
                style = style.copy(
                    color = when {
                        isCurrent -> accent
                        isSung -> primary
                        else -> secondary.copy(alpha = 0.3f)
                    },
                ),
                constraints = Constraints(),
            )

            // Rotate about the glyph's own centre in canvas coordinates, then draw it there.
            //
            // The pivot must be the canvas-space position: `rotate` transforms the whole draw scope,
            // so passing the origin after a translate rotates about the wrong point and scatters the
            // glyphs. Keeping the pivot absolute and the draw coordinates absolute avoids that.
            rotate(char.rotationDeg, Offset(x, y)) {
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(x - measured.size.width / 2f, y - measured.size.height / 2f),
                )
            }
        }
    }
}
