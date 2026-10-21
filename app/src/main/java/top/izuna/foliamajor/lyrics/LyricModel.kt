package top.izuna.foliamajor.lyrics

/**
 * Lyric domain model, ported field-for-field from folia-major's `src/types.ts`.
 *
 * Field names and units are kept identical to the TypeScript original on purpose: the two clients
 * are meant to read the same lyric payloads (and the same IndexedDB-era caches once those are
 * migrated), so a rename here would have to be undone at every serialization boundary.
 *
 * All times are SECONDS, matching the desktop app. Provider APIs hand out milliseconds; converting
 * at the provider boundary rather than here keeps this model identical on both sides.
 */
data class Word(
    val text: String,
    val startTime: Double,
    val endTime: Double,
) {
    val duration: Double get() = (endTime - startTime).coerceAtLeast(0.0)
}

/**
 * One lyric line.
 *
 * `words` is never empty for a parsed line — formats without word timing get evenly distributed
 * placeholder words from [buildTimedWords], which is what lets the renderer treat every format the
 * same way.
 */
data class Line(
    val words: List<Word>,
    val startTime: Double,
    val endTime: Double,
    val fullText: String,
    val translation: String? = null,
    val romanization: String? = null,
    val isChorus: Boolean = false,
    /** TTML `itunes:key`. Only TTML carries it; used to correlate with sidecar translations. */
    val id: String? = null,
    /** TTML `ttm:agent`. Two overlapping lines with different agents are a duet. */
    val agentId: String? = null,
    /** TTML `itunes:songPart` from the enclosing `<div>`, e.g. "Verse" / "Chorus". */
    val songPart: String? = null,
    /** TTML block ordinal. Distinguishes consecutive `<div>`s that share a song part. */
    val blockIndex: Int? = null,
    val renderHints: LineRenderHints? = null,
) {
    /** Latest point a renderer may keep this line on screen; see [buildLineRenderHints]. */
    val renderEndTime: Double get() = renderHints?.renderEndTime ?: endTime
}

data class LyricData(
    val lines: List<Line>,
    val title: String? = null,
    val artist: String? = null,
    val isWordByWord: Boolean = false,
)
