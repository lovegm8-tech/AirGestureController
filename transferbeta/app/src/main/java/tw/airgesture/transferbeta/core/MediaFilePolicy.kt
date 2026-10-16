package tw.airgesture.transferbeta.core

/** Maps the verified original filename to a MediaStore collection and MIME.
 * Never transcodes, guesses a video codec, or changes the original bytes. */
enum class MediaKind { VIDEO, IMAGE, DOWNLOAD }

data class MediaSaveSpec(val kind: MediaKind, val mime: String, val folder: String)

object MediaFilePolicy {
    private val videos = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/x-m4v",
        "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "avi" to "video/x-msvideo",
        "ts" to "video/mp2t", "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg"
    )
    private val images = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "png" to "image/png", "heic" to "image/heic",
        "heif" to "image/heif", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp",
        "avif" to "image/avif"
    )
    fun destination(originalName: String): MediaSaveSpec {
        val name = TransferPolicy.safeName(originalName) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        videos[ext]?.let { return MediaSaveSpec(MediaKind.VIDEO, it, "Movies/AirGrab") }
        images[ext]?.let { return MediaSaveSpec(MediaKind.IMAGE, it, "Pictures/AirGrab") }
        return MediaSaveSpec(MediaKind.DOWNLOAD, "application/octet-stream", "Download/AirGrab")
    }
}
