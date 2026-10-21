package top.izuna.foliamajor.theme

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A light/dark pair, ported from folia-major's `DualTheme` (`src/types.ts`).
 *
 * The generator always returns both, because a single theme would have to be re-derived on a
 * day/night switch and the colours are chosen as a pair anyway.
 */
data class DualTheme(val light: Theme, val dark: Theme)

/**
 * Turns whatever the model returned into a usable [Theme].
 *
 * Ported from `src/services/themeSanitizer.ts`. Every field is validated independently against a
 * fallback rather than rejecting the whole object: a model that returns three good colours and one
 * malformed hex is still worth showing, and the alternative — a hard failure after the user waited
 * for a generation — is much worse. This is also the security boundary for the theme: the only
 * things that ever reach the renderer are validated hex colours and plain strings.
 */
object ThemeSanitizer {

    private val HEX_COLOR_PATTERN = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    val FallbackLight = Theme(
        name = "AI Light",
        backgroundColor = "#ffffff",
        primaryColor = "#111827",
        accentColor = "#2563eb",
        secondaryColor = "#475569",
        provider = "AI",
    )

    val FallbackDark = Theme(
        name = "AI Dark",
        backgroundColor = "#0f172a",
        primaryColor = "#f8fafc",
        accentColor = "#7dd3fc",
        secondaryColor = "#cbd5e1",
        provider = "AI",
    )

    val FallbackDual = DualTheme(light = FallbackLight, dark = FallbackDark)

    /**
     * Expands `#abc` to `#aabbcc` and lower-cases the rest, or returns null.
     *
     * Normalising here rather than at render time matters because the stored theme is compared and
     * de-duplicated by string, and `#FFF` and `#ffffff` would otherwise look like different themes.
     */
    private fun normalizeHexColorCandidate(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (!HEX_COLOR_PATTERN.matches(trimmed)) return null

        val hex = trimmed.substring(1).lowercase()
        if (hex.length == 3) {
            return "#" + hex.map { char -> "$char$char" }.joinToString("")
        }
        return "#$hex"
    }

    /**
     * The colour to use, in priority order: the candidate, then the fallback, then a hard default.
     *
     * Each level is itself validated, because a caller can pass a fallback that came from an already
     * unsanitised source (a re-sanitised cached theme, for example).
     */
    fun normalizeHexColor(value: String?, fallback: String, hardFallback: String = "#ffffff"): String =
        normalizeHexColorCandidate(value)
            ?: normalizeHexColorCandidate(fallback)
            ?: hardFallback

    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

    private fun JsonObject.string(key: String): String? = this[key].asString()

    /**
     * A string field where blank means "not provided".
     *
     * The model routinely returns `""` for `description` when it cannot think of one; treating that
     * as an explicit empty value would erase a fallback description.
     */
    private fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalizeFontStyle(value: String?, fallback: Theme.FontStyle): Theme.FontStyle =
        when (value?.trim()?.lowercase()) {
            "serif" -> Theme.FontStyle.SERIF
            "mono" -> Theme.FontStyle.MONO
            "sans" -> Theme.FontStyle.SANS
            else -> fallback
        }

    private fun normalizeAnimationIntensity(
        value: String?,
        fallback: Theme.AnimationIntensity,
    ): Theme.AnimationIntensity = when (value?.trim()?.lowercase()) {
        "calm" -> Theme.AnimationIntensity.CALM
        "chaotic" -> Theme.AnimationIntensity.CHAOTIC
        "normal" -> Theme.AnimationIntensity.NORMAL
        else -> fallback
    }

    /**
     * Keeps only entries with a non-empty word; the colour falls back to the accent.
     *
     * The colour fallback is the accent rather than a fixed colour because a word colour without a
     * colour is still a statement that this word is emphasised, and the accent is what the theme
     * already uses for emphasis.
     */
    private fun normalizeWordColors(value: JsonElement?, fallbackColor: String): List<WordColor> {
        val entries = value.asArray() ?: return emptyList()
        return entries.mapNotNull { entry ->
            val record = entry as? JsonObject ?: return@mapNotNull null
            val word = nonBlank(record.string("word")) ?: return@mapNotNull null
            WordColor(word = word, color = normalizeHexColor(record.string("color"), fallbackColor))
        }
    }

    /** Icons are names, not images; anything blank is dropped and the list is capped. */
    private fun normalizeLyricsIcons(value: JsonElement?): List<String> {
        val entries = value.asArray() ?: return emptyList()
        return entries
            .mapNotNull { it.asString()?.trim()?.takeIf(String::isNotEmpty) }
            .take(MAX_LYRICS_ICONS)
    }

    const val MAX_LYRICS_ICONS = 12

    /**
     * Sanitises one theme against [fallback].
     *
     * `wordColors` and `lyricsIcons` special-case "absent" versus "empty": absent means the model
     * did not answer that part, so the fallback's list is kept; an explicit `[]` means "no keywords"
     * and is honoured. Every other field has no such distinction because an empty string is never a
     * meaningful value for them.
     */
    fun sanitizeTheme(value: JsonElement?, fallback: Theme): Theme {
        val source = value as? JsonObject ?: JsonObject(emptyMap())
        val accentColor = normalizeHexColor(source.string("accentColor"), fallback.accentColor)

        val wordColors = when {
            !source.containsKey("wordColors") -> fallback.wordColors
            else -> normalizeWordColors(source["wordColors"], accentColor)
        }
        val lyricsIcons = when {
            !source.containsKey("lyricsIcons") -> fallback.lyricsIcons
            else -> normalizeLyricsIcons(source["lyricsIcons"])
        }

        return Theme(
            name = nonBlank(source.string("name")) ?: fallback.name,
            description = nonBlank(source.string("description")) ?: fallback.description,
            backgroundColor = normalizeHexColor(source.string("backgroundColor"), fallback.backgroundColor),
            primaryColor = normalizeHexColor(source.string("primaryColor"), fallback.primaryColor),
            accentColor = accentColor,
            secondaryColor = normalizeHexColor(source.string("secondaryColor"), fallback.secondaryColor),
            fontStyle = normalizeFontStyle(source.string("fontStyle"), fallback.fontStyle),
            fontWeight = fallback.fontWeight,
            animationIntensity = normalizeAnimationIntensity(
                source.string("animationIntensity"),
                fallback.animationIntensity,
            ),
            wordColors = wordColors,
            lyricsIcons = lyricsIcons,
            provider = nonBlank(source.string("provider")) ?: fallback.provider,
        )
    }

    /**
     * Sanitises a `{light, dark}` pair.
     *
     * A missing or non-object half is replaced wholesale by the fallback rather than merged, because
     * a theme with the dark background but the light primary colour is unreadable.
     */
    fun sanitizeDualTheme(
        value: JsonElement?,
        fallback: DualTheme = FallbackDual,
    ): DualTheme {
        val source = value as? JsonObject ?: JsonObject(emptyMap())
        return DualTheme(
            light = sanitizeTheme(source["light"], fallback.light),
            dark = sanitizeTheme(source["dark"], fallback.dark),
        )
    }

    /**
     * Reads a theme back out of storage.
     *
     * Kept separate from [sanitizeDualTheme] because stored records were written by an older build
     * and may be missing fields that the current sanitizer defaults rather than rejects.
     */
    fun sanitizeStored(value: JsonElement?, fallback: DualTheme = FallbackDual): DualTheme =
        sanitizeDualTheme(value ?: JsonNull, fallback)
}
