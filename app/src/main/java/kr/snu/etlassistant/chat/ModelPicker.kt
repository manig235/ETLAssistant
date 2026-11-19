package kr.snu.etlassistant.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.codex.ModelInfo

/**
 * AI 모델과 추론 강도를 고르는 화면 조각(Montage). 설정 화면에는 그대로 넣고, 채팅 화면에서는 바텀시트 안에 넣는다.
 * **스텁**: 모델 담당이 구현한다. 시그니처는 확정이다.
 *
 * @param models `model/list` 결과(비어 있으면 아직 불러오지 못한 것)
 * @param loading 목록을 불러오는 중인가
 * @param onChoice 선택이 바뀔 때마다 호출. 저장은 호출한 쪽이 `CodexSession.setModelChoice`로 한다.
 */
@Composable
fun ModelPickerContent(
    models: List<ModelInfo>,
    choice: ModelChoice,
    loading: Boolean,
    onChoice: (ModelChoice) -> Unit,
) {
    Text("(모델 선택 스텁)")
}
