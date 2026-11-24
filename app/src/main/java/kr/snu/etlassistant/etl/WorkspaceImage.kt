package kr.snu.etlassistant.etl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import kr.snu.etlassistant.mcp.ToolImage
import kr.snu.etlassistant.mcp.ToolResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64

/** The image tool reads only the same private coursework directory as etl_read_file. */
object WorkspaceImage {
    const val NAME = "etl_view_image"
    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    const val MAX_SOURCE_PIXELS = 64_000_000L
    const val MAX_SOURCE_EDGE = 16_384
    const val MAX_DECODE_PIXELS = 3_000_000L
    const val MAX_DECODE_EDGE = 3_072
    const val MAX_OUTPUT_BYTES = 6 * 1024 * 1024

    data class Bounds(val width: Int, val height: Int, val mime: String?)
    data class Encoded(val bytes: ByteArray, val width: Int, val height: Int)
    interface Codec {
        fun bounds(bytes: ByteArray): Bounds
        /** Implementations must return freshly encoded JPEG pixels, never original container bytes. */
        fun sanitize(bytes: ByteArray, sample: Int): Encoded
    }

    internal class Rejected(val reason: String) : Exception()
    private val imageLock = Any() // Concurrent MCP requests must not multiply bitmap allocations.

    fun sample(bounds: Bounds): Int {
        if (bounds.mime !in setOf("image/png", "image/jpeg", "image/webp")) throw Rejected("format")
        val w = bounds.width
        val h = bounds.height
        if (w <= 0 || h <= 0 || w > MAX_SOURCE_EDGE || h > MAX_SOURCE_EDGE || w.toLong() * h > MAX_SOURCE_PIXELS) throw Rejected("pixels")
        var sample = 1
        while (true) {
            val sampledW = (w.toLong() + sample - 1) / sample
            val sampledH = (h.toLong() + sample - 1) / sample
            if (maxOf(sampledW, sampledH) <= MAX_DECODE_EDGE && sampledW * sampledH <= MAX_DECODE_PIXELS) return sample
            sample *= 2
        }
    }

    /** Errors contain fixed messages only; neither paths nor decoder exceptions can expose secrets. */
    fun view(workspace: File, path: String, codec: Codec = AndroidCodec): ToolResult = synchronized(imageLock) {
        try {
            val bytes = read(workspace, path)
            val sample = sample(codec.bounds(bytes))
            val image = codec.sanitize(bytes, sample)
            if (image.width <= 0 || image.height <= 0 || maxOf(image.width, image.height) > MAX_DECODE_EDGE ||
                image.width.toLong() * image.height > MAX_DECODE_PIXELS) throw Rejected("pixels")
            if (image.bytes.isEmpty() || image.bytes.size > MAX_OUTPUT_BYTES) throw Rejected("output-size")
            ToolResult(
                "이미지 ${image.width}×${image.height}. 원본 메타데이터를 제외하고 다시 인코딩했어요.",
                images = listOf(ToolImage("image/jpeg", Base64.getEncoder().encodeToString(image.bytes))),
            )
        } catch (e: Rejected) {
            val message = when (e.reason) {
                "file-size", "output-size" -> "이미지가 너무 커서 열 수 없어요. 파일은 20MB 이하로 줄여 주세요."
                "pixels" -> "이미지의 픽셀 크기가 너무 크거나 올바르지 않아요. 더 작은 이미지로 바꿔 주세요."
                "format" -> "PNG·JPEG·WebP 이미지만 열 수 있어요. PDF는 etl_render_pages로 쪽을 먼저 만들어 주세요."
                else -> "etl-files/ 안의 이미지 파일만 열 수 있어요. etl_render_pages나 첨부가 준 상대 경로를 사용해 주세요."
            }
            ToolResult(message, isError = true, detail = "image-rejected:${e.reason}")
        } catch (_: OutOfMemoryError) {
            ToolResult("이미지를 처리할 메모리가 부족해요. 더 작은 이미지로 바꿔 주세요.", isError = true, detail = "image-memory")
        } catch (_: Exception) {
            ToolResult("이미지를 열 수 없어요. 파일이 손상되었거나 지원하지 않는 이미지일 수 있어요.", isError = true, detail = "image-decode")
        }
    }

    private fun read(workspace: File, path: String): ByteArray {
        val root = File(workspace, EtlFiles.DIR)
        if (Files.isSymbolicLink(root.toPath())) throw Rejected("path")
        val file = try { EtlFiles.resolveInside(workspace, path) } catch (_: Exception) { throw Rejected("path") }
        if (file.length() > MAX_FILE_BYTES) throw Rejected("file-size")
        return file.inputStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size().toLong() + count > MAX_FILE_BYTES) throw Rejected("file-size")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    /** BitmapFactory reads a bounded byte array; re-encoding pixels drops EXIF, XMP and container comments. */
    private object AndroidCodec : Codec {
        override fun bounds(bytes: ByteArray): Bounds {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            return Bounds(options.outWidth, options.outHeight, options.outMimeType)
        }

        override fun sanitize(bytes: ByteArray, sample: Int): Encoded {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw Rejected("format")
            var oriented: Bitmap? = null
            var clean: Bitmap? = null
            try {
                if (bitmap.width.toLong() * bitmap.height > MAX_DECODE_PIXELS || maxOf(bitmap.width, bitmap.height) > MAX_DECODE_EDGE) throw Rejected("pixels")
                val orientation = runCatching {
                    ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    }
                }
                val pixels = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { oriented = it }
                val target = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888).also { clean = it }
                Canvas(target).apply { drawColor(Color.WHITE); drawBitmap(pixels, 0f, 0f, null) }
                val out = LimitedOutput(MAX_OUTPUT_BYTES)
                if (!target.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw Rejected("output-size")
                return Encoded(out.bytes(), target.width, target.height)
            } finally {
                clean?.recycle()
                oriented?.takeIf { it !== bitmap }?.recycle()
                bitmap.recycle()
            }
        }
    }

    private class LimitedOutput(private val limit: Int) : OutputStream() {
        private val out = ByteArrayOutputStream()
        override fun write(value: Int) {
            if (out.size() >= limit) throw Rejected("output-size")
            out.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (out.size().toLong() + length > limit) throw Rejected("output-size")
            out.write(bytes, offset, length)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
