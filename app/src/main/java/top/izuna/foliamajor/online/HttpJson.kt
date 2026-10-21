package top.izuna.foliamajor.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal JSON-over-HTTP helper.
 *
 * OkHttp is used directly rather than through Retrofit because every call in this slice is a GET
 * whose response is inspected field-by-field, and a Retrofit interface would need one nullable
 * wrapper type per endpoint to survive the API's inconsistent envelopes.
 *
 * Failures return null instead of throwing. The callers are all "try to enrich the UI" paths — a
 * search that fails should show an empty list, and a lyric fetch that fails should leave the
 * previous song's lyrics on screen, not crash the player.
 */
class HttpJson(
    private val client: OkHttpClient = defaultClient(),
) {
    /** Status and body of one POST, because callers must distinguish "bad key" from "bad JSON". */
    data class Response(val status: Int, val body: String) {
        val isSuccessful: Boolean get() = status in 200..299
    }

    suspend fun getJson(url: String, json: Json): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                json.parseToJsonElement(body).jsonObject
            }
        }.getOrNull()
    }

    /**
     * Posts a JSON body and returns the raw response, including error bodies.
     *
     * Unlike [getJson] this does not swallow failures: the AI client needs to read the provider's
     * error message to report it, and needs the status to decide whether a rejected parameter is
     * worth retrying without.
     */
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 120,
    ): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        // The theme prompt asks for a long JSON document; the shared client's 20s read timeout is
        // tuned for lyric GETs and is too short for a generation.
        val callClient = client.newBuilder()
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        callClient.newCall(request).execute().use { response ->
            Response(
                status = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }

    companion object {
        /**
         * A desktop browser UA, not an Android one. The public NetEase instances commonly proxy the
         * upstream web API, which serves a reduced payload to unrecognised clients.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
