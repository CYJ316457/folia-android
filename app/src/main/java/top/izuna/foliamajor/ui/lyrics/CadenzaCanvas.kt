package top.izuna.foliamajor.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.layout.CadenzaPlanner
import top.izuna.foliamajor.lyrics.layout.CadenzaOptions
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.theme.fontWeightOrDefault
import top.izuna.foliamajor.theme.resolveFontFamily
import top.izuna.foliamajor.theme.resolveWordColor
import top.izuna.foliamajor.theme.toThemeColor
import kotlin.math.sin

/**
 * `cadenza`: every word floats at its own free position, drifting outward once sung.
 *
 * The placement comes from [CadenzaLayoutPlanner]; this composable adds the per-state motion. The
 * three states are the same as `classic` — waiting, active, passed — but each word also has its own
 * position, rotation and drift, so the line assembles and then disperses rather than sitting still.
 */
@Composable
fun CadenzaLyricCanvas(
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

    val layout = remember(line.startTime, line.fullText, theme.animationIntensity, fontSizeSp) {
        CadenzaPlanner.plan(
            line = line,
            theme = theme,
            options = CadenzaOptions(
                maxWidth = 900f,
                maxHeight = 620f,
                fontPx = fontSizeSp,
                lineHeight = fontSizeSp * 1.45f,
            ),
        )
    }

    val activeWord = remember(layout, positionSec) {
        CadenzaPlanner.activeWordIndex(layout, positionSec)
    }

    Canvas(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (layout.placements.isEmpty()) return@Canvas

        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // The composition is planned in a nominal coordinate space; scale it to the real canvas so
        // the words use the available room instead of clustering in the middle of a wide screen.
        val scaleX = size.width / 900f
        val scaleY = size.height / 620f
        val fit = minOf(scaleX, scaleY).coerceIn(0.55f, 1.25f)

        layout.placements.forEach { placement ->
            val isPassed = positionSec > placement.endTime
            val isActive = placement.wordIndex == activeWord

            val baseStyle = TextStyle(
                color = when {
                    isActive -> resolveWordColor(placement.text, theme.wordColors, accent)
                    isPassed -> primary.copy(alpha = 0.5f)
                    else -> secondary.copy(alpha = 0.32f)
                },
                fontSize = (fontSizeSp * (if (placement.isHero) 1.28f else 0.86f)).sp,
                fontFamily = theme.resolveFontFamily(),
                fontWeight = theme.fontWeightOrDefault(if (placement.isHero || isActive) 700 else 500),
            )

            val measured = measurer.measure(
                text = placement.text,
                style = baseStyle,
                constraints = Constraints(),
            )

            // Passed words drift outward; the drift grows with time since the word finished so the
            // line visibly disperses instead of freezing.
            val driftElapsed = if (isPassed) {
                ((positionSec - placement.endTime) / 1.6).coerceIn(0.0, 1.0).toFloat()
            } else {
                0f
            }

            val idleFloat = if (isActive) {
                sin((positionSec * 2.4).toFloat()) * 2f
            } else {
                0f
            }

            val x = centerX + placement.x * fit + placement.passedDriftX * driftElapsed
            val y = centerY + placement.y * fit + placement.passedDriftY * driftElapsed + idleFloat

            val wordScale = when {
                isActive -> 1.06f
                placement.isHero -> placement.scale
                else -> 1f
            }

            rotate(placement.rotationDeg, Offset(x, y)) {
                scale(wordScale, wordScale, Offset(x, y)) {
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(x - measured.size.width / 2f, y - measured.size.height / 2f),
                    )
                }
            }
        }
    }
}
