package top.izuna.foliamajor.online

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import top.izuna.foliamajor.lyrics.LyricData
import top.izuna.foliamajor.lyrics.LyricParseFormat
import top.izuna.foliamajor.lyrics.parseLRC
import top.izuna.foliamajor.lyrics.parseLyricsByFormat
import top.izuna.foliamajor.lyrics.parseYRC

/**
 * NetEase Cloud Music client.
 *
 * The desktop app runs NeteaseCloudMusicApiEnhanced inside its Electron main process and talks to
 * localhost. That is not possible on Android (the API is a Node package), so this client talks to a
 * self-hosted instance over HTTP instead — the same arrangement the desktop app already supports for
 * its web build via `VITE_NETEASE_API_BASE`.
 *
 * Response shapes are read through [JsonObject] rather than typed classes because this API has a long
 * history of adding fields and occasionally returning a different envelope for the same endpoint; a
 * missing field should degrade to "no result", not to a deserialization crash.
 *
 * Session handling mirrors the desktop client's: the login cookie is sent as a `cookie` query
 * parameter (cross-site HTTP cookies do not survive to a self-hosted API), and when there is no
 * login cookie an anonymous one is fetched from `/register/anonimous` so search and playback work
 * without an account.
 */
class NeteaseClient(
    /** Read per request, so changing the endpoint in settings takes effect without a restart. */
    private val apiBase: () -> String,
    private val http: HttpJson = HttpJson(),
    /** Supplies the current cookies; null in tests and for a signed-out, anonymous-free client. */
    private val session: (() -> NeteaseSessionStore.Session)? = null,
    /** Called when the API rejects the anonymous cookie, so the caller can persist the clear. */
    private val onAnonymousCookieRejected: () -> Unit = {},
) {

    @Serializable
    data class Song(
        val id: Long,
        val name: String,
        @SerialName("ar") val artists: List<Artist> = emptyList(),
        @SerialName("al") val album: Album? = null,
        val duration: Long = 0L,
    ) {
        @Serializable
        data class Artist(val id: Long = 0, val name: String = "")
        @Serializable
        data class Album(val id: Long = 0, val name: String = "", @SerialName("picUrl") val picUrl: String? = null)

        val artistLabel: String get() = artists.joinToString("/") { it.name }
        val coverUrl: String? get() = album?.picUrl
    }

    /** Raw lyric tracks as returned by `/lyric/new`, before format selection. */
    data class LyricPayload(
        val mainLrc: String?,
        val yrcLrc: String?,
        val transLrc: String?,
        val romanizationLrc: String?,
        val isPureMusic: Boolean,
    )

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // explicitNulls=false keeps a JSON `null` from overwriting a non-null default, which matters
        // because `ar`/`al` are absent rather than empty on some search results.
        explicitNulls = false
    }

    private fun endpoint(path: String): String = apiBase().trimEnd('/') + path

    /**
     * The URL for [path], with a `cookie` parameter when the session has one.
     *
     * Login and status endpoints deliberately get no cookie: they are what establishes the session,
     * and echoing an expired cookie back to them makes some API builds answer 301 instead of
     * returning the login status.
     */
    private fun authed(path: String, useAnonymous: Boolean = true): String {
        val base = endpoint(path)
        val current = session?.invoke() ?: return base

        val cookie = current.cookie.ifBlank {
            if (useAnonymous) current.anonymousCookie else ""
        }
        if (cookie.isBlank()) return base

        val separator = if (path.contains('?')) "&" else "?"
        return "$base${separator}cookie=${encode(cookie)}"
    }

    /**
     * Fetches an anonymous cookie when the session has neither a login nor an anonymous one.
     *
     * Called once before the first request that needs a cookie. Failure is not fatal — search still
     * works on instances that do not require one — so it returns null instead of throwing.
     */
    suspend fun ensureAnonymousCookie(current: NeteaseSessionStore.Session): String? {
        if (current.cookie.isNotBlank()) return null
        if (current.anonymousCookie.isNotBlank()) return current.anonymousCookie

        val root = http.getJson(endpoint("/register/anonimous?timestamp=${System.currentTimeMillis()}"), json)
            ?: return null
        return root["cookie"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * Reads a session-bearing endpoint and drops the anonymous cookie if the API rejected it.
     *
     * The anonymous cookie has a shorter life than a login cookie and the API answers 301/401/403
     * once it lapses. Without clearing it here every later request keeps sending the dead value and
     * keeps failing; the desktop client clears it at exactly this point for exactly this reason.
     */
    private suspend fun getSessionJson(path: String): JsonObject? {
        val root = http.getJson(authed(path), json)
        val current = session?.invoke()

        if (root != null && current != null && current.cookie.isBlank() && current.anonymousCookie.isNotBlank()) {
            val code = root["code"]?.jsonPrimitive?.intOrNull
            if (code == 301 || code == 401 || code == 403) {
                onAnonymousCookieRejected()
            }
        }
        return root
    }

    // --- Login -----------------------------------------------------------------------------------

    /** Step one: an opaque key the QR image is generated from. */
    suspend fun getQrKey(): String? {
        val root = http.getJson(endpoint("/login/qr/key?timestamp=${System.currentTimeMillis()}"), json)
            ?: return null
        return root["data"]?.jsonObject?.get("unikey")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Step two: the QR image as a data URL (`data:image/png;base64,...`).
     *
     * Requested with `qrimg=true` so the API renders the QR itself. Generating it client-side would
     * mean shipping a QR encoder for a single image.
     */
    suspend fun createQr(key: String): String? {
        val root = http.getJson(endpoint("/login/qr/create?key=${encode(key)}&qrimg=true"), json)
            ?: return null
        return root["data"]?.jsonObject?.get("qrimg")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    /** Step three: one poll of the scan timeline. */
    suspend fun checkQr(key: String): QrLoginProtocol.CheckResponse? {
        val root = http.getJson(endpoint("/login/qr/check?key=${encode(key)}"), json) ?: return null
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: return null
        return QrLoginProtocol.CheckResponse(
            code = code,
            cookie = root["cookie"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            message = root["message"]?.jsonPrimitive?.contentOrNull
                ?: root["msg"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * The signed-in account, or null.
     *
     * Two endpoints are checked and their ids must agree. `/login/status` alone is not enough: a
     * stale cookie makes it return a profile for the *previous* user while `/user/account` reports
     * the real one, and trusting the first would show the wrong name. The desktop provider does the
     * same comparison for the same reason.
     */
    suspend fun getAccount(current: NeteaseSessionStore.Session): NeteaseSessionStore.Session? {
        if (current.cookie.isBlank()) return null

        val statusRoot = http.getJson(
            endpoint("/login/status?timestamp=${System.currentTimeMillis()}&cookie=${encode(current.cookie)}"),
            json,
        ) ?: return null

        val profile = statusRoot["data"]?.jsonObject?.get("profile")?.jsonObject ?: return null
        val loginId = profile["userId"]?.asLongOrNull() ?: profile["id"]?.asLongOrNull()

        val accountRoot = http.getJson(
            endpoint("/user/account?timestamp=${System.currentTimeMillis()}&cookie=${encode(current.cookie)}"),
            json,
        ) ?: return null

        val accountProfile = accountRoot["profile"]?.jsonObject ?: return null
        val accountId = accountRoot["account"]?.jsonObject?.get("id")?.asLongOrNull()
            ?: accountProfile["userId"]?.asLongOrNull()

        if (loginId == null || accountId == null || loginId != accountId) return null

        // A refreshed cookie is sometimes returned here; keeping the newest one avoids a session
        // that works until the next expiry rotation.
        val refreshedCookie = statusRoot["cookie"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: current.cookie

        return current.copy(
            cookie = refreshedCookie,
            userId = accountId,
            nickname = accountProfile["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            avatarUrl = accountProfile["avatarUrl"]?.jsonPrimitive?.contentOrNull
                ?.replaceFirst("http://", "https://").orEmpty(),
            vipType = accountProfile["vipType"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /** Ends the session server-side. The local cookie is cleared by the caller either way. */
    suspend fun logout(current: NeteaseSessionStore.Session) {
        http.getJson(
            endpoint("/logout?timestamp=${System.currentTimeMillis()}&cookie=${encode(current.cookie)}"),
            json,
        )
    }

    /**
     * Searches songs via `/cloudsearch`.
     *
     * `/search` is deliberately not used: it caps at 30 results and omits `ar`/`al` on some songs,
     * which is exactly the metadata the library grid renders.
     */
    suspend fun searchSongs(keywords: String, limit: Int = 30, offset: Int = 0): List<Song> {
        val root = getSessionJson("/cloudsearch?keywords=${encode(keywords)}&limit=$limit&offset=$offset")
            ?: return emptyList()
        val songs = root["result"]?.jsonObject?.get("songs") ?: return emptyList()

        return runCatching {
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Song.serializer()), songs)
        }.getOrDefault(emptyList())
    }

    suspend fun getSongDetail(songId: Long): Song? {
        val root = getSessionJson("/song/detail?ids=$songId") ?: return null
        val songs = root["songs"] ?: return null

        return runCatching {
            json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(Song.serializer()),
                songs,
            ).firstOrNull()
        }.getOrNull()
    }

    /**
     * Resolves a playable stream URL.
     *
     * `/song/url/v1` is used rather than `/song/url` because only the v1 endpoint honours `level`;
     * the older one silently returns the provider's default bitrate. An unavailable song comes back
     * with `url: null` and a populated `freeTrialInfo`, which the caller reports rather than retrying.
     */
    suspend fun getSongUrl(songId: Long, level: String = "exhigh"): String? {
        val root = getSessionJson("/song/url/v1?id=$songId&level=$level&randomCNIP=true&https=true")
            ?: return null
        val first = (root["data"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject ?: return null

        // The API returns plain http for some CDN hosts; Android blocks cleartext by default and the
        // stream is the same either way.
        return first["url"]?.jsonPrimitive?.contentOrNull?.replaceFirst("http://", "https://")
    }

    /**
     * Fetches every lyric track available for a song.
     *
     * `/lyric/new` returns the word-by-word (YRC) and translation tracks together; the older `/lyric`
     * endpoint omits YRC entirely, so a word-timed song would silently render line-by-line.
     */
    suspend fun getLyricPayload(songId: Long): LyricPayload? {
        val root = getSessionJson("/lyric/new?id=$songId") ?: return null

        fun track(vararg keys: String): String? {
            for (key in keys) {
                val value = root[key]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull
                if (!value.isNullOrEmpty()) return value
            }
            return null
        }

        val mainLrc = track("lrc")
        val yrcLrc = track("yrc")
        val ytlrc = track("ytlrc")
        val yromalrc = track("yromalrc")
        val tlyric = track("tlyric")
        val romalrc = track("romalrc")

        // A word-timed song must use its word-timed translation track: the line-timed `tlyric` is
        // stamped on different boundaries, so pairing it with YRC lines drops most translations.
        val transLrc = if (yrcLrc != null && ytlrc != null) ytlrc else tlyric
        val romanizationLrc = if (yrcLrc != null && yromalrc != null) yromalrc else romalrc

        return LyricPayload(
            mainLrc = mainLrc,
            yrcLrc = yrcLrc,
            transLrc = transLrc,
            romanizationLrc = romanizationLrc,
            isPureMusic = hasPureMusicFlag(root) || isPureMusicLyricText(mainLrc),
        )
    }

    /**
     * Parses a payload into render-ready lyrics, preferring the word-by-word track.
     *
     * Returns null for instrumental tracks: NetEase reports those with a sentinel lyric body rather
     * than an empty one, and rendering "纯音乐，请欣赏" as a lyric line is worse than showing nothing.
     */
    fun toLyricData(payload: LyricPayload): LyricData? {
        val primary = payload.yrcLrc ?: payload.mainLrc ?: return null
        if (payload.isPureMusic) return null

        val format = if (payload.yrcLrc != null) {
            LyricParseFormat.YRC
        } else {
            LyricParseFormat.detect(payload.mainLrc)
        }

        // TTML carries its own inline translations, so the separately-fetched tracks are ignored for
        // it; the other formats take them as an aligned side track.
        val parsed = if (format == LyricParseFormat.TTML) {
            parseLyricsByFormat(format = format, content = primary)
        } else {
            parseLyricsByFormat(
                format = format,
                content = primary,
                translation = payload.transLrc.orEmpty(),
                romanization = payload.romanizationLrc.orEmpty(),
            )
        }

        return parsed.takeIf { it.lines.isNotEmpty() }
    }

    /** Convenience for callers that only want the final lyrics. */
    suspend fun getLyrics(songId: Long): LyricData? =
        getLyricPayload(songId)?.let { toLyricData(it) }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun hasPureMusicFlag(root: JsonObject): Boolean {
        fun read(key: String): Boolean =
            (root[key] as? JsonPrimitive)?.intOrNull == 1

        return read("pureMusic") || read("nolyric")
    }
}

/**
 * True when the lyric body is NetEase's instrumental placeholder.
 *
 * The check is deliberately on a normalized prefix rather than a full match: the placeholder is
 * localized and is sometimes followed by a credit line, so an exact comparison would miss it.
 */
private fun isPureMusicLyricText(text: String?): Boolean {
    val normalized = text?.replace(Regex("""\s+"""), "")?.removePrefix("\uFEFF") ?: return false
    if (normalized.isEmpty()) return false

    val placeholders = listOf("纯音乐", "请欣赏", "instrumental", "此歌曲为没有填词的纯音乐")
    val lowered = normalized.lowercase()
    return placeholders.any { lowered.startsWith(it.lowercase()) }
}

/** Reads `long` from a JSON element without throwing on a string-encoded number. */
internal fun JsonElement?.asLongOrNull(): Long? = when (this) {
    is JsonPrimitive -> longOrNull ?: contentOrNull?.toLongOrNull()
    else -> null
}
