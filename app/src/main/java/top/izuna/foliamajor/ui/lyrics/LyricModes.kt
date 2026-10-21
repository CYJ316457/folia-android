package top.izuna.foliamajor.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.layout.CharTimingBuilder
import top.izuna.foliamajor.lyrics.layout.TiltLayoutPlanner
import top.izuna.foliamajor.lyrics.layout.TiltOptions
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.fontWeightOrDefault
import top.izuna.foliamajor.theme.resolveFontFamily
import top.izuna.foliamajor.theme.toThemeColor
import kotlin.math.sin

/**
 * `still`: three static lines, the current one emphasized.
 *
 * The desktop app's lowest-cost mode — no per-word animation, no background renderer. Ported as a
 * Canvas rather than a Column of Texts so the three lines share one layout pass and the emphasis
 * (size, opacity, weight) is applied without recomposing.
 */
@Composable
fun StillLyricCanvas(
    line: Line?,
    nextLine: Line?,
    previousLine: Line?,
    theme: Theme,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val primary = theme.primaryColor.toThemeColor()
    val secondary = theme.secondaryColor.toThemeColor()

    Canvas(modifier = modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        val centerY = size.height / 2f

        // Three slots: previous, current, next. Missing slots collapse rather than leaving gaps,
        // so a song's first line sits centered instead of low.
        val entries = listOfNotNull(
            previousLine?.let { Triple(it, false, -1f) },
            line?.let { Triple(it, true, 0f) },
            nextLine?.let { Triple(it, false, 1f) },
        )
        if (entries.isEmpty()) return@Canvas

        // Each line is measured first so the spacing can be derived from real heights. A fixed
        // gap sized off the font size overlaps as soon as the current line wraps or the font
        // metrics differ from the assumption.
        val measured = entries.map { (entry, isCurrent, slot) ->
            val style = TextStyle(
                color = if (isCurrent) primary else secondary.copy(alpha = 0.35f),
                fontSize = (if (isCurrent) fontSizeSp else fontSizeSp * 0.68f).sp,
                fontFamily = theme.resolveFontFamily(),
                fontWeight = theme.fontWeightOrDefault(if (isCurrent) 700 else 600),
            )

            val layout = measurer.measure(
                text = entry.fullText,
                style = style,
                constraints = androidx.compose.ui.unit.Constraints(
                    maxWidth = size.width.toInt().coerceAtLeast(1),
                ),
            )

            Triple(layout, slot, isCurrent)
        }

        val spacing = fontSizeSp * 0.9f
        val currentHeight = measured.firstOrNull { it.third }?.first?.size?.height?.toFloat()
            ?: (fontSizeSp * 1.4f)

        // Slots are positioned from the current line's box: the neighbours sit one full line-height
        // plus the gap above and below it, which keeps them clear even when the current line wraps.
        for ((layout, slot, _) in measured) {
            val offset = when {
                slot == 0f -> 0f
                slot < 0 -> -(currentHeight / 2f + spacing + layout.size.height / 2f)
                else -> currentHeight / 2f + spacing + layout.size.height / 2f
            }

            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    (size.width - layout.size.width) / 2f,
                    centerY + offset - layout.size.height / 2f,
                ),
            )
        }
    }
}

/**
 * `tilt`: a line split into 1–4 fragments, one of which is large italic with alternating vertical
 * offsets.
 *
 * Fragments appear in time order: each becomes visible when its first word starts, so a long line
 * unfolds with the vocal instead of appearing all at once.
 */
@Composable
fun TiltLyricCanvas(
    line: Line?,
    positionSec: Double,
    theme: Theme,
    fontSizeSp: Float,
    options: TiltOptions,
    modifier: Modifier = Modifier,
) {
    if (line == null) return

    val measurer = rememberTextMeasurer()
    val primary = theme.primaryColor.toThemeColor()
    val accent = theme.accentColor.toThemeColor()

    // The layout is a pure function of the line and the width, so it is recomputed only when those
    // change — not per frame.
    val layout = remember(line.startTime, line.fullText, options, fontSizeSp) {
        TiltLayoutPlanner.plan(
            fullText = line.fullText,
            lineSeed = line.startTime,
            options = options,
            availableWidth = 1000f,
            baseFontPx = fontSizeSp,
            measureWidth = { text, px, italic ->
                // A cheap estimate: the real measurement happens during draw, and the planner only
                // needs to know whether the widest fragment is likely to overflow.
                text.length * px * (if (italic) 1.05f else 0.95f)
            },
        )
    }

    val segmentTimings = remember(line, layout) {
        layout.segments.map { segment ->
            val words = line.words.filter { word ->
                val index = line.fullText.indexOf(word.text)
                index >= segment.charOffset &&
                    index < segment.charOffset + segment.text.length
            }
            if (words.isEmpty()) {
                line.startTime to line.endTime
            } else {
                words.first().startTime to words.last().endTime
            }
        }
    }

    // The number of fragments whose start time has passed.
    var visibleCount = 0
    segmentTimings.forEachIndexed { index, (start, _) ->
        if (positionSec >= start - 0.25) visibleCount = index + 1
    }

    val charTimings = remember(line, layout) {
        layout.segments.mapIndexed { index, segment ->
            val (start, end) = segmentTimings[index]
            CharTimingBuilder.build(
                charOffset = segment.charOffset,
                segmentText = segment.text,
                segmentStartTime = start,
                segmentEndTime = end,
                line = line,
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        val centerX = size.width / 2f

        // Fragments are distributed evenly over the available height rather than packed by a fixed
        // per-segment height. The canvas height is the authority on how much room exists; deriving
        // the spacing from the font size instead ignores it and overlaps when the two disagree.
        val count = layout.segments.size
        val bandHeight = size.height / count

        // y is the centre of the current band, not its top — the draw calls position text by centre.
        var y = bandHeight / 2f

        layout.segments.forEachIndexed { index, segment ->
            val isVisible = index < visibleCount
            val style = TextStyle(
                color = if (segment.isTilt) accent else primary,
                fontSize = (if (segment.isTilt) fontSizeSp else fontSizeSp * 0.85f).sp,
                fontFamily = theme.resolveFontFamily(),
                fontWeight = theme.fontWeightOrDefault(if (segment.isTilt) 300 else 400),
                fontStyle = if (segment.isTilt) FontStyle.Italic else FontStyle.Normal,
            )

            val alpha = if (isVisible) 1f else 0.18f
            val visibleColor = style.color.copy(alpha = alpha)

            if (segment.isTilt) {
                drawTiltSegment(
                    measurer = measurer,
                    text = segment.text,
                    style = style.copy(color = visibleColor),
                    centerX = centerX,
                    y = y,
                    charTimings = charTimings[index],
                    positionSec = positionSec,
                    // The advance must match the size this segment actually renders at, or a
                    // non-tilt fragment (drawn at 0.85×) has its characters spaced for the full size.
                    fontSizePx = if (segment.isTilt) fontSizeSp else fontSizeSp * 0.85f,
                    isVisible = isVisible,
                )
            } else {
                val measured = measurer.measure(
                    text = segment.text,
                    style = style.copy(color = visibleColor),
                    constraints = androidx.compose.ui.unit.Constraints(
                        maxWidth = size.width.toInt().coerceAtLeast(1),
                    ),
                )

                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        centerX - measured.size.width / 2f,
                        y - measured.size.height / 2f,
                    ),
                )
            }

            y += bandHeight
        }
    }
}

/**
 * Draws one tilt fragment: characters alternate above and below the baseline, and each pulses as it
 * is sung.
 *
 * Characters are laid out individually rather than as one string because the vertical offset and the
 * pulse scale are per character. The advance is measured per character, which is correct for CJK
 * (fixed-width) and acceptable for Latin (the italic style makes exact kerning less visible).
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTiltSegment(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    centerX: Float,
    y: Float,
    charTimings: List<top.izuna.foliamajor.lyrics.layout.CharTiming>,
    positionSec: Double,
    fontSizePx: Float,
    isVisible: Boolean,
) {
    if (text.isEmpty()) return

    val yOffset = fontSizePx / 6f
    val graphemes = text.map { it.toString() }

    val measured = graphemes.map { char ->
        measurer.measure(
            text = char,
            style = style,
            constraints = androidx.compose.ui.unit.Constraints(),
        )
    }

    // The advance is derived from the font size rather than from each glyph's measured box.
    //
    // A single glyph measured alone reports a box tight to its ink, not the font's advance, and an
    // italic glyph's box excludes the slant overhang — so summing those boxes makes characters
    // collide. For CJK the advance is the em square, which is what `fontSizePx` already is; the
    // small multiplier adds the tracking the desktop mode uses (0.15em).
    val letterSpacing = fontSizePx * 0.15f
    val advance = graphemes.map { char ->
        if (char.isBlank()) fontSizePx * 0.35f else fontSizePx + letterSpacing
    }

    val totalWidth = advance.sum()
    var x = centerX - totalWidth / 2f

    var visualIndex = 0
    graphemes.forEachIndexed { index, char ->
        val isSpace = char.isBlank()
        val slot = measured[index]

        if (!isSpace) {
            val isEven = visualIndex % 2 == 0
            val stagger = if (isEven) -1f else 1f

            val timing = charTimings.getOrNull(visualIndex)
            val intensity = if (timing != null && isVisible) {
                CharTimingBuilder.pulseIntensity(positionSec, timing)
            } else {
                0f
            }

            val scale = 1f + intensity * if (isVisible) 0.18f else 0f
            val idleFloat = if (isVisible) sin((positionSec * 1.8 + index).toFloat()) * 0.6f else 0f

            val charCenterX = x + advance[index] / 2f
            val charCenterY = y + stagger * yOffset + idleFloat

            scale(scale, scale, Offset(charCenterX, charCenterY)) {
                drawText(
                    textLayoutResult = slot,
                    topLeft = Offset(
                        charCenterX - slot.size.width / 2f,
                        charCenterY - slot.size.height / 2f,
                    ),
                )
            }

            visualIndex += 1
        }

        x += advance[index]
    }
}
