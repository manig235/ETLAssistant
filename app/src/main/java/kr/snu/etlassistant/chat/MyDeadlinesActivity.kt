package kr.snu.etlassistant.chat

import android.app.DatePickerDialog
import android.content.Intent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.CalendarViewWeek
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import kr.snu.etlassistant.notify.LearningXOpenActivity
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.notify.Agenda
import kr.snu.etlassistant.notify.DeadlineAlarm
import kr.snu.etlassistant.notify.NotifyStateStore
import kr.snu.etlassistant.notify.CachedSnapshot
import kr.snu.etlassistant.notify.CourseFilter
import kr.snu.etlassistant.notify.CourseFilterStore
import kr.snu.etlassistant.notify.SnapshotCache
import kr.snu.etlassistant.notify.SnapshotFetcher
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Event
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.role
import kr.snu.etlassistant.notify.MyKind
import kr.snu.etlassistant.notify.DeadlineForm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.input.textinput.textfield.WantedTextField
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.notify.MyDeadline
import kr.snu.etlassistant.notify.MyDeadlineStore
import kr.snu.etlassistant.notify.MyDeadlines
import kr.snu.etlassistant.notify.MyRepeat
import kr.snu.etlassistant.notify.NotifyScheduler
import kr.snu.etlassistant.notify.NotifySettingsStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * "내 일정": 임박 요약 + 오늘 포함 7일 전체 마감 + 이후 마감. 수업은 시간표에서 보여 준다.
 * eTL 과제는 알림 확인 때 저장해 둔 결과([SnapshotCache])를 바로 보여 주고, 없거나 오래됐으면 뒤에서 새로 받는다.
 * 내 마감(eTL 밖의 마감)은 여기서 추가·수정·완료·삭제하며, eTL 과제처럼 마감 알림과 브리핑에 들어간다. 저장은 폰 안에만.
 */
class MyDeadlinesActivity : AppCompatActivity() {
    private val store by lazy { MyDeadlineStore(this) }
    private var mine by mutableStateOf<List<MyDeadline>>(emptyList())
    private var cached by mutableStateOf<CachedSnapshot?>(null)
    private var now by mutableStateOf(Instant.now())
    private var loading by mutableStateOf(false)
    private var filter by mutableStateOf(Agenda.Filter.ALL)
    private var hideDone by mutableStateOf(true)
    private var courseFilter by mutableStateOf(CourseFilter())
    private var submitCheck by mutableStateOf<SubmissionCheckUi?>(null)
    /** 고른 캘린더의 일정(내 캘린더 일정 보기가 켜져 있을 때만). */
    private var calEvents by mutableStateOf<List<kr.snu.etlassistant.calendar.CalEvent>>(emptyList())
    private var calendarOn by mutableStateOf(false)
    private val calendarListener: () -> Unit = { runOnUiThread { reload(); loadCalendar() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readDraft(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = Instant.now()
                    delay(30_000)
                }
            }
        }
        setContent {
            AgendaScreen(
                schedule = Agenda.build(courseFilter.visibleAssignments(cached?.assignments.orEmpty()), mine, now, ZoneId.systemDefault(), filter, hideDone,
                    calendar = if (calendarOn) calEvents else emptyList()),
                calendarOn = calendarOn,
                courses = remember(cached) { cached?.assignments.orEmpty().map { kr.snu.etlassistant.etl.TimetableLogic.shortName(it.courseName) }.filter { it.isNotBlank() }.distinct().sorted() },
                onOpenEvent = { e -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, kr.snu.etlassistant.calendar.CalendarReader.eventUri(e.eventId))) }.onFailure { toast("캘린더 앱을 열 수 없어요") } },
                now = now,
                takenAt = cached?.takenAt,
                hasToken = remember { !EtlService.tokens(this).load().isNullOrEmpty() },
                loading = loading,
                filter = filter,
                hideDone = hideDone,
                hiddenCourses = courseFilter.hiddenCount,
                onHiddenCourses = { startActivity(Intent(this, CourseAlertsActivity::class.java)) },
                alertsOn = remember { NotifySettingsStore(this).load().deadlineAlerts },
                mineTotal = mine.size,
                initialDeadline = draftExisting ?: remember { kr.snu.etlassistant.notify.MyDeadlines.findByItemId(store.load(), intent.getLongExtra(kr.snu.etlassistant.widget.HomeWidgets.EXTRA_DEADLINE_ID, Long.MIN_VALUE)) },
                draft = draftState,
                onDraftDone = { draftState = null; draftExisting = null },
                // "✨ 비서가 공지 읽고 채우기": 창을 닫고 채팅 입력창에 요청을 채운다(보내지는 않는다). 공지 내용은 이때만 비서(OpenAI)로 간다.
                onAskAssistant = { d ->
                    draftState = null
                    runCatching {
                        startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PREFILL, d.prefill)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                },
                onFilter = { filter = it },
                onHideDone = { hideDone = it },
                onBack = { finish() },
                onTimetable = { startActivity(Intent(this, TimetableActivity::class.java)) },
                onRefresh = ::refresh,
                onOpen = { url -> runCatching { startActivity(LearningXOpenActivity.intent(this, url)) } },
                onSave = { old, new ->
                    // 마감 시각을 바꾸면 새 ID로 저장한다: 예전 시각 기준으로 이미 보낸 알림 기록이 새 알림을 막지 않게.
                    if (old != null && old.id != new.id) store.delete(old.id)
                    save(new)
                },
                // 동영상 강의 공지: "그날 대면 수업은 휴강으로 표시"를 체크하면 같은 저장에서 시간표 휴강도 넣는다.
                onCancelClass = { day, courseName, annId ->
                    val course = kr.snu.etlassistant.etl.TimetableStore(this).load()?.courses?.firstOrNull { kr.snu.etlassistant.etl.TimetableLogic.sameCourse(courseName, it.name) }
                    kr.snu.etlassistant.etl.TimetableChangeStore(this).add(kr.snu.etlassistant.etl.TimetableLogic.Change(
                        id = System.currentTimeMillis() + 1, date = day, kind = "휴강",
                        course = course?.let { kr.snu.etlassistant.etl.TimetableLogic.shortName(it.name) } ?: courseName, courseId = course?.courseId,
                        note = "동영상 강의로 대체", sourceAnnouncementId = annId))
                    runCatching { NotifyScheduler.apply(this) }
                    toast("시간표에 휴강도 넣었어요")
                },
                // 반복 마감의 회차는 그 회차만 완료로 표시한다.
                onToggleDone = { d -> seriesOf(d)?.let { save(MyDeadlines.setDone(it, d.occurrence, !d.done, Instant.now())) } },
                onDelete = { d, onlyThis ->
                    val series = seriesOf(d)
                    if (onlyThis && series != null && d.occurrence != null) save(MyDeadlines.skip(series, d.occurrence, Instant.now()))
                    else { store.delete(d.id); reload() }
                    toast("지웠어요")
                },
                seriesOf = ::seriesOf,
                onAddToCalendar = ::addToCalendar,
                pickDate = ::pickDate,
                pickTime = ::pickTime,
                onCheckSubmission = ::checkSubmission,
            )
            submitCheck?.let { st ->
                SubmissionCheckDialog(st, onDismiss = { submitCheck = null }, onOpen = { url ->
                    if (url.startsWith("https://myetl.snu.ac.kr/")) runCatching { startActivity(LearningXOpenActivity.intent(this, url)) }
                })
            }
        }
    }

    /** "제출 확인": 과제와 내 제출을 지금 받아(GET 두 번) 판정한다. 파일은 받지 않는다. */
    private fun checkSubmission(a: kr.snu.etlassistant.notify.AssignmentItem) {
        val token = EtlService.tokens(this).load()
        if (token.isNullOrEmpty()) return toast("eTL 토큰을 먼저 저장하세요")
        val title = a.name
        submitCheck = SubmissionCheckUi.Loading(title)
        Thread {
            val result = try {
                SubmissionCheckUi.Done(title, kr.snu.etlassistant.etl.SubmissionCheck.live(EtlClient(token), a.courseId, a.assignmentId, Instant.now()))
            } catch (e: EtlAuthException) {
                SubmissionCheckUi.Failed(title, "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요")
            } catch (e: EtlException) {
                SubmissionCheckUi.Failed(title, e.message ?: "eTL 요청 실패")
            } catch (e: Exception) {
                SubmissionCheckUi.Failed(title, "확인하지 못했어요(${e.javaClass.simpleName})")
            }
            // 그사이 닫았으면 다시 띄우지 않는다.
            runOnUiThread { if (submitCheck == SubmissionCheckUi.Loading(title)) submitCheck = result }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        reload()
        // 캘린더에서 고친 내 마감을 가져오고(켜져 있을 때), 고른 캘린더의 일정을 다시 읽는다. 맞춘 뒤 바뀌면 listener가 다시 읽는다.
        kr.snu.etlassistant.calendar.CalendarExport.listeners += calendarListener
        kr.snu.etlassistant.calendar.CalendarExport.requestSync(this)
        loadCalendar()
        // 저장된 결과를 바로 보여 주고, 없거나 오래됐으면(3시간) 뒤에서 새로 받는다.
        val age = cached?.let { System.currentTimeMillis() - it.takenAt }
        if (age == null || age > STALE_MS) refresh()
    }

    override fun onStop() {
        kr.snu.etlassistant.calendar.CalendarExport.listeners -= calendarListener
        super.onStop()
    }

    /** 고른 캘린더의 일정(오늘~30일)을 뒤에서 읽는다. 네트워크 없음. */
    private fun loadCalendar() {
        val on = kr.snu.etlassistant.calendar.CalendarReader.settings(this).showEnabled
        calendarOn = on
        if (!on) { calEvents = emptyList(); if (filter == Agenda.Filter.CALENDAR) filter = Agenda.Filter.ALL; return }
        Thread {
            val from = java.time.LocalDate.now(kr.snu.etlassistant.calendar.CalendarLogic.ZONE).atStartOfDay(kr.snu.etlassistant.calendar.CalendarLogic.ZONE).toInstant()
            val list = runCatching { kr.snu.etlassistant.calendar.CalendarReader.shownEvents(this, from, from.plus(java.time.Duration.ofDays(30))) }.getOrDefault(emptyList())
            runOnUiThread { calEvents = list }
        }.start()
    }

    /** 화면의 행(반복 마감이면 회차)에 해당하는 저장된 원래 항목. */
    private fun seriesOf(d: MyDeadline): MyDeadline? = store.load().firstOrNull { it.id == d.id }

    private fun reload() {
        mine = store.load()
        cached = SnapshotCache(this).load()
        courseFilter = CourseFilterStore(this).load()
    }

    /** eTL 과제를 새로 받아 저장한다(알림 확인과 같은 캐시). */
    private fun refresh() {
        if (loading) return
        val token = EtlService.tokens(this).load()
        if (token.isNullOrEmpty()) return
        loading = true
        Thread {
            val msg = try {
                val etl = EtlClient(token)
                SnapshotCache(this).save(SnapshotFetcher.fetch(etl, Instant.now(), announcementDays = 1))
                null
            } catch (e: EtlAuthException) {
                "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요"
            } catch (e: EtlException) {
                "eTL 과제를 새로 받지 못했어요: ${e.message}"
            } catch (e: Exception) {
                "eTL 과제를 새로 받지 못했어요(${e.javaClass.simpleName})"
            }
            runOnUiThread {
                loading = false
                reload()
                msg?.let(::toast)
            }
        }.start()
    }

    private fun save(d: MyDeadline) {
        store.upsert(d)
        reload()
        // 새 마감이 곧 알림 시점에 들어가면 다음 주기 확인까지 기다리지 않도록 지금 한 번 확인한다(제시간 알람도 다시 잡힌다).
        runCatching {
            NotifyScheduler.apply(this)
            // 직접 정한 알림 시각은 네트워크 없이도 울리도록 알람을 바로 건다.
            DeadlineAlarm.scheduleEarlier(this, MyDeadlines.nextReminderAt(store.load(), Instant.now(), NotifyStateStore(this).load().fired.keys))
            if (NotifySettingsStore(this).load().deadlineAlerts) NotifyScheduler.checkNow(this)
        }
    }

    /** 캘린더 앱의 일정 추가 화면을 연다(권한 없음, 사용자가 저장). 반복 내 마감은 반복 규칙을 함께 넘긴다. */
    private fun addToCalendar(r: Agenda.Row) {
        val intent = when (r.kind) {
            Agenda.Kind.MINE, Agenda.Kind.EXAM -> r.mine?.let { occ -> seriesOf(occ)?.let { kr.snu.etlassistant.calendar.CalendarExport.insertIntentFor(it, occ.takeIf { occ.occurrence != null }) } }
            Agenda.Kind.ASSIGNMENT -> cached?.assignments?.firstOrNull { "assignment-${it.courseId}-${it.assignmentId}" == r.id }
                ?.let { kr.snu.etlassistant.calendar.CalendarExport.insertIntentFor(it) }
            Agenda.Kind.CALENDAR -> null
        }
        if (intent == null) toast("캘린더에 넣을 수 없어요")
        else if (runCatching { startActivity(intent) }.isFailure) toast("일정을 넣을 캘린더 앱이 없어요")
    }

    private fun pickDate(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        DatePickerDialog(this, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    private fun pickTime(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPicked(LocalTime.of(h, m)) }, initial.hour, initial.minute, DateFormat.is24HourFormat(this)).show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STALE_MS = 3 * 3600 * 1000L
        /** 공지 알림·알림 기록의 "내 일정에 등록": [kr.snu.etlassistant.notify.ScheduleDraft] JSON. */
        const val EXTRA_DRAFT = DraftIntents.EXTRA_DRAFT
    }

    /** 알림에서 온 초안. 같은 공지로 이미 넣은 항목이 있으면 그 항목을 고치기로 연다. */
    private var draftState by mutableStateOf<kr.snu.etlassistant.notify.ScheduleDraft?>(null)
    private var draftExisting by mutableStateOf<MyDeadline?>(null)

    private fun readDraft(i: Intent?) {
        val d = kr.snu.etlassistant.notify.ScheduleDraft.fromJson(i?.getStringExtra(EXTRA_DRAFT)) ?: return
        if (d.timetable) { runCatching { startActivity(DraftIntents.open(this, d.toJson())) }; return }
        val existing = store.load().firstOrNull { it.sourceAnnouncementId == d.announcementId }
        if (existing != null) { draftExisting = existing; draftState = null; toast("이 공지로 이미 넣은 일정이에요") }
        else { draftState = d; draftExisting = null }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDraft(intent)
    }
}

private const val WEEKDAYS = "월화수목금토일"

/** 기기 시간대 기준 `11/23(월) 15:00`. */
internal fun myDeadlineLabel(t: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = t.atZone(zone)
    return "%02d/%02d(%s) %02d:%02d".format(d.monthValue, d.dayOfMonth, WEEKDAYS[d.dayOfWeek.value - 1], d.hour, d.minute)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgendaScreen(
    schedule: Agenda.Schedule,
    now: Instant,
    takenAt: Long?,
    hasToken: Boolean,
    loading: Boolean,
    filter: Agenda.Filter,
    hideDone: Boolean,
    hiddenCourses: Int,
    onHiddenCourses: () -> Unit,
    alertsOn: Boolean,
    mineTotal: Int,
    initialDeadline: MyDeadline?,
    onFilter: (Agenda.Filter) -> Unit,
    onHideDone: (Boolean) -> Unit,
    onBack: () -> Unit,
    onTimetable: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onSave: (old: MyDeadline?, new: MyDeadline) -> Unit,
    onToggleDone: (MyDeadline) -> Unit,
    onDelete: (MyDeadline, onlyThis: Boolean) -> Unit,
    seriesOf: (MyDeadline) -> MyDeadline?,
    onAddToCalendar: (Agenda.Row) -> Unit,
    pickDate: (LocalDate, (LocalDate) -> Unit) -> Unit,
    pickTime: (LocalTime, (LocalTime) -> Unit) -> Unit,
    onCheckSubmission: (kr.snu.etlassistant.notify.AssignmentItem) -> Unit = {},
    calendarOn: Boolean = false,
    courses: List<String> = emptyList(),
    onOpenEvent: (kr.snu.etlassistant.calendar.CalEvent) -> Unit = {},
    draft: kr.snu.etlassistant.notify.ScheduleDraft? = null,
    onDraftDone: () -> Unit = {},
    onAskAssistant: (kr.snu.etlassistant.notify.ScheduleDraft) -> Unit = {},
    onCancelClass: (LocalDate, String, Long) -> Unit = { _, _, _ -> },
) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        var editing by remember(initialDeadline) { mutableStateOf(initialDeadline) }
        var adding by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<MyDeadline?>(null) }
        var showOverdue by rememberSaveable { mutableStateOf(false) }
        fun openRow(r: Agenda.Row) {
            when {
                // 반복 마감은 회차가 아니라 반복 전체를 고친다.
                r.mine != null -> editing = seriesOf(r.mine) ?: r.mine
                r.calendar != null -> onOpenEvent(r.calendar)
                r.url != null -> onOpen(r.url)
            }
        }

        Column(Modifier.fillMaxSize().background(c.backgroundNeutralPrimary).systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = c.foregroundNeutralPrimary) }
                Text("내 일정", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                IconButton(onClick = onTimetable) { Icon(Icons.Outlined.CalendarViewWeek, contentDescription = "수업 시간표", tint = c.foregroundNeutralSecondary) }
                if (loading) WantedCircularProgressIndicator()
                else if (hasToken) IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "eTL 과제 새로 받기", tint = c.foregroundNeutralSecondary) }
                IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, contentDescription = "내 마감·시험 추가", tint = c.foregroundBrandPrimary) }
            }
            val status = when {
                !hasToken -> "eTL 토큰을 저장하면 eTL 과제 마감도 여기에 모여요."
                takenAt == null -> if (loading) "eTL 과제를 받는 중…" else "아직 eTL 과제를 받지 않았어요."
                else -> "eTL 과제 마지막 확인 ${myDeadlineLabel(Instant.ofEpochMilli(takenAt))}"
            } + if (alertsOn) "" else " · 마감 알림 꺼짐(설정에서 켤 수 있어요)"
            Text(status, style = t.caption1Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in Agenda.Filter.values()) if (f != Agenda.Filter.CALENDAR || calendarOn) WantedChip(text = f.label, isActive = filter == f, onClick = { onFilter(f) })
                WantedChip(text = if (hideDone) "제출·완료 숨김" else "제출·완료 보기", isActive = !hideDone, onClick = { onHideDone(!hideDone) })
                // 과목별 알림에서 숨긴 과목의 과제는 여기 보이지 않는다. 누르면 과목별 알림 화면으로 간다.
                if (hiddenCourses > 0 && (filter == Agenda.Filter.ALL || filter == Agenda.Filter.ASSIGNMENT)) WantedChip(text = "숨긴 과목 ${hiddenCourses}개", onClick = onHiddenCourses)
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "urgent-summary") {
                    DeadlineSummary(schedule, now, showOverdue, onToggleOverdue = { showOverdue = !showOverdue }, onOpen = ::openRow)
                }
                if (showOverdue && schedule.overdue.isNotEmpty()) {
                    item(key = "overdue-header") {
                        Text("마감 지남 · ${schedule.overdue.size}개", style = t.body2Medium, color = c.foregroundNegativePrimary,
                            modifier = Modifier.padding(start = 8.dp, top = 8.dp))
                    }
                    items(schedule.overdue, key = { "overdue-${it.id}" }) { r ->
                        AgendaRow(r, now, onClick = { openRow(r) }, onToggleDone = { r.mine?.let(onToggleDone) }, onDelete = { deleting = r.mine },
                            onCheck = r.assignment?.takeIf { it.submitted }?.let { a -> { onCheckSubmission(a) } }, onAddToCalendar = { onAddToCalendar(r) })
                    }
                }
                for ((isUpcoming, groups) in listOf(true to schedule.upcoming, false to schedule.later)) {
                    val section = if (isUpcoming) "upcoming" else "later"
                    item(key = "$section-header") {
                        DeadlineSectionHeader(
                            if (isUpcoming) "앞으로 7일" else "그 이후 마감",
                            if (isUpcoming) schedule.upcomingCount else schedule.laterCount,
                            if (isUpcoming) "오늘 포함 · ${Agenda.dateLabel(schedule.today)}~${Agenda.dateLabel(schedule.lastDay)}"
                            else "${Agenda.dateLabel(schedule.lastDay.plusDays(1))}부터",
                        )
                    }
                    if (groups.isEmpty()) {
                        item(key = "$section-empty") {
                            val period = if (isUpcoming) "앞으로 7일 동안" else "그 이후에"
                            DeadlineEmptyText("$period ${if (hideDone) "남은 " else ""}" + when (filter) {
                                Agenda.Filter.EXAM -> "시험이 없어요"
                                Agenda.Filter.CALENDAR -> "캘린더 일정이 없어요"
                                else -> "일정이 없어요"
                            })
                        }
                    }
                    for (g in groups) {
                        item(key = "date-${g.date}") {
                            Text(Agenda.dayLabel(g.date, schedule.today), style = t.body2Medium, color = c.foregroundBrandPrimary,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp))
                        }
                        items(g.rows, key = { it.id }) { r ->
                            AgendaRow(r, now, onClick = { openRow(r) }, onToggleDone = { r.mine?.let(onToggleDone) }, onDelete = { deleting = r.mine },
                            onCheck = r.assignment?.takeIf { it.submitted }?.let { a -> { onCheckSubmission(a) } }, onAddToCalendar = { onAddToCalendar(r) })
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        if (adding || editing != null || draft != null) {
            DeadlineDialog(
                initial = editing,
                countWithoutThis = if (editing != null) mineTotal - 1 else mineTotal,
                onDismiss = { adding = false; editing = null; onDraftDone() },
                onSave = { new -> onSave(editing, new); adding = false; editing = null; onDraftDone() },
                draft = if (editing == null) draft else null,
                onAskAssistant = { d -> adding = false; onAskAssistant(d) },
                onCancelClass = onCancelClass,
                pickDate = pickDate,
                pickTime = pickTime,
                courses = courses,
                startAsExam = filter == Agenda.Filter.EXAM,
            )
        }
        deleting?.let { d ->
            val occurrence = d.occurrence.takeIf { d.repeat != null }
            AlertDialog(
                onDismissRequest = { deleting = null },
                containerColor = c.backgroundNeutralPrimary,
                title = { Text(when { d.isExam -> "이 시험을 지울까요?"; occurrence != null -> "반복 마감을 지울까요?"; else -> "이 마감을 지울까요?" }, style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
                text = {
                    Text(
                        if (occurrence != null) "\"${d.title}\"은(는) ${MyDeadlines.repeatLabel(d)} 반복돼요. ${Agenda.dateLabel(occurrence)} 회차만 지울지, 반복 전체를 지울지 골라 주세요."
                        else "\"${d.title}\"이(가) 목록과 알림에서 사라져요.",
                        style = t.body2Regular, color = c.foregroundNeutralSecondary,
                    )
                },
                // 반복 회차는 버튼 셋(이번 회차만·반복 전체·취소)을 세로로 둔다. 가로로 두면 "이번만전체"처럼 붙어 보였다(S22 보고).
                confirmButton = {
                    if (occurrence != null) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DialogButton("이번 회차만 지우기") { onDelete(d, true); deleting = null }
                        DialogButton("반복 전체 지우기") { onDelete(d, false); deleting = null }
                        DialogButton("취소") { deleting = null }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogButton("취소") { deleting = null }
                        DialogButton("삭제") { onDelete(d, false); deleting = null }
                    }
                },
            )
        }
    }
}

@Composable
private fun DeadlineSummary(
    schedule: Agenda.Schedule,
    now: Instant,
    showOverdue: Boolean,
    onToggleOverdue: () -> Unit,
    onOpen: (Agenda.Row) -> Unit,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (schedule.urgent.isEmpty()) c.surfaceNeutralSecondary else c.surfaceCautionaryPrimary)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("임박한 마감 요약", style = t.body1Medium, color = c.foregroundNeutralPrimary)
        Text(
            if (schedule.urgent.isEmpty()) "24시간 내 남은 마감이 없어요" else "24시간 내 미완료 마감 ${schedule.urgent.size}개",
            style = t.caption1Regular, color = c.foregroundNeutralSecondary,
        )
        for (r in schedule.urgent.take(3)) {
            Column(
                Modifier.fillMaxWidth()
                    .then(if (r.mine != null || r.url != null) Modifier.clickable(role = Role.Button, onClickLabel = if (r.mine != null) "고치기" else "eTL에서 열기") { onOpen(r) } else Modifier)
                    .padding(vertical = 6.dp),
            ) {
                Text(r.title, style = t.body2Medium, color = c.foregroundNeutralPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.subtitle} · ${Agenda.whenLabel(r, now)}", style = t.caption1Regular,
                    color = c.foregroundNeutralSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (schedule.urgent.size > 3) {
            val extra = schedule.urgent.size - 3
            Text("외 ${extra}개",
                style = t.caption1Regular, color = c.foregroundNeutralSecondary)
        }
        if (schedule.overdue.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("마감 지남 ${schedule.overdue.size}개", style = t.body2Medium, color = c.foregroundNegativePrimary)
                WantedTextButton(text = if (showOverdue) "접기" else "펼치기", onClick = onToggleOverdue)
            }
        }
    }
}

@Composable
private fun DeadlineSectionHeader(title: String, count: Int, subtitle: String) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Column(Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$title · ${count}개", style = t.headline2Bold, color = c.foregroundNeutralPrimary)
        Text(subtitle, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
    }
}

@Composable
private fun DeadlineEmptyText(text: String) {
    Text(text, style = DesignSystemTheme.typography.body2Regular, color = DesignSystemTheme.colors.foregroundNeutralSecondary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp))
}

@Composable
private fun AgendaRow(r: Agenda.Row, now: Instant, onClick: () -> Unit, onToggleDone: () -> Unit, onDelete: () -> Unit, onCheck: (() -> Unit)? = null, onAddToCalendar: () -> Unit = {}) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val clickable = r.mine != null || r.url != null || r.calendar != null
    val label = when { r.mine != null -> "고치기"; r.calendar != null -> "캘린더에서 열기"; else -> "eTL에서 열기" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceNeutralSecondary)
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (r.kind) {
            Agenda.Kind.MINE -> IconButton(onClick = onToggleDone, modifier = Modifier.semantics { contentDescription = if (r.done) "'${r.title}' 완료 취소" else "'${r.title}' 완료" }) {
                Icon(if (r.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
                    tint = if (r.done) c.foregroundPositivePrimary else c.foregroundNeutralSecondary)
            }
            Agenda.Kind.ASSIGNMENT -> Icon(if (r.done) Icons.Outlined.CheckCircle else Icons.Outlined.Assignment, contentDescription = null,
                tint = if (r.done) c.foregroundPositivePrimary else c.foregroundBrandPrimary, modifier = Modifier.padding(12.dp).size(24.dp))
            Agenda.Kind.EXAM -> Icon(Icons.Outlined.EditNote, contentDescription = "시험",
                tint = c.foregroundCautionaryPrimary, modifier = Modifier.padding(12.dp).size(24.dp))
            Agenda.Kind.CALENDAR -> Icon(Icons.Outlined.Event, contentDescription = "캘린더 일정",
                tint = r.calendar?.color?.let { androidx.compose.ui.graphics.Color(it or 0xFF000000.toInt()) } ?: c.foregroundNeutralSecondary,
                modifier = Modifier.padding(12.dp).size(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.kind == Agenda.Kind.EXAM) {
                    Text("시험", style = t.caption1Bold, color = c.foregroundCautionaryPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.surfaceCautionaryPrimary).padding(horizontal = 6.dp, vertical = 1.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(r.title, style = t.body1Medium,
                    color = if (r.done) c.foregroundNeutralTertiary else c.foregroundNeutralPrimary,
                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            val whenText = Agenda.whenLabel(r, now)
            Text(listOf(whenText, r.subtitle).filter { it.isNotEmpty() }.joinToString(" · "), style = t.caption1Regular,
                color = if (r.overdue) c.foregroundNegativePrimary else c.foregroundNeutralSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (onCheck != null) {
            IconButton(onClick = onCheck, modifier = Modifier.semantics { contentDescription = "'${r.title}' 제출 확인" }) {
                Icon(Icons.AutoMirrored.Outlined.FactCheck, contentDescription = null,
                    tint = if (r.attention) c.foregroundNegativePrimary else c.foregroundNeutralSecondary, modifier = Modifier.size(20.dp))
            }
        }
        if (r.kind != Agenda.Kind.CALENDAR) {
            IconButton(onClick = onAddToCalendar, modifier = Modifier.semantics { contentDescription = "'${r.title}' 캘린더에 추가" }) {
                Icon(Icons.Outlined.EventAvailable, contentDescription = null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(20.dp))
            }
        }
        if (r.mine != null) {
            IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "'${r.title}' 지우기" }) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 대화상자 버튼. 접근성 트리에 글자가 빠지지 않게 버튼 이름을 함께 단다. */
@Composable
internal fun DialogButton(text: String, onClick: () -> Unit) {
    WantedTextButton(text = text, onClick = onClick,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text; role = Role.Button })
}

@Composable
private fun OutlineButton(text: String, description: String = text, onClick: () -> Unit) {
    WantedButton(
        text = text, type = ButtonType.ASSISTIVE, size = ButtonSize.MEDIUM, variant = ButtonVariant.OUTLINED, onClick = onClick,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button },
    )
}

/**
 * 내 마감·시험 추가/고치기. 종류(마감/시험)를 먼저 고르고, 마감은 반복 종류마다 필요한 입력만 보인다([DeadlineForm]):
 * 없음 = 날짜+시각, 매일 = 시각(+시작 날짜), 매주 = 요일+시각, 격주 = 요일+시각+이번 주/다음 주부터, 매월 = N일+시각.
 * 반복 끝은 끝 없음/날짜까지/횟수 중 하나. 시험은 날짜·시작·끝(선택)·장소·과목·준비물·메모이고 반복이 없다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeadlineDialog(
    initial: MyDeadline?,
    countWithoutThis: Int,
    onDismiss: () -> Unit,
    onSave: (MyDeadline) -> Unit,
    pickDate: (LocalDate, (LocalDate) -> Unit) -> Unit,
    pickTime: (LocalTime, (LocalTime) -> Unit) -> Unit,
    courses: List<String> = emptyList(),
    startAsExam: Boolean = false,
    /** 공지에서 온 초안(새로 넣을 때만). 날짜는 공지에서 하나만 찾았을 때만 들어 있다. */
    draft: kr.snu.etlassistant.notify.ScheduleDraft? = null,
    onAskAssistant: (kr.snu.etlassistant.notify.ScheduleDraft) -> Unit = {},
    onCancelClass: (LocalDate, String, Long) -> Unit = { _, _, _ -> },
) {
    val c = DesignSystemTheme.colors
    var cancelClass by remember { mutableStateOf(false) }
    val t = DesignSystemTheme.typography
    val zone = EtlLogic.KST
    val now = remember { Instant.now() }
    val start = initial?.due?.atZone(zone)?.toLocalDateTime()
        ?: draft?.start?.atZone(zone)?.toLocalDateTime()
        ?: LocalDate.now(zone).plusDays(1).let { if (draft?.kind == MyKind.EXAM) it.atTime(10, 0) else it.atTime(23, 59) } // 기본: 내일 23:59(시험은 10:00)
    var kind by remember { mutableStateOf(initial?.kind ?: draft?.kind ?: if (startAsExam) MyKind.EXAM else MyKind.DEADLINE) }
    var title by remember { mutableStateOf(initial?.title ?: draft?.title ?: "") }
    var memo by remember { mutableStateOf(initial?.memo ?: draft?.memo ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val r0 = initial?.repeat
    var form by remember {
        mutableStateOf(DeadlineForm.Input(
            freq = r0?.freq, date = start.toLocalDate(), time = start.toLocalTime().withSecond(0).withNano(0),
            weekdays = initial?.takeIf { r0 != null }?.let { MyDeadlines.weekdaysOf(it) }.orEmpty(),
            monthDay = start.dayOfMonth,
            biweeklyNext = if (r0?.freq == MyRepeat.Freq.BIWEEKLY) DeadlineForm.biweeklyNextWeek(start.toLocalDate(), now) else false,
            dailyStart = if (r0?.freq == MyRepeat.Freq.DAILY) start.toLocalDate() else null,
        ))
    }
    var endMode by remember { mutableStateOf(when { r0?.count != null -> DeadlineForm.EndMode.COUNT; r0?.until != null -> DeadlineForm.EndMode.UNTIL; else -> DeadlineForm.EndMode.NONE }) }
    var until by remember { mutableStateOf(r0?.until) }
    var count by remember { mutableStateOf(r0?.count ?: 10) }
    // 시험 입력
    var examEnd by remember { mutableStateOf((initial?.end ?: draft?.end)?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)) }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var course by remember { mutableStateOf(initial?.course ?: draft?.course ?: "") }
    var supplies by remember { mutableStateOf(initial?.supplies ?: draft?.supplies ?: "") }
    if (kind == MyKind.EXAM && initial?.isExam != true && form.time == LocalTime.of(23, 59)) {
        // 시험으로 바꾸면 기본 시각을 오전 10시로(마감 기본값 23:59는 시험에 맞지 않는다).
        form = form.copy(time = LocalTime.of(10, 0))
    }
    // 알림 시각은 "마감 몇 분 전"으로 들고 있다가 저장할 때 시각으로 바꾼다(마감을 옮기면 알림도 같이 따라간다).
    // 손대지 않은 새 시험은 기본 알림(전날 20:00, 2시간 전)을 지울 수 있는 칩으로 보이고, 저장은 보이는 칩 그대로다([DeadlineForm.Reminders]).
    var reminderState by remember { mutableStateOf(DeadlineForm.initialReminders(initial)) }

    val exam = kind == MyKind.EXAM
    val keep = initial?.takeIf { !exam && it.repeat != null && DeadlineForm.samePattern(it, form, now) }?.due?.atZone(zone)?.toLocalDate()
    val dueOrNull: Instant? = if (exam) LocalDateTime.of(form.date, form.time).atZone(zone).toInstant() else DeadlineForm.due(form, now, keep)
    fun repeatOf(): MyRepeat? = form.freq?.let { f ->
        val days = if (f == MyRepeat.Freq.WEEKLY || f == MyRepeat.Freq.BIWEEKLY) form.weekdays else emptySet()
        MyRepeat(f, days, until.takeIf { endMode == DeadlineForm.EndMode.UNTIL }, count.takeIf { endMode == DeadlineForm.EndMode.COUNT })
    }
    fun seriesPreview(): MyDeadline? = dueOrNull?.let { MyDeadline(0, "x", it, repeat = repeatOf()) }
    fun offsetLabel(m: Long): String = DeadlineForm.reminderLabel(m, dueOrNull)
    val offsets = DeadlineForm.shownReminders(reminderState, exam, dueOrNull, now)
    fun removeOffset(m: Long) { reminderState = DeadlineForm.removeReminder(reminderState, exam, dueOrNull, now, m); error = null }
    fun addOffset(m: Long) { reminderState = DeadlineForm.addReminder(reminderState, exam, dueOrNull, now, m); error = null }
    val canSave = exam || form.freq == null || dueOrNull != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.backgroundNeutralPrimary,
        title = { Text(if (initial == null) (if (exam) "시험 추가" else "내 마감 추가") else (if (exam) "시험 고치기" else "내 마감 고치기"), style = t.title3Bold, color = c.foregroundNeutralPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (draft != null) {
                    // 공지에서 온 초안: 날짜를 하나만 찾았을 때만 넣었고, 저장은 사용자가 누를 때만.
                    if (!draft.foundDate) {
                        Text(kr.snu.etlassistant.notify.ScheduleDrafts.ASK_HINT, style = t.body2Medium, color = c.foregroundCautionaryPrimary)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (draft.prefill.isNotEmpty()) {
                        DialogButton(kr.snu.etlassistant.notify.ScheduleDrafts.ASK_LINK) { onAskAssistant(draft) }
                        Text("공지 내용은 이걸 누를 때만 비서(내 ChatGPT 계정)로 가요.", style = t.caption2Regular, color = c.foregroundNeutralSecondary)
                    }
                    Text(listOfNotNull(
                        kr.snu.etlassistant.notify.ScheduleDrafts.FOUND_HINT.takeIf { draft.foundDate },
                        kr.snu.etlassistant.notify.ScheduleDrafts.ASSIGNMENT_HINT.takeIf { draft.kind == MyKind.DEADLINE },
                    ).joinToString(" "), style = t.caption1Regular, color = c.foregroundBrandPrimary)
                    Spacer(Modifier.height(8.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in MyKind.values()) WantedChip(text = k.label, isActive = kind == k, onClick = { kind = k; error = null },
                        modifier = Modifier.semantics { contentDescription = "종류 ${k.label}" + if (kind == k) ", 선택됨" else "" })
                }
                Spacer(Modifier.height(10.dp))
                WantedTextField(
                    text = title,
                    placeholder = if (exam) "예: 중간고사" else "예: 장학금 서류 제출",
                    background = c.backgroundNeutralPrimary,
                    onValueChange = { title = it.take(MyDeadlines.TITLE_MAX); error = null },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (exam) {
                    Text("날짜와 시간", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineButton(dateText(form.date), "시험 날짜 ${dateText(form.date)}") { pickDate(form.date) { form = form.copy(date = it); error = null } }
                        OutlineButton(timeText(form.time) + " 시작", "시작 시각 ${timeText(form.time)}") { pickTime(form.time) { form = form.copy(time = it); error = null } }
                        OutlineButton(examEnd?.let { timeText(it) + " 끝" } ?: "끝 시각 (선택)", "끝 시각 " + (examEnd?.let { timeText(it) } ?: "없음")) {
                            pickTime(examEnd ?: form.time.plusHours(2)) { examEnd = it; error = null }
                        }
                        if (examEnd != null) DialogButton("끝 시각 지우기") { examEnd = null }
                    }
                    Spacer(Modifier.height(10.dp))
                    WantedTextField(text = location, placeholder = "장소 (선택, 예: 301동 118호)", background = c.backgroundNeutralPrimary,
                        onValueChange = { location = it.take(MyDeadlines.LOCATION_MAX) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    WantedTextField(text = course, placeholder = "과목 (선택)", background = c.backgroundNeutralPrimary,
                        onValueChange = { course = it.take(MyDeadlines.COURSE_MAX) }, modifier = Modifier.fillMaxWidth())
                    if (courses.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (name in courses.take(12)) WantedChip(text = name, isActive = course == name, onClick = { course = if (course == name) "" else name })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    WantedTextField(text = supplies, placeholder = "준비물 (선택, 예: 공학용 계산기, 신분증)", background = c.backgroundNeutralPrimary,
                        onValueChange = { supplies = it.take(MyDeadlines.SUPPLIES_MAX) }, modifier = Modifier.fillMaxWidth())
                } else {
                    RepeatInputs(form, endMode, until, count, seriesPreview(), now, keep,
                        onForm = { form = it; error = null },
                        onEnd = { mode ->
                            val s = seriesPreview()
                            if (s != null) {
                                val (u, n) = DeadlineForm.switchEnd(s, endMode, mode, until, count)
                                if (mode == DeadlineForm.EndMode.UNTIL) until = u ?: until ?: form.date.plusMonths(1)
                                if (mode == DeadlineForm.EndMode.COUNT) count = n ?: count
                            }
                            endMode = mode
                            error = null
                        },
                        onPickUntil = { pickDate(until ?: form.date.plusMonths(1)) { until = it; error = null } },
                        onCount = { count = it.coerceIn(1, MyDeadlines.COUNT_MAX); error = null },
                        pickDate = pickDate, pickTime = pickTime,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("알림 (선택)", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                Text(
                    if (exam) (
                        if (offsets.isEmpty()) "알림 없이 저장해요. 알림을 받으려면 아래에서 더하세요."
                        else (if (!reminderState.touched) "기본 알림이에요. 필요 없으면 ✕로 빼세요. " else "") +
                            "정한 시각에 꼭 울려요(방해 금지 시간에도). 시험 알림에는 응원 문구가 함께 와요."
                    )
                    else "설정의 기본 마감 알림과 별개로, 정한 시각에 꼭 울려요(방해 금지 시간에도)." + (if (form.freq != null) " 반복 마감은 회차마다 울려요." else ""),
                    style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 시각순(이른 알림 먼저: 전날 20:00 → 2시간 전)
                    for (m in offsets.sortedDescending()) WantedChip(text = offsetLabel(m) + "  ✕", onClick = { removeOffset(m) },
                        modifier = Modifier.semantics { contentDescription = "알림 ${offsetLabel(m)} 지우기" })
                    if (offsets.size < MyDeadlines.MAX_REMINDERS) {
                        for ((m, label) in listOf(10L to "+10분 전", 60L to "+1시간 전", 1440L to "+하루 전")) {
                            if (m !in offsets) WantedChip(text = label, onClick = { addOffset(m) })
                        }
                        WantedChip(text = "+시각 고르기", onClick = {
                            val due = dueOrNull ?: return@WantedChip
                            pickTime(form.time.minusHours(1)) { picked ->
                                val dueDate = due.atZone(zone).toLocalDate()
                                var at = dueDate.atTime(picked).atZone(zone).toInstant()
                                if (!at.isBefore(due)) at = at.minusSeconds(86400) // 마감보다 늦으면 전날 그 시각
                                addOffset(java.time.Duration.between(at, due).toMinutes())
                            }
                        })
                    }
                }
                Spacer(Modifier.height(12.dp))
                WantedTextField(
                    text = memo,
                    placeholder = "메모 (선택)",
                    background = c.backgroundNeutralPrimary,
                    onValueChange = { memo = it.take(MyDeadlines.MEMO_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft?.replacesClass == true && draft.day != null) {
                    // 동영상 강의 공지에 "대면 수업 없음/대체" 단서가 있고 그날을 알 때만.
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { cancelClass = !cancelClass }
                        .semantics(mergeDescendants = true) { contentDescription = "그날 대면 수업은 휴강으로 표시" + if (cancelClass) ", 선택됨" else "" },
                        verticalAlignment = Alignment.CenterVertically) {
                        com.wanted.android.wanted.design.input.input.control.WantedCheckBox(
                            onCheckedChange = { cancelClass = it },
                            style = com.wanted.android.wanted.design.input.input.control.CheckBoxStyle.CheckBox,
                            checkState = if (cancelClass) com.wanted.android.wanted.design.input.input.control.CheckBoxState.Checked else com.wanted.android.wanted.design.input.input.control.CheckBoxState.Unchecked,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("그날(${Agenda.dateLabel(draft.day)}) 대면 수업은 휴강으로 표시", style = t.body2Regular, color = c.foregroundNeutralPrimary)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = t.caption1Regular, color = c.foregroundNegativePrimary)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("취소", onClick = onDismiss)
                DialogButton("저장") {
                    if (!canSave) { error = "요일을 하나 이상 골라 주세요"; return@DialogButton }
                    val titleText = title.trim().ifEmpty { if (exam) "시험" else "" }
                    val err = MyDeadlines.validate(titleText, memo, countWithoutThis)
                    if (err != null) { error = err; return@DialogButton }
                    val due = dueOrNull ?: run { error = "첫 마감을 정할 수 없어요"; return@DialogButton }
                    // 보이는 칩 그대로 저장한다(빈 목록이면 알림 없음).
                    val reminders = DeadlineForm.reminderInstants(reminderState, exam, due, now)
                    MyDeadlines.validateReminders(reminders, due)?.let { error = it; return@DialogButton }
                    val repeat = if (exam) null else repeatOf()
                    val repeating = repeat != null
                    val end = if (exam) examEnd?.let { e -> LocalDateTime.of(form.date, e).atZone(zone).toInstant().let { if (it.isAfter(due)) it else it.plusSeconds(86400) } } else null
                    val new = MyDeadline(
                        id = if (initial != null && initial.due == due) initial.id else System.currentTimeMillis(),
                        title = titleText,
                        due = due,
                        memo = memo.trim(),
                        done = if (repeating || exam) false else initial?.done ?: false,
                        reminders = reminders,
                        repeat = repeat,
                        // 회차별 완료·건너뛰기는 반복을 고쳐도 날짜 기준으로 그대로 둔다.
                        doneDates = if (repeating) initial?.doneDates.orEmpty() else emptySet(),
                        skipDates = if (repeating) initial?.skipDates.orEmpty() else emptySet(),
                        kind = kind,
                        end = end,
                        location = if (exam) location.trim() else "",
                        course = if (exam) course.trim() else "",
                        supplies = if (exam) MyDeadlines.normalizeSupplies(supplies) else "",
                        sourceAnnouncementId = draft?.announcementId ?: initial?.sourceAnnouncementId,
                    )
                    MyDeadlines.validateRepeat(new)?.let { error = it; return@DialogButton }
                    MyDeadlines.validateExam(new)?.let { error = it; return@DialogButton }
                    if (cancelClass && draft?.day != null) onCancelClass(draft.day, draft.course, draft.announcementId)
                    onSave(new)
                }
            }
        },
    )
}

private fun dateText(d: LocalDate) = "%d/%02d/%02d(%s)".format(d.year, d.monthValue, d.dayOfMonth, WEEKDAYS[d.dayOfWeek.value - 1])
private fun timeText(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

/**
 * 마감의 날짜·반복 입력. 반복 종류마다 필요한 입력만: 없음 = 날짜+시각, 매일 = 시각(+시작 날짜), 매주 = 요일+시각,
 * 격주 = 요일+시각+기준 주, 매월 = N일+시각. 반복이면 계산된 첫 마감과 끝(끝 없음/날짜까지/횟수) 안내를 바로 보인다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatInputs(
    form: DeadlineForm.Input,
    endMode: DeadlineForm.EndMode,
    until: LocalDate?,
    count: Int,
    series: MyDeadline?,
    now: Instant,
    keep: LocalDate?,
    onForm: (DeadlineForm.Input) -> Unit,
    onEnd: (DeadlineForm.EndMode) -> Unit,
    onPickUntil: () -> Unit,
    onCount: (Int) -> Unit,
    pickDate: (LocalDate, (LocalDate) -> Unit) -> Unit,
    pickTime: (LocalTime, (LocalTime) -> Unit) -> Unit,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Text("반복", style = t.body2Medium, color = c.foregroundNeutralPrimary)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WantedChip(text = "없음", isActive = form.freq == null, onClick = { onForm(DeadlineForm.switchFreq(form, null, now)) })
        for (f in MyRepeat.Freq.values()) WantedChip(text = f.label, isActive = form.freq == f, onClick = { onForm(DeadlineForm.switchFreq(form, f, now)) })
    }
    Spacer(Modifier.height(10.dp))
    val timeButton: @Composable () -> Unit = {
        OutlineButton(timeText(form.time), "마감 시각 ${timeText(form.time)}") { pickTime(form.time) { onForm(form.copy(time = it)) } }
    }
    when (form.freq) {
        null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton(dateText(form.date), "마감 날짜 ${dateText(form.date)}") { pickDate(form.date) { onForm(form.copy(date = it)) } }
            timeButton()
        }
        MyRepeat.Freq.DAILY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            timeButton()
            DialogButton(form.dailyStart?.let { "시작 ${dateText(it)}" } ?: "시작 날짜 바꾸기") {
                pickDate(form.dailyStart ?: LocalDate.now(EtlLogic.KST)) { onForm(form.copy(dailyStart = it)) }
            }
        }
        MyRepeat.Freq.WEEKLY, MyRepeat.Freq.BIWEEKLY -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (wd in DayOfWeek.values()) {
                    val name = WEEKDAYS[wd.value - 1]
                    val on = wd in form.weekdays
                    WantedChip(text = name.toString(), isActive = on, onClick = { onForm(form.copy(weekdays = if (on) form.weekdays - wd else form.weekdays + wd)) },
                        modifier = Modifier.semantics { contentDescription = "${name}요일" + if (on) ", 선택됨" else "" })
                }
            }
            Spacer(Modifier.height(8.dp))
            if (form.freq == MyRepeat.Freq.BIWEEKLY) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WantedChip(text = "이번 주부터", isActive = !form.biweeklyNext, onClick = { onForm(form.copy(biweeklyNext = false)) })
                    WantedChip(text = "다음 주부터", isActive = form.biweeklyNext, onClick = { onForm(form.copy(biweeklyNext = true)) })
                }
                Spacer(Modifier.height(8.dp))
            }
            timeButton()
            if (form.weekdays.isEmpty()) Text("요일을 하나 이상 골라 주세요.", style = t.caption1Regular, color = c.foregroundNegativePrimary, modifier = Modifier.padding(top = 4.dp))
        }
        MyRepeat.Freq.MONTHLY -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("매월", style = t.body2Regular, color = c.foregroundNeutralPrimary)
                DialogButton("−") { onForm(form.copy(monthDay = if (form.monthDay <= 1) 31 else form.monthDay - 1)) }
                Text("${form.monthDay}일", style = t.body1Medium, color = c.foregroundNeutralPrimary,
                    modifier = Modifier.semantics { contentDescription = "매월 ${form.monthDay}일" })
                DialogButton("+") { onForm(form.copy(monthDay = if (form.monthDay >= 31) 1 else form.monthDay + 1)) }
                Spacer(Modifier.width(8.dp))
                timeButton()
            }
            if (form.monthDay > 28) Text("${form.monthDay}일이 없는 달은 그달 마지막 날이 마감이에요.", style = t.caption1Regular,
                color = c.foregroundNeutralSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (form.freq == null) return
    DeadlineForm.firstLabel(form, now, keep)?.let {
        Text(it, style = t.caption1Regular, color = c.foregroundBrandPrimary, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(10.dp))
    Text("끝", style = t.body2Medium, color = c.foregroundNeutralPrimary)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WantedChip(text = "끝 없음", isActive = endMode == DeadlineForm.EndMode.NONE, onClick = { onEnd(DeadlineForm.EndMode.NONE) })
        WantedChip(text = "날짜까지", isActive = endMode == DeadlineForm.EndMode.UNTIL, onClick = { onEnd(DeadlineForm.EndMode.UNTIL) })
        WantedChip(text = "횟수", isActive = endMode == DeadlineForm.EndMode.COUNT, onClick = { onEnd(DeadlineForm.EndMode.COUNT) })
    }
    when (endMode) {
        DeadlineForm.EndMode.UNTIL -> {
            Spacer(Modifier.height(8.dp))
            OutlineButton(until?.let { dateText(it) + "까지" } ?: "끝 날짜 고르기", "끝 날짜 " + (until?.let { dateText(it) } ?: "없음"), onClick = onPickUntil)
        }
        DeadlineForm.EndMode.COUNT -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DialogButton("−") { onCount(count - 1) }
                Text("${count}번", style = t.body1Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.semantics { contentDescription = "총 ${count}번" })
                DialogButton("+") { onCount(count + 1) }
                DialogButton("+10") { onCount(count + 10) }
            }
        }
        DeadlineForm.EndMode.NONE -> Unit
    }
    series?.let { s ->
        Text(MyDeadlines.endSummary(s), style = t.caption1Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.padding(top = 6.dp))
    }
    if (keep != null) Text("반복 모양이 그대로면 시작 날짜는 바뀌지 않아요. 고치면 반복 전체에 적용돼요.", style = t.caption1Regular,
        color = c.foregroundNeutralSecondary, modifier = Modifier.padding(top = 4.dp))
}
