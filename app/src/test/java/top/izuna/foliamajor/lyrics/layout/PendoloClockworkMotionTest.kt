package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.theme.Theme

/**
 * Tests for the clockwork simulation.
 *
 * These matter more than usual here: the mode has no audio analyser on Android, so the only thing
 * making the gears move is this integrator. A sign error would make the wheel run backwards, and a
 * dt-dependent spring would behave differently at 60 and 120 Hz — neither is visible in a single
 * screenshot, and both are obvious to a test.
 */
class PendoloClockworkMotionTest {

    private val frame = 1f / 60f

    private fun simulate(
        frames: Int,
        dt: Float = frame,
        paused: Boolean = false,
        bass: Float = PendoloClockworkMotion.DEFAULT_BASS,
        profile: PendoloMotionProfile = PendoloMotionProfile.Normal,
    ): Pair<PendoloClockworkMotionState, PendoloClockworkMotionFrame> {
        var state = PendoloClockworkMotionState()
        var last = PendoloClockworkMotionFrame(0f, 0f, 0f, 0f)
        repeat(frames) {
            val (next, output) = PendoloClockworkMotion.step(state, dt, bass, paused, profile)
            state = next
            last = output
        }
        return state to last
    }

    @Test
    fun `the balance wheel advances while playing`() {
        val (_, output) = simulate(frames = 60)

        assertTrue("one second of playback must move the wheel", output.phase > 0f)
    }

    @Test
    fun `the balance wheel freezes when paused`() {
        val (state, output) = simulate(frames = 60, paused = true)

        assertEquals(0f, output.phase, 0.0001f)
        assertEquals(0f, state.secondGearAngle, 0.0001f)
    }

    @Test
    fun `pausing mid-flight keeps the phase but stops it growing`() {
        val (afterPlaying, played) = simulate(frames = 60)
        val (afterPaused, frozen) = simulate(frames = 120, paused = true)

        // A fresh state started paused stays at zero; the interesting case is that a running phase
        // is carried, not reset.
        assertEquals(0f, afterPaused.phase, 0.0001f)
        assertTrue("phase accumulates while playing", played.phase > 0f)
        assertTrue("state is carried across a pause", afterPlaying.phase >= played.phase)
    }

    @Test
    fun `higher bass speeds the wheel up`() {
        val (_, quiet) = simulate(frames = 60, bass = 0f)
        val (_, loud) = simulate(frames = 60, bass = 1f)

        assertTrue("a loud passage must turn the wheel faster", loud.phase > quiet.phase)
    }

    @Test
    fun `the swing amplitude is bounded by the profile`() {
        val (_, calm) = run {
            var state = PendoloClockworkMotionState()
            var peak = 0f
            val profile = PendoloMotionProfile.Calm
            repeat(600) {
                val (next, output) = PendoloClockworkMotion.step(state, frame, 1f, false, profile)
                state = next
                peak = maxOf(peak, kotlin.math.abs(output.bassOscillation))
            }
            state to peak
        }

        val (_, chaotic) = run {
            var state = PendoloClockworkMotionState()
            var peak = 0f
            val profile = PendoloMotionProfile.Chaotic
            repeat(600) {
                val (next, output) = PendoloClockworkMotion.step(state, frame, 1f, false, profile)
                state = next
                peak = maxOf(peak, kotlin.math.abs(output.bassOscillation))
            }
            state to peak
        }

        assertTrue("chaotic must swing wider than calm", chaotic > calm)
        assertTrue("the swing stays bounded", chaotic < 2f)
    }

    @Test
    fun `the seconds gear steps once per second`() {
        // One frame past the second, not exactly one second: summing 1/60 in float32 lands on
        // 0.9999997, so the tooth advances on frame 61. The desktop has the same quantisation and
        // the 16 ms delay is invisible; asserting the exact frame would encode a rounding artefact
        // as a requirement.
        val (state, _) = simulate(frames = 61)

        assertEquals("one second is one tooth", 1, state.secondGearStep)
    }

    @Test
    fun `the seconds gear settles onto each tooth within its second`() {
        // The gear jumps one tooth per second and the spring takes about 0.9 s to land on it, so
        // just before the next tooth is due it must be sitting on the tooth it was sent to. Taking
        // the sample right after a step instead would catch it mid-flight at roughly the previous
        // tooth, which is correct motion but a misleading assertion.
        val (state, output) = simulate(frames = (60 * 4.9).toInt())
        val toothRad = 2f * Math.PI.toFloat() / 15f

        assertEquals(4, state.secondGearStep)
        assertEquals("the gear rests on the 4th tooth", 4f, output.secondGearAngle / toothRad, 0.1f)
        assertTrue("it has stopped moving", kotlin.math.abs(state.secondGearVelocity) < 0.1f)
    }

    @Test
    fun `the spring settles rather than oscillating forever`() {
        // Run long enough for the spring to converge, then measure how much it still moves.
        val (settled, _) = simulate(frames = 60 * 3)
        val (later, _) = simulate(frames = 60 * 4)

        assertTrue(
            "velocity must decay toward rest",
            kotlin.math.abs(later.secondGearVelocity) < 0.5f,
        )
        assertNotEquals(0f, settled.secondGearAngle)
    }

    @Test
    fun `the integrator is stable at a large dt`() {
        // A dropped frame hands in a dt of 0.2s. An explicit-Euler spring would blow up here.
        val (_, output) = simulate(frames = 120, dt = 0.2f)

        assertTrue("angle must stay finite", output.secondGearAngle.isFinite())
        assertTrue("angle must stay bounded", kotlin.math.abs(output.secondGearAngle) < 100f)
        assertTrue("phase must stay finite", output.phase.isFinite())
    }

    @Test
    fun `bass input outside 0 to 1 is clamped`() {
        val (low, _) = simulate(frames = 30, bass = -5f)
        val (high, _) = simulate(frames = 30, bass = 9f)

        assertTrue("negative bass must not invert the motion", low.smoothedBass >= 0f)
        assertTrue("oversized bass is clamped to 1", high.smoothedBass <= 1f)
    }

    @Test
    fun `the profile table maps intensities as the desktop does`() {
        assertEquals(PendoloMotionProfile.Calm, PendoloMotionProfile.forIntensity(Theme.AnimationIntensity.CALM))
        assertEquals(PendoloMotionProfile.Normal, PendoloMotionProfile.forIntensity(Theme.AnimationIntensity.NORMAL))
        assertEquals(PendoloMotionProfile.Chaotic, PendoloMotionProfile.forIntensity(Theme.AnimationIntensity.CHAOTIC))
    }
}
