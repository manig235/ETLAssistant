package kr.snu.etlassistant.chat

import android.content.Intent
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.input.textinput.textfield.WantedTextField
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.etl.Timetable
import kr.snu.etlassistant.etl.TimetableChangeStore
import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.etl.TimetableStore
import kr.snu.etlassistant.notify.CourseFilterStore
import java.time.LocalDate
import java.time.LocalTime

/**
 * 시간표: 수강편람의 주간 수업·직접 추가한 반복 수업·보강과 휴강.
 * 보강·휴강은 AI 답변(etl_timetable), 브리핑의 "오늘 수업", 수업 알림에 반영된다.
 * 과목별 알림에서 "시간표·오늘 수업에서도 숨기기"를 켠 과목은 수업과 보강·휴강 모두 이 화면에 보이지 않는다(AI 도구에는 그대로).
 */
class TimetableActivity : AppCompatActivity() {
    private val store by lazy { TimetableStore(this) }
    private val changes by lazy { TimetableChangeStore(this) }
    private var weekly by mutableStateOf<TimetableLogic.Weekly?>(null)
    private var changeList by mutableStateOf<List<TimetableLogic.Change>>(emptyList())
    private var loading by mutableStateOf(false)
    /** 보강·휴강 공지의 "시간표에 반영"으로 온 초안. */
    private var draft by mutableStateOf<kr.snu.etlassistant.notify.ScheduleDraft?>(null)

    private fun readDraft(i: Intent?) {
        val d = kr.snu.etlassistant.notify.ScheduleDraft.fromJson(i?.getStringExtra(DraftIntents.EXTRA_DRAFT))?.takeIf { it.timetable } ?: return
        if (changes.load().any { it.sourceAnnouncementId == d.announcementId }) { toast("이 공지는 이미 시간표에 반영했어요"); return }
        draft = d
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDraft(intent)
    }
    private var hiddenCourses by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readDraft(intent)
        setContent {
            TimetableScreen(
                weekly = weekly, changes = changeList, loading = loading,
                draft = draft, onDraftDone = { draft = null },
                // "✨ 비서가 공지 읽고 채우기": 창을 닫고 채팅 입력창에 요청을 채운다(보내지는 않는다).
                onAskAssistant = { d ->
                    draft = null
                    runCatching { startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PREFILL, d.prefill).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                },
                hiddenCourses = hiddenCourses,
                onHiddenCourses = { startActivity(Intent(this, CourseAlertsActivity::class.java)) },
                onBack = { finish() },
                onSaveCustom = { store.saveCustom(it); reload() },
                onDeleteCustom = { store.deleteCustom(it); reload() },
                onAdd = { changes.add(it); reload() },
                onDelete = { changes.delete(it.id); reload() },
                pickDate = { initial, done ->
                    DatePickerDialog(this, { _, y, m, d -> done(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                },
                pickTime = { initial, done ->
                    TimePickerDialog(this, { _, h, m -> done(LocalTime.of(h, m)) }, initial.hour, initial.minute, DateFormat.is24HourFormat(this)).show()
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
        val cached = store.loadCatalog()
        val now = System.currentTimeMillis()
        if (cached == null || cached.metadataVersion < 2 || now < cached.fetchedAt || now - cached.fetchedAt >= Timetable.CACHE_MS) {
            refresh(force = false)
        }
    }

    private fun reload() {
        val filter = CourseFilterStore(this).load()
        val all = store.load()
        weekly = filter.timetableWeekly(all)
        val today = LocalDate.now(EtlLogic.KST)
        changeList = filter.timetableChanges(changes.load().filter { !it.date.isBefore(today) }, all)
        hiddenCourses = filter.timetableHiddenCount
    }

    private fun refresh(force: Boolean) {
        if (loading) return
        val token = EtlService.tokens(this).load()
        if (token.isNullOrEmpty()) {
            return
        }
        loading = true
        Thread {
            val msg = try {
                Timetable.weekly(EtlClient(token), store, force = force)
                null
            } catch (e: EtlAuthException) {
                "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요"
            } catch (e: EtlException) {
                "시간표를 가져오지 못했어요: ${e.message}"
            } catch (e: Exception) {
                "시간표를 가져오지 못했어요(${e.javaClass.simpleName})"
            }
            runOnUiThread {
                loading = false
                reload()
                msg?.let(::toast)
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimetableScreen(
    weekly: TimetableLogic.Weekly?,
    changes: List<TimetableLogic.Change>,
    loading: Boolean,
    hiddenCourses: Int,
    onHiddenCourses: () -> Unit,
    onBack: () -> Unit,
    onSaveCustom: (TimetableLogic.CourseMeetings) -> Unit,
    onDeleteCustom: (Long) -> Unit,
    onAdd: (TimetableLogic.Change) -> Unit,
    onDelete: (TimetableLogic.Change) -> Unit,
    pickDate: (LocalDate, (LocalDate) -> Unit) -> Unit,
    pickTime: (LocalTime, (LocalTime) -> Unit) -> Unit,
    draft: kr.snu.etlassistant.notify.ScheduleDraft? = null,
    onDraftDone: () -> Unit = {},
    onAskAssistant: (kr.snu.etlassistant.notify.ScheduleDraft) -> Unit = {},
) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        var adding by remember { mutableStateOf(false) }
        var editingCustom by rememberSaveable { mutableStateOf(false) }
        var editingCustomId by rememberSaveable { mutableStateOf<Long?>(null) }
        Column(Modifier.fillMaxSize().background(c.backgroundNeutralPrimary).systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = c.foregroundNeutralPrimary) }
                Text("시간표", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                if (loading) WantedCircularProgressIndicator()
                IconButton(onClick = { editingCustomId = null; editingCustom = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "수업 직접 추가", tint = c.foregroundBrandPrimary)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                Spacer(Modifier.height(8.dp))
                // 과목별 알림에서 시간표에서도 숨긴 과목이 있으면 알려 준다. 누르면 과목별 알림 화면으로 간다.
                if (hiddenCourses > 0) {
                    Row(Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                        WantedChip(text = "숨긴 과목 ${hiddenCourses}개", onClick = onHiddenCourses)
                    }
                }
                val w = weekly
                if (w == null) {
                    Text(if (loading) "가져오는 중…" else "오른쪽 위 + 버튼으로 수업을 추가해 보세요. eTL을 연결하면 수강 중인 수업도 자동으로 가져와요.",
                        style = t.body2Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = 16.dp))
                } else {
                    if (TimetableGridLogic.build(w.courses).blocks.isEmpty()) {
                        Text("수업 시간이 없어요. 오른쪽 위 + 버튼으로 직접 추가할 수 있어요.", style = t.body2Regular, color = c.foregroundNeutralSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    } else WeeklyTimetable(w, onEditCustom = { editingCustomId = it.courseId; editingCustom = true })
                    if (w.errors.isNotEmpty()) {
                        Text("일부 정보를 확인하지 못했어요\n" + w.errors.joinToString("\n") { "· $it" },
                            style = t.caption1Regular, color = c.foregroundNeutralSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("보강 · 휴강", style = t.headline1Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, contentDescription = "보강·휴강 추가", tint = c.foregroundBrandPrimary) }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (changes.isEmpty()) Text("예정된 보강·휴강이 없어요", style = t.body2Regular, color = c.foregroundNeutralSecondary)
                    for (ch in changes) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceNeutralSecondary).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                val wd = TimetableLogic.weekdayOf(ch.date)
                                Text("${ch.date.monthValue}/${ch.date.dayOfMonth}($wd) ${ch.kind} · ${ch.course}", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                                val detail = listOfNotNull(
                                    if (ch.start != null) "${ch.start}" + (ch.end?.let { "~$it" } ?: "") else null,
                                    ch.room, ch.note,
                                ).joinToString(" · ")
                                if (detail.isNotEmpty()) Text(detail, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                            }
                            IconButton(onClick = { onDelete(ch) }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "지우기", tint = c.foregroundNeutralSecondary, modifier = Modifier.size(20.dp)) }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
        if (adding || draft != null) {
            ChangeDialog(
                courses = weekly?.courses.orEmpty(),
                onDismiss = { adding = false; onDraftDone() },
                onSave = { onAdd(it); adding = false; onDraftDone() },
                pickDate = pickDate,
                pickTime = pickTime,
                draft = draft,
                onAskAssistant = { adding = false; onAskAssistant(it) },
            )
        }
        if (editingCustom) {
            CustomCourseDialog(
                course = weekly?.courses?.firstOrNull { it.courseId == editingCustomId && it.courseId < 0 },
                onDismiss = { editingCustom = false },
                onSave = { onSaveCustom(it); editingCustom = false },
                onDelete = { onDeleteCustom(it); editingCustom = false },
                pickTime = pickTime,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangeDialog(
    courses: List<TimetableLogic.CourseMeetings>,
    onDismiss: () -> Unit,
    onSave: (TimetableLogic.Change) -> Unit,
    pickDate: (LocalDate, (LocalDate) -> Unit) -> Unit,
    pickTime: (LocalTime, (LocalTime) -> Unit) -> Unit,
    /** 보강·휴강 공지에서 온 초안(날짜·시각은 공지에서 하나만 찾았을 때만). */
    draft: kr.snu.etlassistant.notify.ScheduleDraft? = null,
    onAskAssistant: (kr.snu.etlassistant.notify.ScheduleDraft) -> Unit = {},
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val pre = remember(draft) { draft?.let { TimetableDraftLogic.prefill(it, courses) } }
    var kind by remember { mutableStateOf(pre?.kind ?: "휴강") }
    var course by remember { mutableStateOf(pre?.course) }
    var courseText by remember { mutableStateOf(if (pre?.course == null) pre?.courseText.orEmpty() else "") }
    var date by remember { mutableStateOf(pre?.date ?: LocalDate.now(EtlLogic.KST)) }
    var start by remember { mutableStateOf(pre?.start) }
    var end by remember { mutableStateOf(pre?.end) }
    var room by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(pre?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val hm = { x: LocalTime? -> x?.let { "%02d:%02d".format(it.hour, it.minute) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.backgroundNeutralPrimary,
        title = { Text("보강·휴강 추가", style = t.title3Bold, color = c.foregroundNeutralPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (draft != null) {
                    if (!draft.foundDate) {
                        Text(kr.snu.etlassistant.notify.ScheduleDrafts.ASK_HINT, style = t.body2Medium, color = c.foregroundCautionaryPrimary)
                        Spacer(Modifier.height(4.dp))
                    } else Text(kr.snu.etlassistant.notify.ScheduleDrafts.FOUND_HINT, style = t.caption1Regular, color = c.foregroundBrandPrimary)
                    if (draft.prefill.isNotEmpty()) {
                        DialogButton(kr.snu.etlassistant.notify.ScheduleDrafts.ASK_LINK) { onAskAssistant(draft) }
                        Text("공지 내용은 이걸 누를 때만 비서(내 ChatGPT 계정)로 가요.", style = t.caption2Regular, color = c.foregroundNeutralSecondary)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in listOf("휴강", "보강")) WantedChip(text = k, isActive = kind == k, onClick = { kind = k; error = null })
                }
                Spacer(Modifier.height(12.dp))
                Text("과목", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                Spacer(Modifier.height(6.dp))
                if (courses.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (co in courses) {
                            WantedChip(text = TimetableLogic.shortName(co.name), isActive = course?.courseId == co.courseId, onClick = {
                                course = co; courseText = ""; error = null
                                if (start == null) co.meetings.firstOrNull { it.day == TimetableLogic.weekdayOf(date) }?.let { m ->
                                    if (kind == "보강") { start = LocalTime.parse(m.start); end = LocalTime.parse(m.end) }
                                }
                            })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                WantedTextField(
                    text = courseText,
                    placeholder = if (courses.isEmpty()) "과목명" else "목록에 없으면 직접 입력",
                    background = c.backgroundNeutralPrimary,
                    onValueChange = { courseText = it.take(60); if (it.isNotBlank()) course = null; error = null },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WantedButton(
                        text = "${date.monthValue}/${date.dayOfMonth}(${TimetableLogic.weekdayOf(date)})",
                        type = ButtonType.ASSISTIVE, size = ButtonSize.MEDIUM, variant = ButtonVariant.OUTLINED,
                        onClick = { pickDate(date) { date = it; error = null } },
                    )
                    if (kind == "보강") {
                        WantedButton(text = hm(start) ?: "시작", type = ButtonType.ASSISTIVE, size = ButtonSize.MEDIUM, variant = ButtonVariant.OUTLINED,
                            onClick = { pickTime(start ?: LocalTime.of(9, 0)) { start = it; error = null } })
                        Text("~", color = c.foregroundNeutralSecondary)
                        WantedButton(text = hm(end) ?: "끝", type = ButtonType.ASSISTIVE, size = ButtonSize.MEDIUM, variant = ButtonVariant.OUTLINED,
                            onClick = { pickTime(end ?: (start?.plusMinutes(75) ?: LocalTime.of(10, 15))) { end = it; error = null } })
                    }
                }
                if (kind == "휴강") {
                    Text("그날 그 과목의 수업이 모두 빠져요.", style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                }
                if (kind == "보강") {
                    Spacer(Modifier.height(12.dp))
                    WantedTextField(text = room, placeholder = "강의실 (선택)", background = c.backgroundNeutralPrimary,
                        onValueChange = { room = it.take(40) }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                WantedTextField(text = note, placeholder = "메모 (선택)", background = c.backgroundNeutralPrimary,
                    onValueChange = { note = it.take(100) }, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = t.caption1Regular, color = c.foregroundNegativePrimary)
                }
            }
        },
        confirmButton = {
            WantedTextButton(text = "저장", onClick = {
                val name = course?.let { TimetableLogic.shortName(it.name) } ?: courseText.trim()
                val err = TimetableLogic.validateChange(kind, name, if (kind == "보강") hm(start) else null, if (kind == "보강") hm(end) else null)
                if (err != null) { error = err; return@WantedTextButton }
                onSave(
                    TimetableLogic.Change(
                        id = System.currentTimeMillis(), date = date, kind = kind, course = name, courseId = course?.courseId,
                        start = if (kind == "보강") hm(start) else null, end = if (kind == "보강") hm(end) else null,
                        room = room.trim().ifEmpty { null }, note = note.trim().ifEmpty { null },
                        sourceAnnouncementId = draft?.announcementId,
                    )
                )
            })
        },
        dismissButton = { WantedTextButton(text = "취소", onClick = onDismiss) },
    )
}
