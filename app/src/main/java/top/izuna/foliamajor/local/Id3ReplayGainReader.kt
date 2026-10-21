package top.izuna.foliamajor.local

/**
 * Reads ReplayGain out of an ID3v2 tag.
 *
 * Neither `MediaMetadataRetriever` nor Media3's `MediaMetadata` exposes ReplayGain: the former has
 * no key for it, and the latter leaves `extras` null for MP3 files entirely (verified on device —
 * the metadata callback fires with a title and no extras). So the tag is read directly.
 *
 * Only ID3v2 `TXXX` frames are parsed, which is where every tool that writes ReplayGain to MP3 puts
 * it (`TXXX:replaygain_track_gain`). FLAC and Ogg carry Vorbis comments instead, which need a
 * different reader; those files currently fall back to no gain, which is a silent degradation rather
 * than a wrong value.
 *
 * The parser is deliberately narrow: it walks the frame headers and reads only the frames it wants,
 * because it runs on the import path where a 5 MB file must not be fully scanned.
 */
object Id3ReplayGainReader {

    /** How far into the file the tag may live before it is not worth scanning. */
    private const val MAX_TAG_BYTES = 1024 * 1024

    /**
     * Extracts the ReplayGain values from an ID3v2 tag at the start of [bytes].
     *
     * Returns null when the file has no ID3v2 tag, no ReplayGain frames, or the tag is malformed.
     * A malformed tag is common enough (truncated writes, unsynchronised frames) that treating it as
     * "no gain" is the only behaviour that cannot make playback worse.
     */
    fun read(bytes: ByteArray): RawReplayGainTags? {
        if (bytes.size < 10) return null
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            return null
        }

        val majorVersion = bytes[3].toInt()
        // 2.x uses 3-byte frame ids, 3.x and 4.x use 4-byte ids. Only 3/4 are handled; ID3v2.2 is
        // vanishingly rare and its frame layout is different enough to not be worth the branch.
        if (majorVersion != 3 && majorVersion != 4) return null

        val flags = bytes[5].toInt()
        if (flags and 0x80 != 0) return null // unsynchronisation: not handled

        val tagSize = readSyncSafeInt(bytes, 6) ?: return null
        val tagEnd = (10 + tagSize).coerceAtMost(bytes.size).coerceAtMost(MAX_TAG_BYTES)

        var offset = 10
        // An extended header sits between the tag header and the first frame.
        if (flags and 0x40 != 0) {
            val extendedSize = readSyncSafeInt(bytes, offset) ?: return null
            offset += 4 + extendedSize
        }

        val values = mutableMapOf<String, String>()

        while (offset + 10 <= tagEnd) {
            val frameId = readFrameId(bytes, offset, majorVersion)
            if (frameId.isNullOrBlank() || !frameId.all { it.isLetterOrDigit() }) break

            val frameSize = if (majorVersion == 4) {
                readSyncSafeInt(bytes, offset + 4) ?: break
            } else {
                readBigEndianInt(bytes, offset + 4) ?: break
            }
            if (frameSize <= 0) break

            val frameDataStart = offset + 10
            val frameDataEnd = (frameDataStart + frameSize).coerceAtMost(tagEnd)
            if (frameDataEnd <= frameDataStart) break

            if (frameId == "TXXX") {
                readTxxxFrame(bytes, frameDataStart, frameDataEnd, values)
            }

            offset = frameDataStart + frameSize
        }

        if (values.isEmpty()) return null

        return RawReplayGainTags(
            trackGain = values["replaygain_track_gain"],
            trackPeak = values["replaygain_track_peak"],
            albumGain = values["replaygain_album_gain"],
            albumPeak = values["replaygain_album_peak"],
        )
    }

    /**
     * Reads one `TXXX` frame.
     *
     * Layout: a 1-byte text encoding, then a null-terminated description, then the value. The
     * description is the part before the first null and is what identifies the field.
     */
    private fun readTxxxFrame(
        bytes: ByteArray,
        start: Int,
        end: Int,
        into: MutableMap<String, String>,
    ) {
        if (end - start < 2) return

        val encoding = bytes[start].toInt()
        val contentStart = start + 1

        val separator = when (encoding) {
            0, 3 -> findNull(bytes, contentStart, end, wide = false)
            1, 2 -> findNull(bytes, contentStart, end, wide = true)
            else -> return
        } ?: return

        val description = decodeText(bytes, contentStart, separator, encoding)?.lowercase() ?: return
        val valueStart = separator + if (encoding == 1 || encoding == 2) 2 else 1
        if (valueStart >= end) return

        val value = decodeText(bytes, valueStart, end, encoding) ?: return
        if (value.isNotBlank()) {
            into[description] = value
        }
    }

    /** Finds the null terminator of the description field. */
    private fun findNull(bytes: ByteArray, start: Int, end: Int, wide: Boolean): Int? {
        var index = start
        if (wide) {
            while (index + 1 < end) {
                if (bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0) return index
                index += 2
            }
            return null
        }
        while (index < end) {
            if (bytes[index].toInt() == 0) return index
            index += 1
        }
        return null
    }

    /**
     * Decodes a frame field.
     *
     * Encoding 0 is ISO-8859-1 and 3 is UTF-8; both are handled. Encodings 1 and 2 (UTF-16 with and
     * without BOM) are read as UTF-16LE, which covers every writer seen in practice. Bytes after a
     * terminator are trimmed because some tools pad the frame.
     */
    private fun decodeText(bytes: ByteArray, start: Int, end: Int, encoding: Int): String? {
        if (end <= start) return null
        val slice = bytes.copyOfRange(start, end)

        return runCatching {
            when (encoding) {
                0 -> String(slice, Charsets.ISO_8859_1)
                1, 2 -> String(slice, Charsets.UTF_16LE)
                3 -> String(slice, Charsets.UTF_8)
                else -> null
            }?.substringBefore('\u0000')?.trim()
        }.getOrNull()
    }

    private fun readFrameId(bytes: ByteArray, offset: Int, majorVersion: Int): String? {
        val length = if (majorVersion == 2) 3 else 4
        if (offset + length > bytes.size) return null
        return String(bytes, offset, length, Charsets.ISO_8859_1)
    }

    /** ID3 sizes are "sync-safe": 7 bits per byte, high bit always clear. */
    private fun readSyncSafeInt(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        var value = 0
        for (index in 0 until 4) {
            val byte = bytes[offset + index].toInt() and 0xFF
            if (byte and 0x80 != 0) return null
            value = (value shl 7) or byte
        }
        return value
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}

/** ReplayGain values as written in the file, before parsing. */
data class RawReplayGainTags(
    val trackGain: String? = null,
    val trackPeak: String? = null,
    val albumGain: String? = null,
    val albumPeak: String? = null,
) {
    val isEmpty: Boolean
        get() = trackGain == null && trackPeak == null && albumGain == null && albumPeak == null
}
