package kr.snu.etlassistant.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kr.snu.etlassistant.chat.MarkdownLite.Kind

/** 수식 글자 크기 배율. JLatexMath(Computer Modern)는 같은 크기에서 본문 글꼴보다 작아 보여 조금 키운다. */
private const val INLINE_MATH_SCALE = 1.12f
private const val BLOCK_MATH_SCALE = 1.18f
/** 블록 수식이 말풍선보다 넓을 때 이 비율까지는 줄여서 맞춘다. */
private const val BLOCK_MIN_FIT = 0.78f

/** 그리기 준비가 끝난 한 조각. */
sealed class MdPiece {
    class Text(val text: AnnotatedString, val inline: Map<String, InlineTextContent>) : MdPiece()
    class Block(val image: MathRender.Image?, val raw: String, val fallback: String) : MdPiece()
}

/**
 * 어시스턴트 본문(마크다운 일부 + LaTeX 수식)을 말풍선 안에 그린다.
 * 인라인 수식은 글줄 안의 그림([InlineTextContent]), 블록 수식은 가로로 밀어 볼 수 있는 그림이다.
 * 부분 선택·복사하면 수식 자리는 원래 LaTeX 글이 나온다. 그리지 못한 수식은 유니코드 글로 대신한다.
 */
@Composable
fun MarkdownBody(
    text: String,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    linkColor: Color,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pieces = remember(text, style, color, codeBackground, linkColor, density) {
        buildPieces(text, style, color, codeBackground, linkColor, density, onLink)
    }
    Column(modifier) {
        for (p in pieces) {
            when (p) {
                is MdPiece.Text -> Text(p.text, style = style, color = color, inlineContent = p.inline)
                is MdPiece.Block -> {
                    val img = p.image
                    if (img == null) {
                        Text(p.fallback, style = style, color = color, modifier = Modifier.padding(vertical = 4.dp))
                    } else {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            // 조금 넓은 식은 최대 [BLOCK_MIN_FIT]까지 줄여 한 화면에 넣고, 그래도 넘치면 가로로 밀어 본다
                            val availPx = constraints.maxWidth.toFloat()
                            val bw = img.bitmap.width.toFloat()
                            val scale = if (bw > availPx && availPx > 0f) maxOf(availPx / bw, BLOCK_MIN_FIT) else 1f
                            val w = with(density) { (bw * scale).toDp() }
                            val h = with(density) { (img.bitmap.height * scale).toDp() }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Image(
                                    img.bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier
                                        .size(w, h)
                                        .semantics { contentDescription = p.raw },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun buildPieces(
    text: String,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    linkColor: Color,
    density: Density,
    onLink: (String) -> Unit,
): List<MdPiece> {
    val fontPx = with(density) { (if (style.fontSize.isSpecified) style.fontSize else TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)).toPx() }
    val argb = color.toArgb()
    return MathText.split(MarkdownLite.render(text)).map { piece ->
        when (piece) {
            is MathText.Piece.Block -> MdPiece.Block(
                MathRender.render(piece.latex, fontPx * BLOCK_MATH_SCALE, argb, inline = false),
                piece.raw,
                MathText.toUnicode(piece.latex),
            )
            is MathText.Piece.Text -> buildText(piece.result, style, fontPx, argb, codeBackground, linkColor, density, onLink)
        }
    }
}

private fun buildText(
    r: MarkdownLite.Result,
    style: TextStyle,
    fontPx: Float,
    argb: Int,
    codeBackground: Color,
    linkColor: Color,
    density: Density,
    onLink: (String) -> Unit,
): MdPiece.Text {
    val inline = HashMap<String, InlineTextContent>()
    val reps = ArrayList<MathText.Replacement>()
    val maths = r.spans.filter { it.kind == Kind.MATH && it.latex != null }.sortedBy { it.start }
    // 글줄 가운데에서 글자 기준선까지 거리(대략 글자 크기의 1/3)
    val axisPx = fontPx * 0.34f
    val annotated = buildAnnotatedString {
        var pos = 0
        for (m in maths) {
            if (m.start < pos || m.end > r.text.length) continue
            append(r.text.substring(pos, m.start))
            val raw = r.text.substring(m.start, m.end)
            val sizePx = fontPx * INLINE_MATH_SCALE
            val img = MathRender.render(m.latex!!, sizePx, argb, inline = true, axisPx = axisPx * INLINE_MATH_SCALE)
            val newStart = length
            if (img != null) {
                val id = "math${inline.size}"
                val w = with(density) { img.bitmap.width.toSp() }
                val h = with(density) { img.bitmap.height.toSp() }
                inline[id] = InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
                    Image(img.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                // 대체 글을 원문 그대로 두어, 선택·복사하면 LaTeX가 나오게 한다
                appendInlineContent(id, raw)
            } else {
                append(MathText.toUnicode(m.latex))
            }
            reps += MathText.Replacement(m.start, m.end, newStart, length)
            pos = m.end
        }
        append(r.text.substring(pos))

        for (s in ChatLogic.styleRanges(r)) {
            val start = MathText.mapOffset(reps, s.start, isEnd = false)
            val end = MathText.mapOffset(reps, s.end, isEnd = true).coerceAtMost(length)
            if (end <= start) continue
            if (s.url != null) {
                val url = s.url
                addLink(
                    LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ) { onLink(url) },
                    start,
                    end,
                )
            } else {
                addStyle(
                    SpanStyle(
                        fontWeight = if (s.bold) FontWeight.Bold else null,
                        fontFamily = if (s.mono) FontFamily.Monospace else null,
                        background = if (s.background) codeBackground else Color.Unspecified,
                        fontSize = if (s.sizeScale != 1f && style.fontSize.isSpecified) style.fontSize * s.sizeScale else TextUnit.Unspecified,
                    ),
                    start,
                    end,
                )
            }
        }
    }
    return MdPiece.Text(annotated, inline)
}
