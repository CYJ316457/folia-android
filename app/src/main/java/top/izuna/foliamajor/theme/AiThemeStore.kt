package top.izuna.foliamajor.theme

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists the AI settings and the generated themes.
 *
 * Two separate files because they have different lifetimes and sensitivities: the config holds an
 * API key and belongs to this install, while generated themes are content that would be worth
 * exporting later. Keeping them apart means a future "export my themes" feature cannot accidentally
 * include the key.
 */
class AiThemeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val themeFile = java.io.File(context.filesDir, "ai-themes.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class ThemeRecord(
        val songKey: String,
        val lightJson: String,
        val darkJson: String,
        val generatedAt: Long,
    )

    @Serializable
    private data class ThemeSnapshot(
        val version: Int = SNAPSHOT_VERSION,
        val themes: List<ThemeRecord> = emptyList(),
    )

    // --- Config ----------------------------------------------------------------------------------

    /**
     * Reads the config.
     *
     * The provider is stored by name rather than ordinal so adding a provider later cannot silently
     * reinterpret an existing install's setting.
     */
    fun loadConfig(): AiThemeConfig {
        val providerName = prefs.getString(KEY_PROVIDER, null)
        val provider = AiThemeProvider.entries.firstOrNull { it.name == providerName }
            ?: AiThemeConfig().provider

        return AiThemeConfig(
            provider = provider,
            baseUrl = prefs.getString(KEY_BASE_URL, null) ?: defaultUrlFor(provider),
            model = prefs.getString(KEY_MODEL, null) ?: defaultModelFor(provider),
            apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
            autoGenerate = prefs.getBoolean(KEY_AUTO_GENERATE, false),
        )
    }

    fun saveConfig(config: AiThemeConfig) {
        prefs.edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_API_KEY, config.apiKey)
            .putBoolean(KEY_AUTO_GENERATE, config.autoGenerate)
            .apply()
    }

    /**
     * The default endpoint for a provider.
     *
     * Used both for a fresh install and when the user switches provider: keeping the previous
     * provider's URL after a switch would send a Gemini request to an OpenAI endpoint.
     */
    fun defaultUrlFor(provider: AiThemeProvider): String = when (provider) {
        AiThemeProvider.GEMINI -> AiThemeConfig.DEFAULT_GEMINI_URL
        AiThemeProvider.OPENAI_COMPATIBLE -> AiThemeConfig.DEFAULT_OPENAI_URL
    }

    fun defaultModelFor(provider: AiThemeProvider): String = when (provider) {
        AiThemeProvider.GEMINI -> AiThemeConfig.GEMINI_MODEL
        AiThemeProvider.OPENAI_COMPATIBLE -> AiThemeConfig.DEFAULT_OPENAI_MODEL
    }

    // --- Generated themes ------------------------------------------------------------------------

    /**
     * Caches one theme per song.
     *
     * Keyed by the playback key (`local:<id>` / `online:<id>`), the same identity the desktop app
     * uses, so a song does not regenerate a theme when it is played again. The theme is stored as
     * raw JSON per half rather than as a typed record: [ThemeSanitizer] is the only writer, and
     * storing the sanitizer's output means a future field addition is a sanitizer change, not a
     * migration.
     */
    fun saveTheme(songKey: String, theme: DualTheme, now: Long = System.currentTimeMillis()) {
        val snapshot = loadSnapshot()
        val record = ThemeRecord(
            songKey = songKey,
            lightJson = json.encodeToString(ThemeDto.serializer(), theme.light.toDto()),
            darkJson = json.encodeToString(ThemeDto.serializer(), theme.dark.toDto()),
            generatedAt = now,
        )

        val next = snapshot.themes.filterNot { it.songKey == songKey } + record
        writeSnapshot(snapshot.copy(themes = next))
    }

    /** The cached theme for a song, or null when it was never generated. */
    fun loadTheme(songKey: String): DualTheme? {
        val record = loadSnapshot().themes.firstOrNull { it.songKey == songKey } ?: return null
        val light = runCatching {
            json.decodeFromString(ThemeDto.serializer(), record.lightJson).toModel()
        }.getOrNull() ?: return null
        val dark = runCatching {
            json.decodeFromString(ThemeDto.serializer(), record.darkJson).toModel()
        }.getOrNull() ?: return null
        return DualTheme(light = light, dark = dark)
    }

    fun deleteTheme(songKey: String) {
        val snapshot = loadSnapshot()
        if (snapshot.themes.none { it.songKey == songKey }) return
        writeSnapshot(snapshot.copy(themes = snapshot.themes.filterNot { it.songKey == songKey }))
    }

    fun allThemeKeys(): Set<String> = loadSnapshot().themes.map { it.songKey }.toSet()

    private fun loadSnapshot(): ThemeSnapshot {
        if (!themeFile.exists()) return ThemeSnapshot()
        return runCatching {
            json.decodeFromString(ThemeSnapshot.serializer(), themeFile.readText())
        }.getOrDefault(ThemeSnapshot())
    }

    /** Temp-file + rename, so an interrupted write cannot leave an unparseable theme cache. */
    private fun writeSnapshot(snapshot: ThemeSnapshot) {
        val temp = java.io.File(themeFile.parentFile, "${themeFile.name}.tmp")
        temp.writeText(json.encodeToString(ThemeSnapshot.serializer(), snapshot))
        if (!temp.renameTo(themeFile)) {
            themeFile.delete()
            temp.renameTo(themeFile)
        }
    }

    @Serializable
    private data class ThemeDto(
        val name: String,
        val backgroundColor: String,
        val primaryColor: String,
        val accentColor: String,
        val secondaryColor: String,
        val fontStyle: String = "SANS",
        val fontWeight: Int? = null,
        val animationIntensity: String = "NORMAL",
        val wordColors: List<WordColorDto> = emptyList(),
        val lyricsIcons: List<String> = emptyList(),
        val description: String? = null,
        val provider: String? = null,
    )

    @Serializable
    private data class WordColorDto(val word: String, val color: String)

    private fun Theme.toDto(): ThemeDto = ThemeDto(
        name = name,
        backgroundColor = backgroundColor,
        primaryColor = primaryColor,
        accentColor = accentColor,
        secondaryColor = secondaryColor,
        fontStyle = fontStyle.name,
        fontWeight = fontWeight,
        animationIntensity = animationIntensity.name,
        wordColors = wordColors.map { WordColorDto(it.word, it.color) },
        lyricsIcons = lyricsIcons,
        description = description,
        provider = provider,
    )

    private fun ThemeDto.toModel(): Theme = Theme(
        name = name,
        backgroundColor = backgroundColor,
        primaryColor = primaryColor,
        accentColor = accentColor,
        secondaryColor = secondaryColor,
        fontStyle = runCatching { Theme.FontStyle.valueOf(fontStyle) }.getOrDefault(Theme.FontStyle.SANS),
        fontWeight = fontWeight,
        animationIntensity = runCatching { Theme.AnimationIntensity.valueOf(animationIntensity) }
            .getOrDefault(Theme.AnimationIntensity.NORMAL),
        wordColors = wordColors.map { WordColor(it.word, it.color) },
        lyricsIcons = lyricsIcons,
        description = description,
        provider = provider,
    )

    companion object {
        private const val PREFS_NAME = "ai_theme_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_AUTO_GENERATE = "auto_generate"

        /** Bumped when the theme record shape changes; older files are read as empty. */
        const val SNAPSHOT_VERSION = 1
    }
}
