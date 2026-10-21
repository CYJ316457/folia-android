package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for cover extraction and content-addressed storage.
 *
 * The magic-byte sniffing is the part worth testing: a tag's declared MIME type is written by dozens
 * of unrelated tools and routinely disagrees with the actual bytes, so trusting it produces covers
 * that fail to decode *after* the import has reported success.
 */
class LocalCoverStoreTest {

    // Minimal valid image headers.
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
    )

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D,
    )

    private fun withSuffix(bytes: ByteArray, size: Int = 64) =
        bytes + ByteArray(size - bytes.size)

    @Test
    fun `sniffs jpeg`() {
        assertEquals("image/jpeg", sniffImageMimeType(withSuffix(jpeg)))
    }

    @Test
    fun `sniffs png`() {
        assertEquals("image/png", sniffImageMimeType(withSuffix(png)))
    }

    @Test
    fun `sniffs gif`() {
        val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0)
            .map { it.toByte() }.toByteArray()

        assertEquals("image/gif", sniffImageMimeType(withSuffix(gif)))
    }

    @Test
    fun `sniffs webp`() {
        // RIFF<size>WEBP
        val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)
            .map { it.toByte() }.toByteArray()

        assertEquals("image/webp", sniffImageMimeType(withSuffix(webp)))
    }

    @Test
    fun `sniffs bmp`() {
        val bmp = byteArrayOf(0x42, 0x4D, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            .map { it.toByte() }.toByteArray()

        assertEquals("image/bmp", sniffImageMimeType(withSuffix(bmp)))
    }

    @Test
    fun `sniffs avif from the brand box`() {
        val avif = byteArrayOf(
            0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70,   // ISO-BMFF "ftyp"
            0x61, 0x76, 0x69, 0x66,                  // brand "avif"
        ).map { it.toByte() }.toByteArray()

        assertEquals("image/avif", sniffImageMimeType(withSuffix(avif)))
    }

    @Test
    fun `rejects an ftyp box that is not avif`() {
        // Other brands (isom, mp42 ...) are containers, not still images.
        val mp4Head = byteArrayOf(
            0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70,
            0x69, 0x73, 0x6F, 0x6D,
        ).map { it.toByte() }.toByteArray()

        assertNull(sniffImageMimeType(withSuffix(mp4Head)))
    }

    @Test
    fun `rejects a truncated header`() {
        assertNull(sniffImageMimeType(ByteArray(4)))
    }

    @Test
    fun `rejects empty input`() {
        assertNull(sniffImageMimeType(ByteArray(0)))
    }

    @Test
    fun `rejects random bytes`() {
        val garbage = ByteArray(64) { (it * 37 % 256).toByte() }

        // No magic number, and the platform decoder cannot make a bitmap from it.
        assertNull(sniffImageMimeType(garbage))
    }

    // --- coverFromBytes --------------------------------------------------------------------------

    @Test
    fun `accepts a valid png`() {
        val payload = coverFromBytes(withSuffix(png))

        assertEquals("image/png", payload?.mimeType)
        assertEquals(64, payload?.bytes?.size)
    }

    @Test
    fun `rejects empty bytes`() {
        assertNull(coverFromBytes(ByteArray(0)))
    }

    @Test
    fun `rejects a non-image`() {
        assertNull(coverFromBytes(ByteArray(64) { 0 }))
    }

    // --- Asset ids -------------------------------------------------------------------------------

    @Test
    fun `the asset id is sha256 of the bytes`() {
        val id = coverAssetIdFor(byteArrayOf(1, 2, 3, 4))

        assertTrue(id.startsWith("sha256:"))
        assertEquals(7 + 64, id.length)
        assertTrue(Regex("""^sha256:[0-9a-f]{64}$""").matches(id))
    }

    @Test
    fun `identical bytes produce an identical id`() {
        // This is what makes dedup work with no reference counting.
        assertEquals(coverAssetIdFor(byteArrayOf(9, 8, 7)), coverAssetIdFor(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun `different bytes produce different ids`() {
        assertTrue(coverAssetIdOf(9) != coverAssetIdOf(10))
    }

    private fun coverAssetIdOf(value: Int) = coverAssetIdFor(byteArrayOf(value.toByte()))

    @Test
    fun `validates the asset id shape`() {
        assertTrue(isValidCoverAssetId("sha256:" + "a".repeat(64)))
        assertFalse(isValidCoverAssetId("sha256:" + "a".repeat(63)))
        assertFalse(isValidCoverAssetId("sha256:" + "A".repeat(64)))
        assertFalse(isValidCoverAssetId("md5:" + "a".repeat(32)))
        assertFalse(isValidCoverAssetId(null))
        assertFalse(isValidCoverAssetId(""))
    }
}
