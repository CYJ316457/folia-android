package top.izuna.foliamajor.online

/**
 * The QR login timeline, ported from folia-major's `checkQr` switch in
 * `src/services/onlineMusic/neteaseProvider.ts`.
 *
 * Kept as a sealed class rather than an enum plus fields because the states carry different data:
 * only `confirmed` carries a cookie, and only `error` carries a message and whether it is worth
 * retrying. A flat enum would need nullable fields that are meaningless in most states.
 */
sealed class QrLoginState {
    /** A fresh code has been fetched; the image is on screen. */
    data object Waiting : QrLoginState()

    /** The user scanned the code but has not confirmed on the phone yet. */
    data object Scanned : QrLoginState()

    /** The user confirmed. [cookie] is the login session and must be persisted. */
    data class Confirmed(val cookie: String) : QrLoginState()

    /** The code timed out; the user must request a new one. */
    data object Expired : QrLoginState()

    /**
     * The API answered with something else: rate limiting (8821), a network error proxied as 502, or
     * an upstream failure.
     *
     * [transient] is set for network errors, which leave the QR code valid — polling continues. A
     * rate-limit response still carries a valid code, but hammering it again immediately is exactly
     * what the limit is there to prevent, so it is treated as terminal.
     */
    data class Failed(
        val message: String,
        val code: Int?,
        val transient: Boolean,
    ) : QrLoginState()
}

/**
 * Maps one `/login/qr/check` response to a state.
 *
 * The four success codes are NetEase's own protocol and appear nowhere else in the codebase; keeping
 * the mapping in one pure function is what makes the login flow testable without a network.
 */
object QrLoginProtocol {

    const val CODE_EXPIRED = 800
    const val CODE_WAITING = 801
    const val CODE_SCANNED = 802
    const val CODE_CONFIRMED = 803

    /** NetEase's reply when it decides the client is asking too often. */
    const val CODE_RATE_LIMITED = 8821

    /**
     * Reads a check response.
     *
     * [response] is null when the HTTP call itself failed (timeout, DNS, connection reset). That is
     * treated as transient: the QR code is still valid, and the desktop app keeps polling through
     * these rather than ending the login.
     */
    fun fromCheckResponse(response: CheckResponse?): QrLoginState {
        if (response == null) {
            return QrLoginState.Failed(message = "Network error", code = null, transient = true)
        }

        return when (response.code) {
            CODE_WAITING -> QrLoginState.Waiting
            CODE_SCANNED -> QrLoginState.Scanned
            CODE_EXPIRED -> QrLoginState.Expired
            CODE_CONFIRMED -> {
                // A confirmed scan without a cookie is a broken response, not a successful login:
                // accepting it would leave the app "signed in" with no credential.
                if (response.cookie.isBlank()) {
                    QrLoginState.Failed(
                        message = "Confirmed without a session cookie",
                        code = response.code,
                        transient = true,
                    )
                } else {
                    QrLoginState.Confirmed(response.cookie)
                }
            }
            else -> QrLoginState.Failed(
                message = response.message ?: "code ${response.code}",
                code = response.code,
                // 502 is how the API reports that it could not reach NetEase; the QR itself is
                // unaffected, so polling continues. Everything else (rate limiting, an expired
                // request key) needs the user to start over.
                transient = response.code == 502,
            )
        }
    }

    data class CheckResponse(
        val code: Int,
        val cookie: String = "",
        val message: String? = null,
    )
}

/**
 * Decodes the `data:image/png;base64,…` URL the API returns for a QR code.
 *
 * Coil 2 — the version this app uses — has no data-URI fetcher; that arrived in Coil 3. Passing the
 * data URL straight to `AsyncImage` therefore renders nothing and logs nothing, which is exactly how
 * this failed on the emulator. Decoding here keeps the image loading path to plain bytes.
 *
 * Returns null for anything that is not a base64 image payload: a provider that answers with a
 * remote http URL is not this function's business, and silently downloading it would be a surprise.
 */
object DataUrl {

    private const val BASE64_MARKER = ";base64,"

    fun decodeBase64Image(dataUrl: String?): ByteArray? {
        val value = dataUrl?.trim() ?: return null
        if (!value.startsWith("data:", ignoreCase = true)) return null

        val markerIndex = value.indexOf(BASE64_MARKER, ignoreCase = true)
        if (markerIndex < 0) return null

        val payload = value.substring(markerIndex + BASE64_MARKER.length)
        if (payload.isBlank()) return null

        return runCatching {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
