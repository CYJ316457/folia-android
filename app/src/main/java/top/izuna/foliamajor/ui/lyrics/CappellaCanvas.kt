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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.layout.BubbleMetrics
import top.izuna.foliamajor.lyrics.layout.BubbleSize
import top.izuna.foliamajor.lyrics.layout.BubbleTextMeasurer
import top.izuna.foliamajor.lyrics.layout.CappellaBubble
import top.izuna.foliamajor.lyrics.layout.CappellaIntensity
import top.izuna.foliamajor.lyrics.layout.CappellaMotion
import top.izuna.foliamajor.lyrics.layout.CappellaTranscript
import top.izuna.foliamajor.lyrics.layout.ChatMessage
import top.izuna.foliamajor.lyrics.layout.ChatSide
import top.izuna.foliamajor.lyrics.layout.FumeMath
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.fontWeightOrDefault
import top.izuna.foliamajor.theme.resolveFontFamily
import top.izuna.foliamajor.theme.toThemeColor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `cappella`: the lyrics staged as a chat conversation.
 *
 * Each line becomes a message bubble from a sender on the left or the right, with a stable avatar and
 * a chat timestamp. The current line is larger and brighter, earlier lines recede, and characters
 * fade in as they are sung. The bubble itself widens in step with the text — that widening is the
 * signature of the mode: a real chat bubble grows as the message is typed.
 *
 * The avatars are a 3×3 sprite sheet in the desktop app. There is no avatar art in this port, so each
 * sender gets a generated monogram: the avatar cell index selects a hue, and the sender's letter is
 * drawn in it. That keeps the sides distinguishable without shipping images, and it degrades
 * gracefully when an AI theme changes the palette.
 */
@Composable
fun CappellaLyricCanvas(
    lines: List<Line>,
    positionSec: Double,
    theme: Theme,
    title: String,
    modifier: Modifier = Modifier,
    baseFontSizeSp: Float = 16f,
) {
    if (lines.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val primary = theme.primaryColor.toThemeColor()
    val accent = theme.accentColor.toThemeColor()
    val secondary = theme.secondaryColor.toThemeColor()
    val background = theme.backgroundColor.toThemeColor()

    var canvasWidth by remember { mutableFloatStateOf(1080f) }
    var canvasHeight by remember { mutableFloatStateOf(800f) }

    fun pxToTextUnit(px: Float): TextUnit = (px / (density.density * fontScale)).sp

    val intensity = remember(theme.animationIntensity) {
        CappellaIntensity.forIntensity(
            when (theme.animationIntensity) {
                Theme.AnimationIntensity.CALM -> "calm"
                Theme.AnimationIntensity.CHAOTIC -> "chaotic"
                Theme.AnimationIntensity.NORMAL -> "normal"
            }
        )
    }
    val motion = intensity.motion

    val transcript = remember(lines, title, intensity) {
        CappellaTranscript.build(lines, title, intensity)
    }

    // Base font size in pixels: the chat is scaled from this by the per-message multipliers.
    val baseFontPx = baseFontSizeSp * density.density

    // Bubble metrics are expensive (one text measurement per character prefix) and only depend on
    // the line, the font size and the available width, so they are cached for the life of the screen.
    val metricsCache = remember { mutableMapOf<String, BubbleMetrics>() }

    val bubbleMeasurer = remember(textMeasurer, theme) {
        val style = TextStyle(
            fontFamily = theme.resolveFontFamily(),
            fontWeight = theme.fontWeightOrDefault(400),
        )
        BubbleTextMeasurer { text, fontSize, lineHeightPx, maxTextWidth ->
            if (text.isEmpty()) return@BubbleTextMeasurer 0f to 1
            val layout = textMeasurer.measure(
                text = text,
                style = style.copy(
                    fontSize = pxToTextUnit(fontSize),
                    lineHeight = pxToTextUnit(lineHeightPx),
                ),
                constraints = androidx.compose.ui.unit.Constraints(
                    maxWidth = maxTextWidth.roundToInt().coerceAtLeast(1),
                ),
            )
            layout.size.width.toFloat() to layout.lineCount.coerceAtLeast(1)
        }
    }

    // Per-frame state: how far the scroll has settled, so the chat list slides up smoothly when a
    // new message arrives instead of jumping.
    var scrollOffset by remember { mutableFloatStateOf(0f) }
    var scrollTarget by remember { mutableFloatStateOf(0f) }
    var lastFrameNanos by remember { mutableLongStateOf(0L) }
    val latestPosition by rememberUpdatedState(positionSec)

    LaunchedEffect(transcript) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val previous = lastFrameNanos
                lastFrameNanos = now
                val dt = if (previous == 0L) {
                    1f / 60f
                } else {
                    ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                }
                // Critically-damped approach; 14 is fast enough to read as motion, slow enough that
                // the list does not snap when several messages arrive at once.
                scrollOffset += (scrollTarget - scrollOffset) * (1f - kotlin.math.exp(-14f * dt))
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                if (it.width > 0 && it.height > 0) {
                    canvasWidth = it.width.toFloat()
                    canvasHeight = it.height.toFloat()
                }
            },
    ) {
        drawRect(color = background)

        val horizontalPadding = 16f * density.density
        val avatarSize = 34f * density.density
        val avatarGap = 10f * density.density
        val bubbleGap = 14f * density.density
        val maxTextWidth = max(size.width - horizontalPadding * 2 - avatarSize - avatarGap - 120f, 80f)

        val currentLineIndex = CappellaTranscript.visibleLineIndexAt(lines, positionSec)
        val currentTime = positionSec

        // Build the visible slice. Heights come from the real bubble table where it already exists
        // and from the estimate otherwise; the window only needs to be close enough that the floor of
        // the list sits off-screen.
        fun metricsFor(message: ChatMessage.Lyric): BubbleMetrics {
            val isActive = message.lineIndex == currentLineIndex
            val fontSize = baseFontPx * (if (isActive) motion.activeFontMultiplier else motion.inactiveFontMultiplier)
            val lineHeight = fontSize * 1.45f
            val paddingX = if (isActive) motion.activePaddingX else motion.inactivePaddingX
            val paddingY = if (isActive) motion.activePaddingY else motion.inactivePaddingY
            val key = "${message.id}|$fontSize|${paddingX}|$paddingY|$maxTextWidth"
            return metricsCache.getOrPut(key) {
                CappellaBubble.metrics(
                    line = message.line,
                    fontSize = fontSize,
                    lineHeightPx = lineHeight,
                    maxTextWidth = maxTextWidth,
                    paddingX = paddingX * density.density,
                    paddingY = paddingY * density.density,
                    measurer = bubbleMeasurer,
                )
            }
        }

        val visible = CappellaTranscript.visibleMessages(
            messages = transcript,
            currentLineIndex = currentLineIndex,
            heightBudget = size.height - 40f,
            estimatedHeight = { message ->
                when (message) {
                    is ChatMessage.Title -> {
                        val fontSize = baseFontPx
                        val lineHeight = fontSize * 1.45f
                        CappellaBubble.estimatedRowHeight(
                            isActive = false,
                            motion = motion,
                            fontSize = fontSize,
                            lineHeightPx = lineHeight,
                            bubbleHeight = lineHeight + 24f * density.density,
                        )
                    }
                    is ChatMessage.Lyric -> {
                        val isActive = message.lineIndex == currentLineIndex
                        val fontSize = baseFontPx *
                            (if (isActive) motion.activeFontMultiplier else motion.inactiveFontMultiplier)
                        val metrics = metricsFor(message)
                        val size0 = metrics.sizes.last()
                        CappellaBubble.estimatedRowHeight(
                            isActive = isActive,
                            motion = motion,
                            fontSize = fontSize,
                            lineHeightPx = fontSize * 1.45f,
                            bubbleHeight = size0.height,
                        )
                    }
                }
            },
        )

        // Lay out bottom-up: the newest message sits just above the transport, older ones stack above.
        // The scroll offset animates toward zero as new messages push the stack up.
        // The lyric box overlaps the transport row visually, so the chat stack starts above it
        // rather than at the box's own bottom edge.
        val bottomInset = 8f * density.density
        var cursorY = size.height - bottomInset + scrollOffset

        for (message in visible.asReversed()) {
            val isActive = message is ChatMessage.Lyric && message.lineIndex == currentLineIndex
            val isPassed = message is ChatMessage.Lyric && message.lineIndex < currentLineIndex

            when (message) {
                is ChatMessage.Title -> {
                    val fontSize = baseFontPx
                    val lineHeight = fontSize * 1.45f
                    val paddingX = motion.inactivePaddingX * density.density
                    val paddingY = motion.inactivePaddingY * density.density
                    val bubble = CappellaBubble.sizeFor(
                        text = message.text,
                        fontSize = fontSize,
                        lineHeightPx = lineHeight,
                        maxTextWidth = maxTextWidth,
                        paddingX = paddingX,
                        paddingY = paddingY,
                        measurer = bubbleMeasurer,
                    )
                    cursorY -= bubble.height + bubbleGap
                    drawChatRow(
                        text = message.text,
                        side = message.sender.side,
                        avatarIndex = message.sender.avatarIndex,
                        bubble = bubble,
                        bottomY = cursorY,
                        innerWidth = size.width,
                        horizontalPadding = horizontalPadding,
                        avatarSize = avatarSize,
                        avatarGap = avatarGap,
                        fontSizePx = fontSize,
                        lineHeightPx = lineHeight,
                        paddingX = paddingX,
                        paddingY = paddingY,
                        textColor = primary,
                        bubbleColor = secondary.copy(alpha = 0.16f),
                        borderColor = secondary.copy(alpha = 0.22f),
                        alpha = 0.85f,
                        scale = 1f,
                        visibleCharacters = null,
                        metrics = null,
                        timestamp = null,
                        measurer = textMeasurer,
                        style = TextStyle(
                            fontFamily = theme.resolveFontFamily(),
                            fontWeight = theme.fontWeightOrDefault(600),
                        ),
                        pxToTextUnit = { px -> pxToTextUnit(px) },
                        accent = accent,
                    )
                }

                is ChatMessage.Lyric -> {
                    val metrics = metricsFor(message)
                    val fontSize = baseFontPx *
                        (if (isActive) motion.activeFontMultiplier else motion.inactiveFontMultiplier)
                    val lineHeight = fontSize * 1.45f
                    val paddingX = (if (isActive) motion.activePaddingX else motion.inactivePaddingX) *
                        density.density
                    val paddingY = (if (isActive) motion.activePaddingY else motion.inactivePaddingY) *
                        density.density

                    val bubbleNow = CappellaBubble.sizeAtTime(metrics, currentTime)
                    val visibleChars = CappellaBubble.visibleCharactersAt(metrics, currentTime)

                    // The tallest the row will get: reserving the final height stops the whole stack
                    // from shifting upward as the bubble grows character by character.
                    val bubbleFinal = metrics.sizes.last()
                    val rowHeight = max(bubbleNow.height, bubbleFinal.height)

                    cursorY -= rowHeight + bubbleGap

                    val scale = when {
                        isActive -> motion.activeScale
                        isPassed -> motion.passedScale
                        else -> 1f
                    }
                    val alpha = if (isPassed) motion.passedOpacity else 1f
                    val timestamp = if (currentTime >= metrics.timestampReadyTime) {
                        CappellaTranscript.formatTimestamp(message.line.startTime)
                    } else {
                        null
                    }

                    drawChatRow(
                        text = message.line.fullText,
                        side = message.sender.side,
                        avatarIndex = message.sender.avatarIndex,
                        bubble = BubbleSize(bubbleNow.width, rowHeight),
                        bottomY = cursorY,
                        innerWidth = size.width,
                        horizontalPadding = horizontalPadding,
                        avatarSize = avatarSize,
                        avatarGap = avatarGap,
                        fontSizePx = fontSize,
                        lineHeightPx = lineHeight,
                        paddingX = paddingX,
                        paddingY = paddingY,
                        textColor = primary,
                        bubbleColor = if (message.sender.side == ChatSide.RIGHT) {
                            accent.copy(alpha = 0.20f)
                        } else {
                            secondary.copy(alpha = 0.14f)
                        },
                        borderColor = if (message.sender.side == ChatSide.RIGHT) {
                            accent.copy(alpha = 0.30f)
                        } else {
                            secondary.copy(alpha = 0.20f)
                        },
                        alpha = alpha,
                        scale = scale,
                        visibleCharacters = visibleChars,
                        metrics = metrics,
                        timestamp = timestamp,
                        measurer = textMeasurer,
                        style = TextStyle(
                            fontFamily = theme.resolveFontFamily(),
                            fontWeight = theme.fontWeightOrDefault(400),
                        ),
                        pxToTextUnit = { px -> pxToTextUnit(px) },
                        accent = accent,
                    )
                }
            }
        }

        scrollTarget = 0f
    }
}

/**
 * Draws one chat row: avatar plus bubble, mirrored for the right side.
 *
 * [visibleCharacters] is null for the title (fully printed) and the reveal count for a lyric, which
 * is what lets the bubble draw only the characters that have appeared.
 */
private fun DrawScope.drawChatRow(
    text: String,
    side: ChatSide,
    avatarIndex: Int,
    bubble: BubbleSize,
    bottomY: Float,
    innerWidth: Float,
    horizontalPadding: Float,
    avatarSize: Float,
    avatarGap: Float,
    fontSizePx: Float,
    lineHeightPx: Float,
    paddingX: Float,
    paddingY: Float,
    textColor: Color,
    bubbleColor: Color,
    borderColor: Color,
    alpha: Float,
    scale: Float,
    visibleCharacters: Int?,
    metrics: BubbleMetrics?,
    timestamp: String?,
    measurer: TextMeasurer,
    style: TextStyle,
    pxToTextUnit: (Float) -> TextUnit,
    accent: Color,
) {
    val isRight = side == ChatSide.RIGHT
    val bubbleHeight = bubble.height
    val top = bottomY

    // Scale from the bubble's anchored corner so it appears to grow out of the avatar rather than
    // from its own centre.
    val bubbleLeft = if (isRight) {
        innerWidth - horizontalPadding - avatarSize - avatarGap - bubble.width
    } else {
        horizontalPadding + avatarSize + avatarGap
    }
    val avatarLeft = if (isRight) innerWidth - horizontalPadding - avatarSize else horizontalPadding
    val avatarTop = top + bubbleHeight - avatarSize

    drawMonogramAvatar(
        left = avatarLeft,
        top = avatarTop,
        size = avatarSize,
        avatarIndex = avatarIndex,
        accent = accent,
        alpha = alpha,
        measurer = measurer,
        pxToTextUnit = pxToTextUnit,
    )

    translate(left = bubbleLeft, top = top) {
        // The bubble body. A slight corner radius asymmetry (the corner nearest the avatar is
        // tighter) is what makes a rectangle read as a chat bubble.
        drawRoundRect(
            color = bubbleColor.copy(alpha = bubbleColor.alpha * alpha),
            size = Size(bubble.width, bubbleHeight),
            cornerRadius = CornerRadius(14f, 14f),
        )
        drawRoundRect(
            color = borderColor.copy(alpha = borderColor.alpha * alpha),
            size = Size(bubble.width, bubbleHeight),
            cornerRadius = CornerRadius(14f, 14f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
        )

        val shown = if (visibleCharacters == null) text else {
            if (visibleCharacters <= 0) "" else {
                // Character-level reveal: take the prefix rather than animating each glyph, because
                // one draw call per message is what keeps a long transcript affordable.
                val characters = metrics?.characters ?: FumeMath.splitGraphemes(text)
                characters.take(visibleCharacters).joinToString("")
            }
        }

        if (shown.isNotEmpty()) {
            val layout = measurer.measure(
                text = shown,
                style = style.copy(
                    color = textColor.copy(alpha = alpha),
                    fontSize = pxToTextUnit(fontSizePx),
                    lineHeight = pxToTextUnit(lineHeightPx),
                ),
                constraints = androidx.compose.ui.unit.Constraints(
                    maxWidth = (bubble.width - paddingX * 2).roundToInt().coerceAtLeast(1),
                ),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(paddingX, paddingY),
            )
        }

        // Timestamp: bottom-right inside the bubble, small and dim so it reads as metadata.
        if (timestamp != null) {
            val layout = measurer.measure(
                text = timestamp,
                style = style.copy(
                    color = textColor.copy(alpha = 0.45f * alpha),
                    fontSize = pxToTextUnit(fontSizePx * 0.62f),
                ),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    bubble.width - paddingX - layout.size.width,
                    bubble.height - paddingY * 0.2f - layout.size.height,
                ),
            )
        }
    }
}

/**
 * A generated avatar: a tinted disc with a letter.
 *
 * The desktop app uses a 3×3 portrait sprite sheet. There is no equivalent art here, and shipping
 * invented portraits would look worse than an honest monogram; the cell index still controls the hue
 * so two senders read as two people.
 */
private fun DrawScope.drawMonogramAvatar(
    left: Float,
    top: Float,
    size: Float,
    avatarIndex: Int,
    accent: Color,
    alpha: Float,
    measurer: TextMeasurer,
    pxToTextUnit: (Float) -> TextUnit,
) {
    val hueStep = (avatarIndex % 9).toFloat() / 8f
    val tone = 0.35f + hueStep * 0.45f
    val color = mixChatColor(accent, Color.White, tone)

    drawCircle(
        color = color.copy(alpha = 0.35f * alpha),
        radius = size / 2f,
        center = Offset(left + size / 2f, top + size / 2f),
    )
    drawCircle(
        color = color.copy(alpha = 0.55f * alpha),
        radius = size / 2f,
        center = Offset(left + size / 2f, top + size / 2f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
    )

    // A letter, not a portrait. Two senders have to be tellable apart at a glance, and a plain disc
    // is not: the letter is what makes the left column read as specific people.
    val label = ('A' + (avatarIndex % 9)).toString()
    val layout = measurer.measure(
        text = label,
        style = TextStyle(
            color = color.copy(alpha = 0.95f * alpha),
            fontSize = pxToTextUnit(size * 0.46f),
        ),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            left + (size - layout.size.width) / 2f,
            top + (size - layout.size.height) / 2f,
        ),
    )
}

/**
 * Linear interpolation in sRGB, matching the desktop's `mixColors`.
 *
 * Named distinctly from the private `lerpColor` in `LyricCanvas.kt`: both are top-level functions in
 * this package, and an overload on the same signature shadows rather than coexists.
 */
internal fun mixChatColor(from: Color, to: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t,
    )
}
