package kr.snu.etlassistant.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kr.snu.etlassistant.etl.EtlFiles
import kr.snu.etlassistant.etl.EtlLogic
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 사용자가 고른 사진·파일을 비서의 작업 폴더(`etl-files/uploads/`)로 가져온다. 메인 스레드에서 부르지 않는다.
 * 다른 앱이 준 content:// 권한은 잠깐만 유효하므로 고르는 즉시 복사해 둔다.
 */
object AttachmentImporter {

    class ImportException(message: String) : Exception(message)

    private val stamp = AtomicLong(0)

    private fun nextStamp(): Long {
        while (true) {
            val prev = stamp.get()
            val next = maxOf(prev + 1, System.currentTimeMillis())
            if (stamp.compareAndSet(prev, next)) return next
        }
    }

    fun uploadDir(context: Context): File = File(context.filesDir, "workspace/${AttachmentLogic.UPLOAD_DIR}")

    fun import(context: Context, uri: Uri): Attachment {
        val resolver = context.contentResolver
        var rawName: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    rawName = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val name = EtlLogic.safeName(rawName, "첨부")
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val dir = uploadDir(context).apply { mkdirs() }
        EtlFiles.prune(dir, System.currentTimeMillis())
        val stamp = nextStamp()

        if (AttachmentLogic.shouldReencode(mime)) {
            val bmp = runCatching { decodeScaled(context, uri, AttachmentLogic.IMAGE_MAX_SIDE) }.getOrNull()
            if (bmp != null) {
                val out = File(dir, AttachmentLogic.uploadName(stamp, name, reencoded = true))
                try {
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, AttachmentLogic.IMAGE_QUALITY, it) }
                } finally {
                    bmp.recycle()
                }
                return Attachment(out.name.substringAfter('-'), "image/jpeg", out.length(), out.absolutePath)
            }
            // 해독하지 못한 사진은 파일로 그대로 복사한다.
        }

        if (size > AttachmentLogic.MAX_FILE_BYTES) throw ImportException("50MB보다 큰 파일은 첨부할 수 없어요")
        val out = File(dir, AttachmentLogic.uploadName(stamp, name, reencoded = false))
        try {
            val input = resolver.openInputStream(uri) ?: throw ImportException("파일을 열 수 없어요")
            input.use { ins ->
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > AttachmentLogic.MAX_FILE_BYTES) throw ImportException("50MB보다 큰 파일은 첨부할 수 없어요")
                        os.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw if (e is ImportException) e else ImportException("파일을 가져오지 못했어요")
        }
        return Attachment(name, mime, out.length(), out.absolutePath)
    }

    /** 긴 변이 [maxSide] 이하가 되도록 줄여서 읽는다. 사진의 회전 정보는 API 28 이상에서 반영된다. */
    private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale >= 1f) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }
}

/** 채팅 미리보기용 작은 그림. 같은 파일을 스크롤할 때마다 다시 읽지 않도록 메모리에 조금 들고 있는다. */
object Thumbnails {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** 파일이 없거나 그림이 아니면 null. 메인 스레드에서 부르지 않는다. */
    fun load(path: String, maxPx: Int): ImageBitmap? {
        val key = "$path@$maxPx"
        cache.get(key)?.let { return it }
        val f = File(path)
        if (!f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            bmp.asImageBitmap().also { cache.put(key, it) }
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
