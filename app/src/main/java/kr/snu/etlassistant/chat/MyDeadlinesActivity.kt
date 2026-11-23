package kr.snu.etlassistant.chat

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.CalendarViewWeek
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                schedule = Agenda.build(courseFilter.visibleAssignments(cached?.assignments.orEmpty()), mine, now, ZoneId.systemDefault(), filter, hideDone),
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
                initialDeadline = remember { store.load().firstOrNull { it.id == intent.getLongExtra(kr.snu.etlassistant.widget.HomeWidgets.EXTRA_DEADLINE_ID, Long.MIN_VALUE) } },
                onFilter = { filter = it },
                onHideDone = { hideDone = it },
                onBack = { finish() },
                onTimetable = { startActivity(Intent(this, TimetableActivity::class.java)) },
                onRefresh = ::refresh,
                onOpen = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                onSave = { old, new ->
                    // 마감 시각을 바꾸면 새 ID로 저장한다: 예전 시각 기준으로 이미 보낸 알림 기록이 새 알림을 막지 않게.
                    if (old != null && old.id != new.id) store.delete(old.id)
                    save(new)
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
            )
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
        // 저장된 결과를 바로 보여 주고, 없거나 오래됐으면(3시간) 뒤에서 새로 받는다.
        val age = cached?.let { System.currentTimeMillis() - it.takenAt }
        if (age == null || age > STALE_MS) refresh()
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
            Agenda.Kind.MINE -> r.mine?.let { occ -> seriesOf(occ)?.let { kr.snu.etlassistant.calendar.CalendarExport.insertIntentFor(it, occ.takeIf { occ.occurrence != null }) } }
            Agenda.Kind.ASSIGNMENT -> cached?.assignments?.firstOrNull { "assignment-${it.courseId}-${it.assignmentId}" == r.id }
                ?.let { kr.snu.etlassistant.calendar.CalendarExport.insertIntentFor(it) }
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

    private companion object {
        const val STALE_MS = 3 * 3600 * 1000L
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
) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        var editing by remember { mutableStateOf(initialDeadline) }
        var adding by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<MyDeadline?>(null) }
        var showOverdue by rememberSaveable { mutableStateOf(false) }
        fun openRow(r: Agenda.Row) {
            when {
                // 반복 마감은 회차가 아니라 반복 전체를 고친다.
                r.mine != null -> editing = seriesOf(r.mine) ?: r.mine
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
                IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, contentDescription = "내 마감 추가", tint = c.foregroundBrandPrimary) }
            }
            val status = when {
                !hasToken -> "eTL 토큰을 저장하면 eTL 과제 마감도 여기에 모여요."
                takenAt == null -> if (loading) "eTL 과제를 받는 중…" else "아직 eTL 과제를 받지 않았어요."
                else -> "eTL 과제 마지막 확인 ${myDeadlineLabel(Instant.ofEpochMilli(takenAt))}"
            } + if (alertsOn) "" else " · 마감 알림 꺼짐(설정에서 켤 수 있어요)"
            Text(status, style = t.caption1Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in Agenda.Filter.values()) WantedChip(text = f.label, isActive = filter == f, onClick = { onFilter(f) })
                WantedChip(text = if (hideDone) "제출·완료 숨김" else "제출·완료 보기", isActive = !hideDone, onClick = { onHideDone(!hideDone) })
                // 과목별 알림에서 숨긴 과목의 과제는 여기 보이지 않는다. 누르면 과목별 알림 화면으로 간다.
                if (hiddenCourses > 0 && filter != Agenda.Filter.MINE) WantedChip(text = "숨긴 과목 ${hiddenCourses}개", onClick = onHiddenCourses)
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
                        AgendaRow(r, now, onClick = { openRow(r) }, onToggleDone = { r.mine?.let(onToggleDone) }, onDelete = { deleting = r.mine }, onAddToCalendar = { onAddToCalendar(r) })
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
                            DeadlineEmptyText("$period ${if (hideDone) "남은 " else ""}마감이 없어요")
                        }
                    }
                    for (g in groups) {
                        item(key = "date-${g.date}") {
                            Text(Agenda.dayLabel(g.date, schedule.today), style = t.body2Medium, color = c.foregroundBrandPrimary,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp))
                        }
                        items(g.rows, key = { it.id }) { r ->
                            AgendaRow(r, now, onClick = { openRow(r) }, onToggleDone = { r.mine?.let(onToggleDone) }, onDelete = { deleting = r.mine }, onAddToCalendar = { onAddToCalendar(r) })
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        if (adding || editing != null) {
            DeadlineDialog(
                initial = editing,
                countWithoutThis = if (editing != null) mineTotal - 1 else mineTotal,
                onDismiss = { adding = false; editing = null },
                onSave = { new -> onSave(editing, new); adding = false; editing = null },
                pickDate = pickDate,
                pickTime = pickTime,
            )
        }
        deleting?.let { d ->
            val occurrence = d.occurrence.takeIf { d.repeat != null }
            AlertDialog(
                onDismissRequest = { deleting = null },
                containerColor = c.backgroundNeutralPrimary,
                title = { Text(if (occurrence != null) "반복 마감을 지울까요?" else "이 마감을 지울까요?", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
                text = {
                    Text(
                        if (occurrence != null) "\"${d.title}\"은(는) ${MyDeadlines.repeatLabel(d)} 반복돼요. ${Agenda.dateLabel(occurrence)} 회차만 지울지, 반복 전체를 지울지 골라 주세요."
                        else "\"${d.title}\"이(가) 목록과 알림에서 사라져요.",
                        style = t.body2Regular, color = c.foregroundNeutralSecondary,
                    )
                },
                confirmButton = {
                    if (occurrence != null) Row {
                        WantedTextButton(text = "이번만", onClick = { onDelete(d, true); deleting = null })
                        WantedTextButton(text = "전체", onClick = { onDelete(d, false); deleting = null })
                    } else WantedTextButton(text = "삭제", onClick = { onDelete(d, false); deleting = null })
                },
                dismissButton = { WantedTextButton(text = "취소", onClick = { deleting = null }) },
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
private fun AgendaRow(r: Agenda.Row, now: Instant, onClick: () -> Unit, onToggleDone: () -> Unit, onDelete: () -> Unit, onAddToCalendar: () -> Unit = {}) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val clickable = r.mine != null || r.url != null
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceNeutralSecondary)
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClickLabel = if (r.mine != null) "고치기" else "eTL에서 열기", onClick = onClick) else Modifier)
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
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(r.title, style = t.body1Medium,
                color = if (r.done) c.foregroundNeutralTertiary else c.foregroundNeutralPrimary,
                textDecoration = if (r.done) TextDecoration.LineThrough else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val whenText = Agenda.whenLabel(r, now)
            Text(listOf(whenText, r.subtitle).filter { it.isNotEmpty() }.joinToString(" · "), style = t.caption1Regular,
                color = if (r.overdue) c.foregroundNegativePrimary else c.foregroundNeutralSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onAddToCalendar, modifier = Modifier.semantics { contentDescription = "'${r.title}' 캘린더에 추가" }) {
            Icon(Icons.Outlined.EventAvailable, contentDescription = null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(20.dp))
        }
        if (r.mine != null) {
            IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "'${r.title}' 지우기" }) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = c.foregroundNeutralSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeadlineDialog(
    initial: MyDeadline?,
    countWithoutThis: Int,
    onDismiss: () -> Unit,
    onSave: (MyDeadline) -> Unit,
    pickDate: (LocalDate, (LocalDate) -> Unit) -> Unit,
    pickTime: (LocalTime, (LocalTime) -> Unit) -> Unit,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    val zone = ZoneId.systemDefault()
    val start = initial?.due?.atZone(zone)?.toLocalDateTime()
        ?: LocalDate.now(zone).plusDays(1).atTime(23, 59) // 기본: 내일 23:59
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var memo by remember { mutableStateOf(initial?.memo ?: "") }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(start.toLocalTime().withSecond(0).withNano(0)) }
    var error by remember { mutableStateOf<String?>(null) }
    var freq by remember { mutableStateOf(initial?.repeat?.freq) }
    var weekdays by remember { mutableStateOf(initial?.repeat?.weekdays.orEmpty()) }
    var until by remember { mutableStateOf(initial?.repeat?.until) }
    // 알림 시각은 "마감 몇 분 전"으로 들고 있다가 저장할 때 시각으로 바꾼다(마감을 옮기면 알림도 같이 따라간다).
    var offsets by remember {
        mutableStateOf(initial?.let { d -> d.reminders.map { java.time.Duration.between(it, d.due).toMinutes() }.filter { it > 0 } }.orEmpty().sorted())
    }
    fun dueNow() = LocalDateTime.of(date, time).atZone(zone).toInstant()
    fun offsetLabel(m: Long): String {
        val at = dueNow().minusSeconds(m * 60).atZone(zone)
        val hm = "%02d:%02d".format(at.hour, at.minute)
        val rel = when {
            m % 1440 == 0L -> "${m / 1440}일 전"
            m % 60 == 0L -> "${m / 60}시간 전"
            m > 60 -> "${m / 60}시간 ${m % 60}분 전"
            else -> "${m}분 전"
        }
        return if (at.toLocalDate() == date) "$hm · $rel" else "${at.monthValue}/${at.dayOfMonth} $hm · $rel"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.backgroundNeutralPrimary,
        title = { Text(if (initial == null) "내 마감 추가" else "내 마감 고치기", style = t.title3Bold, color = c.foregroundNeutralPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                WantedTextField(
                    text = title,
                    placeholder = "예: 장학금 서류 제출",
                    background = c.backgroundNeutralPrimary,
                    onValueChange = { title = it.take(MyDeadlines.TITLE_MAX); error = null },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (freq != null) Text("첫 마감 날짜와 시각", style = t.caption1Regular, color = c.foregroundNeutralSecondary,
                    modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WantedButton(
                        text = "%d/%02d/%02d".format(date.year, date.monthValue, date.dayOfMonth),
                        type = ButtonType.ASSISTIVE,
                        size = ButtonSize.MEDIUM,
                        variant = ButtonVariant.OUTLINED,
                        onClick = { pickDate(date) { date = it; error = null } },
                    )
                    WantedButton(
                        text = "%02d:%02d".format(time.hour, time.minute),
                        type = ButtonType.ASSISTIVE,
                        size = ButtonSize.MEDIUM,
                        variant = ButtonVariant.OUTLINED,
                        onClick = { pickTime(time) { time = it; error = null } },
                    )
                }
                Spacer(Modifier.height(12.dp))
                RepeatEditor(
                    freq = freq, weekdays = weekdays, until = until, date = date, editingSeries = initial?.repeat != null,
                    onFreq = { f ->
                        freq = f
                        // 매주·격주를 처음 고르면 마감 날짜의 요일을 골라 둔다.
                        if ((f == MyRepeat.Freq.WEEKLY || f == MyRepeat.Freq.BIWEEKLY) && weekdays.isEmpty()) weekdays = setOf(date.dayOfWeek)
                        error = null
                    },
                    onToggleWeekday = { wd -> weekdays = if (wd in weekdays) weekdays - wd else weekdays + wd; error = null },
                    onPickUntil = { pickDate(until ?: date.plusMonths(1)) { until = it; error = null } },
                    onClearUntil = { until = null; error = null },
                )
                Spacer(Modifier.height(12.dp))
                Text("알림 (선택)", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                Text("설정의 기본 마감 알림과 별개로, 정한 시각에 꼭 울려요(방해 금지 시간에도)." +
                    (if (freq != null) " 반복 마감은 회차마다 울려요." else ""), style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in offsets) WantedChip(text = offsetLabel(m) + "  ✕", onClick = { offsets = offsets - m; error = null })
                    if (offsets.size < MyDeadlines.MAX_REMINDERS) {
                        for ((m, label) in listOf(10L to "+10분 전", 60L to "+1시간 전", 1440L to "+하루 전")) {
                            if (m !in offsets) WantedChip(text = label, onClick = { offsets = (offsets + m).sorted(); error = null })
                        }
                        WantedChip(text = "+시각 고르기", onClick = {
                            pickTime(time.minusHours(1)) { picked ->
                                val due = dueNow()
                                var at = date.atTime(picked).atZone(zone).toInstant()
                                if (!at.isBefore(due)) at = at.minusSeconds(86400) // 마감보다 늦으면 전날 그 시각
                                val m = java.time.Duration.between(at, due).toMinutes()
                                if (m > 0 && m !in offsets) offsets = (offsets + m).sorted()
                                error = null
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
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = t.caption1Regular, color = c.foregroundNegativePrimary)
                }
            }
        },
        confirmButton = {
            WantedTextButton(text = "저장", onClick = {
                val err = MyDeadlines.validate(title, memo, countWithoutThis)
                if (err != null) {
                    error = err
                    return@WantedTextButton
                }
                val due = LocalDateTime.of(date, time).atZone(zone).toInstant()
                val reminders = MyDeadlines.normalizeReminders(offsets.map { due.minusSeconds(it * 60) })
                MyDeadlines.validateReminders(reminders, due)?.let { error = it; return@WantedTextButton }
                val repeating = freq != null
                val new = MyDeadline(
                    id = if (initial != null && initial.due == due) initial.id else System.currentTimeMillis(),
                    title = title.trim(),
                    due = due,
                    memo = memo.trim(),
                    done = if (repeating) false else initial?.done ?: false,
                    reminders = reminders,
                    repeat = freq?.let { f ->
                        val days = if (f == MyRepeat.Freq.WEEKLY || f == MyRepeat.Freq.BIWEEKLY) weekdays else emptySet()
                        MyRepeat(f, days, until)
                    },
                    // 회차별 완료·건너뛰기는 반복을 고쳐도 날짜 기준으로 그대로 둔다.
                    doneDates = if (repeating) initial?.doneDates.orEmpty() else emptySet(),
                    skipDates = if (repeating) initial?.skipDates.orEmpty() else emptySet(),
                )
                MyDeadlines.validateRepeat(new)?.let { error = it; return@WantedTextButton }
                onSave(new)
            })
        },
        dismissButton = { WantedTextButton(text = "취소", onClick = onDismiss) },
    )
}

/** 반복: 없음/매일/매주/격주/매월, 매주·격주는 요일 여러 개, 끝 날짜(선택). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatEditor(
    freq: MyRepeat.Freq?,
    weekdays: Set<DayOfWeek>,
    until: LocalDate?,
    date: LocalDate,
    editingSeries: Boolean,
    onFreq: (MyRepeat.Freq?) -> Unit,
    onToggleWeekday: (DayOfWeek) -> Unit,
    onPickUntil: () -> Unit,
    onClearUntil: () -> Unit,
) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Text("반복", style = t.body2Medium, color = c.foregroundNeutralPrimary)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WantedChip(text = "없음", isActive = freq == null, onClick = { onFreq(null) })
        for (f in MyRepeat.Freq.values()) WantedChip(text = f.label, isActive = freq == f, onClick = { onFreq(f) })
    }
    if (freq == MyRepeat.Freq.WEEKLY || freq == MyRepeat.Freq.BIWEEKLY) {
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (wd in DayOfWeek.values()) {
                val name = WEEKDAYS[wd.value - 1]
                WantedChip(text = name.toString(), isActive = wd in weekdays, onClick = { onToggleWeekday(wd) },
                    modifier = Modifier.semantics { contentDescription = "${name}요일" + if (wd in weekdays) ", 선택됨" else "" })
            }
        }
        if (weekdays.isEmpty()) Text("요일을 고르지 않으면 ${WEEKDAYS[date.dayOfWeek.value - 1]}요일마다 마감이에요.",
            style = t.caption1Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.padding(top = 4.dp))
    }
    if (freq == MyRepeat.Freq.MONTHLY && date.dayOfMonth > 28) {
        Text("${date.dayOfMonth}일이 없는 달은 그달 마지막 날이 마감이에요.", style = t.caption1Regular,
            color = c.foregroundNeutralSecondary, modifier = Modifier.padding(top = 4.dp))
    }
    if (freq != null) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WantedButton(
                text = until?.let { "%d/%02d/%02d까지".format(it.year, it.monthValue, it.dayOfMonth) } ?: "끝나는 날 없음",
                type = ButtonType.ASSISTIVE,
                size = ButtonSize.MEDIUM,
                variant = ButtonVariant.OUTLINED,
                onClick = onPickUntil,
            )
            if (until != null) WantedTextButton(text = "끝 날짜 지우기", onClick = onClearUntil)
        }
        if (editingSeries) Text("고치면 반복 전체에 적용돼요.", style = t.caption1Regular, color = c.foregroundNeutralSecondary,
            modifier = Modifier.padding(top = 4.dp))
    }
}
