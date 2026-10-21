package top.izuna.foliamajor.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.getLineRenderEndTime
import top.izuna.foliamajor.lyrics.layout.FumeMath
import top.izuna.foliamajor.lyrics.layout.PendoloClockworkBuilder
import top.izuna.foliamajor.lyrics.layout.PendoloClockworkDrawing
import top.izuna.foliamajor.lyrics.layout.PendoloClockworkFrame
import top.izuna.foliamajor.lyrics.layout.PendoloClockworkMotion
import top.izuna.foliamajor.lyrics.layout.PendoloClockworkMotionState
import top.izuna.foliamajor.lyrics.layout.PendoloClockworkScene
import top.izuna.foliamajor.lyrics.layout.PendoloDrawItem
import top.izuna.foliamajor.lyrics.layout.PendoloGraphemeTiming
import top.izuna.foliamajor.lyrics.layout.PendoloLineItem
import top.izuna.foliamajor.lyrics.layout.PendoloMotionProfile
import top.izuna.foliamajor.lyrics.layout.PendoloSweepFill
import top.izuna.foliamajor.lyrics.layout.PendoloTimeline
import top.izuna.foliamajor.lyrics.layout.PendoloTuning
import top.izuna.foliamajor.lyrics.layout.PendoloWheelGeometry
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.fontWeightOrDefault
import top.izuna.foliamajor.theme.resolveFontFamily
import top.izuna.foliamajor.theme.toThemeColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * `pendolo`: lyrics on a clockwork escapement arc.
 *
 * The wheel hub sits at the left edge; the current line is at angle 0 (straight right) and the wheel
 * *rotates* as lines advance rather than the text scrolling. Behind it runs a wireframe gear train:
 * balance wheel with a hairspring, an orbiting epicyclic set, a transmission train, a Geneva-striped
 * face and a seconds gear that ticks once per second.
 *
 * Rendering is split in two. [PendoloClockworkBuilder] produces a display list of primitives — pure
 * arithmetic, covered by JVM tests — and this file only turns those primitives into draw calls. The
 * desktop's equivalent is `pendoloClockworkDraw2d.ts`, its Canvas2D fallback for the WebGL path;
 * that fallback draws every element at full quality, so nothing is lost by following it.
 *
 * The desktop additionally drives the swing from a live audio analyser. This client has none, and
 * asking for RECORD_AUDIO to animate a decorative pendulum would be a bad trade, so the motion uses
 * the same constant the desktop falls back to when no analyser is present.
 */
@Composable
fun PendoloLyricCanvas(
    lines: List<Line>,
    positionSec: Double,
    theme: Theme,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    tuning: PendoloTuning = PendoloTuning(),
) {
    if (lines.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val primary = theme.primaryColor.toThemeColor()
    val accent = theme.accentColor.toThemeColor()
    val secondary = theme.secondaryColor.toThemeColor()
    val background = theme.backgroundColor.toThemeColor()

    // Size measured through onSizeChanged, not written during draw: mutating state inside a draw
    // pass schedules recomposition from the frame being drawn, which Compose reports as a violation.
    var canvasWidth by remember { mutableFloatStateOf(1080f) }
    var canvasHeight by remember { mutableFloatStateOf(700f) }

    // `.sp` multiplies by density * font scale, so a pixel size from the layout must be divided
    // once at this boundary or the text renders ~3x too large on the emulator.
    val density = LocalDensity.current
    val fontScale = LocalDensity.current.fontScale
    fun pxToTextUnit(px: Float): TextUnit = (px / (density.density * fontScale)).sp

    val baseRadius = min(canvasWidth, canvasHeight) * tuning.arcRadius
    val lyricRadiusOffset = min(canvasWidth, canvasHeight) * 0.06f
    val lyricRingRadius = baseRadius + lyricRadiusOffset

    // Text width available to the right of the wheel's rightmost point.
    val maxItemX = canvasWidth * tuning.wheelCenterX + baseRadius
    val availableTextWidth = max(
        140f,
        min(canvasWidth * 0.46f, canvasWidth - maxItemX - 48f),
    )

    val activeIndex = remember(lines, positionSec) { activeLineIndexFor(lines, positionSec) }

    // Measured block heights feed the wheel's angular spacing, so a two-line-tall block does not
    // overlap its neighbours. Only lines near the focal point are measured: the wheel emits at most
    // 17 of them and measuring the whole song every frame would be wasted work.
    val lineBlockHeights = remember(lines, canvasWidth, canvasHeight, activeIndex, tuning.activeScale) {
        val focalFontPx = 28f
        val measureWidth = availableTextWidth / tuning.activeScale
        lines.mapIndexed { index, line ->
            if (abs(index - activeIndex) > 10) {
                0f
            } else {
                val layout = textMeasurer.measure(
                    text = line.fullText,
                    style = TextStyle(
                        fontSize = pxToTextUnit(focalFontPx),
                        fontFamily = theme.resolveFontFamily(),
                        fontWeight = theme.fontWeightOrDefault(400),
                    ),
                    constraints = Constraints(maxWidth = measureWidth.toInt().coerceAtLeast(1)),
                )
                layout.size.height.toFloat() * tuning.activeScale
            }
        }
    }

    // Per-character timings for the sweep, built once per line rather than per frame.
    val sweepTimings = remember(lines) {
        lines.map { PendoloSweepFill.buildTimings(it) }
    }

    val motionProfile = remember(theme.animationIntensity) {
        PendoloMotionProfile.forIntensity(theme.animationIntensity)
    }

    // The escapement spring animates the wheel's rotation, and the layout is computed from the
    // *discrete* anchor. These are two different quantities and mixing them breaks the mode: the
    // layout only marks a line as focal when its target is a whole index, so feeding it the spring's
    // continuous value leaves every line unfocused and the sweep never runs. The desktop separates
    // them the same way — integer index into the layout, spring offset into the container rotation.
    var escapementValue by remember(lines) {
        mutableFloatStateOf(activeIndex.takeIf { it >= 0 }?.toFloat() ?: 0f)
    }
    var springVelocity by remember(lines) { mutableFloatStateOf(0f) }
    var clockworkState by remember(lines) { mutableStateOf(PendoloClockworkMotionState()) }
    var clockworkFrame by remember(lines) { mutableStateOf<PendoloClockworkFrame?>(null) }
    var lastFrameNanos by remember { mutableLongStateOf(0L) }

    // The wheel's rotation follows the spring, so it is state the draw pass reads.
    var wheelRotationDeg by remember(lines) { mutableFloatStateOf(0f) }
    var lineItems by remember(lines) { mutableStateOf<List<PendoloLineItem>>(emptyList()) }

    // Tracked across frames: a fallback anchor needs to know the last line playback was actually
    // inside, and a plain variable captured in the loop would reset on every recomposition.
    var lastValidLineIndex by remember(lines) { mutableIntStateOf(0) }
    var hasObservedLine by remember(lines) { mutableStateOf(false) }

    val latestPosition by rememberUpdatedState(positionSec)
    val latestActiveIndex by rememberUpdatedState(activeIndex)
    val latestPlaying by rememberUpdatedState(isPlaying)

    val angleStepRad = PendoloWheelGeometry.angleStepRad(tuning)

    // One frame loop for the whole mode: the gears and the escapement must be stepped exactly once
    // per frame even when nothing recomposes, and keying the effect on the position would restart
    // it four times a second (resetting dt, so the motion would stutter).
    LaunchedEffect(lines, canvasWidth, canvasHeight, theme.name, tuning) {
        while (true) {
            withFrameNanos { now ->
                val previous = lastFrameNanos
                lastFrameNanos = now
                val dt = if (previous == 0L) 1f / 60f else ((now - previous) / 1_000_000_000.0).toFloat()

                val time = latestPosition
                val currentActive = latestActiveIndex
                if (currentActive >= 0 && currentActive < lines.size) {
                    lastValidLineIndex = currentActive
                    hasObservedLine = true
                }

                val target = PendoloTimeline.fallbackAnchorIndex(
                    lines = lines,
                    currentLineIndex = currentActive,
                    lastValidLineIndex = lastValidLineIndex,
                    hasObservedLine = hasObservedLine,
                    currentTime = time,
                )

                // Spring toward the target. Stiffness and damping come from the tuning and the
                // theme's intensity, matching the desktop's `useSpring` configuration.
                val stiffness = 180f * tuning.tickSnappiness * motionProfile.escapementSpringMultiplier
                val damping = (18f + 4f / max(0.5f, tuning.tickSnappiness)) *
                    motionProfile.escapementDampingMultiplier
                val clampedDt = dt.coerceIn(0f, 0.05f)
                // `target` arrives as a Double because a fractional anchor ("between lines") is a
                // supported state; the spring itself works in Float to stay cheap per frame.
                val targetFloat = target.toFloat()
                val acceleration = (targetFloat - escapementValue) * stiffness - springVelocity * damping
                springVelocity += acceleration * clampedDt
                escapementValue += springVelocity * clampedDt

                // The rendered rotation is the spring's offset from the target: at rest it is zero,
                // and it swings while the spring is still travelling.
                wheelRotationDeg = -(escapementValue - targetFloat) * angleStepRad * (180f / PI.toFloat())

                // The layout takes the *anchor*, not the spring's position. `layout` only marks a
                // line focal when its target is a whole index, so passing the continuous spring
                // value leaves every line unfocused and the karaoke sweep never runs. The spring's
                // motion is expressed entirely by `wheelRotationDeg` above — which is how the
                // desktop separates the two: integer index into the layout, spring offset into the
                // container's rotation.
                lineItems = PendoloWheelGeometry.layout(
                    lines = lines,
                    targetLineIndex = target,
                    escapementAngleOffsetRad = 0f,
                    viewportWidth = canvasWidth,
                    viewportHeight = canvasHeight,
                    tuning = tuning,
                    radiusOffsetPx = lyricRadiusOffset,
                    lineBlockHeightsPx = lineBlockHeights,
                )

                val (nextState, motionFrame) = PendoloClockworkMotion.step(
                    state = clockworkState,
                    dt = dt,
                    rawBass = PendoloClockworkMotion.DEFAULT_BASS,
                    // A paused player freezes the mechanism. The desktop clamps playback-driven
                    // motion the same way; a clock that keeps ticking while the song is stopped
                    // reads as a bug rather than as ambience.
                    paused = !latestPlaying,
                    profile = motionProfile,
                )
                clockworkState = nextState

                val centerX = canvasWidth * tuning.wheelCenterX
                val centerY = canvasHeight * tuning.wheelCenterY
                clockworkFrame = PendoloClockworkFrame(
                    centerX = centerX,
                    centerY = centerY,
                    baseRadius = baseRadius,
                    // The gear train follows the same spring as the lyrics, so the mechanism and the
                    // text turn together instead of drifting apart.
                    escapementAngle = escapementValue * angleStepRad,
                    secondGearAngle = motionFrame.secondGearAngle,
                    bassOscillation = motionFrame.bassOscillation,
                    phase = motionFrame.phase,
                    palette = PendoloClockworkScene.buildPalette(
                        primaryArgb = primary.toArgbPacked(),
                        accentArgb = accent.toArgbPacked(),
                        decor = tuning.showGearDecor,
                    ),
                    anchors = PendoloClockworkScene.anchors(
                        centerX = centerX,
                        centerY = centerY,
                        baseRadius = baseRadius,
                        lyricRingRadius = lyricRingRadius,
                    ),
                    accentArgb = accent.toArgbPacked(),
                    decor = tuning.showGearDecor,
                    showCenterGradient = tuning.showCenterGradient,
                )
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
        drawRect(color = background)

        clockworkFrame?.let { frame ->
            drawClockwork(PendoloClockworkBuilder.build(frame))
        }

        val centerX = canvasWidth * tuning.wheelCenterX
        val centerY = canvasHeight * tuning.wheelCenterY

        for (item in lineItems) {
            val opacity = PendoloTimeline.rotatingLineOpacity(
                baseAngleDeg = item.angleDeg,
                wheelRotationDeg = wheelRotationDeg,
                baseOpacity = item.alpha,
            )
            if (opacity <= 0.01f) continue

            val isFocal = item.isActive
            val fontPx = if (isFocal) 28f else 22f
            val maxTextWidth = availableTextWidth / max(item.scale, 0.01f)

            val style = TextStyle(
                color = if (isFocal) primary else primary.copy(alpha = 0.75f * opacity),
                fontSize = pxToTextUnit(fontPx),
                fontFamily = theme.resolveFontFamily(),
                fontWeight = theme.fontWeightOrDefault(400),
            )
            val layout = textMeasurer.measure(
                text = item.line.fullText,
                style = style,
                constraints = Constraints(maxWidth = maxTextWidth.toInt().coerceAtLeast(1)),
            )

            // The desktop nests three rotations: the whole wheel rail rotates by `wheelRotationDeg`
            // about the hub, the text element counter-rotates by -0.65 of that, and each line adds
            // its own `angleDeg * 0.35`. Composed, the glyphs end up at 0.35 * (wheel + angle), and
            // the line's anchor travels with the wheel. Doing the three as one sum instead would
            // tilt every line by its full arc angle and leave the positions frozen while the wheel
            // turned.
            val anchor = rotateAbout(item.x, item.y, centerX, centerY, wheelRotationDeg)
            val totalRotation = 0.35f * (wheelRotationDeg + item.angleDeg)
            val topLeft = Offset(anchor.x, anchor.y - layout.size.height / 2f)

            // Halo first: drawn after the glyphs it would wash them out.
            if (isFocal && item.line.isChorus) {
                val haloRadius = max(layout.size.width, layout.size.height) * 0.7f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.14f * opacity), accent.copy(alpha = 0f)),
                        center = anchor,
                        radius = haloRadius,
                    ),
                    radius = haloRadius,
                    center = anchor,
                )
            }

            rotate(degrees = totalRotation, pivot = anchor) {
                if (isFocal) {
                    drawSweptLine(
                        line = item.line,
                        layout = layout,
                        topLeft = topLeft,
                        timings = sweepTimings.getOrElse(item.index) { emptyList() },
                        positionSec = positionSec,
                        opacity = opacity,
                        primary = primary,
                        accent = accent,
                    )
                } else {
                    drawText(layout, topLeft = topLeft)
                }
            }
        }
    }
}

/**
 * Turns the display list into draw calls.
 *
 * Ordering comes from the builder, not from this function: the desktop's layering (rings, then
 * gears, then jewels) is part of how the mechanism reads, and reordering here would break it.
 */
private fun DrawScope.drawClockwork(drawing: PendoloClockworkDrawing) {
    drawing.gradient?.let { gradient ->
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(gradient.centerArgb),
                    Color(gradient.midArgb),
                    Color(gradient.outerArgb),
                ),
                center = Offset(gradient.cx, gradient.cy),
                radius = max(gradient.radius, 1f),
            ),
            radius = gradient.radius,
            center = Offset(gradient.cx, gradient.cy),
        )
    }

    for (item in drawing.items) {
        when (item) {
            is PendoloDrawItem.Ring -> drawCircle(
                color = Color(item.value.strokeArgb),
                radius = item.value.radius,
                center = Offset(item.value.cx, item.value.cy),
                style = Stroke(width = item.value.lineWidth),
            )

            is PendoloDrawItem.Jewel -> {
                drawCircle(
                    color = Color(item.value.outerArgb),
                    radius = item.value.outerRadius,
                    center = Offset(item.value.cx, item.value.cy),
                    style = Stroke(width = 1.2f),
                )
                drawCircle(
                    color = Color(item.value.innerArgb),
                    radius = item.value.innerRadius,
                    center = Offset(item.value.cx, item.value.cy),
                    style = Stroke(width = 0.8f),
                )
            }

            is PendoloDrawItem.Gear -> {
                val outline = PendoloClockworkBuilder.gearOutline(item.value)
                if (outline.isNotEmpty()) {
                    val path = Path().apply {
                        moveTo(outline.first().first, outline.first().second)
                        for (index in 1 until outline.size) {
                            lineTo(outline[index].first, outline[index].second)
                        }
                        close()
                    }
                    item.value.fillArgb?.let { fill -> drawPath(path, color = Color(fill)) }
                    drawPath(
                        path = path,
                        color = Color(item.value.strokeArgb),
                        style = Stroke(width = item.value.lineWidth),
                    )
                }
            }

            is PendoloDrawItem.Wheel -> drawSpokedWheel(item.value)

            is PendoloDrawItem.Ray -> drawLine(
                color = Color(item.value.strokeArgb),
                start = Offset(
                    item.value.cx + item.value.innerRadius * cos(item.value.angleRad),
                    item.value.cy + item.value.innerRadius * sin(item.value.angleRad),
                ),
                end = Offset(
                    item.value.cx + item.value.outerRadius * cos(item.value.angleRad),
                    item.value.cy + item.value.outerRadius * sin(item.value.angleRad),
                ),
                strokeWidth = item.value.lineWidth,
            )

            is PendoloDrawItem.Segment -> drawLine(
                color = Color(item.value.strokeArgb),
                start = Offset(item.value.startX, item.value.startY),
                end = Offset(item.value.endX, item.value.endY),
                strokeWidth = item.value.lineWidth,
            )

            is PendoloDrawItem.Triangle -> {
                val path = Path().apply {
                    moveTo(item.value.ax, item.value.ay)
                    lineTo(item.value.bx, item.value.by)
                    lineTo(item.value.cx, item.value.cy)
                    close()
                }
                drawPath(path, color = Color(item.value.fillArgb))
            }

            is PendoloDrawItem.Hairspring -> {
                val points = PendoloClockworkBuilder.hairspringPoints(item.value)
                if (points.size > 1) {
                    val path = Path().apply {
                        moveTo(points.first().first, points.first().second)
                        for (index in 1 until points.size) {
                            lineTo(points[index].first, points[index].second)
                        }
                    }
                    drawPath(
                        path = path,
                        color = Color(item.value.strokeArgb),
                        style = Stroke(width = item.value.lineWidth),
                    )
                }
            }

            is PendoloDrawItem.Geneva -> {
                val geneva = item.value
                val clip = Path().apply {
                    addOval(
                        Rect(center = Offset(geneva.cx, geneva.cy), radius = geneva.clipRadius),
                    )
                }
                clipPath(clip) {
                    rotate(
                        degrees = geneva.rotationRad * (180f / PI.toFloat()),
                        pivot = Offset(geneva.cx, geneva.cy),
                    ) {
                        val span = geneva.spanRadius * 1.6f
                        val step = span / 10f
                        for (index in 1..geneva.stripes) {
                            val yOffset = -span * 0.5f + index * step
                            drawLine(
                                color = Color(if (index % 2 == 0) geneva.strokeEvenArgb else geneva.strokeOddArgb),
                                start = Offset(geneva.cx - geneva.spanRadius, geneva.cy + yOffset),
                                end = Offset(geneva.cx + geneva.spanRadius, geneva.cy + yOffset),
                                strokeWidth = geneva.lineWidth,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSpokedWheel(
    wheel: top.izuna.foliamajor.lyrics.layout.PendoloSpokedWheel,
) {
    val stroke = Color(wheel.strokeArgb)

    drawCircle(stroke, wheel.hubRadius, Offset(wheel.cx, wheel.cy), style = Stroke(wheel.lineWidth))
    drawCircle(stroke, wheel.rimRadius, Offset(wheel.cx, wheel.cy), style = Stroke(wheel.lineWidth))

    val angleStep = 2f * PI.toFloat() / wheel.spokes
    for (index in 0 until wheel.spokes) {
        val angle = index * angleStep + wheel.rotationRad
        drawLine(
            color = stroke,
            start = Offset(wheel.cx + wheel.hubRadius * cos(angle), wheel.cy + wheel.hubRadius * sin(angle)),
            end = Offset(wheel.cx + wheel.rimRadius * cos(angle), wheel.cy + wheel.rimRadius * sin(angle)),
            strokeWidth = wheel.lineWidth,
        )
    }

    // Lightening holes, offset half a step so they sit between the spokes.
    val midRadius = (wheel.hubRadius + wheel.rimRadius) * 0.5f
    val holeRadius = (wheel.rimRadius - wheel.hubRadius) * 0.22f
    for (index in 0 until wheel.spokes) {
        val angle = index * angleStep + angleStep * 0.5f + wheel.rotationRad
        drawCircle(
            color = stroke,
            radius = holeRadius,
            center = Offset(wheel.cx + midRadius * cos(angle), wheel.cy + midRadius * sin(angle)),
            style = Stroke(wheel.lineWidth),
        )
    }
}

/**
 * Draws one line with its sung prefix overdrawn in the accent colour.
 *
 * The dim base is drawn first and the accent version is clipped to the measured fill width, which is
 * the same two-layer trick the desktop uses with a CSS mask. Clipping rather than drawing a
 * substring keeps the wrapping identical between the two layers — a substring would re-wrap.
 */
private fun DrawScope.drawSweptLine(
    line: Line,
    layout: TextLayoutResult,
    topLeft: Offset,
    timings: List<PendoloGraphemeTiming>,
    positionSec: Double,
    opacity: Float,
    primary: Color,
    accent: Color,
) {
    drawText(layout, topLeft = topLeft, color = primary.copy(alpha = 0.52f * opacity))

    if (!PendoloSweepFill.visible(timings, line.endTime, positionSec)) return

    // Cumulative offsets are uniform here because Compose does not expose per-grapheme advances;
    // the proportional approximation keeps the fill monotonic, which is what the eye tracks.
    val graphemeCount = timings.size
    if (graphemeCount == 0) return

    val fullWidth = layout.size.width.toFloat()
    val perGrapheme = fullWidth / graphemeCount
    val offsets = List(graphemeCount + 1) { it * perGrapheme }

    val fillWidth = PendoloSweepFill.widthAt(timings, offsets, line.endTime, positionSec, fullWidth)
    if (fillWidth <= 0f) return

    clipRect(
        left = topLeft.x,
        top = topLeft.y,
        right = topLeft.x + fillWidth,
        bottom = topLeft.y + layout.size.height,
    ) {
        drawText(layout, topLeft = topLeft, color = accent.copy(alpha = opacity))
    }
}

/** Active line index, held through its render-end so a line does not vanish the instant it ends. */
private fun activeLineIndexFor(lines: List<Line>, positionSec: Double): Int {
    for (index in lines.indices.reversed()) {
        val line = lines[index]
        if (positionSec >= line.startTime && positionSec <= getLineRenderEndTime(line)) return index
    }
    return -1
}

/**
 * Rotates a point about a pivot by [degrees], clockwise in canvas space.
 *
 * Used to carry a line's anchor around the hub as the wheel turns. Compose's `rotate` transforms the
 * whole draw scope, which would leave the anchor itself fixed; computing the position explicitly is
 * what makes the text travel around the wheel instead of spinning in place.
 */
private fun rotateAbout(x: Float, y: Float, pivotX: Float, pivotY: Float, degrees: Float): Offset {
    val radians = degrees * PI.toFloat() / 180f
    val cosA = cos(radians)
    val sinA = sin(radians)
    val dx = x - pivotX
    val dy = y - pivotY
    return Offset(
        x = pivotX + dx * cosA - dy * sinA,
        y = pivotY + dx * sinA + dy * cosA,
    )
}

/** Packs a Compose [Color] into the ARGB layout the palette builder works in. */
private fun Color.toArgbPacked(): Int {
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
