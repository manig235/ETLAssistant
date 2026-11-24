package kr.snu.etlassistant.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kr.snu.etlassistant.chat.MathRender
import kr.snu.etlassistant.chat.MathText
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.pdf.PdfLayoutLogic.MARGIN_BOTTOM
import kr.snu.etlassistant.pdf.PdfLayoutLogic.MARGIN_TOP
import kr.snu.etlassistant.pdf.PdfLayoutLogic.MARGIN_X
import kr.snu.etlassistant.pdf.PdfLayoutLogic.PAGE_HEIGHT
import kr.snu.etlassistant.pdf.PdfLayoutLogic.PAGE_WIDTH
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 마크다운 블록([PdfMarkdown])을 기기 안에서 A4 PDF로 그린다(android.graphics.pdf.PdfDocument, 네트워크·WebView 없음).
 * 글은 시스템 글꼴(StaticLayout, 한글은 시스템 대체 글꼴), 수식은 JLatexMath 그림([MathRender]), 못 그리면 유니코드 글.
 * 글은 줄 단위로 쪽을 나누고([PdfLayoutLogic.paginate]), 쪽마다 아래에 "제목 · n / N"을 적는다.
 */
class PdfMaker(private val title: String, private val subtitle: String?) {

    class Made(val pages: Int)

    private val width = PdfLayoutLogic.contentWidth
    private val ink = 0xFF1F2328.toInt()
    private val gray = 0xFF6B7280.toInt()
    private val line = 0xFFD0D7DE.toInt()
    private val codeBg = 0xFFF3F4F6.toInt()
    private val headerBg = 0xFFEEF2F7.toInt()
    private val link = 0xFF1D4ED8.toInt()

    private fun paint(size: Float, typeface: Typeface = Typeface.DEFAULT, color: Int = ink) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = typeface
            this.color = color
        }

    private val body = paint(10.5f)
    private val small = paint(8f, color = gray)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = 0.6f; style = Paint.Style.STROKE }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 쪽에 놓을 조각. [draw]는 본문 영역 왼쪽 위 기준 (left, top)에 그린다. */
    private class Piece(val item: PdfLayoutLogic.Item, val draw: (Canvas, Float, Float) -> Unit)

    fun write(blocks: List<PdfMarkdown.Block>, out: OutputStream): Made {
        val pieces = ArrayList<Piece>()
        titlePieces(pieces)
        var prev: PdfMarkdown.Block? = null
        for (b in blocks) {
            when (b) {
                is PdfMarkdown.Block.Heading -> {
                    val size = when (b.level) { 1 -> 16.5f; 2 -> 14f; else -> 12f }
                    val gap = when (b.level) { 1 -> 18f; 2 -> 14f; else -> 10f }
                    textPieces(pieces, styled(b.text, size), paint(size, Typeface.DEFAULT_BOLD), gap, keepAll = true)
                }
                is PdfMarkdown.Block.Paragraph -> textPieces(pieces, styled(b.text, body.textSize), body, 7f)
                is PdfMarkdown.Block.ListItem -> {
                    val indent = 4f + b.level * 16f
                    val markerWidth = body.measureText(b.marker + " ")
                    val sb = SpannableStringBuilder(b.marker + " ").append(styled(b.text, body.textSize))
                    sb.setSpan(LeadingMarginSpan.Standard(indent.toInt(), (indent + markerWidth).toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    textPieces(pieces, sb, body, if (prev is PdfMarkdown.Block.ListItem) 3f else 7f)
                }
                is PdfMarkdown.Block.Quote -> textPieces(pieces, styled(b.text, body.textSize), paint(10.5f, color = 0xFF4B5563.toInt()), 7f, inset = 12f, bar = true)
                is PdfMarkdown.Block.Code -> textPieces(pieces, b.text.ifEmpty { " " }, paint(9f, Typeface.MONOSPACE), 8f, inset = 8f, background = codeBg, lineSpacing = 1.15f)
                is PdfMarkdown.Block.MathBlock -> mathBlock(pieces, b.latex)
                is PdfMarkdown.Block.Table -> table(pieces, b)
                PdfMarkdown.Block.Rule -> pieces += Piece(PdfLayoutLogic.Item(12f, 4f)) { c, l, t -> c.drawLine(l, t + 6f, l + width, t + 6f, strokePaint) }
            }
            prev = b
        }
        val placements = PdfLayoutLogic.paginate(pieces.map { it.item })
        val total = PdfLayoutLogic.pageCount(placements)
        if (total > PdfLayoutLogic.MAX_PAGES) throw EtlException("PDF가 ${PdfLayoutLogic.MAX_PAGES}쪽을 넘어요(${total}쪽). 내용을 나눠서 만들어 주세요")
        val doc = PdfDocument()
        try {
            var index = 0
            for (p in 0 until total) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, p + 1).create())
                val c = page.canvas
                while (index < pieces.size && placements[index].page == p) {
                    c.save()
                    // 쪽보다 큰 조각(긴 표 행 등)이 아래 여백을 넘지 않게 자른다.
                    c.clipRect(0f, MARGIN_TOP - 4f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT - MARGIN_BOTTOM + 4f)
                    pieces[index].draw(c, MARGIN_X, MARGIN_TOP + placements[index].y)
                    c.restore()
                    index++
                }
                val footer = PdfLayoutLogic.footer(title, p + 1, total)
                c.drawText(footer, (PAGE_WIDTH - small.measureText(footer)) / 2f, PAGE_HEIGHT - 30f, small)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        return Made(total)
    }

    // ---- 조각 만들기 -------------------------------------------------------------

    private fun titlePieces(pieces: MutableList<Piece>) {
        if (title.isNotBlank()) textPieces(pieces, title.trim(), paint(20f, Typeface.DEFAULT_BOLD), 0f, keepAll = true, lineSpacing = 1.15f)
        subtitle?.takeIf { it.isNotBlank() }?.let { textPieces(pieces, it, paint(8.5f, color = gray), 4f, keepAll = true) }
        pieces += Piece(PdfLayoutLogic.Item(10f, 4f)) { c, l, t -> c.drawLine(l, t + 5f, l + width, t + 5f, strokePaint) }
    }

    private fun layout(text: CharSequence, paint: TextPaint, w: Float, spacing: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, w.toInt()))
            .setAlignment(align)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    /**
     * 글을 줄 단위 조각으로. [keepAll]이면 모든 줄을 다음 조각과 붙여 둔다(제목). 문단은 첫 줄만 붙여 쪽 끝에 한 줄만 남지 않게 한다.
     */
    private fun textPieces(
        pieces: MutableList<Piece>, text: CharSequence, paint: TextPaint, gapBefore: Float,
        keepAll: Boolean = false, inset: Float = 0f, background: Int? = null, bar: Boolean = false,
        lineSpacing: Float = 1.25f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ) {
        val w = width - inset * 2
        val l = layout(text, paint, w, lineSpacing, align)
        for (i in 0 until l.lineCount) {
            val top = l.getLineTop(i).toFloat()
            val bottom = l.getLineBottom(i).toFloat()
            val pad = if (background != null) 3f else 0f
            val extraTop = if (i == 0) pad else 0f
            val extraBottom = if (i == l.lineCount - 1) pad else 0f
            val item = PdfLayoutLogic.Item(bottom - top + extraTop + extraBottom, if (i == 0) gapBefore else 0f,
                keepAll || (i == 0 && l.lineCount > 1))
            pieces += Piece(item) { c, left, y ->
                if (background != null) {
                    fillPaint.color = background
                    c.drawRect(left, y, left + width, y + item.height, fillPaint)
                }
                if (bar) {
                    fillPaint.color = line
                    c.drawRect(left, y, left + 2.5f, y + item.height, fillPaint)
                }
                c.save()
                c.translate(left + inset, y + extraTop - top)
                c.clipRect(0f, top, w + 1f, bottom)
                l.draw(c)
                c.restore()
            }
        }
    }

    private fun mathBlock(pieces: MutableList<Piece>, latex: String) {
        val img = MathRender.render(latex, 12f * SCALE, ink, inline = false)
        if (img == null) {
            textPieces(pieces, MathText.toUnicode(latex).ifBlank { latex }, paint(11f, Typeface.create(Typeface.SERIF, Typeface.ITALIC)), 8f,
                align = Layout.Alignment.ALIGN_CENTER)
            return
        }
        var w = img.bitmap.width / SCALE
        var h = img.bitmap.height / SCALE
        val fit = min(1f, min(width / w, PdfLayoutLogic.contentHeight / h))
        w *= fit
        h *= fit
        pieces += Piece(PdfLayoutLogic.Item(h, 8f)) { c, left, top ->
            val x = left + (width - w) / 2f
            c.drawBitmap(img.bitmap, null, RectF(x, top, x + w, top + h), bitmapPaint)
        }
    }

    private fun table(pieces: MutableList<Piece>, t: PdfMarkdown.Block.Table) {
        val n = t.header.size
        val size = 9.5f
        val cellPaint = paint(size)
        val headPaint = paint(size, Typeface.DEFAULT_BOLD)
        val pad = 4f
        val minCol = 40f
        if (n == 0) return
        if (n * minCol > width) {
            // 칸이 너무 많으면 행마다 "머리: 값" 글로 적는다.
            for (row in t.rows) {
                val sb = SpannableStringBuilder()
                row.forEachIndexed { j, cell ->
                    if (j > 0) sb.append("  ·  ")
                    val start = sb.length
                    sb.append(t.header[j].text).append(": ")
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(styled(cell, size))
                }
                textPieces(pieces, sb, cellPaint, 4f)
            }
            return
        }
        val all = listOf(t.header) + t.rows
        val natural = FloatArray(n) { j ->
            val widest = all.maxOf { r -> r[j].text.split('\n').maxOf { s -> (if (r === t.header) headPaint else cellPaint).measureText(s) } }
            (widest + pad * 2 + 1f).coerceIn(minCol, width * 0.6f)
        }
        val sum = natural.sum()
        val widths = if (sum <= width) FloatArray(n) { natural[it] * width / sum } else {
            // 넓은 칸을 줄여 맞춘다: 최소 폭을 먼저 주고 남은 폭을 원래 폭에 비례해 나눈다.
            val spare = width - n * minCol
            val over = natural.map { it - minCol }.sum()
            FloatArray(n) { minCol + (natural[it] - minCol) * spare / over }
        }
        all.forEachIndexed { r, row ->
            val head = r == 0
            val layouts = row.mapIndexed { j, cell -> layout(styled(cell, size), if (head) headPaint else cellPaint, widths[j] - pad * 2, 1.2f) }
            val h = layouts.maxOf { it.height }.toFloat() + pad * 2
            pieces += Piece(PdfLayoutLogic.Item(h, if (head) 8f else 0f, keepWithNext = head)) { c, left, top ->
                var x = left
                for (j in 0 until n) {
                    if (head) {
                        fillPaint.color = headerBg
                        c.drawRect(x, top, x + widths[j], top + h, fillPaint)
                    }
                    c.drawRect(x, top, x + widths[j], top + h, strokePaint)
                    c.save()
                    c.translate(x + pad, top + pad)
                    layouts[j].draw(c)
                    c.restore()
                    x += widths[j]
                }
            }
        }
    }

    // ---- 인라인 서식 ---------------------------------------------------------------

    private fun styled(inline: PdfMarkdown.Inline, size: Float): CharSequence {
        val sb = SpannableStringBuilder(inline.text)
        for (sp in inline.spans) {
            val s = sp.start.coerceIn(0, sb.length)
            val e = sp.end.coerceIn(s, sb.length)
            if (s == e) continue
            val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            when (sp.style) {
                PdfMarkdown.Style.BOLD -> sb.setSpan(StyleSpan(Typeface.BOLD), s, e, flag)
                PdfMarkdown.Style.ITALIC -> sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, flag)
                PdfMarkdown.Style.CODE -> {
                    sb.setSpan(TypefaceSpan("monospace"), s, e, flag)
                    sb.setSpan(BackgroundColorSpan(codeBg), s, e, flag)
                }
                PdfMarkdown.Style.LINK -> {
                    sb.setSpan(ForegroundColorSpan(link), s, e, flag)
                    sb.setSpan(UnderlineSpan(), s, e, flag)
                }
                PdfMarkdown.Style.MATH -> {
                    val img = sp.latex?.let { MathRender.render(it, size * SCALE, ink, inline = true, axisPx = size * SCALE * 0.3f) }
                    if (img != null) sb.setSpan(MathSpan(img.bitmap, img.baseline, SCALE, width), s, e, flag)
                    else sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, flag)
                }
            }
        }
        return sb
    }

    /** 인라인 수식 그림. 기준선을 글줄 기준선에 맞추고, 줄보다 넓으면 줄인다. */
    private inner class MathSpan(val bmp: Bitmap, baseline: Int, scale: Float, maxWidth: Float) : ReplacementSpan() {
        private val k = min(1f / scale, maxWidth / bmp.width)
        private val w = bmp.width * k
        private val asc = baseline * k
        private val desc = (bmp.height - baseline) * k

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val p = paint.fontMetricsInt
                fm.ascent = min(p.ascent, -ceil(asc).toInt())
                fm.top = min(p.top, fm.ascent)
                fm.descent = max(p.descent, ceil(desc).toInt())
                fm.bottom = max(p.bottom, fm.descent)
                fm.leading = p.leading
            }
            return ceil(w).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            canvas.drawBitmap(bmp, null, RectF(x, y - asc, x + w, y + desc), bitmapPaint)
        }
    }

    private companion object {
        /** 수식 그림 해상도 배율(포인트당 픽셀). */
        const val SCALE = 3f
    }
}
