package top.izuna.foliamajor.ui.lyrics

/**
 * The lyric visualizer modes this client implements.
 *
 * The desktop app has 14; each is a separate rendering strategy rather than a parameter of one.
 * Keeping them behind an enum and a `when` is deliberate: the renderers differ in layout, timing and
 * what they read from the line, and a shared "mode config" object would end up with every field
 * meaning something different per mode.
 *
 * `CLASSIC` is the word-level glow renderer; `STILL` shows three static lines; `TILT` splits a line
 * into fragments and alternates their typography.
 *
 * The two properties below are exhaustive `when`s rather than constructor flags so that adding a
 * mode is a compile error until its stage policy is decided. Both answer questions the shared screen
 * asks, and both used to be answered by enumerating modes at the call site — which meant every new
 * mode silently inherited the default and drew its chrome over the previous mode's layout.
 */
enum class LyricMode(val label: String) {
    CLASSIC("Classic"),
    STILL("Still"),
    TILT("Tilt"),
    PARTITA("Partita"),
    CLADDAGH("Claddagh"),
    CADENZA("Cadenza"),
    FUME("Fume"),
    CAPPELLA("Cappella"),
    PENDOLO("Pendolo"),
    ;

    /**
     * Whether the mode already shows the surrounding lyrics, so the shared next-line preview and
     * translation must stay out of the way.
     *
     * `STILL` counts even though it is not a whole-page composition: it draws the previous, current
     * and next line itself, so a preview underneath is a duplicate drawn on top of live text.
     */
    val drawsOwnContext: Boolean
        get() = when (this) {
            STILL, FUME, CAPPELLA, PENDOLO -> true
            CLASSIC, TILT, PARTITA, CLADDAGH, CADENZA -> false
        }

    fun next(): LyricMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}
