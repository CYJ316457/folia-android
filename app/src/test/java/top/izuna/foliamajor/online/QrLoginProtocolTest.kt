package top.izuna.foliamajor.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the QR login protocol mapping.
 *
 * The four codes are NetEase's own protocol; every other value is a failure of some kind, and the
 * distinction that matters is transient (keep polling) versus terminal (stop and tell the user).
 * Getting that wrong in either direction is visible: too strict ends the login on a dropped packet,
 * too lenient spins forever against a rate limit.
 */
class QrLoginProtocolTest {

    private fun check(code: Int, cookie: String = "", message: String? = null) =
        QrLoginProtocol.CheckResponse(code = code, cookie = cookie, message = message)

    @Test
    fun `801 means waiting`() {
        assertEquals(QrLoginState.Waiting, QrLoginProtocol.fromCheckResponse(check(801)))
    }

    @Test
    fun `802 means scanned`() {
        assertEquals(QrLoginState.Scanned, QrLoginProtocol.fromCheckResponse(check(802)))
    }

    @Test
    fun `800 means expired`() {
        assertEquals(QrLoginState.Expired, QrLoginProtocol.fromCheckResponse(check(800)))
    }

    @Test
    fun `803 with a cookie means confirmed`() {
        val state = QrLoginProtocol.fromCheckResponse(check(803, cookie = "MUSIC_U=abc"))
        assertEquals(QrLoginState.Confirmed("MUSIC_U=abc"), state)
    }

    @Test
    fun `803 without a cookie is a transient failure not a login`() {
        // Accepting this would mark the user signed in while holding no credential.
        val state = QrLoginProtocol.fromCheckResponse(check(803))
        assertTrue(state is QrLoginState.Failed)
        assertTrue((state as QrLoginState.Failed).transient)
    }

    @Test
    fun `502 is transient because the QR code is still valid`() {
        val state = QrLoginProtocol.fromCheckResponse(check(502, message = "connect ECONNRESET"))
        assertTrue(state is QrLoginState.Failed)
        assertEquals(502, (state as QrLoginState.Failed).code)
        assertTrue(state.transient)
    }

    @Test
    fun `rate limiting is terminal`() {
        // Polling again immediately is exactly what the limit exists to prevent.
        val state = QrLoginProtocol.fromCheckResponse(check(8821, message = "risk control"))
        assertTrue(state is QrLoginState.Failed)
        assertFalse((state as QrLoginState.Failed).transient)
    }

    @Test
    fun `unknown code is terminal and keeps its message`() {
        val state = QrLoginProtocol.fromCheckResponse(check(400, message = "key not found"))
        assertTrue(state is QrLoginState.Failed)
        assertEquals("key not found", (state as QrLoginState.Failed).message)
        assertFalse(state.transient)
    }

    @Test
    fun `unknown code without a message reports the code`() {
        val state = QrLoginProtocol.fromCheckResponse(check(999))
        assertEquals("code 999", (state as QrLoginState.Failed).message)
    }

    @Test
    fun `a null response is a transient network failure`() {
        val state = QrLoginProtocol.fromCheckResponse(null)
        assertTrue(state is QrLoginState.Failed)
        assertTrue((state as QrLoginState.Failed).transient)
    }

    @Test
    fun `an empty cookie is treated as absent`() {
        val state = QrLoginProtocol.fromCheckResponse(check(803, cookie = "   "))
        assertTrue(state is QrLoginState.Failed)
    }
}
