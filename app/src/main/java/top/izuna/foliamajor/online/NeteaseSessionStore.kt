package top.izuna.foliamajor.online

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists the NetEase session: the login cookie, the anonymous cookie, and the account profile.
 *
 * The desktop app keeps these in `localStorage` under `netease_cookie` / `netease_anonymous_cookie`
 * and sends the login cookie as a `cookie=` query parameter on every request. Its API is a Node
 * process it owns, so it can also rely on real HTTP cookies; a self-hosted instance on a different
 * host cannot, which is why the query parameter form exists in the desktop web build too. This store
 * holds the same three values so both clients can talk to the same API instance.
 *
 * The cookie is a bearer credential — anyone with it is logged in as the user — so it is kept out of
 * the library snapshot (which a backup might contain) and in its own file.
 */
class NeteaseSessionStore(context: Context) {

    @Serializable
    data class Session(
        /** The login cookie from a confirmed QR scan; absent when signed out. */
        val cookie: String = "",
        /**
         * The anonymous cookie issued by `/register/anonimous`.
         *
         * It lets search and song URLs work without an account. The desktop app fetches it lazily on
         * the first request that needs it and drops it when the server answers 301/401/403, because
         * an expired anonymous cookie makes the API refuse requests it would otherwise allow.
         */
        val anonymousCookie: String = "",
        val userId: Long = 0L,
        val nickname: String = "",
        val avatarUrl: String = "",
        val vipType: Int = 0,
    ) {
        val isSignedIn: Boolean get() = cookie.isNotBlank() && userId > 0L
    }

    private val file = java.io.File(context.filesDir, "netease-session.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): Session {
        if (!file.exists()) return Session()
        return runCatching {
            json.decodeFromString(Session.serializer(), file.readText())
        }.getOrDefault(Session())
    }

    fun save(session: Session) {
        val temp = java.io.File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(Session.serializer(), session))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    /** Clears the login cookie but keeps the anonymous one, which is independent of the account. */
    fun signOut(): Session {
        val next = load().copy(cookie = "", userId = 0L, nickname = "", avatarUrl = "", vipType = 0)
        save(next)
        return next
    }

    fun setAnonymousCookie(cookie: String): Session {
        val next = load().copy(anonymousCookie = cookie)
        save(next)
        return next
    }

    /** Drops the anonymous cookie after the API rejects it, so the next request fetches a fresh one. */
    fun clearAnonymousCookie(): Session {
        val next = load().copy(anonymousCookie = "")
        save(next)
        return next
    }
}

/**
 * The self-hosted NetEase API address.
 *
 * `BuildConfig.NETEASE_API_BASE` is only the default. Every user of this app runs their own API
 * instance — there is no public one — so a URL baked into the APK would mean every user had to
 * rebuild the app to point at their server. Editing it at runtime is the difference between an
 * installable APK and a source checkout.
 */
class NeteaseEndpointStore(context: Context) {

    private val prefs = context.getSharedPreferences("netease_endpoint", Context.MODE_PRIVATE)

    fun load(defaultBase: String): String =
        prefs.getString(KEY_API_BASE, null)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultBase

    fun save(base: String) {
        prefs.edit().putString(KEY_API_BASE, base.trim()).apply()
    }

    /** Restores the compiled-in default. */
    fun reset() {
        prefs.edit().remove(KEY_API_BASE).apply()
    }

    companion object {
        private const val KEY_API_BASE = "api_base"
    }
}
