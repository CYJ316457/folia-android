package top.izuna.foliamajor.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Embedded cover extraction and content-addressed storage.
 *
 * Ported from folia-major's `localCoverAssetService.ts` + `localCoverBinaryStore.ts`. The desktop
 * app stores covers as `sha256:<hex>` keyed blobs; this does the same, with the same reasoning:
 *
 *  - **Content addressing makes dedup automatic.** An album's 12 tracks share one embedded cover, so
 *    a 2000-track library holds ~200 distinct covers, not 2000. No reference counting, no "is this
 *    asset still used" query — the same bytes always map to the same id.
 *  - **The id is verifiable.** A file named `sha256/<hex>.jpg` either contains the bytes that hash to
 *    `<hex>` or it is corrupt; there is no way for a stale write to masquerade as a valid asset.
 *
 * Two deliberate differences from the desktop implementation:
 *
 *  - Thumbnails are not generated. Coil decodes and scales at load time, and Android's
 *    `BitmapFactory` bounds-decode is cheaper than storing a second full copy per size.
 *  - The binary store is a plain app-private directory rather than OPFS/Electron, which is the
 *    equivalent storage on this platform.
 */

/** Mime types accepted, mirroring the desktop allow-list. */
private val ALLOWED_MIME_TYPES = setOf(
    "image/avif", "image/bmp", "image/gif", "image/jpeg", "image/png", "image/webp",
)

private val ASSET_ID_REGEX = Regex("""^sha256:([0-9a-f]{64})$""")

/** True when [assetId] is well-formed. Guards against a corrupt index pointing at garbage. */
fun isValidCoverAssetId(assetId: String?): Boolean =
    assetId != null && ASSET_ID_REGEX.matches(assetId)

/** Reads a cover out of an audio file's tags. */
suspend fun extractEmbeddedCover(context: Context, uri: Uri): CoverPayload? =
    withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val picture = retriever.embeddedPicture ?: return@withContext null

            // The tag carries no reliable MIME type, so it is sniffed from the magic bytes.
            val mimeType = sniffImageMimeType(picture) ?: return@withContext null
            if (mimeType !in ALLOWED_MIME_TYPES) return@withContext null

            CoverPayload(bytes = picture, mimeType = mimeType)
        } catch (error: Exception) {
            // A corrupt or unusual file must not abort the scan; a song without a cover is normal.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

data class CoverPayload(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CoverPayload && mimeType == other.mimeType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

/**
 * Detects the image format from magic bytes.
 *
 * Trusting a declared MIME type here is unsafe: tags are written by dozens of tools and a wrong type
 * makes the decode fail at display time — long after the import reported success. So only the
 * signature is consulted, and an unrecognised signature means "not a supported image".
 *
 * There is deliberately no "let the platform decoder try it" fallback. A format with no signature in
 * this list is one the app has not verified it can show, and labelling such bytes `image/jpeg`
 * produces an asset that fails to decode later — the exact failure this function exists to prevent.
 */
internal fun sniffImageMimeType(bytes: ByteArray): String? {
    if (bytes.size < 12) return null

    return when {
        // JPEG: FF D8 FF
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"

        // GIF: GIF87a / GIF89a
        bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() -> "image/gif"

        // WebP: RIFF....WEBP
        bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() -> "image/webp"

        // BMP: BM
        bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> "image/bmp"

        // AVIF: an ISO-BMFF box whose brand is avif.
        bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte() -> {
            val brand = String(bytes, 8, 4, Charsets.US_ASCII).lowercase()
            if (brand == "avif") "image/avif" else null
        }

        else -> null
    }
}

/**
 * Wraps raw file bytes in a payload after checking they really are a supported image.
 *
 * Kept separate from [extractEmbeddedCover] because a sidecar `cover.jpg` and an embedded picture
 * arrive by different routes but must clear the same bar: an unsupported or corrupt file should
 * produce a null rather than an asset id that fails to decode later.
 */
fun coverFromBytes(bytes: ByteArray): CoverPayload? {
    if (bytes.isEmpty()) return null
    val mimeType = sniffImageMimeType(bytes) ?: return null
    if (mimeType !in ALLOWED_MIME_TYPES) return null
    return CoverPayload(bytes = bytes, mimeType = mimeType)
}

/** SHA-256 over the bytes, formatted as the desktop app's asset id. */
internal fun coverAssetIdFor(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
}

/**
 * Content-addressed cover store.
 *
 * Writes go to a temp file and are renamed, matching the snapshot store: a half-written image must
 * never be visible under a valid id, because the id *is* the verification.
 */
class LocalCoverStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "local-cover-assets")

    suspend fun write(assetId: String, payload: CoverPayload): Boolean =
        withContext(Dispatchers.IO) {
            if (!isValidCoverAssetId(assetId)) return@withContext false
            if (payload.bytes.isEmpty()) return@withContext false

            runCatching {
                root.mkdirs()
                val target = fileFor(assetId)
                if (target.exists()) return@runCatching

                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeBytes(payload.bytes)
                if (!temp.renameTo(target)) {
                    temp.delete()
                    error("Failed to publish $assetId")
                }
            }.isSuccess
        }

    fun exists(assetId: String): Boolean =
        isValidCoverAssetId(assetId) && fileFor(assetId).exists()

    fun uriFor(assetId: String): Uri? {
        if (!isValidCoverAssetId(assetId)) return null
        val file = fileFor(assetId)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    suspend fun delete(assetId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidCoverAssetId(assetId)) return@withContext false
        runCatching { fileFor(assetId).delete() }.getOrDefault(false)
    }

    /**
     * Removes assets no song references.
     *
     * Content addressing means a deleted song's cover is either still shared by another track (the
     * file is referenced by id, so nothing breaks) or orphaned (it occupies disk forever). This is
     * the only place orphans are reclaimed.
     */
    suspend fun deleteUnreferenced(referencedIds: Set<String>): Int = withContext(Dispatchers.IO) {
        runCatching {
            val directory = root
            if (!directory.isDirectory) return@runCatching 0

            var removed = 0
            directory.listFiles()?.forEach { file ->
                val id = "sha256:" + file.nameWithoutExtension
                if (file.isFile && id !in referencedIds) {
                    if (file.delete()) removed += 1
                }
            }
            removed
        }.getOrDefault(0)
    }

    /**
     * The file a given id maps to.
     *
     * The hex is flattened into the filename rather than nested into directories by prefix, because
     * the store holds a few hundred files at most — directory fan-out would help a library of
     * millions and cost an extra lookup for every one of these.
     */
    private fun fileFor(assetId: String): File {
        val hex = ASSET_ID_REGEX.matchEntire(assetId)?.groupValues?.get(1)
            ?: error("Malformed cover asset id: $assetId")
        return File(root, "$hex.jpg")
    }
}
