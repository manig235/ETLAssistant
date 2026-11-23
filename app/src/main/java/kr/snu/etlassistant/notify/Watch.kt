package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.TimetableLogic
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * ttokttok-pet `src/etl/watch.js`의 새 과제·마감 변경·성적 등록 판정과 수업별 알림(timetable.js의 수업 말풍선)을 옮긴 것.
 * 순수 함수(시계·네트워크·저장소 없음). 저장은 [WatchStore], 실행은 [NotifyRunner].
 */

/** 과제 하나에 대해 지난 확인에서 본 것. [submittedAt]은 제출 직후 점검이 이미 본 제출 시각(epoch millis). */
data class WatchRecord(val name: String, val course: String, val due: Long?, val score: Double?, val submittedAt: Long? = null)

/**
 * [initialized]가 false인 첫 확인은 기준선만 잡고 알리지 않는다.
 * [submitInitialized]: 제출 시각을 기록하기 시작했는가(2026-11-23 추가). 예전 기록에는 제출 시각이 없어서
 * 업데이트 뒤 첫 확인에서 모든 제출이 "새 제출"로 보이지 않게, 이것도 처음에는 기준선만 잡는다.
 */
data class WatchState(
    val initialized: Boolean = false,
    val records: Map<String, WatchRecord> = emptyMap(),
    val submitInitialized: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("version", 2)
        .put("initialized", initialized)
        .put("submitInitialized", submitInitialized)
        .put("records", JSONObject().also { o ->
            records.forEach { (k, r) ->
                o.put(k, JSONObject().put("name", r.name).put("course", r.course)
                    .put("due", r.due ?: JSONObject.NULL).put("score", r.score ?: JSONObject.NULL)
                    .put("sub", r.submittedAt ?: JSONObject.NULL))
            }
        }).toString()

    companion object {
        fun fromJson(s: String?): WatchState = try {
            val o = JSONObject(s ?: return WatchState())
            val recs = mutableMapOf<String, WatchRecord>()
            o.optJSONObject("records")?.let { r ->
                r.keys().forEach { k ->
                    val x = r.optJSONObject(k) ?: return@forEach
                    recs[k] = WatchRecord(
                        x.optString("name"), x.optString("course"),
                        if (x.isNull("due") || !x.has("due")) null else x.optLong("due"),
                        if (x.isNull("score") || !x.has("score")) null else x.optDouble("score"),
                        if (x.isNull("sub") || !x.has("sub")) null else x.optLong("sub"),
                    )
                }
            }
            WatchState(o.optBoolean("initialized", false), recs, o.optBoolean("submitInitialized", false))
        } catch (_: Exception) {
            WatchState()
        }
    }
}

object AssignmentWatch {
    /** 한 번에 이보다 많으면 하나의 묶음 알림으로 보낸다. */
    private const val INDIVIDUAL_MAX = 3

    private fun scoreText(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(d).trimEnd('0').trimEnd('.')

    /** 제출 직후 점검 알림의 키(중복 방지·알림 교체). */
    fun submitKey(a: AssignmentItem): String = "submit:${a.courseId}:${a.assignmentId}:${a.submittedAt?.toEpochMilli()}"

    /**
     * 이 과제의 제출이 지난 확인 뒤 새로 생겼거나 바뀌었는가(제출 직후 점검 대상).
     * 기준선을 잡기 전이거나, 이번 결과에 점검 정보가 없으면(받아 둔 옛 저장본) false.
     */
    fun isNewSubmission(prev: WatchState, a: AssignmentItem): Boolean {
        if (!prev.initialized || !prev.submitInitialized || a.check == null) return false
        val at = a.submittedAt?.toEpochMilli() ?: return false
        return prev.records["${a.courseId}:${a.assignmentId}"]?.submittedAt != at
    }

    /**
     * 지난 상태와 이번 조회를 비교해 새 과제, 마감 변경, 성적 등록([changes]), 제출 직후 점검([submits]) 알림을 만든다.
     * 내 마감(강의 ID −1)은 보지 않는다. 이번에 못 본 과제(조회 실패한 강의 등)는 기록을 남겨 다음에 "새 과제"가 되지 않게 한다.
     * 과제가 하나도 오지 않고 오류만 있으면 상태를 바꾸지 않는다(빈 결과로 기준선을 다시 잡지 않는다).
     * [muted]인 과목(과목별 알림 끄기)은 알리지 않지만 기록은 갱신한다(다시 켰을 때 그동안의 변경이 몰려오지 않게).
     *
     * 제출 직후 점검: 새 제출·다시 제출(제출 시각이 바뀜)이고 문제가 있을 때만 알린다(키 [submitKey]).
     * 늦은 제출만 있으면 알리지 않는다(본인이 안다; "내 일정"에는 보인다). 파일 업로드인데 첨부 목록을 아직 못 봤으면
     * 제출 시각을 기록하지 않고 미뤄 다음 확인에서 다시 본다(조회기가 `submissions/self`를 받아 채운다).
     */
    fun diff(
        prev: WatchState,
        snapshot: Snapshot,
        now: Instant,
        muted: (Long) -> Boolean = { false },
        changes: Boolean = true,
        submits: Boolean = false,
    ): Pair<WatchState, List<Notice>> {
        val items = snapshot.assignments.filter { it.courseId != MyDeadlines.COURSE_ID }
        if (items.isEmpty() && snapshot.errors.isNotEmpty()) return prev to emptyList()
        val first = !prev.initialized
        val records = mutableMapOf<String, WatchRecord>()
        data class Event(val kind: String, val item: AssignmentItem, val line: String, val tag: String)
        val events = mutableListOf<Event>()
        val submitEvents = mutableListOf<AssignmentItem>()
        for (a in items) {
            val key = "${a.courseId}:${a.assignmentId}"
            val old = prev.records[key]
            val subAt = when {
                // 점검 정보가 없는 항목(받아 둔 옛 저장본)은 지난 기록을 그대로 둔다.
                a.check == null && a.submittedAt != null -> old?.submittedAt
                isNewSubmission(prev, a) && submits && !muted(a.courseId) && a.check?.attachmentsKnown == false -> old?.submittedAt
                else -> a.submittedAt?.toEpochMilli()
            }
            val rec = WatchRecord(a.name, a.courseName, a.due?.toEpochMilli(), a.score, subAt)
            val course = NoticeText.course(a.courseName)
            val name = NoticeText.name(a.name)
            if (old == null) {
                if (!first) {
                    val due = a.due?.let { "마감 ${NoticeText.dateLabel(it)} (${EtlLogic.remaining(it, now)})" } ?: "마감일 없음"
                    events += Event("새 과제", a, "$name · $due", "new:$key")
                }
            } else {
                if (old.due != rec.due) {
                    val fmt = { ms: Long? -> ms?.let { NoticeText.dateLabel(Instant.ofEpochMilli(it)) } ?: "없음" }
                    events += Event("마감 변경", a, "$name · ${fmt(old.due)} → ${fmt(rec.due)}", "due:$key:${rec.due ?: "none"}")
                }
                if (rec.score != null && old.score != rec.score) {
                    val pts = a.points?.let { " / ${scoreText(it)}" } ?: ""
                    events += Event("성적", a, "$name · ${scoreText(rec.score)}$pts", "grade:$key:${rec.score}")
                }
            }
            if (submits && isNewSubmission(prev, a) && rec.submittedAt == a.submittedAt?.toEpochMilli() &&
                a.check?.problems?.isNotEmpty() == true && !muted(a.courseId)
            ) submitEvents += a
            records[key] = rec
        }
        for ((k, r) in prev.records) if (k !in records) records[k] = r
        val state = WatchState(true, records, submitInitialized = true)
        if (!changes) events.clear()
        events.removeAll { muted(it.item.courseId) }
        val submitNotices = submitEvents.sortedBy { it.submittedAt }.map(::submitNotice)
        if (events.isEmpty()) return state to submitNotices

        val order = mapOf("마감 변경" to 0, "새 과제" to 1, "성적" to 2)
        val sorted = events.sortedWith(compareBy({ order[it.kind] ?: 9 }, { it.item.due ?: Instant.MAX }))
        if (sorted.size <= INDIVIDUAL_MAX) {
            return state to sorted.map { e ->
                Notice(
                    channel = Channel.CHANGE,
                    title = NoticeText.cut("${e.kind} · ${NoticeText.clean(e.item.courseName)}", NoticeText.TITLE_MAX),
                    text = NoticeText.cut(e.line, NoticeText.LINE_MAX),
                    url = NoticeText.assignmentUrl(e.item),
                    tag = e.tag,
                )
            } + submitNotices
        }
        val lines = sorted.map { "• [${it.kind}] ${NoticeText.course(it.item.courseName)} — ${it.line}" }
        val counts = sorted.groupingBy { it.kind }.eachCount().entries.sortedBy { order[it.key] ?: 9 }.joinToString(" · ") { "${it.key} ${it.value}" }
        return state to listOf(
            Notice(
                channel = Channel.CHANGE,
                title = "eTL 변경 ${sorted.size}건",
                text = NoticeText.cut(counts, NoticeText.LINE_MAX),
                bigText = NoticeText.joinLimited(lines, 8),
                url = NoticeText.collectionUrl(sorted.map { it.item.courseId }) ?: NoticeText.ETL_PREFIX,
                tag = "change:summary",
            )
        ) + submitNotices
    }

    /** 제출에 문제가 있을 때의 알림. 문제 문구는 eTL 파일 이름이 들어갈 수 있어 정리해서 쓴다. */
    private fun submitNotice(a: AssignmentItem): Notice {
        val problems = a.check?.problems.orEmpty().map { NoticeText.clean(it) }
        val late = if (a.check?.late == true) listOf("늦은 제출로 표시됨") else emptyList()
        val key = submitKey(a)
        return Notice(
            channel = Channel.SUBMIT,
            title = NoticeText.cut("제출 확인 필요 · ${NoticeText.clean(a.courseName)}", NoticeText.TITLE_MAX),
            text = NoticeText.cut("${NoticeText.name(a.name)} · ${problems.first()}" + (if (problems.size > 1) " 외 ${problems.size - 1}건" else ""), NoticeText.LINE_MAX),
            bigText = NoticeText.joinLimited(listOf(NoticeText.name(a.name)) + (problems + late).map { "• $it" }, 8),
            url = NoticeText.assignmentUrl(a),
            tag = key,
            firedKeys = listOf(key),
        )
    }
}

/** 수업 시작 [NotifySettings.classLeadMinutes]분 전 알림. 키: `class:{날짜}:{시작}:{과목}`. */
object ClassNotices {
    fun key(date: LocalDate, r: TimetableLogic.ClassRow) = "class:$date:${r.start}:${r.courseId ?: r.course}"

    private fun startAt(date: LocalDate, hm: String, zone: ZoneId): Instant =
        ZonedDateTime.of(date, java.time.LocalTime.parse(hm), zone).toInstant()

    /** 지금 알릴 수업: 시작 [leadMin]분 전부터 시작 전까지이고 아직 알리지 않은 것. */
    fun plan(day: TimetableLogic.Day, now: Instant, zone: ZoneId, leadMin: Int, fired: Set<String>): List<Notice> =
        day.classes.filter { r ->
            val start = startAt(day.date, r.start, zone)
            !now.isBefore(start.minus(Duration.ofMinutes(leadMin.toLong()))) && now.isBefore(start) && key(day.date, r) !in fired
        }.map { r ->
            val mins = Duration.between(now, startAt(day.date, r.start, zone)).toMinutes().coerceAtLeast(1)
            val kind = if (r.kind == "정규") "" else " [${r.kind}]"
            Notice(
                channel = Channel.CLASS,
                title = NoticeText.cut("${mins}분 뒤 수업 · ${NoticeText.clean(r.course)}$kind", NoticeText.TITLE_MAX),
                text = NoticeText.cut("${r.start}~${r.end}" + (r.room?.let { " · $it" } ?: "") + (r.note?.let { " · ${NoticeText.clean(it)}" } ?: ""), NoticeText.LINE_MAX),
                url = r.courseId?.takeIf { it > 0 }?.let { "${NoticeText.ETL_PREFIX}courses/$it" },
                tag = key(day.date, r),
                firedKeys = listOf(key(day.date, r)),
            )
        }

    /** 다음 수업 알림 시각(오늘·내일 중 가장 이른 것). 이때 확인을 한 번 더 돌린다. */
    fun nextAt(days: List<TimetableLogic.Day>, now: Instant, zone: ZoneId, leadMin: Int, fired: Set<String>): Instant? =
        days.flatMap { d -> d.classes.filter { key(d.date, it) !in fired }.map { startAt(d.date, it.start, zone).minus(Duration.ofMinutes(leadMin.toLong())) } }
            .filter { it.isAfter(now) }
            .minOrNull()
}

class WatchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notify_watch", Context.MODE_PRIVATE)
    @Synchronized fun load(): WatchState = WatchState.fromJson(prefs.getString("state", null))
    @Synchronized fun save(s: WatchState) { prefs.edit().putString("state", s.toJson()).apply() }
}
