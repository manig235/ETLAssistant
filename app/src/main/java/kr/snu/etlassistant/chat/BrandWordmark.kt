package kr.snu.etlassistant.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import com.wanted.android.wanted.design.theme.DesignSystemTheme

/**
 * 영문 이름(워드마크). 한국어 이름은 어디서나 "영민이" 그대로이고, "Young Mini"(영미니처럼 읽힘)는
 * 로고 아래·앱 정보에만 붙는 보조 이름이다. 런처·알림·비서 이름·캘린더·폴더 이름에는 쓰지 않는다.
 */
object Brand {
    const val ENGLISH_NAME = "Young Mini"
}

/** 로고 아래 등에 쓰는 작은 워드마크: 중간 굵기, 약간 벌린 자간, 보조 글자색(라이트·다크 모두 Montage 토큰). */
@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = DesignSystemTheme.typography.headline2Medium,
) {
    Text(
        Brand.ENGLISH_NAME,
        modifier = modifier,
        style = style.copy(letterSpacing = 0.06.em),
        color = DesignSystemTheme.colors.foregroundNeutralSecondary,
        textAlign = TextAlign.Center,
    )
}
