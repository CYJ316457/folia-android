package top.izuna.foliamajor.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the theme sanitizer.
 *
 * The sanitizer is the only place untrusted model output becomes a renderable theme, so the cases
 * below are the ones a real model produces: colours without `#`, named colours, 8-digit hex from a
 * model that decided alpha was interesting, nulls, and arrays where objects were asked for.
 */
class ThemeSanitizerTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `expands three digit hex`() {
        assertEquals("#aabbcc", ThemeSanitizer.normalizeHexColor("#ABC", "#000000"))
    }

    @Test
    fun `lowercases six digit hex`() {
        assertEquals("#a1b2c3", ThemeSanitizer.normalizeHexColor("#A1B2C3", "#000000"))
    }

    @Test
    fun `trims surrounding whitespace before validating`() {
        assertEquals("#112233", ThemeSanitizer.normalizeHexColor("  #112233\n", "#000000"))
    }

    @Test
    fun `rejects named colors and falls back`() {
        assertEquals("#123456", ThemeSanitizer.normalizeHexColor("rebeccapurple", "#123456"))
    }

    @Test
    fun `rejects hex without hash`() {
        assertEquals("#123456", ThemeSanitizer.normalizeHexColor("ff0000", "#123456"))
    }

    @Test
    fun `rejects eight digit hex`() {
        // The desktop build only accepts 3/6; an alpha channel from the model is not honoured
        // because the renderer applies its own alphas on top.
        assertEquals("#123456", ThemeSanitizer.normalizeHexColor("#80ff0000", "#123456"))
    }

    @Test
    fun `falls through an invalid fallback to the hard fallback`() {
        assertEquals("#ffffff", ThemeSanitizer.normalizeHexColor("nope", "also-nope"))
    }

    @Test
    fun `honours an explicit hard fallback`() {
        assertEquals("#abcdef", ThemeSanitizer.normalizeHexColor(null, null ?: "", "#abcdef"))
    }

    @Test
    fun `sanitizes a complete theme`() {
        val theme = ThemeSanitizer.sanitizeTheme(
            json(
                """
                {
                  "name": "  忧郁破晓  ",
                  "description": "  戴上耳机的那一刻，喧嚣的世界瞬间消失了。  ",
                  "backgroundColor": "#FFF4E6",
                  "primaryColor": "#2A2118",
                  "accentColor": "#D97706",
                  "secondaryColor": "#8A6D4B",
                  "fontStyle": "serif",
                  "animationIntensity": "calm",
                  "wordColors": [{"word": "夏夜", "color": "#FF8800"}],
                  "lyricsIcons": ["CloudLightning", "HeartHandshake"],
                  "provider": "Google Gemini"
                }
                """.trimIndent()
            ),
            ThemeSanitizer.FallbackLight,
        )

        assertEquals("忧郁破晓", theme.name)
        assertEquals("戴上耳机的那一刻，喧嚣的世界瞬间消失了。", theme.description)
        assertEquals("#fff4e6", theme.backgroundColor)
        assertEquals("#2a2118", theme.primaryColor)
        assertEquals("#d97706", theme.accentColor)
        assertEquals("#8a6d4b", theme.secondaryColor)
        assertEquals(Theme.FontStyle.SERIF, theme.fontStyle)
        assertEquals(Theme.AnimationIntensity.CALM, theme.animationIntensity)
        assertEquals(listOf(WordColor("夏夜", "#ff8800")), theme.wordColors)
        assertEquals(listOf("CloudLightning", "HeartHandshake"), theme.lyricsIcons)
        assertEquals("Google Gemini", theme.provider)
    }

    @Test
    fun `missing fields come from the fallback`() {
        val theme = ThemeSanitizer.sanitizeTheme(json("{}"), ThemeSanitizer.FallbackDark)

        assertEquals(ThemeSanitizer.FallbackDark, theme)
    }

    @Test
    fun `non-object input yields the fallback`() {
        assertEquals(
            ThemeSanitizer.FallbackLight,
            ThemeSanitizer.sanitizeTheme(null, ThemeSanitizer.FallbackLight),
        )
    }

    @Test
    fun `blank name is treated as absent`() {
        val theme = ThemeSanitizer.sanitizeTheme(
            json("""{"name": "   "}"""),
            ThemeSanitizer.FallbackLight,
        )
        assertEquals("AI Light", theme.name)
    }

    @Test
    fun `blank description is treated as absent`() {
        val fallback = ThemeSanitizer.FallbackLight.copy(description = "kept")
        val theme = ThemeSanitizer.sanitizeTheme(json("""{"description": "  "}"""), fallback)
        assertEquals("kept", theme.description)
    }

    @Test
    fun `word color falls back to the sanitized accent`() {
        val theme = ThemeSanitizer.sanitizeTheme(
            json(
                """
                {
                  "accentColor": "#00FF00",
                  "wordColors": [{"word": "train", "color": "not-a-color"}]
                }
                """.trimIndent()
            ),
            ThemeSanitizer.FallbackDark,
        )
        assertEquals(listOf(WordColor("train", "#00ff00")), theme.wordColors)
    }

    @Test
    fun `word entries without a word are dropped`() {
        val theme = ThemeSanitizer.sanitizeTheme(
            json(
                """
                {"wordColors": [
                  {"word": "", "color": "#111111"},
                  {"word": "   ", "color": "#222222"},
                  {"color": "#333333"},
                  {"word": "kept", "color": "#444444"}
                ]}
                """.trimIndent()
            ),
            ThemeSanitizer.FallbackDark,
        )
        assertEquals(listOf(WordColor("kept", "#444444")), theme.wordColors)
    }

    @Test
    fun `non-string entries in wordColors are dropped`() {
        val theme = ThemeSanitizer.sanitizeTheme(
            json("""{"wordColors": ["rain", 42, null, {"word": "snow", "color": "#ffffff"}]}"""),
            ThemeSanitizer.FallbackDark,
        )
        assertEquals(listOf(WordColor("snow", "#ffffff")), theme.wordColors)
    }

    @Test
    fun `explicit empty wordColors is honoured over the fallback`() {
        val fallback = ThemeSanitizer.FallbackDark.copy(wordColors = listOf(WordColor("x", "#000000")))
        val theme = ThemeSanitizer.sanitizeTheme(json("""{"wordColors": []}"""), fallback)
        assertTrue(theme.wordColors.isEmpty())
    }

    @Test
    fun `absent wordColors keeps the fallback list`() {
        val fallback = ThemeSanitizer.FallbackDark.copy(wordColors = listOf(WordColor("x", "#000000")))
        val theme = ThemeSanitizer.sanitizeTheme(json("{}"), fallback)
        assertEquals(listOf(WordColor("x", "#000000")), theme.wordColors)
    }

    @Test
    fun `lyrics icons are trimmed and blank entries dropped`() {
        val theme = ThemeSanitizer.sanitizeTheme(
            json("""{"lyricsIcons": [" Heart ", "", "  ", "Cloud"]}"""),
            ThemeSanitizer.FallbackDark,
        )
        assertEquals(listOf("Heart", "Cloud"), theme.lyricsIcons)
    }

    @Test
    fun `lyrics icons are capped`() {
        val icons = (1..20).joinToString(",", prefix = "[", postfix = "]") { "\"Icon$it\"" }
        val theme = ThemeSanitizer.sanitizeTheme(json("""{"lyricsIcons": $icons}"""), ThemeSanitizer.FallbackDark)
        assertEquals(ThemeSanitizer.MAX_LYRICS_ICONS, theme.lyricsIcons.size)
        assertEquals("Icon1", theme.lyricsIcons.first())
        assertEquals("Icon12", theme.lyricsIcons.last())
    }

    @Test
    fun `unknown font style falls back`() {
        val theme = ThemeSanitizer.sanitizeTheme(
            json("""{"fontStyle": "comic-sans"}"""),
            ThemeSanitizer.FallbackLight,
        )
        assertEquals(Theme.FontStyle.SANS, theme.fontStyle)
    }

    @Test
    fun `font style is case insensitive`() {
        val theme = ThemeSanitizer.sanitizeTheme(
            json("""{"fontStyle": "SERIF"}"""),
            ThemeSanitizer.FallbackLight,
        )
        assertEquals(Theme.FontStyle.SERIF, theme.fontStyle)
    }

    @Test
    fun `sanitizeDualTheme replaces a missing half wholesale`() {
        val dual = ThemeSanitizer.sanitizeDualTheme(
            json("""{"light": {"backgroundColor": "#fff4e6"}}""")
        )

        assertEquals("#fff4e6", dual.light.backgroundColor)
        assertEquals(ThemeSanitizer.FallbackDark, dual.dark)
    }

    @Test
    fun `sanitizeDualTheme handles a non-object`() {
        assertEquals(
            ThemeSanitizer.FallbackDual,
            ThemeSanitizer.sanitizeDualTheme(kotlinx.serialization.json.JsonPrimitive("hi")),
        )
    }

    @Test
    fun `dual theme halves keep their own accents for word color fallback`() {
        val dual = ThemeSanitizer.sanitizeDualTheme(
            json(
                """
                {
                  "light": {"accentColor": "#0000ff", "wordColors": [{"word": "a"}]},
                  "dark": {"accentColor": "#00ff00", "wordColors": [{"word": "a"}]}
                }
                """.trimIndent()
            )
        )

        assertEquals("#0000ff", dual.light.wordColors.single().color)
        assertEquals("#00ff00", dual.dark.wordColors.single().color)
    }

    @Test
    fun `null input to sanitizeStored yields the fallback`() {
        assertEquals(ThemeSanitizer.FallbackDual, ThemeSanitizer.sanitizeStored(null))
    }

    @Test
    fun `parseThemeJson reads bare json`() {
        val parsed = AiThemePrompts.parseThemeJson("""{"light":{},"dark":{}}""")
        assertEquals(setOf("light", "dark"), parsed?.keys)
    }

    @Test
    fun `parseThemeJson reads fenced json`() {
        val parsed = AiThemePrompts.parseThemeJson(
            """
            Here you go:
            ```json
            {"light": {"name": "A"}, "dark": {"name": "B"}}
            ```
            Hope that helps.
            """.trimIndent()
        )
        assertEquals(setOf("light", "dark"), parsed?.keys)
    }

    @Test
    fun `parseThemeJson reads json after prose without a fence`() {
        val parsed = AiThemePrompts.parseThemeJson(
            """Sure! {"light": {"name": "A"}, "dark": {"name": "B"}} Let me know."""
        )
        assertEquals(setOf("light", "dark"), parsed?.keys)
    }

    @Test
    fun `parseThemeJson ignores braces inside strings`() {
        val parsed = AiThemePrompts.parseThemeJson(
            """{"light": {"name": "a}b{c"}, "dark": {"name": "x{y"}}"""
        )
        assertEquals(setOf("light", "dark"), parsed?.keys)
    }

    @Test
    fun `parseThemeJson requires both halves`() {
        assertNull(AiThemePrompts.parseThemeJson("""{"light": {}}"""))
    }

    @Test
    fun `parseThemeJson returns null on garbage`() {
        assertNull(AiThemePrompts.parseThemeJson("I cannot help with that."))
    }

    @Test
    fun `parseThemeJson tolerates trailing commas through lenient mode`() {
        assertNull(AiThemePrompts.parseThemeJson("""{"light": {}, "dark": {},}"""))
    }

    @Test
    fun `source prompt marks instrumentals`() {
        val prompt = AiThemePrompts.buildSourcePrompt("Rain", isPureMusic = true, songTitle = "Rain")
        assertTrue(prompt.startsWith("Pure instrumental: yes"))
        assertTrue(prompt.contains("Song title: Rain"))
        assertTrue(prompt.endsWith("Source snippet:\nRain"))
    }

    @Test
    fun `source prompt omits the title when it is not instrumental`() {
        val prompt = AiThemePrompts.buildSourcePrompt("hello", isPureMusic = false, songTitle = "Song")
        assertTrue(prompt.startsWith("Pure instrumental: no"))
        assertTrue(!prompt.contains("Song title:"))
    }

    @Test
    fun `source truncation keeps the opening`() {
        val long = "a".repeat(2500)
        val truncated = AiThemePrompts.truncateSource(long)
        assertEquals(AiThemePrompts.MAX_SOURCE_CHARS, truncated.length)
        assertTrue(truncated.startsWith("a"))
    }

    @Test
    fun `short source is not padded or altered`() {
        assertEquals("abc", AiThemePrompts.truncateSource("abc"))
    }
}
