package top.izuna.foliamajor.theme

import kotlinx.serialization.Serializable

/**
 * Which service generates themes, and how to reach it.
 *
 * The desktop app never holds a key: the browser posts lyrics to its own Vercel function, which
 * reads `GEMINI_API_KEY` / `OPENAI_API_KEY` from the environment. Android has no such server, so the
 * key lives on the device and the endpoint is user-supplied. That is a real difference in the trust
 * model, and the reason [AiThemeConfig] defaults to "not configured" rather than shipping a key: a
 * key baked into an APK is a key given away.
 *
 * Both providers speak the same request shape once the body is built, so the enum only selects the
 * wire format (Gemini's `generateContent` vs. OpenAI's `chat/completions`).
 */
enum class AiThemeProvider {
    /** Google Gemini `generateContent`, which returns a JSON string in `candidates[0].content`. */
    GEMINI,

    /** Any OpenAI-compatible `/chat/completions` endpoint, including self-hosted gateways. */
    OPENAI_COMPATIBLE,
}

/**
 * Persisted settings for AI theme generation.
 *
 * Stored in SharedPreferences rather than the library snapshot: it is device configuration (a key
 * is tied to this install), and mixing it into the library JSON would sync a secret to any backup
 * that file ends up in.
 */
@Serializable
data class AiThemeConfig(
    val provider: AiThemeProvider = AiThemeProvider.OPENAI_COMPATIBLE,
    /** Full chat-completions URL, e.g. `https://api.deepseek.com/v1/chat/completions`. */
    val baseUrl: String = DEFAULT_OPENAI_URL,
    val model: String = DEFAULT_OPENAI_MODEL,
    val apiKey: String = "",
    /** Generate a theme automatically when a new song starts, instead of only on demand. */
    val autoGenerate: Boolean = false,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        /** Gemini's model names change often; this is the one the desktop handler pins. */
        const val GEMINI_MODEL = "gemini-2.0-flash"
        const val DEFAULT_GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/**
 * Where a theme came from, as a stable string.
 *
 * Kept as an enum plus a label rather than the raw provider string because the desktop app stamps
 * "Google Gemini" / "OpenAI Compatible" into the theme itself and the UI shows that label verbatim.
 */
enum class AiThemeSource(val providerLabel: String) {
    GEMINI("Google Gemini"),
    OPENAI_COMPATIBLE("OpenAI Compatible"),
    MANUAL("Manual"),
}
