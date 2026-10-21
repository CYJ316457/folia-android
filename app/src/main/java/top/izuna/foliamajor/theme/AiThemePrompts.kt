package top.izuna.foliamajor.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Prompt text and response handling for AI theme generation.
 *
 * Ported from `src/utils/aiThemePrompts.ts` and the two serverless handlers
 * (`api-ts/generate-theme.ts`, `api-ts/generate-theme_openai.ts`). The desktop app hides the prompt
 * on the server: the browser posts lyrics and the Vercel function owns the instructions. On Android
 * there is no server of ours, so the client must send the prompt itself — which is why the whole
 * contract lives here as testable text.
 *
 * The prompt is kept verbatim, including its capitalisation quirks. It is tuned against real model
 * behaviour (the English-only instructions produce Chinese output, the "do not default to pure
 * white" lines exist because models did exactly that), and rewriting it would silently change the
 * quality of every generated theme.
 */
object AiThemePrompts {

    /**
     * Output cap for the source text.
     *
     * Matches the server handler's `lyricsText.slice(0, 2000)`. A whole song adds tokens without
     * changing the mood, and the interesting part is the chorus, which is usually early.
     */
    const val MAX_SOURCE_CHARS = 2000

    val THEME_GENERATION_PROMPT_PREFIX: String = """
        Analyze the mood of the provided song source text and generate TWO visual theme configurations for a music player - one for LIGHT mode and one for DARK mode.

        DUAL THEME REQUIREMENTS:
        1. Generate TWO complete themes: one optimized for LIGHT/DAYLIGHT mode, one for DARK/MIDNIGHT mode.
        2. Both themes should capture the SAME emotional essence of the source text, but with appropriate color palettes for their respective modes.
        3. The theme names must be in Chinese and strictly limited to 10 characters or less. They should reflect both the mood AND the mode (e.g., "忧郁破晓" for light, "忧郁子夜" for dark).
        4. The theme description must be a brief, emotional sentence in Chinese (strictly limited to 15 to 30 Chinese characters) reflecting a stream-of-consciousness style with youth and literary characteristics, capturing a listener's immediate emotional reaction to this song. Do not write formal analytical text. Must be written from a first-person listener perspective.
           GUIDELINES FOR THE EXPRESSIVE STYLE:
           - Stream of Consciousness & Literary Vibe: Emphasize poetic, reflective, or introspective thoughts (e.g., emotional connection, existential thoughts, quiet solitude).
           - Youth & Nostalgia: Associate the mood with nostalgic memories of youth, dreams, seasons, or romantic longing.
           - Spatial & Situational Synesthesia: Translate the music's vibe into a vivid situation, atmosphere, weather, or imagery (e.g., summer breeze, starry sky, quiet room).
           Examples for reference: "戴上耳机的那一刻，喧嚣的世界瞬间消失了。", "然后，这份爱编织了太阳和所有星星", "你的世界，也包括我在内吗？", "微醺的夏夜吹拂过一阵海风。", "青春是一种眺望的姿态！", "仿佛回到了那个满是汽水味和单车后座的夏天。"。

        SOURCE MODE:
        1. If 'Pure instrumental' is yes, the source text below is the song title of a pure instrumental track, not lyrics.
        2. If 'Pure instrumental' is no, the source text below is a lyrics snippet.
        3. Base your mood inference only on the provided source text.

        COLOR & THEME GENERATION WORKFLOW:
        1. First, identify 10-20 key emotional standalone words from the source text that represent the core mood and atmosphere of the song.
        2. Assign a specific, representative color to each of these key emotional standalone words under 'wordColors'.
        3. Based on the emotional direction and colors of these identified words, construct the overall color palettes (backgroundColor, primaryColor, secondaryColor, accentColor) for the light and dark themes.
        4. Coordinated Colors: The colors assigned in 'wordColors' must be designed in coordination and harmony with the overall color schemes of the themes.

        LIGHT THEME RULES:
        - Use LIGHT backgrounds. Avoid defaulting to pure white background for every light theme. Generate diverse and rich light-colored backgrounds (e.g., warm creams, soft pastel blues, pale sage greens, gentle peach, warm sands, pale lavenders) that directly match the song's mood.
        - Ensure text/icons are dark enough for contrast, but avoid defaulting to pure black (#000000). Generate a very dark tone that coordinates with the background color's hue (e.g., deep navy, dark charcoal, dark plum).
        - 'accentColor' must be visible against the light background.

        DARK THEME RULES:
        - Use DARK backgrounds. Avoid generic pure black backgrounds; use rich, diverse dark colors (e.g., deep midnight blue, dark forest green, charcoal gray, dark plum, deep chocolate, burgundy) matching the song's mood.
        - Ensure text/icons are light enough for contrast, but avoid defaulting to pure white (#ffffff). Generate a very bright, soft tone that coordinates with the background color's hue (e.g., soft sky blue, pale mint green, light warm cream).
        - 'accentColor' must contrast with the dark background and should be creatively derived from the song's specific mood (e.g., soft blues, mint greens, warm corals, lavender, pale gold) rather than defaulting to generic bright yellow.

        SHARED RULES FOR BOTH THEMES:
        1. CRITICAL for 'secondaryColor': This color is used for secondary TEXT (e.g., album name, artist name).
           - It MUST have sufficient contrast against 'backgroundColor' to be easily readable.
           - Aim for a contrast ratio of at least 4.5:1 for accessibility.
        2. 'wordColors' and 'lyricsIcons' should be the SAME for both themes (they represent the source text's meaning).

        IMPORTANT for 'wordColors':
        1. Extract 10-20 emotional standalone words. For Latin-script text, each 'word' MUST be one complete word only, not a phrase.
        2. CRITICAL: Do NOT include punctuation, apostrophes, curly quotes, hyphens, or spaces in Latin-script 'word' values. Use clean whole words like "train", "gone", "hidden", "cities"; do NOT return "train’s gone", "well-hidden", "set me free", or "shun the light".
        3. Avoid function words such as articles, prepositions, pronouns, particles, and auxiliaries (for example: the, a, an, to, me, and, of, in, on).
        4. For CJK lyrics, short meaningful semantic terms may contain multiple CJK characters, but do not select single particles unless they are emotionally meaningful.
        5. The 'word' field MUST match text from the source snippet after removing surrounding punctuation. If the pure-instrumental title is very short, using the exact full title as a phrase is allowed.

        IMPORTANT for 'lyricsIcons':
        1. Identify 3-5 visual concepts/objects mentioned in or strongly implied by the source text.
        2. Return them as valid Lucide React icon names (PascalCase, e.g., 'CloudLightning', 'HeartHandshake').

        OUTPUT CONTRACT:
        Return one JSON object with required "light" and "dark" objects. Each theme object requires "name", "backgroundColor", "primaryColor", "accentColor", "secondaryColor", "wordColors", and "lyricsIcons".

        Expected JSON structure:
        {
          "light": {
            "name": "string (max 10 chars, in Chinese)",
            "description": "string (15-30 chars, in Chinese)",
            "backgroundColor": "hex string",
            "primaryColor": "hex string",
            "accentColor": "hex string",
            "secondaryColor": "hex string",
            "wordColors": [
              {
                "word": "string",
                "color": "hex string"
              }
            ],
            "lyricsIcons": ["string", "string"]
          },
          "dark": {
            "name": "string (max 10 chars, in Chinese)",
            "description": "string (15-30 chars, in Chinese)",
            "backgroundColor": "hex string",
            "primaryColor": "hex string",
            "accentColor": "hex string",
            "secondaryColor": "hex string",
            "wordColors": [
              {
                "word": "string",
                "color": "hex string"
              }
            ],
            "lyricsIcons": ["string", "string"]
          }
        }
    """.trimIndent()

    /**
     * The user-turn payload: a mode flag followed by the source text.
     *
     * The mode flag is separate from the system prompt because the model treats "this is a title,
     * not lyrics" as the single most important distinction — an instrumental's title is 2–5 words
     * and asking for emotional keywords from it needs that framing.
     */
    fun buildSourcePrompt(
        snippet: String,
        isPureMusic: Boolean,
        songTitle: String? = null,
    ): String = buildString {
        append("Pure instrumental: ").append(if (isPureMusic) "yes" else "no").append('\n')
        if (isPureMusic && !songTitle.isNullOrBlank()) {
            append("Song title: ").append(songTitle.trim()).append('\n')
        }
        append("Source snippet:\n").append(snippet)
    }

    /**
     * Truncates from the end, not the middle.
     *
     * A lyric's opening verse already establishes the mood, and the desktop app makes the same
     * choice; keeping a middle slice would cut a line in half and confuse the word-matching
     * instruction.
     */
    fun truncateSource(text: String): String = text.take(MAX_SOURCE_CHARS)

    private val lenientJson = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * Finds the first complete balanced `{...}` in [text].
     *
     * Depth counting has to respect string literals: a lyric-derived keyword can legitimately
     * contain braces inside a quoted value, and a naive brace count would end the object early.
     */
    private fun findBalancedJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val char = text[index]

            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private val FENCE_PATTERN = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    /**
     * Every substring that could plausibly be the JSON document.
     *
     * Models answer in three shapes depending on the provider: bare JSON, fenced JSON, or a JSON
     * object with a sentence of prose in front of it. All three appear in practice, so all three are
     * tried rather than failing a generation the user already paid for.
     */
    internal fun jsonCandidates(input: String): List<String> {
        val trimmed = input.trim()
        val candidates = mutableListOf(trimmed)

        FENCE_PATTERN.findAll(trimmed).forEach { match ->
            candidates.add(match.groupValues[1].trim())
        }

        findBalancedJsonObject(trimmed)?.let(candidates::add)

        return candidates.filter { it.isNotEmpty() }.distinct()
    }

    private fun hasDualThemeShape(parsed: JsonObject): Boolean =
        parsed["light"] is JsonObject && parsed["dark"] is JsonObject

    /**
     * Extracts the theme object from a model response.
     *
     * Returns null rather than throwing: the caller turns that into a user-visible status message,
     * and an exception would have to carry the same information anyway.
     */
    fun parseThemeJson(input: String): JsonObject? {
        for (candidate in jsonCandidates(input)) {
            val parsed = runCatching {
                lenientJson.parseToJsonElement(candidate).jsonObject
            }.getOrNull() ?: continue

            if (hasDualThemeShape(parsed)) return parsed
        }
        return null
    }
}
