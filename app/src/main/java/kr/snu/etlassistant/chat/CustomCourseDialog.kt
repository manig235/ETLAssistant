package kr.snu.etlassistant.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.input.textinput.textfield.WantedTextField
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kr.snu.etlassistant.etl.CustomTimetableLogic
import kr.snu.etlassistant.etl.TimetableLogic
import java.time.LocalTime
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomCourseDialog(
    course: TimetableLogic.CourseMeetings?,
    onDismiss: () -> Unit,
    onSave: (TimetableLogic.CourseMeetings) -> Unit,
    onDelete: (Long) -> Unit,
    pickTime: (LocalTime, (LocalTime) -> Unit) -> Unit,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val id = rememberSaveable { course?.courseId ?: (UUID.randomUUID().mostSignificantBits or Long.MIN_VALUE) }
    var name by rememberSaveable { mutableStateOf(course?.name.orEmpty()) }
    var professor by rememberSaveable { mutableStateOf(course?.professor.orEmpty()) }
    var room by rememberSaveable { mutableStateOf(course?.meetings?.firstOrNull()?.room.orEmpty()) }
    var days by rememberSaveable { mutableStateOf(course?.meetings?.map { it.day }?.distinct()?.joinToString("") ?: "월") }
    var start by rememberSaveable { mutableStateOf(course?.meetings?.firstOrNull()?.start ?: "09:00") }
    var end by rememberSaveable { mutableStateOf(course?.meetings?.firstOrNull()?.end ?: "10:15") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.backgroundNeutralPrimary,
        title = { Text(if (course == null) "수업 직접 추가" else "직접 추가한 수업 수정", style = t.title3Bold, color = c.foregroundNeutralPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("매주 반복되는 수업이나 일정을 추가해요.", style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                Text("과목명", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                WantedTextField(text = name, placeholder = "과목명 또는 일정 이름", background = c.backgroundNeutralPrimary,
                    onValueChange = { name = it.take(80); error = null }, modifier = Modifier.fillMaxWidth())
                Text("요일 · 여러 개 선택 가능", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TimetableLogic.WEEKDAYS.forEach { day ->
                        WantedChip(text = day.toString(), isActive = day in days, onClick = {
                            days = if (day in days) days.replace(day.toString(), "") else days + day
                            error = null
                        })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WantedButton(text = "시작 $start", type = ButtonType.ASSISTIVE, size = ButtonSize.MEDIUM, variant = ButtonVariant.OUTLINED,
                        onClick = { pickTime(LocalTime.parse(start)) { start = it.toString(); error = null } })
                    WantedButton(text = "종료 $end", type = ButtonType.ASSISTIVE, size = ButtonSize.MEDIUM, variant = ButtonVariant.OUTLINED,
                        onClick = { pickTime(LocalTime.parse(end)) { end = it.toString(); error = null } })
                }
                WantedTextField(text = room, placeholder = "강의실 (선택)", background = c.backgroundNeutralPrimary,
                    onValueChange = { room = it.take(80) }, modifier = Modifier.fillMaxWidth())
                WantedTextField(text = professor, placeholder = "교수님 (선택)", background = c.backgroundNeutralPrimary,
                    onValueChange = { professor = it.take(80) }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = t.caption1Regular, color = c.foregroundNegativePrimary) }
                if (course != null) WantedTextButton(text = "이 수업 삭제", onClick = { confirmingDelete = true })
            }
        },
        confirmButton = { WantedTextButton(text = "저장", onClick = {
            val result = TimetableLogic.CourseMeetings(id, name, TimetableLogic.WEEKDAYS.filter { it in days }.map {
                TimetableLogic.Meeting(it.toString(), start, end, room)
            }, professor)
            error = CustomTimetableLogic.validationError(result)
            if (error == null) onSave(CustomTimetableLogic.normalize(result)!!)
        }) },
        dismissButton = { WantedTextButton(text = "취소", onClick = onDismiss) },
    )
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false }, containerColor = c.backgroundNeutralPrimary,
        title = { Text("수업을 삭제할까요?", color = c.foregroundNeutralPrimary) },
        text = { Text(name, color = c.foregroundNeutralSecondary) },
        confirmButton = { WantedTextButton(text = "삭제", onClick = { onDelete(id) }) },
        dismissButton = { WantedTextButton(text = "취소", onClick = { confirmingDelete = false }) },
    )
}
