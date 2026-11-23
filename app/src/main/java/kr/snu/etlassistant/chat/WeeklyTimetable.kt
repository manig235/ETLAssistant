package kr.snu.etlassistant.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kr.snu.etlassistant.etl.TimetableLogic

private val courseColors = listOf(
    Color(0xFF9ACBF0) to Color(0xFF19364E), Color(0xFFE5AFBC) to Color(0xFF492A34),
    Color(0xFFAFA9E4) to Color(0xFF302C55), Color(0xFF88CDBD) to Color(0xFF183D35),
    Color(0xFFECC596) to Color(0xFF49351E), Color(0xFFE3CC8E) to Color(0xFF473C1A),
    Color(0xFFC6B1DD) to Color(0xFF3E2D4D), Color(0xFFB4CE9E) to Color(0xFF2F4023),
    Color(0xFF9DB3CF) to Color(0xFF24364D),
)

private fun courseColor(id: Long) = courseColors[Math.floorMod(id.hashCode(), courseColors.size)]

/** 실제 수업 데이터로 시간에 비례한 블록과 강의 상세 정보를 표시한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeeklyTimetable(weekly: TimetableLogic.Weekly, modifier: Modifier = Modifier) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val grid = remember(weekly) { TimetableGridLogic.build(weekly.courses) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val horizontal = rememberScrollState()
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val hourHeight = 46.dp * scale
    val axisWidth = 32.dp
    val gridHeight = hourHeight * (grid.endHour - grid.startHour)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val dayWidth = maxOf((maxWidth - axisWidth) / grid.days.size, 64.dp * scale)
        Column {
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                Spacer(Modifier.width(axisWidth))
                Row(Modifier.horizontalScroll(horizontal).width(dayWidth * grid.days.size)) {
                    grid.days.forEach { day ->
                        Box(Modifier.width(dayWidth).height(44.dp), contentAlignment = Alignment.Center) {
                            Text(day, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.foregroundNeutralSecondary)
                        }
                    }
                }
            }
            Row(Modifier.height(gridHeight + 16.dp)) {
                Box(Modifier.width(axisWidth).height(gridHeight + 16.dp)) {
                    for (hour in grid.startHour..grid.endHour) {
                        Box(Modifier.offset(y = hourHeight * (hour - grid.startHour)).width(axisWidth - 5.dp).height(16.dp), contentAlignment = Alignment.CenterEnd) {
                            Text("%02d".format(hour), fontSize = 10.sp, color = c.foregroundNeutralSecondary)
                        }
                    }
                }
                Box(Modifier.horizontalScroll(horizontal).width(dayWidth * grid.days.size).height(gridHeight + 16.dp)) {
                    val lineColor = c.foregroundNeutralSecondary.copy(alpha = .13f)
                    val halfColor = c.foregroundNeutralSecondary.copy(alpha = .06f)
                    Canvas(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                        for (i in 0..(grid.endHour - grid.startHour) * 2) {
                            val y = i * hourHeight.toPx() / 2
                            drawLine(if (i % 2 == 0) lineColor else halfColor, Offset(0f, y), Offset(size.width, y), .5.dp.toPx())
                        }
                        for (day in 0..grid.days.size) {
                            val x = dayWidth.toPx() * day
                            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), .5.dp.toPx())
                        }
                    }
                    grid.blocks.forEach { block ->
                        val (background, foreground) = courseColor(block.course.courseId)
                        val width = dayWidth / block.lanes - 4.dp
                        val narrow = width < 56.dp * scale
                        val top = hourHeight * ((block.startMinute - grid.startHour * 60) / 60f) + 10.dp
                        val name = TimetableLogic.shortName(block.course.name)
                        Column(
                            Modifier.offset(x = dayWidth * grid.days.indexOf(block.meeting.day) + dayWidth / block.lanes * block.lane + 2.dp, y = top)
                                .width(width).height(hourHeight * (block.duration / 60f) - 4.dp)
                                .clip(RoundedCornerShape(6.dp)).background(background)
                                .clickable(onClickLabel = "수업 상세 보기", role = Role.Button) { selectedId = block.course.courseId }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "$name, ${block.meeting.day}요일, ${block.meeting.start}~${block.meeting.end}"
                                }.padding(horizontal = 5.dp, vertical = if (block.duration < 60) 3.dp else 5.dp),
                        ) {
                            Text(name, color = foreground, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                                style = TextStyle(lineBreak = LineBreak.Paragraph),
                                maxLines = if (block.duration < 60 || narrow) 1 else if (block.duration <= 90) 2 else 4,
                                overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(if (block.duration < 60) 2.dp else 3.dp))
                            Text(if (narrow) "${block.meeting.start}\n${block.meeting.end}" else "${block.meeting.start}–${block.meeting.end}",
                                color = foreground.copy(alpha = .85f), fontSize = 9.sp, lineHeight = 11.sp,
                                maxLines = if (narrow) 2 else 1, softWrap = narrow)
                        }
                    }
                }
            }
            Text("수업 블록을 누르면 자세한 정보를 볼 수 있어요", style = t.caption1Regular, color = c.foregroundNeutralSecondary,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 12.dp))
        }
    }
    weekly.courses.firstOrNull { it.courseId == selectedId }?.let { course ->
        val dark = isSystemInDarkTheme()
        ModalBottomSheet(
            onDismissRequest = { selectedId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.backgroundNeutralPrimary,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !dark, isAppearanceLightNavigationBars = !dark),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(9.dp).clip(CircleShape).background(courseColor(course.courseId).first))
                    Spacer(Modifier.width(7.dp))
                    Text("수업 정보", style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                }
                Spacer(Modifier.height(12.dp))
                Text(TimetableLogic.shortName(course.name), style = t.title2Bold, color = c.foregroundNeutralPrimary)
                Spacer(Modifier.height(24.dp))
                CourseDetailRow(Icons.Outlined.PersonOutline, "교수님", course.professor?.takeIf { it.isNotBlank() } ?: "교수 정보를 확인하지 못했어요")
                CourseDetailRow(Icons.Outlined.LocationOn, "강의실", course.meetings.map { TimetableGridLogic.roomLabel(it.room) }.distinct().joinToString("\n"))
                CourseDetailRow(Icons.Outlined.Schedule, "수업 시간", course.meetings
                    .sortedWith(compareBy({ TimetableLogic.WEEKDAYS.indexOf(it.day) }, { it.start }))
                    .joinToString("\n") { "${it.day}요일  ${it.start} – ${it.end}" })
                Spacer(Modifier.height(20.dp))
                WantedButton(text = "확인", modifier = Modifier.fillMaxWidth(), type = ButtonType.PRIMARY,
                    size = ButtonSize.LARGE, variant = ButtonVariant.SOLID, onClick = { selectedId = null })
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CourseDetailRow(icon: ImageVector, label: String, value: String) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = t.body2Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.width(76.dp))
        Text(value, style = t.body2Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
    }
}
