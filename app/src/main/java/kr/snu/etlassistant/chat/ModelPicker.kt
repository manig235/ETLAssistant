package kr.snu.etlassistant.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import kr.snu.etlassistant.codex.ModelCatalog
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.codex.ModelInfo

/**
 * AI 모델과 추론 강도를 고르는 화면 조각(Montage). 설정 화면에는 그대로 넣고, 채팅 화면에서는 바텀시트 안에 넣는다.
 * 스크롤은 만들지 않는다(호출한 쪽이 준다). `DesignSystemTheme` 안에서 쓰인다.
 *
 * @param models `model/list` 결과(비어 있으면 아직 불러오지 못한 것)
 * @param loading 목록을 불러오는 중인가
 * @param onChoice 선택이 바뀔 때마다 호출. 저장은 호출한 쪽이 `CodexSession.setModelChoice`로 한다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelPickerContent(
    models: List<ModelInfo>,
    choice: ModelChoice,
    loading: Boolean,
    onChoice: (ModelChoice) -> Unit,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography

    // 강도 칩의 기준 모델: 선택한 모델 → 기본 모델 → 첫 모델
    val target = models.firstOrNull { it.model == choice.model }
        ?: models.firstOrNull { it.isDefault }
        ?: models.firstOrNull()
    val unknownSelected = models.isNotEmpty() && choice.model != null && models.none { it.model == choice.model }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "다음 질문부터 적용돼요. 잘 모르겠으면 '자동'을 쓰세요.",
            style = t.body2Regular,
            color = c.foregroundNeutralSecondary,
        )
        Spacer(Modifier.height(16.dp))

        // ---- 모델 ----
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionCard(
                title = "자동 (권장)",
                description = "Codex가 정한 기본 모델을 써요",
                selected = choice.model == null,
                onClick = { onChoice(ModelChoice(null, choice.effort)) },
                extra = { EffortPicker(target, choice.effort, auto = true) { onChoice(choice.copy(effort = it)) } },
            )
            if (models.isEmpty()) {
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WantedCircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text("모델 목록을 불러오는 중이에요…", style = t.body1Medium, color = c.foregroundNeutralPrimary)
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.surfaceNeutralSecondary)
                            .padding(16.dp)
                    ) {
                        Text(
                            "아직 모델 목록을 불러오지 못했어요. 비서가 준비되고 로그인되면 보여요.",
                            style = t.body2Regular,
                            color = c.foregroundNeutralSecondary,
                        )
                    }
                }
            } else {
                for (m in models) {
                    OptionCard(
                        title = m.displayName,
                        description = m.description,
                        tag = if (m.isDefault) "기본" else null,
                        selected = choice.model == m.model,
                        onClick = {
                            val keep = choice.effort?.takeIf { e -> m.efforts.any { it.effort == e } }
                            onChoice(choice.copy(model = m.model, effort = keep))
                        },
                        extra = { EffortPicker(m, choice.effort, auto = false) { onChoice(choice.copy(effort = it)) } },
                    )
                }
                if (unknownSelected) {
                    OptionCard(
                        title = choice.model.orEmpty(),
                        description = "(지금은 쓸 수 없는 모델)",
                        selected = true,
                        dimmed = true,
                        onClick = {},
                    )
                }
            }
        }
    }
}

/** 선택한 강도(없으면 모델 기본 강도)의 설명. 설명이 없으면 null. */
private fun effortNote(target: ModelInfo?, effort: String?): String? {
    if (target == null) return null
    val key = effort ?: target.defaultEffort ?: return null
    val desc = target.efforts.firstOrNull { it.effort == key }?.description?.takeIf { it.isNotBlank() } ?: return null
    return if (effort == null) "기본값은 ${ModelCatalog.effortLabel(key)}이에요. $desc" else desc
}

@Composable
private fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String? = null,
    dimmed: Boolean = false,
    /** 선택됐을 때만 카드 안(설명 아래)에 보이는 내용. */
    extra: (@Composable () -> Unit)? = null,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.55f else 1f)
            .clip(shape)
            .background(c.surfaceNeutralSecondary)
            .then(if (selected) Modifier.border(BorderStroke(2.dp, c.lineBrandPrimary), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = t.body1Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f, fill = false))
                if (tag != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tag,
                        style = t.caption1Bold,
                        color = c.foregroundBrandPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.surfaceBrandSubtle)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(description, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
            }
            if (selected && extra != null) extra()
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "선택됨",
                tint = c.foregroundBrandPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 선택된 모델 카드 안의 '생각의 깊이' 칩. 지원하는 강도가 없으면 아무것도 그리지 않는다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffortPicker(model: ModelInfo?, effort: String?, auto: Boolean, onEffort: (String?) -> Unit) {
    if (model == null || model.efforts.isEmpty()) return
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Spacer(Modifier.height(14.dp))
    Text("생각의 깊이", style = t.body2Medium, color = c.foregroundNeutralPrimary)
    Spacer(Modifier.height(8.dp))
    // 줄이 넘치면 다음 줄로 넘긴다(옆으로 잘리면 안 된다).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WantedChip(text = "기본", isActive = effort == null, onClick = { onEffort(null) })
        for (e in model.efforts) {
            WantedChip(
                text = ModelCatalog.effortLabel(e.effort),
                isActive = effort == e.effort,
                onClick = { onEffort(e.effort) },
            )
        }
    }
    val note = effortNote(model, effort)
    Spacer(Modifier.height(8.dp))
    Text(
        (if (auto) "기본 모델(${model.displayName}) 기준이에요. " else "") + "높을수록 오래 생각해서 어려운 문제에 좋고, 낮을수록 빨라요." +
            (note?.let { " $it" } ?: ""),
        style = t.caption1Regular,
        color = c.foregroundNeutralSecondary,
    )
}
