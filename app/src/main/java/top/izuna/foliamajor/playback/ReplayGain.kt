package top.izuna.foliamajor.playback

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * ReplayGain, ported from folia-major's `src/utils/replayGain.ts`.
 *
 * The purpose is loudness consistency: tracks mastered in different eras and at different levels
 * otherwise force the listener to ride the volume control. The tags carry a gain in dB measured
 * against a reference, plus a peak for the loudest sample.
 *
 * The peak is not decoration. A **positive** gain raises every sample, and if the peak was already
 * near full scale the result clips — audibly, and worse than the level mismatch it was fixing. So a
 * positive gain is clamped to whatever the peak can absorb, which is the one non-obvious rule here.
 */

enum class ReplayGainMode { OFF, TRACK, ALBUM }

/** The gain values read from a track's tags. Any field may be absent. */
data class ReplayGainInfo(
    val trackGain: Double? = null,
    val trackPeak: Double? = null,
    val albumGain: Double? = null,
    val albumPeak: Double? = null,
) {
    val isEmpty: Boolean
        get() = trackGain == null && trackPeak == null && albumGain == null && albumPeak == null
}

data class ReplayGainCalculation(
    /** The gain the tags asked for, before clipping protection. */
    val gainDb: Double,
    /** The gain actually applied. Differs from [gainDb] only when a positive gain had to be cut. */
    val effectiveGainDb: Double,
    val peak: Double? = null,
    /** The multiplier to apply to the player volume. */
    val linearGain: Double,
)

/**
 * Parses a gain tag.
 *
 * Tags are written as `-7.25 dB` by most tools and as a bare `-7.25` by some. The unit suffix is
 * stripped rather than required, and a value that does not parse yields null — treating garbage as
 * 0 dB would silently disable the feature for that track.
 */
fun parseGainTag(value: String?): Double? {
    if (value.isNullOrBlank()) return null
    val cleaned = value.trim()
        .removeSuffix("dB").removeSuffix("db").removeSuffix("DB")
        .trim()
    return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Parses a peak tag. Always a linear ratio, never dB, despite the neighbouring tags being dB. */
fun parsePeakTag(value: String?): Double? {
    if (value.isNullOrBlank()) return null
    return value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}

/**
 * Computes the gain to apply.
 *
 * Album mode falls back to the track gain when the album tags are missing, because a partially
 * tagged album is common and falling back to 0 dB would make those tracks jump out.
 */
fun calculateReplayGain(
    info: ReplayGainInfo?,
    mode: ReplayGainMode,
): ReplayGainCalculation {
    if (info == null || mode == ReplayGainMode.OFF) {
        return ReplayGainCalculation(gainDb = 0.0, effectiveGainDb = 0.0, linearGain = 1.0)
    }

    val gainDb = when (mode) {
        ReplayGainMode.ALBUM -> info.albumGain ?: info.trackGain ?: 0.0
        ReplayGainMode.TRACK -> info.trackGain ?: 0.0
        ReplayGainMode.OFF -> 0.0
    }

    val peak = when (mode) {
        ReplayGainMode.ALBUM -> info.albumPeak ?: info.trackPeak
        ReplayGainMode.TRACK -> info.trackPeak
        ReplayGainMode.OFF -> null
    }

    var effectiveGainDb = gainDb

    // A boost can only be applied as far as the headroom allows. `-20*log10(peak)` is the largest
    // positive gain that leaves the peak at or below full scale.
    if (gainDb > 0 && peak != null && peak > 0) {
        val clipSafeGainDb = -20 * log10(peak)
        effectiveGainDb = min(gainDb, clipSafeGainDb)
    }

    return ReplayGainCalculation(
        gainDb = gainDb,
        effectiveGainDb = effectiveGainDb,
        peak = peak,
        linearGain = 10.0.pow(effectiveGainDb / 20.0),
    )
}

/**
 * Combines ReplayGain with the user's volume.
 *
 * The product can exceed 1 for a boosted track at high volume; the caller must clamp to the player's
 * range. Kept as a separate function so that clamp lives in one place rather than at every call site.
 */
fun applyReplayGainToVolume(userVolume: Double, linearGain: Double): Double =
    (userVolume * linearGain).coerceIn(0.0, 1.0)

