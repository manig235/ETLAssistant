package kr.snu.etlassistant.codex

import kr.snu.etlassistant.chat.Attachment
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Completed engine image artifacts only; paths supplied in assistant text are never imported. */
object GeneratedImages {
    const val MAX_BYTES = 12 * 1024 * 1024

    fun attachment(item: JSONObject, roots: List<File>, destination: File? = null): Attachment? {
        if (item.optString("type") != "imageGeneration" || item.optString("status") != "completed") return null
        val bases = roots.map { it.canonicalFile }
        val path = item.optString("savedPath").takeIf { it.isNotBlank() && it != "null" }
        if (path != null) {
            val file = File(path).canonicalFile
            if (bases.none { file.toPath().startsWith(it.toPath()) && file != it }) return null
            val mime = mime(file.extension)
            if (file.isFile && file.length() in 1..MAX_BYTES.toLong() && mime != null) {
                // The managed attachment directory is covered by FileProvider,
                // chat deletion and the normal work-file retention policy.
                if (destination == null) return attachment(file, mime)
                val target = target(destination, file.name)
                if (file != target) writeAtomically(target) { temp -> file.copyTo(temp, overwrite = true) }
                return attachment(target, mime)
            }
        }
        // v0.170 items may only include inline Base64. Save it for thumbnails,
        // sharing and restored chat history instead of keeping Base64 in messages.
        val root = (destination ?: bases.firstOrNull())?.canonicalFile ?: return null
        val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val encoded = item.optString("result")
        if (encoded.isBlank() || encoded.length > 16 * 1024 * 1024) return null
        val data = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
        if (data.isEmpty() || data.size > MAX_BYTES) return null
        val ext = when {
            data.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "png"
            data.take(4).toByteArray().contentEquals("GIF8".toByteArray(Charsets.US_ASCII)) -> "gif"
            else -> return null
        }
        val name = id.take(128).replace(Regex("[^a-zA-Z0-9_-]"), "_") + "." + ext
        val file = target(root, name)
        writeAtomically(file) { it.writeBytes(data) }
        return attachment(file, mime(ext)!!)
    }

    private fun target(directory: File, name: String): File {
        val root = directory.canonicalFile
        val file = File(root, name).canonicalFile
        if (file.parentFile != root) throw java.io.IOException("Invalid generated image path")
        root.mkdirs()
        return file
    }

    private fun writeAtomically(file: File, write: (File) -> Unit) {
        // No partial image may be observed by the thumbnail loader.
        val temp = File.createTempFile("generated-", ".tmp", file.parentFile)
        try {
            write(temp)
            if (!temp.renameTo(file)) throw java.io.IOException("Cannot save generated image")
        } finally { temp.delete() }
    }

    private fun attachment(file: File, mime: String) =
        Attachment(file.name, mime, file.length(), file.path, referenceOnly = false)

    private fun mime(ext: String): String? = when (ext.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
    }
}
