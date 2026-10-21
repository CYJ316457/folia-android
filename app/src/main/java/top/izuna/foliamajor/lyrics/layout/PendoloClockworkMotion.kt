package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.theme.Theme
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Per-intensity motion parameters for `pendolo`, ported from `pendoloMotionProfile.ts`.
 *
 * The desktop resolves these from the theme's `animationIntensity`. Everything else in the mode is
 * scaled by this table: wheel speed, swing amplitude, how hard the bass pushes, and how snappy the
 * escapement is. Keeping it as a table rather than as tunables on [PendoloTuning] is deliberate —
 * these are ratios relative to "normal", not independent knobs.
 */
data class PendoloMotionProfile(
    val balanceSpeedMultiplier: Float,
    val balanceAmplitudeMultiplier: Float,
    val bassResponseMultiplier: Float,
    val escapementSpringMultiplier: Float,
    val escapementDampingMultiplier: Float,
) {
    companion object {
        val Calm = PendoloMotionProfile(
            balanceSpeedMultiplier = 0.72f,
            balanceAmplitudeMultiplier = 0.58f,
            bassResponseMultiplier = 0.62f,
            escapementSpringMultiplier = 0.72f,
            escapementDampingMultiplier = 1.2f,
        )

        val Normal = PendoloMotionProfile(
            balanceSpeedMultiplier = 1f,
            balanceAmplitudeMultiplier = 1f,
            bassResponseMultiplier = 1f,
            escapementSpringMultiplier = 1f,
            escapementDampingMultiplier = 1f,
        )

        val Chaotic = PendoloMotionProfile(
            balanceSpeedMultiplier = 1.3f,
            balanceAmplitudeMultiplier = 1.42f,
            bassResponseMultiplier = 1.38f,
            escapementSpringMultiplier = 1.3f,
            escapementDampingMultiplier = 0.84f,
        )

        fun forIntensity(intensity: Theme.AnimationIntensity): PendoloMotionProfile = when (intensity) {
            Theme.AnimationIntensity.CALM -> Calm
            Theme.AnimationIntensity.NORMAL -> Normal
            Theme.AnimationIntensity.CHAOTIC -> Chaotic
        }
    }
}

/**
 * Mutable clockwork simulation state, carried across frames.
 *
 * This cannot be recomputed from the position alone: the seconds gear is a spring that settles
 * toward a target tooth, and the balance wheel's speed depends on bass smoothed over previous
 * frames. Both are path-dependent.
 */
data class PendoloClockworkMotionState(
    val phase: Float = 0f,
    val smoothedBass: Float = 0.15f,
    val secondGearElapsed: Float = 0f,
    val secondGearStep: Int = 0,
    val secondGearAngle: Float = 0f,
    val secondGearVelocity: Float = 0f,
)

/** What one frame of the clockwork produces. */
data class PendoloClockworkMotionFrame(
    val bass: Float,
    val bassOscillation: Float,
    val secondGearAngle: Float,
    val phase: Float,
)

/**
 * The clockwork's motion, ported from `pendoloClockworkMotion.ts`.
 *
 * The desktop drives this from a live audio analyser. This client has none (adding `RECORD_AUDIO`
 * to make a decorative swing respond would be a bad trade), so the caller passes the same fallback
 * the desktop uses when no analyser is present: a constant [PendoloClockworkMotion.DEFAULT_BASS].
 * The swing is then steady rather than reactive, which is what a clock should look like anyway.
 */
object PendoloClockworkMotion {

    /** Bass level used when there is no audio analyser, matching the desktop's default prop. */
    const val DEFAULT_BASS = 0.18f

    /** One tooth of the seconds gear per second. */
    private const val SECOND_GEAR_TEETH = 15

    /** Advances the simulation by [dt] seconds. */
    fun step(
        state: PendoloClockworkMotionState,
        dt: Float,
        rawBass: Float,
        paused: Boolean,
        profile: PendoloMotionProfile,
    ): Pair<PendoloClockworkMotionState, PendoloClockworkMotionFrame> {
        val clampedBass = rawBass.coerceIn(0f, 1f)

        // Smoothing, not the raw value: a bass that jumps every frame would make the swing jitter
        // instead of breathe. The 0.24 cap keeps a step change visibly settling over ~4 frames.
        val smoothingRate = minOf(0.24f, 0.12f * profile.bassResponseMultiplier)
        val smoothedBass = state.smoothedBass + (clampedBass - state.smoothedBass) * smoothingRate

        var phase = state.phase
        var secondGearElapsed = state.secondGearElapsed
        var secondGearStep = state.secondGearStep
        var secondGearAngle = state.secondGearAngle
        var secondGearVelocity = state.secondGearVelocity

        if (!paused) {
            // The balance wheel runs faster with more bass, so a loud passage visibly speeds up the
            // escapement. Base rate 2.8 rad/s is about 27 swings a minute.
            phase += dt * (2.8f + smoothedBass * 3.5f * profile.bassResponseMultiplier) *
                profile.balanceSpeedMultiplier

            secondGearElapsed += dt
            val completedSteps = floor(secondGearElapsed)
            if (completedSteps > 0f) {
                secondGearStep += completedSteps.toInt()
                secondGearElapsed -= completedSteps
            }

            val target = secondGearStep * (2f * Math.PI.toFloat() / SECOND_GEAR_TEETH)
            val displacement = target - secondGearAngle
            secondGearVelocity += displacement * 92f * profile.escapementSpringMultiplier * dt
            // Exponential damping rather than a fixed subtraction: this stays stable at any dt,
            // where a linear term can overshoot into oscillation on a slow frame.
            secondGearVelocity *= exp(-13f * profile.escapementDampingMultiplier * dt)
            secondGearAngle += secondGearVelocity * dt
        }

        val bassOscillation = sin(phase) *
            (0.15f + smoothedBass * 0.70f * profile.bassResponseMultiplier) *
            profile.balanceAmplitudeMultiplier

        val next = state.copy(
            phase = phase,
            smoothedBass = smoothedBass,
            secondGearElapsed = secondGearElapsed,
            secondGearStep = secondGearStep,
            secondGearAngle = secondGearAngle,
            secondGearVelocity = secondGearVelocity,
        )
        return next to PendoloClockworkMotionFrame(
            bass = smoothedBass,
            bassOscillation = bassOscillation,
            secondGearAngle = secondGearAngle,
            phase = phase,
        )
    }
}
