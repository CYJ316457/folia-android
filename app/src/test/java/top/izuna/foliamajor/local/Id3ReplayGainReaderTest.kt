package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the ID3 ReplayGain reader.
 *
 * Fixtures are hand-assembled ID3v2 tags rather than real MP3 files: the point is the frame walk and
 * the sync-safe integer decoding, both of which are easy to get subtly wrong and impossible to see
 * by reading the parser.
 */
class Id3ReplayGainReaderTest {

    /** Builds a sync-safe size (7 bits per byte). */
    private fun syncSafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    /** Big-endian size, as used by ID3v2.3 frames. */
    private fun bigEndian(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /**
     * Builds a `TXXX` frame body: encoding byte, description, null, value.
     *
     * Encoding 0 (ISO-8859-1) is used because every character in these fixtures is ASCII.
     */
    private fun txxxBody(description: String, value: String): ByteArray =
        byteArrayOf(0) +
            description.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            value.toByteArray(Charsets.ISO_8859_1)

    private fun frame(id: String, body: ByteArray, version: Int = 3): ByteArray {
        val size = if (version == 4) syncSafe(body.size) else bigEndian(body.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, 0) + body
    }

    private fun tag(frames: ByteArray, version: Int = 3): ByteArray {
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            version.toByte(),
            0, 0, // revision, flags
        ) + syncSafe(frames.size)
        return header + frames
    }

    // --- Tag detection ---------------------------------------------------------------------------

    @Test
    fun `a file without an ID3 tag yields nothing`() {
        assertNull(Id3ReplayGainReader.read(ByteArray(64)))
    }

    @Test
    fun `a truncated tag yields nothing`() {
        assertNull(Id3ReplayGainReader.read(byteArrayOf('I'.code.toByte(), 'D'.code.toByte())))
    }

    @Test
    fun `a tag with no ReplayGain frames yields nothing`() {
        val bytes = tag(frame("TIT2", byteArrayOf(0) + "Title".toByteArray()))

        assertNull(Id3ReplayGainReader.read(bytes))
    }

    // --- Reading frames --------------------------------------------------------------------------

    @Test
    fun `reads a track gain`() {
        val bytes = tag(frame("TXXX", txxxBody("replaygain_track_gain", "-7.25 dB")))

        val result = Id3ReplayGainReader.read(bytes)

        assertEquals("-7.25 dB", result?.trackGain)
    }

    @Test
    fun `reads a track peak`() {
        val bytes = tag(frame("TXXX", txxxBody("replaygain_track_peak", "0.988")))

        assertEquals("0.988", Id3ReplayGainReader.read(bytes)?.trackPeak)
    }

    @Test
    fun `reads album gain and peak`() {
        val frames = frame("TXXX", txxxBody("replaygain_album_gain", "-9.5 dB")) +
            frame("TXXX", txxxBody("replaygain_album_peak", "0.95"))

        val result = Id3ReplayGainReader.read(tag(frames))

        assertEquals("-9.5 dB", result?.albumGain)
        assertEquals("0.95", result?.albumPeak)
    }

    @Test
    fun `reads all four values from one tag`() {
        val frames = frame("TXXX", txxxBody("replaygain_track_gain", "-3.0 dB")) +
            frame("TXXX", txxxBody("replaygain_track_peak", "0.9")) +
            frame("TXXX", txxxBody("replaygain_album_gain", "-5.0 dB")) +
            frame("TXXX", txxxBody("replaygain_album_peak", "0.85"))

        val result = Id3ReplayGainReader.read(tag(frames))

        assertEquals("-3.0 dB", result?.trackGain)
        assertEquals("0.9", result?.trackPeak)
        assertEquals("-5.0 dB", result?.albumGain)
        assertEquals("0.85", result?.albumPeak)
    }

    @Test
    fun `the description is matched case-insensitively`() {
        // Some writers use REPLAYGAIN_TRACK_GAIN, others lower-case it.
        val bytes = tag(frame("TXXX", txxxBody("REPLAYGAIN_TRACK_GAIN", "-4.0 dB")))

        assertEquals("-4.0 dB", Id3ReplayGainReader.read(bytes)?.trackGain)
    }

    @Test
    fun `other TXXX frames are ignored`() {
        val frames = frame("TXXX", txxxBody("MusicBrainz Album Id", "abc123")) +
            frame("TXXX", txxxBody("replaygain_track_gain", "-6.0 dB"))

        val result = Id3ReplayGainReader.read(tag(frames))

        assertEquals("-6.0 dB", result?.trackGain)
    }

    @Test
    fun `non-TXXX frames are skipped`() {
        val frames = frame("TIT2", byteArrayOf(0) + "Title".toByteArray()) +
            frame("TPE1", byteArrayOf(0) + "Artist".toByteArray()) +
            frame("TXXX", txxxBody("replaygain_track_gain", "-8.0 dB"))

        assertEquals("-8.0 dB", Id3ReplayGainReader.read(tag(frames))?.trackGain)
    }

    // --- Version handling ------------------------------------------------------------------------

    @Test
    fun `reads an ID3v2_4 tag with sync-safe frame sizes`() {
        val bytes = tag(
            frame("TXXX", txxxBody("replaygain_track_gain", "-2.5 dB"), version = 4),
            version = 4,
        )

        assertEquals("-2.5 dB", Id3ReplayGainReader.read(bytes)?.trackGain)
    }

    @Test
    fun `an ID3v2_2 tag is not parsed`() {
        // v2.2 uses 3-byte frame ids; rather than guess, the reader declines.
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 2, 0, 0,
        ) + syncSafe(20)

        assertNull(Id3ReplayGainReader.read(header + ByteArray(20)))
    }

    // --- Robustness ------------------------------------------------------------------------------

    @Test
    fun `a tag claiming a size beyond the buffer does not overflow`() {
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
        ) + syncSafe(999999)

        // The declared size is larger than the data; the reader must clamp rather than throw.
        val result = Id3ReplayGainReader.read(header + frame("TXXX", txxxBody("replaygain_track_gain", "-1.0 dB")))

        assertEquals("-1.0 dB", result?.trackGain)
    }

    @Test
    fun `a frame with a zero size stops the walk`() {
        val frames = frame("TXXX", txxxBody("replaygain_track_gain", "-1.0 dB")) +
            "TXXX".toByteArray() + bigEndian(0) + byteArrayOf(0, 0)

        // The valid frame before it must still be read.
        assertEquals("-1.0 dB", Id3ReplayGainReader.read(tag(frames))?.trackGain)
    }

    @Test
    fun `an unsynchronised tag is declined`() {
        // Flag 0x80 means the tag body is byte-stuffed; parsing it without reversing that would
        // read garbage, so the reader returns nothing rather than a wrong gain.
        val frames = frame("TXXX", txxxBody("replaygain_track_gain", "-1.0 dB"))
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0x80.toByte(),
        ) + syncSafe(frames.size)

        assertNull(Id3ReplayGainReader.read(header + frames))
    }

    @Test
    fun `an empty value is not recorded`() {
        val bytes = tag(frame("TXXX", txxxBody("replaygain_track_gain", "")))

        assertNull(Id3ReplayGainReader.read(bytes))
    }

    @Test
    fun `reads ReplayGain from a real ffmpeg-written file`() {
        // The hand-built fixtures prove the frame walk; this proves it against bytes a real encoder
        // produced. ffmpeg writes TXXX with an ISO-8859-1 description and a trailing NUL, which is
        // the layout every tool uses — but it is worth confirming rather than assuming.
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("rg_sample.mp3")) {
            "rg_sample.mp3 is missing from test resources"
        }.use { it.readBytes() }

        val result = Id3ReplayGainReader.read(bytes)

        assertEquals("-12.0 dB", result?.trackGain)
        assertEquals("0.98", result?.trackPeak)
    }

    @Test
    fun `raw tags report emptiness`() {
        assertEquals(true, RawReplayGainTags().isEmpty)
        assertEquals(false, RawReplayGainTags(trackGain = "-1.0 dB").isEmpty)
    }
}
