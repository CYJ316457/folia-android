package top.izuna.foliamajor.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ReplayGain.
 *
 * The clipping guard is what these mostly exist for. Without it, a positive gain on a loud master
 * clips — and clipping sounds worse than the level mismatch ReplayGain was added to fix, so the
 * failure is a regression in perceived quality rather than a visible bug.
 */
class ReplayGainTest {

    // --- Tag parsing -----------------------------------------------------------------------------

    @Test
    fun `parses a gain with a dB suffix`() {
        assertEquals(-7.25, parseGainTag("-7.25 dB")!!, 0.001)
    }

    @Test
    fun `parses a bare gain`() {
        assertEquals(-7.25, parseGainTag("-7.25")!!, 0.001)
    }

    @Test
    fun `parses a gain with a lowercase suffix`() {
        assertEquals(3.0, parseGainTag("3.0 db")!!, 0.001)
    }

    @Test
    fun `parses a positive gain`() {
        assertEquals(2.5, parseGainTag("+2.5 dB")!!, 0.001)
    }

    @Test
    fun `a blank gain is null`() {
        assertNull(parseGainTag(""))
        assertNull(parseGainTag("   "))
        assertNull(parseGainTag(null))
    }

    @Test
    fun `a non-numeric gain is null`() {
        // Returning 0 would silently disable the feature for that track instead of surfacing it.
        assertNull(parseGainTag("loud"))
    }

    @Test
    fun `parses a peak ratio`() {
        assertEquals(0.988, parsePeakTag("0.988")!!, 0.0001)
    }

    @Test
    fun `a negative peak is rejected`() {
        assertNull(parsePeakTag("-1.0"))
    }

    @Test
    fun `a non-numeric peak is null`() {
        assertNull(parsePeakTag("n/a"))
    }

    // --- Off mode --------------------------------------------------------------------------------

    @Test
    fun `off mode applies no gain`() {
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = -12.0, trackPeak = 0.9),
            ReplayGainMode.OFF,
        )

        assertEquals(0.0, calculation.effectiveGainDb, 0.0001)
        assertEquals(1.0, calculation.linearGain, 0.0001)
    }

    @Test
    fun `a missing tag applies no gain`() {
        val calculation = calculateReplayGain(null, ReplayGainMode.TRACK)

        assertEquals(1.0, calculation.linearGain, 0.0001)
    }

    // --- Negative gain ---------------------------------------------------------------------------

    @Test
    fun `a negative gain is applied directly`() {
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = -6.0, trackPeak = 0.99),
            ReplayGainMode.TRACK,
        )

        assertEquals(-6.0, calculation.effectiveGainDb, 0.001)
        // -6 dB is half amplitude.
        assertEquals(0.501, calculation.linearGain, 0.001)
    }

    @Test
    fun `a negative gain is never clamped by the peak`() {
        // Cutting volume can never clip, so the peak must not interfere.
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = -6.0, trackPeak = 0.1),
            ReplayGainMode.TRACK,
        )

        assertEquals(-6.0, calculation.effectiveGainDb, 0.001)
    }

    // --- Positive gain and clipping --------------------------------------------------------------

    @Test
    fun `a positive gain with headroom is applied in full`() {
        // peak 0.5 leaves 6 dB of headroom, so +3 dB is safe.
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = 3.0, trackPeak = 0.5),
            ReplayGainMode.TRACK,
        )

        assertEquals(3.0, calculation.gainDb, 0.001)
        assertEquals(3.0, calculation.effectiveGainDb, 0.001)
    }

    @Test
    fun `a positive gain that would clip is cut to the headroom`() {
        // peak 0.99 leaves only ~0.087 dB before full scale, so a 6 dB boost is cut to that.
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = 6.0, trackPeak = 0.99),
            ReplayGainMode.TRACK,
        )

        assertEquals(6.0, calculation.gainDb, 0.001)
        assertTrue(
            "effective gain must be cut below the requested 6 dB",
            calculation.effectiveGainDb < 6.0,
        )
        assertEquals(-20 * kotlin.math.log10(0.99), calculation.effectiveGainDb, 0.001)
    }

    @Test
    fun `a peak above full scale forces a negative gain`() {
        // peak 1.2 means the master already exceeds full scale; the safe gain is negative.
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = 6.0, trackPeak = 1.2),
            ReplayGainMode.TRACK,
        )

        assertTrue(
            "an over-full-scale peak must reduce gain",
            calculation.effectiveGainDb < 0.0,
        )
        assertEquals(-20 * kotlin.math.log10(1.2), calculation.effectiveGainDb, 0.001)
    }

    @Test
    fun `a positive gain with no peak tag is applied unclamped`() {
        // Nothing to clamp against. Better to trust the tag than to silently drop the feature.
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = 6.0),
            ReplayGainMode.TRACK,
        )

        assertEquals(6.0, calculation.effectiveGainDb, 0.001)
    }

    @Test
    fun `a positive gain at full peak is reduced to zero`() {
        // peak 1.0 means no headroom at all: -20*log10(1) = 0.
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = 4.0, trackPeak = 1.0),
            ReplayGainMode.TRACK,
        )

        assertEquals(0.0, calculation.effectiveGainDb, 0.001)
        assertEquals(1.0, calculation.linearGain, 0.001)
    }

    @Test
    fun `a zero peak is treated as no peak`() {
        // A peak of 0 is a malformed tag; dividing by it would produce an infinite gain.
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = 4.0, trackPeak = 0.0),
            ReplayGainMode.TRACK,
        )

        assertEquals(4.0, calculation.effectiveGainDb, 0.001)
    }

    // --- Album mode ------------------------------------------------------------------------------

    @Test
    fun `album mode uses the album gain`() {
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = -2.0, albumGain = -8.0, albumPeak = 0.8),
            ReplayGainMode.ALBUM,
        )

        assertEquals(-8.0, calculation.effectiveGainDb, 0.001)
    }

    @Test
    fun `album mode falls back to the track gain`() {
        // A partially tagged album is common; falling back keeps those tracks in line.
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = -5.0),
            ReplayGainMode.ALBUM,
        )

        assertEquals(-5.0, calculation.effectiveGainDb, 0.001)
    }

    @Test
    fun `album mode falls back to the track peak`() {
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = 6.0, trackPeak = 0.5),
            ReplayGainMode.ALBUM,
        )

        // 0.5 peak allows 6 dB, so the boost survives.
        assertEquals(6.0, calculation.effectiveGainDb, 0.001)
    }

    @Test
    fun `track mode ignores the album gain`() {
        val calculation = calculateReplayGain(
            ReplayGainInfo(trackGain = -2.0, albumGain = -8.0),
            ReplayGainMode.TRACK,
        )

        assertEquals(-2.0, calculation.effectiveGainDb, 0.001)
    }

    // --- Volume combination ----------------------------------------------------------------------

    @Test
    fun `gain multiplies the user volume`() {
        assertEquals(0.5, applyReplayGainToVolume(1.0, 0.5), 0.0001)
        assertEquals(0.25, applyReplayGainToVolume(0.5, 0.5), 0.0001)
    }

    @Test
    fun `a boost at high volume is clamped to full scale`() {
        // 0.9 * 2.0 = 1.8; the player only accepts 0..1.
        assertEquals(1.0, applyReplayGainToVolume(0.9, 2.0), 0.0001)
    }

    @Test
    fun `a negative volume cannot happen`() {
        assertEquals(0.0, applyReplayGainToVolume(-1.0, 1.0), 0.0001)
    }

    // --- Info emptiness --------------------------------------------------------------------------

    @Test
    fun `an info with no fields is empty`() {
        assertTrue(ReplayGainInfo().isEmpty)
    }

    @Test
    fun `an info with one field is not empty`() {
        assertTrue(!ReplayGainInfo(trackGain = -3.0).isEmpty)
    }
}
