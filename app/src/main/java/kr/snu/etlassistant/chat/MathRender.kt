package kr.snu.etlassistant.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color
import ru.noties.jlatexmath.awt.Insets

/**
 * LaTeX 수식을 기기 안에서 비트맵으로 그린다(JLatexMath, 외부 서버·네트워크 없음).
 * 같은 수식·크기·색은 다시 그리지 않는다. 그리지 못한 수식은 null을 주고, 화면은 유니코드 글([MathText.toUnicode])로 대신한다.
 */
object MathRender {

    /**
     * [baseline]은 그림 위쪽에서 수식 기준선까지의 거리(px).
     * 인라인 수식은 기준선이 줄의 글자 기준선과 맞도록 위아래 여백을 넣어 둔다([render]의 inline).
     */
    class Image(val bitmap: Bitmap, val baseline: Int)

    private const val MAX_PIXELS = 4_000_000 // 너무 큰 수식(잘못된 입력 등)은 그리지 않는다
    private val lock = Any()
    private val failed = HashSet<String>()
    private val cache = object : LruCache<String, Image>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Image): Int = value.bitmap.byteCount
    }

    /**
     * [sizePx] 글자 크기로 [latex]를 그린다. [inline]이면 본문 글줄에 끼워 넣기 좋게 글자 스타일(분수 작게)로,
     * 아니면 디스플레이 스타일로 그린다. [axisPx]는 인라인일 때 글줄 가운데에서 기준선까지 내려간 거리(px):
     * 그림의 가운데를 글줄 가운데에 맞추면 수식 기준선이 글자 기준선과 같은 높이가 되도록 여백을 준다.
     */
    fun render(latex: String, sizePx: Float, argb: Int, inline: Boolean, axisPx: Float = 0f): Image? {
        val key = "$inline|$sizePx|$argb|$axisPx|$latex"
        synchronized(lock) {
            cache.get(key)?.let { return it }
            if (key in failed) return null
            val img = runCatching { draw(latex, sizePx, argb, inline, axisPx) }.getOrNull()
            if (img == null) failed += key else cache.put(key, img)
            return img
        }
    }

    /** 글꼴을 처음 읽는 비용을 화면 밖(백그라운드)에서 미리 치른다. */
    fun warmUp() {
        render("x^2+\\frac{1}{2}", 40f, 0xFF000000.toInt(), inline = true)
    }

    private fun draw(latex: String, sizePx: Float, argb: Int, inline: Boolean, axisPx: Float): Image? {
        val icon = TeXFormula(latex).TeXIconBuilder()
            .setStyle(if (inline) TeXConstants.STYLE_TEXT else TeXConstants.STYLE_DISPLAY)
            .setSize(sizePx)
            .setFGColor(Color(argb or (0xFF shl 24))) // 이 포트의 Color(int)는 ARGB 그대로 쓴다(알파 0이면 안 보임)
            .build()
        val pad = (sizePx * 0.08f).toInt().coerceAtLeast(1)
        // 여백 없는 높이·깊이(기준선 위/아래)
        icon.setInsets(Insets(0, 0, 0, 0))
        val depth = icon.iconDepth
        val ascent = icon.iconHeight - depth
        var top = pad
        var bottom = pad
        if (inline) {
            // 그림 가운데 = 글줄 가운데가 되도록: (top + ascent) - (depth + bottom) = 2 * axis
            val diff = (2 * axisPx).toInt() - (ascent - depth)
            if (diff > 0) top += diff else bottom += -diff
        }
        icon.setInsets(Insets(top, pad, bottom, pad))
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val g = AndroidGraphics2D()
        g.setCanvas(Canvas(bitmap))
        icon.paintIcon(null, g, 0, 0)
        return Image(bitmap, top + ascent)
    }
}
