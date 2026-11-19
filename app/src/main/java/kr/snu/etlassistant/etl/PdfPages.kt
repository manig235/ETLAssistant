package kr.snu.etlassistant.etl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** PDF를 쪽 이미지로 바꾼다(안드로이드 내장 [PdfRenderer], 별도 라이브러리 없음). 손글씨·스캔본도 이미지로 볼 수 있다. */
object PdfPages {

    data class Rendered(val page: Int, val file: File, val width: Int, val height: Int)

    fun pageCount(file: File): Int = open(file) { it.pageCount }

    /**
     * [pages](1부터)를 [outDir]에 `{baseName}-p{n}.png`로 그린다. 가로는 [targetWidth]픽셀로 맞추고(작은 쪽은 키우지 않음),
     * 세로가 너무 길면 [maxPixels]를 넘지 않게 줄인다. 투명 배경은 흰색으로 채운다.
     */
    fun render(file: File, pages: List<Int>, outDir: File, baseName: String, targetWidth: Int = 1400, maxPixels: Int = 4_000_000): List<Rendered> {
        outDir.mkdirs()
        return open(file) { r ->
            val out = mutableListOf<Rendered>()
            for (p in pages) {
                if (p < 1 || p > r.pageCount) continue
                val page = r.openPage(p - 1)
                try {
                    var w = targetWidth.coerceAtMost(maxOf(page.width * 2, 600))
                    var h = (w.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                    if (w.toLong() * h > maxPixels) {
                        val k = Math.sqrt(maxPixels.toDouble() / (w.toDouble() * h))
                        w = (w * k).toInt().coerceAtLeast(1)
                        h = (h * k).toInt().coerceAtLeast(1)
                    }
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(outDir, "$baseName-p$p.png")
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        out += Rendered(p, f, w, h)
                    } finally {
                        bmp.recycle()
                    }
                } finally {
                    page.close() // Page가 AutoCloseable인 것은 최신 API뿐이라 use 대신 직접 닫는다
                }
            }
            out
        }
    }

    private fun <T> open(file: File, block: (PdfRenderer) -> T): T {
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw EtlException("PDF 파일을 열 수 없습니다")
        }
        try {
            val r = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                // 암호가 걸렸거나 손상된 파일(SecurityException, IOException 등). 내용은 노출하지 않는다.
                throw EtlException("PDF를 열 수 없습니다(암호가 있거나 손상된 파일일 수 있습니다)")
            }
            try {
                return block(r)
            } finally {
                r.close()
            }
        } finally {
            pfd.close()
        }
    }
}
