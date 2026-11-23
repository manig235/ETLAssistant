package kr.snu.etlassistant.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * 일반 마크다운은 Compose Text, 수식이 있으면 하나의 선택 가능한 TextView로 그린다.
 * 수식 이미지 앞뒤로 본문을 나누지 않아 문장·수식을 가로질러 부분 선택/복사할 수 있다.
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
    val result = remember(text) { MarkdownLite.render(text) }
    if (result.spans.any { it.kind == MarkdownLite.Kind.MATH || it.kind == MarkdownLite.Kind.MATH_BLOCK }) {
        SelectableMathBody(result, style, color, codeBackground, linkColor, onLink, modifier)
    } else {
        val latestOnLink = rememberUpdatedState(onLink)
        val annotated = remember(result, style, codeBackground, linkColor) {
            markdownText(result, style, codeBackground, linkColor) { latestOnLink.value(it) }
        }
        Text(annotated, modifier = modifier, style = style, color = color)
    }
}

private fun markdownText(
    result: MarkdownLite.Result, style: TextStyle, codeBackground: Color,
    linkColor: Color, onLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(result.text)
    for (span in ChatLogic.styleRanges(result)) {
        if (span.url != null) {
            val url = span.url
            addLink(LinkAnnotation.Clickable(
                tag = url,
                styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            ) { onLink(url) }, span.start, span.end)
        } else {
            addStyle(SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontFamily = if (span.mono) FontFamily.Monospace else null,
                background = if (span.background) codeBackground else Color.Unspecified,
                fontSize = if (span.sizeScale != 1f && style.fontSize.isSpecified) style.fontSize * span.sizeScale else TextUnit.Unspecified,
            ), span.start, span.end)
        }
    }
}
