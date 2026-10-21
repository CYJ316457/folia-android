package top.izuna.foliamajor.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Visual theme, ported from folia-major's `Theme` interface (`src/types.ts`).
 *
 * Colors stay as hex strings rather than Compose [Color] values because that is the form the AI
 * theme generator and the sync server both produce and store; parsing happens once here instead of
 * at every render site. `wordColors` is kept as a list (not a map) because the desktop app applies
 * it in declaration order and a keyword may legitimately be listed twice.
 */
data class Theme(
    val name: String,
    val backgroundColor: String,
    val primaryColor: String,
    val accentColor: String,
    val secondaryColor: String,
    val fontStyle: FontStyle = FontStyle.SANS,
    val fontWeight: Int? = null,
    val animationIntensity: AnimationIntensity = AnimationIntensity.NORMAL,
    val wordColors: List<WordColor> = emptyList(),
    /** Lucide icon names chosen by the generator; kept so a theme survives a round trip intact. */
    val lyricsIcons: List<String> = emptyList(),
    /** One-line mood sentence from the generator, shown in the theme picker. */
    val description: String? = null,
    /** Which service produced the theme ("Google Gemini", "OpenAI", …). */
    val provider: String? = null,
) {
    enum class FontStyle { SANS, SERIF, MONO }

    enum class AnimationIntensity { CALM, NORMAL, CHAOTIC }
}

data class WordColor(val word: String, val color: String)

/** Parses `#RRGGBB` / `#AARRGGBB`, falling back instead of throwing on AI-generated garbage. */
fun String.toThemeColor(fallback: Color = Color.White): Color {
    val hex = trim().removePrefix("#")
    return when (hex.length) {
        6 -> runCatching { Color(hex.toLong(16) or 0xFF000000L) }.getOrDefault(fallback)
        8 -> runCatching { Color(hex.toLong(16)) }.getOrDefault(fallback)
        else -> fallback
    }
}

/**
 * Resolves the per-word highlight color.
 *
 * Matching is by substring containment, not equality, because the theme generator emits keyword
 * stems ("夜" for "夜色") that are meant to color any word containing them.
 */
fun resolveWordColor(word: String, wordColors: List<WordColor>, fallback: Color): Color {
    for (entry in wordColors) {
        if (entry.word.isNotEmpty() && word.contains(entry.word)) {
            return entry.color.toThemeColor(fallback)
        }
    }
    return fallback
}

fun Theme.fontWeightOrDefault(fallback: Int): FontWeight =
    FontWeight((fontWeight ?: fallback).coerceIn(100, 900))

/**
 * The default theme. Matches the desktop app's built-in baseline (#09090b surface) so a song with
 * no AI theme looks the same on both clients.
 */
val DefaultTheme = Theme(
    name = "Default",
    backgroundColor = "#09090b",
    primaryColor = "#f4f4f5",
    accentColor = "#a78bfa",
    secondaryColor = "#a1a1aa",
    animationIntensity = Theme.AnimationIntensity.NORMAL,
)

/** Rough stand-in for the desktop font stacks; Android resolves these from the system font list. */
fun Theme.resolveFontFamily(): FontFamily = when (fontStyle) {
    Theme.FontStyle.SANS -> FontFamily.SansSerif
    Theme.FontStyle.SERIF -> FontFamily.Serif
    Theme.FontStyle.MONO -> FontFamily.Monospace
}

/** Scaled lyric size, matching the desktop clamp() behaviour at a typical phone width. */
fun lyricFontSize(base: TextUnit, scale: Float): TextUnit = (base.value * scale).sp
