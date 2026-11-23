package kr.snu.etlassistant.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** 수식도 같은 선택 가능한 본문 안에 그린다. 여러 이미지/선택 영역 사이에서 제스처를 넘기지 않는다. */
@Composable
internal fun SelectableMathBody(
    result: MarkdownLite.Result, style: TextStyle, color: Color, codeBackground: Color,
    linkColor: Color, onLink: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val content = remember(result) { MathSelectionText.prepare(result) }
    val fontPx = with(density) { (if (style.fontSize.isSpecified) style.fontSize else 16.sp).toPx() }
    val linePx = with(density) { if (style.lineHeight.isSpecified) style.lineHeight.toPx() else fontPx * 1.4f }
    val typeface = LocalFontFamilyResolver.current.resolve(style.fontFamily,
        style.fontWeight ?: FontWeight.Normal, style.fontStyle ?: FontStyle.Normal,
        style.fontSynthesis ?: FontSynthesis.All).value as? Typeface ?: Typeface.DEFAULT
    AndroidView(
        factory = { SelectableMathTextView(it) },
        modifier = modifier.fillMaxWidth(),
        update = { it.bind(content, fontPx, linePx, typeface, color.toArgb(), codeBackground.toArgb(), linkColor.toArgb(), onLink) },
    )
}

/** TextView의 기본 선택 손잡이/부분 복사와 ReplacementSpan의 수식 그리기를 함께 사용한다. */
internal class SelectableMathTextView(context: Context) : AppCompatTextView(context) {
    private var content: MathSelectionText? = null
    private var bodyColor = 0
    private var codeColor = 0
    private var urlColor = 0
    private var openLink: (String) -> Unit = {}
    private var dirty = true
    private var lineHeightPx = 0f
    private var renderedWidth = -1
    private var mathSpans = emptyList<MathImageSpan>()
    private var actionMode: ActionMode? = null
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var downHadSelection = false
    private var panSpan: MathImageSpan? = null
    private var panning = false
    private var lastX = 0f
    private var pendingLink: ClickableSpan? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextIsSelectable(true)
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { actionMode = mode; return true }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) { actionMode = null }
        }
    }

    fun bind(value: MathSelectionText, fontPx: Float, linePx: Float, fontTypeface: Typeface, foreground: Int, background: Int,
        link: Int, onLink: (String) -> Unit) {
        openLink = onLink
        // 상태 재구성 때 같은 본문을 다시 setText하면 진행 중인 선택이 사라진다.
        if (content == value && textSize == fontPx && lineHeightPx == linePx && typeface == fontTypeface &&
            bodyColor == foreground && codeColor == background && urlColor == link) return
        content = value
        bodyColor = foreground
        codeColor = background
        urlColor = link
        lineHeightPx = linePx
        typeface = fontTypeface
        setTextColor(foreground)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
        setLineSpacing((linePx - (paint.fontMetrics.descent - paint.fontMetrics.ascent)).coerceAtLeast(0f), 1f)
        highlightColor = (link and 0x00ffffff) or 0x44000000
        dirty = true
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight).coerceAtLeast(1)
        if (dirty || renderedWidth != available) rebuild(available)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun rebuild(available: Int) {
        val model = content ?: return
        val styled = SpannableString(model.display)
        fun add(span: Any, start: Int, end: Int) { styled.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        for (style in ChatLogic.styleRanges(model.original)) {
            if (style.bold) add(StyleSpan(Typeface.BOLD), style.start, style.end)
            if (style.mono) add(TypefaceSpan("monospace"), style.start, style.end)
            if (style.background) add(BackgroundColorSpan(codeColor), style.start, style.end)
            if (style.sizeScale != 1f) add(RelativeSizeSpan(style.sizeScale), style.start, style.end)
            if (style.url != null) {
                val url = style.url
                add(object : ClickableSpan() {
                    override fun onClick(widget: View) = openLink(url)
                    override fun updateDrawState(ds: android.text.TextPaint) { ds.color = urlColor; ds.isUnderlineText = true }
                }, style.start, style.end)
                add(ForegroundColorSpan(urlColor), style.start, style.end)
            }
        }
        mathSpans = model.maths.map { math ->
            val block = math.kind == MarkdownLite.Kind.MATH_BLOCK
            val image = MathRender.render(math.latex.orEmpty(), textSize * if (block) 1.18f else 1.12f,
                bodyColor, inline = !block, axisPx = if (block) 0f else textSize * .34f * 1.12f)
            MathImageSpan(image, MathText.toUnicode(math.latex.orEmpty()), available.toFloat(),
                if (block) resources.displayMetrics.density * 4 else 0f).also { add(it, math.start, math.end) }
        }
        setText(styled, BufferType.SPANNABLE)
        renderedWidth = available
        dirty = false
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.copy && hasSelection()) {
            val selected = content?.copySelection(selectionStart, selectionEnd) ?: return super.onTextContextMenuItem(id)
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", selected))
            actionMode?.finish()
            (text as? Spannable)?.let { Selection.removeSelection(it) }
            return true
        }
        return super.onTextContextMenuItem(id)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                pendingLink = null
                downAt = SystemClock.uptimeMillis()
                downHadSelection = hasSelection()
                panning = false
                panSpan = mathSpans.firstOrNull { it.canPan && it.bounds.contains(
                    event.x - totalPaddingLeft + scrollX, event.y - totalPaddingTop + scrollY) }
            }
            MotionEvent.ACTION_MOVE -> {
                val span = panSpan
                if (span != null && !hasSelection() && !downHadSelection &&
                    (panning || (abs(event.x - downX) > slop && abs(event.x - downX) > abs(event.y - downY)))) {
                    if (!panning) {
                        MotionEvent.obtain(event).also { cancel ->
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.onTouchEvent(cancel)
                            cancel.recycle()
                        }
                    }
                    panning = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    span.pan(lastX - event.x)
                    lastX = event.x
                    // TextView의 하드웨어 글줄 캐시도 갱신해야 바뀐 수식 위치가 화면에 반영된다.
                    (text as? Spannable)?.let { styled ->
                        val start = styled.getSpanStart(span)
                        val end = styled.getSpanEnd(span)
                        if (start >= 0 && end > start) styled.setSpan(span, start, end, styled.getSpanFlags(span))
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (panning) {
                    panning = false
                    panSpan = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP && !downHadSelection && !hasSelection() &&
                    SystemClock.uptimeMillis() - downAt < ViewConfiguration.getLongPressTimeout() &&
                    abs(event.x - downX) < slop && abs(event.y - downY) < slop) {
                    val link = linkAt(event)
                    if (link != null) {
                        pendingLink = link
                        super.onTouchEvent(event)
                        performClick()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        val handled = super.performClick()
        val link = pendingLink ?: return handled
        pendingLink = null
        link.onClick(this)
        return true
    }

    private fun linkAt(event: MotionEvent): ClickableSpan? {
        val textLayout = layout ?: return null
        val x = event.x - totalPaddingLeft + scrollX
        val y = event.y - totalPaddingTop + scrollY
        if (y < 0 || y >= textLayout.height) return null
        val line = textLayout.getLineForVertical(y.toInt())
        if (x < textLayout.getLineLeft(line) || x > textLayout.getLineRight(line)) return null
        val at = getOffsetForPosition(event.x, event.y)
        val styled = text as? Spanned ?: return null
        return styled.getSpans(at, at, ClickableSpan::class.java).firstOrNull {
            at >= styled.getSpanStart(it) && at < styled.getSpanEnd(it)
        }
    }
}

/** 넓은 수식은 기존처럼 최대 0.78배까지만 줄이고, 수식 위에서 가로로 밀어 나머지를 본다. */
private class MathImageSpan(
    private val image: MathRender.Image?, private val fallback: String,
    private val maxWidth: Float, private val verticalPadding: Float,
) : ReplacementSpan() {
    val bounds = RectF()
    private var fullWidth = 0f
    private var visibleWidth = 0f
    private var height = 0f
    private var baseline = 0f
    private var offset = 0f
    val canPan: Boolean get() = fullWidth > visibleWidth
    fun pan(delta: Float) { offset = (offset + delta).coerceIn(0f, (fullWidth - visibleWidth).coerceAtLeast(0f)) }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (image != null) {
            val scale = if (image.bitmap.width > maxWidth) max(.78f, maxWidth / image.bitmap.width) else 1f
            fullWidth = image.bitmap.width * scale
            height = image.bitmap.height * scale
            baseline = image.baseline * scale
        } else {
            fullWidth = paint.measureText(fallback)
            baseline = -paint.fontMetrics.ascent
            height = baseline + paint.fontMetrics.descent
        }
        visibleWidth = minOf(fullWidth, maxWidth)
        fm?.let {
            paint.getFontMetricsInt(it)
            it.ascent = minOf(it.ascent, -ceil(baseline + verticalPadding).toInt())
            it.descent = maxOf(it.descent, ceil(height - baseline + verticalPadding).toInt())
            it.top = minOf(it.top, it.ascent)
            it.bottom = maxOf(it.bottom, it.descent)
        }
        return ceil(visibleWidth).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint) {
        bounds.set(x, y - baseline, x + visibleWidth, y - baseline + height)
        val saved = canvas.save()
        canvas.clipRect(bounds)
        if (image != null) {
            val target = RectF(x - offset, y - baseline, x - offset + fullWidth, y - baseline + height)
            canvas.drawBitmap(image.bitmap, null, target, paint)
        } else canvas.drawText(fallback, x - offset, y.toFloat(), paint)
        canvas.restoreToCount(saved)
    }
}
