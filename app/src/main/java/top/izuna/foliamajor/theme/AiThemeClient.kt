package top.izuna.foliamajor.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import top.izuna.foliamajor.online.HttpJson

/**
 * Calls a theme-capable LLM and returns a sanitised [DualTheme].
 *
 * Two wire formats are supported because the desktop app supports two, and a self-hosted
 * OpenAI-compatible gateway is the only option that does not require sending lyrics to a specific
 * vendor.
 *
 * The response never leaves this class un-sanitised: callers get a [DualTheme] that is guaranteed to
 * have valid hex colours, so no render site has to defend against model output.
 */
class AiThemeClient(
    private val http: HttpJson = HttpJson(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** A failure with a message meant for the status line, not a stack trace. */
    class ThemeGenerationException(message: String) : Exception(message)

    /**
     * Generates a theme for [sourceText].
     *
     * [sourceText] is lyrics, or the title when the track is instrumental — the caller decides,
     * matching the desktop hook's `isPureMusic ? songTitle : allText` choice. An empty source is a
     * programming error rather than a user error, so it fails loudly here instead of sending a
     * prompt that would produce an arbitrary theme.
     */
    suspend fun generate(
        sourceText: String,
        config: AiThemeConfig,
        isPureMusic: Boolean = false,
        songTitle: String? = null,
    ): DualTheme {
        if (!config.isConfigured) {
            throw ThemeGenerationException("AI theme is not configured")
        }

        val snippet = AiThemePrompts.truncateSource(sourceText)
        if (snippet.isBlank()) {
            throw ThemeGenerationException("Nothing to analyse")
        }

        val sourcePrompt = AiThemePrompts.buildSourcePrompt(snippet, isPureMusic, songTitle)

        val raw = when (config.provider) {
            AiThemeProvider.GEMINI -> requestGemini(sourcePrompt, config)
            AiThemeProvider.OPENAI_COMPATIBLE -> requestOpenAiCompatible(sourcePrompt, config)
        }

        val parsed = AiThemePrompts.parseThemeJson(raw)
            ?: throw ThemeGenerationException("The model did not return theme JSON")

        return stampProvider(ThemeSanitizer.sanitizeDualTheme(parsed), config.provider)
    }

    /**
     * Stamps the provider and pins the font style.
     *
     * The desktop handler forces `fontStyle = 'sans'` after sanitising, because the generator
     * occasionally answers "serif" and a serif lyric field reads as a different product. The same
     * rule is applied in the same place so both clients agree on what a generated theme looks like.
     */
    private fun stampProvider(theme: DualTheme, provider: AiThemeProvider): DualTheme {
        val label = when (provider) {
            AiThemeProvider.GEMINI -> AiThemeSource.GEMINI.providerLabel
            AiThemeProvider.OPENAI_COMPATIBLE -> AiThemeSource.OPENAI_COMPATIBLE.providerLabel
        }
        return DualTheme(
            light = theme.light.copy(fontStyle = Theme.FontStyle.SANS, provider = label),
            dark = theme.dark.copy(fontStyle = Theme.FontStyle.SANS, provider = label),
        )
    }

    // --- Gemini ----------------------------------------------------------------------------------

    /**
     * Gemini's request body.
     *
     * `responseMimeType` plus `responseSchema` is what makes the model return the object instead of
     * prose around it. Without the schema the model still returns JSON often enough to be tempting,
     * but the failure rate on a 10-key nested object is high enough to matter.
     */
    internal fun geminiRequest(sourcePrompt: String, config: AiThemeConfig): String =
        buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    addJsonObject { put("text", AiThemePrompts.THEME_GENERATION_PROMPT_PREFIX) }
                })
            })
            put("contents", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        addJsonObject { put("text", sourcePrompt) }
                    })
                }
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseSchema", geminiResponseSchema())
            })
        }.toString()

    private suspend fun requestGemini(sourcePrompt: String, config: AiThemeConfig): String {
        val base = config.baseUrl.trim().trimEnd('/')
        // Users paste either the API root or a full model URL; accept both, because the error for
        // the wrong one is an opaque 404 from Google.
        val url = if (base.contains(":generateContent")) {
            base
        } else if (base.endsWith("/models")) {
            "$base/${config.model}:generateContent"
        } else {
            "$base/models/${config.model}:generateContent"
        }

        val response = http.postJson(
            url = withKey(url, config.apiKey),
            body = geminiRequest(sourcePrompt, config),
            headers = mapOf("Content-Type" to "application/json"),
        )

        if (!response.isSuccessful) {
            throw ThemeGenerationException(describeFailure("Gemini", response))
        }

        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw ThemeGenerationException("Gemini returned an unreadable response")

        // A blocked prompt comes back as a 200 with no candidates and a finishReason.
        val candidate = root["candidates"]?.jsonArrayOrNull()?.firstOrNull() as? JsonObject
            ?: throw ThemeGenerationException(blockedReason(root) ?: "Gemini returned no candidates")

        val text = candidate["content"]
            ?.let { it as? JsonObject }
            ?.get("parts")
            ?.jsonArrayOrNull()
            ?.mapNotNull { part ->
                (part as? JsonObject)?.get("text")
                    ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            }
            ?.joinToString("")
            .orEmpty()

        return text.ifBlank {
            throw ThemeGenerationException(blockedReason(root) ?: "Gemini returned an empty response")
        }
    }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    /** Reads `promptFeedback.blockReason`, which is how Gemini reports a refusal. */
    private fun blockedReason(root: JsonObject): String? {
        val reason = (root["promptFeedback"] as? JsonObject)
            ?.get("blockReason")
            ?.let { (it as? JsonPrimitive)?.content }
        return reason?.let { "Gemini blocked the request ($it)" }
    }

    /**
     * Appends the key as a query parameter.
     *
     * Gemini takes `?key=`, not an Authorization header. Handled here rather than by the caller so a
     * stored key that already carries a query string cannot produce a malformed URL.
     */
    private fun withKey(url: String, apiKey: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}key=$apiKey"
    }

    /**
     * The schema sent to Gemini.
     *
     * Only the required keys are marked required, matching the desktop handler: making `description`
     * mandatory caused the model to invent filler, and an absent description is handled by the
     * sanitizer's fallback.
     */
    private fun geminiResponseSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonArray("required") { add("light"); add("dark") }
        put("properties", buildJsonObject {
            put("light", themeObjectSchema("Theme optimized for light/daylight mode", "light background", "dark text"))
            put("dark", themeObjectSchema("Theme optimized for dark/midnight mode", "dark background", "light text"))
        })
    }

    private fun themeObjectSchema(description: String, backgroundHint: String, textHint: String): JsonObject =
        buildJsonObject {
            put("type", "OBJECT")
            put("description", description)
            putJsonArray("required") {
                add("name"); add("backgroundColor"); add("primaryColor")
                add("accentColor"); add("secondaryColor")
            }
            put("properties", buildJsonObject {
                put("name", schemaString("A creative name for this theme in Chinese, max 10 characters"))
                put("description", schemaString("A 15-30 character Chinese sentence describing the mood"))
                put("backgroundColor", schemaString("Hex code for $backgroundHint"))
                put("primaryColor", schemaString("Hex code for main text ($textHint)"))
                put("accentColor", schemaString("Hex code for highlighted text/effects"))
                put("secondaryColor", schemaString("Hex code for secondary elements"))
                put("wordColors", buildJsonObject {
                    put("type", "ARRAY")
                    put("description", "Emotional standalone words and their colors")
                    put("items", buildJsonObject {
                        put("type", "OBJECT")
                        putJsonArray("required") { add("word"); add("color") }
                        put("properties", buildJsonObject {
                            put("word", schemaString("One whole word, no punctuation or spaces"))
                            put("color", schemaString("Hex color for this word"))
                        })
                    })
                })
                put("lyricsIcons", buildJsonObject {
                    put("type", "ARRAY")
                    put("description", "Lucide icon names related to the source text")
                    put("items", buildJsonObject { put("type", "STRING") })
                })
            })
        }

    private fun schemaString(description: String): JsonObject = buildJsonObject {
        put("type", "STRING")
        put("description", description)
    }

    // --- OpenAI-compatible -----------------------------------------------------------------------

    /**
     * An OpenAI chat-completions body.
     *
     * `response_format: json_object` rather than `json_schema` because the latter is rejected by
     * every non-OpenAI gateway (DeepSeek, vLLM, Ollama). The desktop app negotiates this by
     * retrying without the parameter; on Android the prompt already contains the expected JSON
     * shape, so the cheaper and more portable choice is to ask for a JSON object and parse
     * defensively.
     */
    internal fun openAiRequest(sourcePrompt: String, config: AiThemeConfig): String =
        buildJsonObject {
            put("model", config.model)
            put("temperature", 0.7)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "system")
                    put("content", AiThemePrompts.THEME_GENERATION_PROMPT_PREFIX)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", sourcePrompt)
                }
            })
        }.toString()

    private suspend fun requestOpenAiCompatible(sourcePrompt: String, config: AiThemeConfig): String {
        val response = http.postJson(
            url = config.baseUrl.trim(),
            body = openAiRequest(sourcePrompt, config),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer ${config.apiKey.trim()}",
            ),
        )

        if (!response.isSuccessful) {
            throw ThemeGenerationException(describeFailure("AI provider", response))
        }

        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw ThemeGenerationException("The provider returned an unreadable response")

        val message = root["choices"]
            ?.jsonArrayOrNull()
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("message") as? JsonObject
            ?: throw ThemeGenerationException("The provider returned no message")

        message["refusal"]?.let { refusal ->
            val text = (refusal as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!text.isNullOrBlank()) {
                throw ThemeGenerationException("The model refused: ${text.trim()}")
            }
        }

        // Some gateways answer with a content array instead of a string.
        return when (val content = message["content"]) {
            is JsonPrimitive -> content.takeIf { it.isString }?.content.orEmpty()
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                val type = (obj["type"] as? JsonPrimitive)?.content
                val text = (obj["text"] as? JsonPrimitive)?.content
                if (type == "text") text else null
            }.joinToString("")
            else -> ""
        }.ifBlank { throw ThemeGenerationException("The model returned an empty response") }
    }

    /**
     * Turns a provider error body into one line.
     *
     * Providers disagree on the shape (`{error: {message}}`, `{error: "..."}`, `{message: "..."}`),
     * and the status code alone is not actionable — "401" without "invalid api key" leaves the user
     * guessing whether the URL or the key is wrong.
     */
    internal fun describeFailure(prefix: String, response: HttpJson.Response): String {
        val detail = runCatching {
            val root = json.parseToJsonElement(response.body).jsonObject
            val error = root["error"]
            when (error) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.content
                is JsonPrimitive -> error.content
                else -> (root["message"] as? JsonPrimitive)?.content
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return if (detail != null) {
            "$prefix error ${response.status}: $detail"
        } else {
            "$prefix error ${response.status}"
        }
    }

    companion object {
        /**
         * The desktop handler uses 4096 for OpenAI and 8192 for the fallback path. The output is a
         * small object, so the larger ceiling only matters when a gateway ignores `response_format`
         * and wraps the JSON in prose.
         */
        const val MAX_OUTPUT_TOKENS = 4096

        /** JSON null, used where the schema wants an explicit empty value. */
        internal val Empty: JsonElement = JsonNull
    }
}
