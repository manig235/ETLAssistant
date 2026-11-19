package kr.snu.etlassistant.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/** 어시스턴트 본문(마크다운 일부)을 [AnnotatedString]으로 바꾼다. 같은 글과 색이면 다시 계산하지 않는다. */
@Composable
fun rememberMarkdown(
    text: String,
    codeBackground: Color,
    linkColor: Color,
    baseFontSize: TextUnit,
    onLink: (String) -> Unit,
): AnnotatedString = remember(text, codeBackground, linkColor, baseFontSize) {
    buildMarkdown(MarkdownLite.render(text), codeBackground, linkColor, baseFontSize, onLink)
}

fun buildMarkdown(
    r: MarkdownLite.Result,
    codeBackground: Color,
    linkColor: Color,
    baseFontSize: TextUnit,
    onLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(r.text)
    for (s in ChatLogic.styleRanges(r)) {
        if (s.url != null) {
            val url = s.url
            addLink(
                LinkAnnotation.Clickable(
                    tag = url,
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ) { onLink(url) },
                s.start,
                s.end,
            )
        } else {
            addStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontFamily = if (s.mono) FontFamily.Monospace else null,
                    background = if (s.background) codeBackground else Color.Unspecified,
                    fontSize = if (s.sizeScale != 1f && baseFontSize.isSpecified) baseFontSize * s.sizeScale else TextUnit.Unspecified,
                ),
                s.start,
                s.end,
            )
        }
    }
}
