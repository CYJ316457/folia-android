package top.izuna.foliamajor.lyrics.layout

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * One completed frame of the clockwork, ready to draw.
 *
 * Separating this from the drawing keeps the arithmetic pure: the renderer walks this structure and
 * emits draw calls, and a test can assert on the structure without a canvas.
 */
data class PendoloClockworkFrame(
    val centerX: Float,
    val centerY: Float,
    val baseRadius: Float,
    val escapementAngle: Float,
    val secondGearAngle: Float,
    val bassOscillation: Float,
    val phase: Float,
    val palette: PendoloClockworkPalette,
    val anchors: PendoloClockworkAnchors,
    val accentArgb: Int,
    val decor: PendoloGearDecor,
    val showCenterGradient: Boolean,
)

/** A wireframe gear: teeth around a circle, with an optional spoked face and fill. */
data class PendoloGear(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val teeth: Int,
    val toothDepth: Float,
    val rotationRad: Float,
    val strokeArgb: Int,
    val lineWidth: Float,
    val fillArgb: Int? = null,
)

/** A spoked wheel: hub, rim, radial spokes and lightening holes. */
data class PendoloSpokedWheel(
    val cx: Float,
    val cy: Float,
    val hubRadius: Float,
    val rimRadius: Float,
    val spokes: Int,
    val rotationRad: Float,
    val strokeArgb: Int,
    val lineWidth: Float,
)

/** A double-ring jewel bearing. */
data class PendoloJewel(
    val cx: Float,
    val cy: Float,
    val outerRadius: Float,
    val innerRadius: Float,
    val outerArgb: Int,
    val innerArgb: Int,
)

/** A plain stroked circle. */
data class PendoloRing(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val strokeArgb: Int,
    val lineWidth: Float,
)

/** A radial engraving ray from [innerRadius] to [outerRadius] at [angleRad]. */
data class PendoloRay(
    val cx: Float,
    val cy: Float,
    val innerRadius: Float,
    val outerRadius: Float,
    val angleRad: Float,
    val strokeArgb: Int,
    val lineWidth: Float,
)

/** A straight line, in absolute canvas coordinates. */
data class PendoloSegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val strokeArgb: Int,
    val lineWidth: Float,
)

/** A filled triangle, used for the focal arrowhead. */
data class PendoloTriangle(
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    val cx: Float,
    val cy: Float,
    val fillArgb: Int,
)

/** A spiral hairspring, approximated by [steps] points on a growing radius. */
data class PendoloHairspring(
    val cx: Float,
    val cy: Float,
    val startRadius: Float,
    val endRadius: Float,
    val coils: Float,
    val oscillationRad: Float,
    val strokeArgb: Int,
    val lineWidth: Float,
)

/**
 * Parallel Geneva stripes across a gear face, clipped to [clipRadius].
 *
 * Distinct from [PendoloSegment] because the clipping is part of the figure: the stripes are
 * wider than the gear and must be cut at its inner circle. Drawing them as segments would let
 * them run past the rim and read as scratches on the background.
 */
data class PendoloGenevaStripes(
    val cx: Float,
    val cy: Float,
    val spanRadius: Float,
    val clipRadius: Float,
    val rotationRad: Float,
    val stripes: Int,
    val strokeEvenArgb: Int,
    val strokeOddArgb: Int,
    val lineWidth: Float,
)

/**
 * The whole clockwork as an ordered list of primitives.
 *
 * Ordering is the painter's algorithm: rings and ticks first, gears over them, jewels last. The
 * desktop draws in the same order and the layering is load-bearing — a filled gear body over the
 * guide rings is what makes the mechanism read as solid rather than as a pile of circles.
 */
data class PendoloClockworkDrawing(
    val gradient: PendoloRadialGradient?,
    val items: List<PendoloDrawItem>,
)

/** The central vignette: background colour, opaque at the hub, transparent at [radius]. */
data class PendoloRadialGradient(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val centerArgb: Int,
    val midArgb: Int,
    val outerArgb: Int,
)

sealed class PendoloDrawItem {
    data class Gear(val value: PendoloGear) : PendoloDrawItem()
    data class Wheel(val value: PendoloSpokedWheel) : PendoloDrawItem()
    data class Jewel(val value: PendoloJewel) : PendoloDrawItem()
    data class Ring(val value: PendoloRing) : PendoloDrawItem()
    data class Ray(val value: PendoloRay) : PendoloDrawItem()
    data class Segment(val value: PendoloSegment) : PendoloDrawItem()
    data class Triangle(val value: PendoloTriangle) : PendoloDrawItem()
    data class Hairspring(val value: PendoloHairspring) : PendoloDrawItem()
    data class Geneva(val value: PendoloGenevaStripes) : PendoloDrawItem()
}

/**
 * Builds the clockwork for one frame.
 *
 * Ported from `pendoloClockworkDraw2d.ts`, which is the desktop's Canvas2D fallback for the WebGL
 * path. That fallback is a complete renderer, not a stub — every element here (escapement wheel,
 * guilloche engraving, Geneva stripes, orbit planets, hairspring, jewels, focal arrow) is drawn by
 * the desktop at the same quality, so porting it loses nothing but the shading.
 *
 * Emitting a display list instead of drawing directly means the composition can be asserted in a
 * JVM test. The alternative — a `DrawScope` function — would only be verifiable on a device.
 */
object PendoloClockworkBuilder {

    /** How many radial ticks ring the outer wheel. */
    private const val TICK_COUNT = 60

    /** Spacing of the guilloche engraving, in rays. */
    private const val GUILLOCHE_RAYS = 48

    fun build(frame: PendoloClockworkFrame): PendoloClockworkDrawing {
        val items = mutableListOf<PendoloDrawItem>()
        val a = frame.anchors
        val p = frame.palette
        val m = p.decorOpacityMultiplier
        val background = frame.palette.decorOpacityMultiplier

        // A palette built for NONE has a zero multiplier, but the caller may still ask for a
        // gradient; guard so the early return below is the only thing that turns drawing off.
        if (frame.decor == PendoloGearDecor.NONE || background <= 0f) {
            return PendoloClockworkDrawing(gradient = null, items = emptyList())
        }

        // 0. Central vignette.
        val gradient = if (frame.showCenterGradient) {
            PendoloRadialGradient(
                cx = frame.centerX,
                cy = frame.centerY,
                radius = a.gradientR,
                centerArgb = PendoloColor.withAlpha(frame.palette.jewelFillColor, 0.72f * m),
                midArgb = PendoloColor.withAlpha(frame.palette.jewelFillColor, 0.52f * m),
                outerArgb = PendoloColor.withAlpha(frame.palette.jewelFillColor, 0f),
            )
        } else {
            null
        }

        // 1. Guide rings and outer ticks.
        val ringRadii = listOf(0.30f, 0.60f, 0.85f, 1.15f, 1.40f).map { it * frame.baseRadius }
        ringRadii.forEach { radius ->
            items += PendoloDrawItem.Ring(
                PendoloRing(frame.centerX, frame.centerY, radius, p.primaryAlpha15, 1f),
            )
        }

        val outerTickRadius = frame.baseRadius * 1.15f
        for (index in 0 until TICK_COUNT) {
            val angle = index * (2f * PI.toFloat()) / TICK_COUNT + frame.escapementAngle * 0.2f
            val isMajor = index % 5 == 0
            val tickLength = if (isMajor) 12f else 6f
            items += PendoloDrawItem.Segment(
                PendoloSegment(
                    startX = frame.centerX + outerTickRadius * cos(angle),
                    startY = frame.centerY + outerTickRadius * sin(angle),
                    endX = frame.centerX + (outerTickRadius + tickLength) * cos(angle),
                    endY = frame.centerY + (outerTickRadius + tickLength) * sin(angle),
                    strokeArgb = if (isMajor) p.accentAlpha35 else p.primaryAlpha15,
                    lineWidth = if (isMajor) 1.5f else 1f,
                ),
            )
        }

        // 2. Main escapement wheel.
        items += PendoloDrawItem.Gear(
            PendoloGear(
                cx = frame.centerX,
                cy = frame.centerY,
                radius = frame.baseRadius + 8f,
                teeth = 36,
                toothDepth = 10f,
                rotationRad = frame.escapementAngle,
                strokeArgb = p.gearAccentAlpha,
                lineWidth = 2.2f,
                fillArgb = p.mainGearFill,
            ),
        )
        items += PendoloDrawItem.Wheel(
            PendoloSpokedWheel(
                cx = frame.centerX,
                cy = frame.centerY,
                hubRadius = frame.baseRadius * 0.2f,
                rimRadius = frame.baseRadius * 0.85f,
                spokes = 6,
                rotationRad = frame.escapementAngle,
                strokeArgb = p.gearPrimaryAlpha,
                lineWidth = 1.8f,
            ),
        )

        // Beveled double ring on the rim.
        items += PendoloDrawItem.Ring(PendoloRing(frame.centerX, frame.centerY, frame.baseRadius * 0.88f, p.bevelRing, 0.8f))
        items += PendoloDrawItem.Ring(PendoloRing(frame.centerX, frame.centerY, frame.baseRadius * 0.92f, p.bevelRing, 0.8f))

        // Guilloche engraving: alternating long and short rays give the face its texture.
        val guillocheInner = frame.baseRadius * 0.62f
        val guillocheOuter = frame.baseRadius * 0.83f
        for (index in 0 until GUILLOCHE_RAYS) {
            val angle = index * (2f * PI.toFloat()) / GUILLOCHE_RAYS + frame.escapementAngle
            val inner = if (index % 2 == 0) {
                guillocheInner
            } else {
                guillocheInner + (guillocheOuter - guillocheInner) * 0.3f
            }
            items += PendoloDrawItem.Ray(
                PendoloRay(
                    cx = frame.centerX,
                    cy = frame.centerY,
                    innerRadius = inner,
                    outerRadius = guillocheOuter,
                    angleRad = angle,
                    strokeArgb = if (index % 2 == 0) p.primaryAlpha10 else p.guillocheAlt,
                    lineWidth = 0.7f,
                ),
            )
        }

        // Rivets along the rim.
        val rivetRadius = frame.baseRadius * 0.96f
        for (index in 0 until 12) {
            val angle = index * (2f * PI.toFloat()) / 12f + frame.escapementAngle
            val cx = frame.centerX + rivetRadius * cos(angle)
            val cy = frame.centerY + rivetRadius * sin(angle)
            items += PendoloDrawItem.Ring(PendoloRing(cx, cy, 2.2f, p.rivetStroke, 0.8f))
        }

        // 3. Center hub pinion and its jewel.
        items += PendoloDrawItem.Gear(
            PendoloGear(
                cx = frame.centerX,
                cy = frame.centerY,
                radius = frame.baseRadius * 0.22f,
                teeth = 12,
                toothDepth = 6f,
                rotationRad = -frame.escapementAngle * 2.5f,
                strokeArgb = p.gearAccentStrongAlpha,
                lineWidth = 2.1f,
            ),
        )
        items += PendoloDrawItem.Jewel(
            PendoloJewel(frame.centerX, frame.centerY, 4.5f, 2.5f, p.jewelStrokeColor, p.jewelFillColor),
        )

        // 4. Orbiting planet gears.
        for (planetIndex in 0 until 3) {
            val planetAngle = frame.escapementAngle * 0.4f + planetIndex * (2f * PI.toFloat()) / 3f
            val px = frame.centerX + a.orbitR * cos(planetAngle)
            val py = frame.centerY + a.orbitR * sin(planetAngle)
            items += PendoloDrawItem.Gear(
                PendoloGear(
                    cx = px,
                    cy = py,
                    radius = a.planetR,
                    teeth = 14,
                    toothDepth = 5f,
                    rotationRad = -frame.escapementAngle * 3f + planetIndex * 0.5f,
                    strokeArgb = p.planetGearAlpha,
                    lineWidth = 1.7f,
                ),
            )
            items += PendoloDrawItem.Jewel(
                PendoloJewel(px, py, 3.5f, 1.8f, p.jewelStrokeColor, p.jewelFillColor),
            )
        }

        // 5. Balance wheel with hairspring, and the transmission train.
        val balanceAngle = frame.phase * 0.1f
        items += PendoloDrawItem.Gear(
            PendoloGear(
                cx = a.balanceCx,
                cy = a.balanceCy,
                radius = a.balanceR,
                teeth = 20,
                toothDepth = 7f,
                rotationRad = balanceAngle,
                strokeArgb = p.gearAccentStrongAlpha,
                lineWidth = 2.1f,
                fillArgb = p.balanceGearFill,
            ),
        )
        // A quiet seat for the theme icon the renderer overlays here.
        items += PendoloDrawItem.Ring(
            PendoloRing(a.balanceCx, a.balanceCy, a.balanceR * 0.52f, p.gearPrimarySubtleAlpha, 1.8f),
        )
        items += PendoloDrawItem.Hairspring(
            PendoloHairspring(
                cx = a.balanceCx,
                cy = a.balanceCy,
                startRadius = a.balanceR * 0.56f,
                endRadius = a.balanceR * 0.88f,
                coils = 3.5f,
                oscillationRad = frame.bassOscillation * 0.6f + balanceAngle * 0.3f,
                strokeArgb = PendoloColor.withAlpha(frame.accentArgb, 0.30f * m),
                lineWidth = 0.8f,
            ),
        )
        items += PendoloDrawItem.Jewel(
            PendoloJewel(a.balanceCx, a.balanceCy, 3.5f, 1.8f, p.jewelStrokeColor, p.jewelFillColor),
        )

        items += PendoloDrawItem.Gear(
            PendoloGear(
                cx = a.transCx,
                cy = a.transCy,
                radius = a.transR,
                teeth = 24,
                toothDepth = 7f,
                rotationRad = -frame.escapementAngle * 1.4f,
                strokeArgb = p.gearPrimaryAlpha,
                lineWidth = 1.8f,
            ),
        )
        items += PendoloDrawItem.Wheel(
            PendoloSpokedWheel(
                cx = a.transCx,
                cy = a.transCy,
                hubRadius = a.transR * 0.25f,
                rimRadius = a.transR * 0.85f,
                spokes = 5,
                rotationRad = -frame.escapementAngle * 1.4f,
                strokeArgb = p.gearPrimarySubtleAlpha,
                lineWidth = 1.5f,
            ),
        )

        // Geneva stripes on the transmission gear face, clipped to its inner circle by the renderer.
        items += PendoloDrawItem.Geneva(
            PendoloGenevaStripes(
                cx = a.transCx,
                cy = a.transCy,
                spanRadius = a.transR * 1.6f,
                clipRadius = a.transR * 0.80f,
                rotationRad = -frame.escapementAngle * 1.4f,
                stripes = 9,
                strokeEvenArgb = p.primaryAlpha10,
                strokeOddArgb = p.genevaAlt,
                lineWidth = 0.7f,
            ),
        )
        items += PendoloDrawItem.Jewel(
            PendoloJewel(a.transCx, a.transCy, 3.5f, 1.8f, p.jewelStrokeColor, p.jewelFillColor),
        )

        // Seconds gear: one visible tick per second.
        items += PendoloDrawItem.Gear(
            PendoloGear(
                cx = a.secondGearCx,
                cy = a.secondGearCy,
                radius = a.secondGearR,
                teeth = 15,
                toothDepth = 5f,
                rotationRad = frame.secondGearAngle,
                strokeArgb = p.gearAccentAlpha,
                lineWidth = 1.8f,
                fillArgb = p.secondGearFill,
            ),
        )
        items += PendoloDrawItem.Wheel(
            PendoloSpokedWheel(
                cx = a.secondGearCx,
                cy = a.secondGearCy,
                hubRadius = a.secondGearR * 0.28f,
                rimRadius = a.secondGearR * 0.76f,
                spokes = 4,
                rotationRad = -frame.secondGearAngle,
                strokeArgb = p.gearPrimarySubtleAlpha,
                lineWidth = 1.3f,
            ),
        )
        items += PendoloDrawItem.Jewel(
            PendoloJewel(a.secondGearCx, a.secondGearCy, 2.8f, 1.5f, p.jewelStrokeColor, p.jewelFillColor),
        )

        // Idler pinion between the transmission and seconds gears.
        items += PendoloDrawItem.Gear(
            PendoloGear(
                cx = a.idlerCx,
                cy = a.idlerCy,
                radius = a.idlerR,
                teeth = 10,
                toothDepth = 3.5f,
                rotationRad = frame.escapementAngle * 2.2f,
                strokeArgb = p.idlerStroke,
                lineWidth = 1.3f,
            ),
        )
        items += PendoloDrawItem.Ring(
            PendoloRing(a.idlerCx, a.idlerCy, a.idlerR * 0.45f, p.idlerInner, 0.8f),
        )
        items += PendoloDrawItem.Jewel(
            PendoloJewel(a.idlerCx, a.idlerCy, 2.2f, 1.2f, p.jewelStrokeColor, p.jewelFillColor),
        )

        // 6. The focal axis the current line sits on, with its arrowhead.
        items += PendoloDrawItem.Segment(
            PendoloSegment(
                startX = frame.centerX + frame.baseRadius * 0.8f,
                startY = frame.centerY,
                endX = frame.centerX + a.focalAxisEndRadius,
                endY = frame.centerY,
                strokeArgb = p.gearAccentStrongAlpha,
                lineWidth = 2f,
            ),
        )
        val arrowX = frame.centerX + a.focalAxisEndRadius
        items += PendoloDrawItem.Triangle(
            PendoloTriangle(
                ax = arrowX,
                ay = frame.centerY - 4f,
                bx = arrowX + 8f,
                by = frame.centerY,
                cx = arrowX,
                cy = frame.centerY + 4f,
                fillArgb = p.gearAccentStrongAlpha,
            ),
        )

        return PendoloClockworkDrawing(gradient = gradient, items = items)
    }

    /** Points of the hairspring spiral, as a polyline the renderer strokes. */
    fun hairspringPoints(spring: PendoloHairspring): List<Pair<Float, Float>> {
        val coils = spring.coils
        if (coils <= 0f) return emptyList()

        val totalAngle = coils * 2f * PI.toFloat()
        val steps = (coils * 60f).toInt()
        val points = ArrayList<Pair<Float, Float>>(steps + 1)
        for (index in 0..steps) {
            val t = index.toFloat() / steps
            val angle = t * totalAngle + spring.oscillationRad
            val radius = spring.startRadius + (spring.endRadius - spring.startRadius) * t.pow(0.9f)
            points += (spring.cx + radius * cos(angle)) to (spring.cy + radius * sin(angle))
        }
        return points
    }

    /** Vertices of a gear's tooth ring, ready to stroke (or fill) as one closed path. */
    fun gearOutline(gear: PendoloGear): List<Pair<Float, Float>> {
        if (gear.teeth <= 0) return emptyList()

        val innerRadius = gear.radius - gear.toothDepth
        val anglePerTooth = 2f * PI.toFloat() / gear.teeth
        val points = ArrayList<Pair<Float, Float>>(gear.teeth * 4)

        for (index in 0 until gear.teeth) {
            val baseAngle = index * anglePerTooth + gear.rotationRad
            val offsets = floatArrayOf(-0.22f, -0.12f, 0.12f, 0.22f)
            val radii = floatArrayOf(innerRadius, gear.radius, gear.radius, innerRadius)
            for (corner in 0..3) {
                val angle = baseAngle + offsets[corner] * anglePerTooth
                points += (gear.cx + radii[corner] * cos(angle)) to (gear.cy + radii[corner] * sin(angle))
            }
        }
        return points
    }
}
