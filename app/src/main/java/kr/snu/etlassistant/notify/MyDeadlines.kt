package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.AssignmentQuery
import kr.snu.etlassistant.etl.EtlLogic
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * 사용자가 직접 등록한 마감("내 마감"). eTL과 무관한 일정(장학금 서류, 동아리 회의 등)도 넣을 수 있다.
 * 폰 안에만 저장하고([MyDeadlineStore]), 알림·브리핑에서는 [MyDeadlines.toItems]로 [AssignmentItem]처럼 다뤄
 * 마감 알림 시점·방해 금지·중복 방지·브리핑 로직을 그대로 쓴다.
 */
data class MyDeadline(
    /** 만든 시각(epoch millis)을 쓴다. 지운 항목의 알림 기록 키와 새 항목이 겹치지 않게 재사용하지 않는다. */
    val id: Long,
    val title: String,
    val due: Instant,
    val memo: String = "",
    val done: Boolean = false,
    /** 사용자가 정한 알림 시각들(예: 마감 22:00에 21:00·21:50). 설정의 기본 알림 시점과 별개로, 방해 금지와 무관하게 울린다. */
    val reminders: List<Instant> = emptyList(),
)

object MyDeadlines {
    /** 알림 키(`deadline-m:{courseId}:{id}:{분}`)에서 eTL 강의 ID와 겹치지 않는 값. */
    const val COURSE_ID = -1L
    const val COURSE_NAME = "내 마감"
    const val TITLE_MAX = 60
    const val MEMO_MAX = 200
    const val MAX_ITEMS = 100
    const val MAX_REMINDERS = 5

    /** 알림·브리핑용으로 바꾼다. 완료한 항목은 "제출됨", 지난 미완료 항목은 "누락"으로 본다. */
    fun toItems(list: List<MyDeadline>, now: Instant): List<AssignmentItem> = list.map { d ->
        AssignmentItem(
            courseId = COURSE_ID,
            courseName = COURSE_NAME,
            assignmentId = d.id,
            name = d.title,
            due = d.due,
            online = true, // 알림 문구에 "(오프라인)"을 붙이지 않는다
            points = null,
            url = null,
            submitted = d.done,
            missing = !d.done && !d.due.isAfter(now),
        )
    }

    /** eTL 조회 결과에 내 마감을 더한다(마감순 정렬). */
    fun merge(snapshot: Snapshot, mine: List<MyDeadline>, now: Instant): Snapshot =
        if (mine.isEmpty()) snapshot
        else snapshot.copy(assignments = SnapshotFetcher.sortAssignments(snapshot.assignments + toItems(mine, now)))

    /** 입력 확인. 문제가 없으면 null, 있으면 사용자에게 보일 문구. */
    fun validate(title: String, memo: String, countWithoutThis: Int): String? = when {
        title.isBlank() -> "제목을 입력하세요"
        title.trim().length > TITLE_MAX -> "제목은 ${TITLE_MAX}자까지 쓸 수 있어요"
        memo.trim().length > MEMO_MAX -> "메모는 ${MEMO_MAX}자까지 쓸 수 있어요"
        countWithoutThis >= MAX_ITEMS -> "내 마감은 ${MAX_ITEMS}개까지 등록할 수 있어요. 지난 항목을 지워 주세요"
        else -> null
    }

    /** 알림 시각 확인: 마감보다 앞이어야 하고 최대 [MAX_REMINDERS]개. 문제가 없으면 null. */
    fun validateReminders(reminders: List<Instant>, due: Instant): String? = when {
        reminders.size > MAX_REMINDERS -> "알림 시각은 ${MAX_REMINDERS}개까지 정할 수 있어요"
        reminders.any { !it.isBefore(due) } -> "알림 시각은 마감보다 앞이어야 해요"
        else -> null
    }

    /** 정리: 분 단위로 맞추고 중복 제거, 시각순. */
    fun normalizeReminders(reminders: List<Instant>): List<Instant> =
        reminders.map { Instant.ofEpochSecond(it.epochSecond / 60 * 60) }.distinct().sorted()

    /**
     * 알림 시각 인자: "YYYY-MM-DD HH:MM", 또는 "HH:MM"(마감 날짜의 그 시각; 마감보다 늦으면 전날).
     * 못 읽으면 null.
     */
    fun parseReminder(s: String?, due: Instant): Instant? {
        val t = s?.trim() ?: return null
        parseDue(t)?.takeIf { t.length > 10 }?.let { return it }
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t) ?: return null
        return try {
            val dueDate = due.atZone(EtlLogic.KST).toLocalDate()
            val at = dueDate.atTime(m.groupValues[1].toInt(), m.groupValues[2].toInt()).atZone(EtlLogic.KST).toInstant()
            if (at.isBefore(due)) at else at.minusSeconds(86400)
        } catch (_: Exception) {
            null
        }
    }

    fun reminderKey(d: MyDeadline, at: Instant) = "mine-remind:${d.id}:${at.epochSecond / 60}"

    /** 지금 울릴 알림: 시각이 지났고 마감 전이며 아직 안 보낸 것(폰이 꺼져 있었으면 마감 전까지는 늦게라도 보낸다). */
    fun reminderNotices(list: List<MyDeadline>, now: Instant, fired: Set<String>): List<Notice> = list.flatMap { d ->
        if (d.done || !d.due.isAfter(now)) return@flatMap emptyList()
        val due = d.reminders.filter { !it.isAfter(now) && reminderKey(d, it) !in fired }
        if (due.isEmpty()) emptyList() else listOf(
            Notice(
                channel = Channel.DEADLINE,
                title = NoticeText.cut("알림 · ${NoticeText.clean(d.title)}", NoticeText.TITLE_MAX),
                text = NoticeText.cut("마감 ${NoticeText.dateLabel(d.due)} (${EtlLogic.remaining(d.due, now)})" +
                    (if (d.memo.isNotBlank()) " · ${NoticeText.clean(d.memo)}" else ""), NoticeText.LINE_MAX),
                tag = "mine-remind:${d.id}",
                firedKeys = due.map { reminderKey(d, it) },
            )
        )
    }

    /** 다음 알림 시각(없으면 null). */
    fun nextReminderAt(list: List<MyDeadline>, now: Instant, fired: Set<String>): Instant? =
        list.filter { !it.done && it.due.isAfter(now) }
            .flatMap { d -> d.reminders.filter { it.isAfter(now) && it.isBefore(d.due) && reminderKey(d, it) !in fired } }
            .minOrNull()

    fun reminderLabel(d: MyDeadline): String = d.reminders.joinToString(", ") { r ->
        val z = r.atZone(EtlLogic.KST)
        val sameDay = z.toLocalDate() == d.due.atZone(EtlLogic.KST).toLocalDate()
        if (sameDay) "%02d:%02d".format(z.hour, z.minute) else NoticeText.dateLabel(r)
    }

    /** 화면 순서: 미완료(마감 가까운 순) → 완료(최근 마감 순). */
    fun sorted(list: List<MyDeadline>): List<MyDeadline> =
        list.filter { !it.done }.sortedWith(compareBy({ it.due }, { it.id })) +
            list.filter { it.done }.sortedWith(compareByDescending<MyDeadline> { it.due }.thenBy { it.id })

    /**
     * 비서 도구 `my_deadlines`의 결과(읽기 전용). [window]가 null이면 기간 제한 없음.
     * 지난 미완료 항목은 기간과 관계없이 넣는다(먼저 알려야 하므로). [includeDone]이 false면 완료 항목을 뺀다.
     */
    fun toolResult(list: List<MyDeadline>, now: Instant, window: AssignmentQuery.Window?, includeDone: Boolean): JSONObject {
        val rows = JSONArray()
        list.filter { d ->
            val overdue = !d.done && !d.due.isAfter(now)
            (includeDone || !d.done) && (window == null || overdue || window.contains(d.due))
        }.sortedWith(compareBy({ it.due }, { it.id })).forEach { d ->
            rows.put(
                JSONObject()
                    .put("id", d.id)
                    .put("title", d.title)
                    .put("due_kst", EtlLogic.fmtKst(d.due))
                    .put("remaining", EtlLogic.remaining(d.due, now))
                    .put("done", d.done)
                    .put("overdue", !d.done && !d.due.isAfter(now))
                    .apply { if (d.reminders.isNotEmpty()) put("reminders_kst", JSONArray(d.reminders.map { EtlLogic.fmtKst(it) })) }
                    .apply { if (d.memo.isNotBlank()) put("memo", d.memo) }
            )
        }
        return JSONObject()
            .put("now_kst", EtlLogic.fmtKst(now))
            .put("source", "사용자가 앱의 '내 마감'에 직접 등록한 마감(eTL 과제 아님)")
            .put("count", rows.length())
            .put("items", rows)
    }

    /** "YYYY-MM-DD HH:MM"(또는 T 구분, 초 허용)을 KST 시각으로. 날짜만 오면 그날 23:59. 못 읽으면 null. */
    fun parseDue(s: String?): Instant? {
        val t = s?.trim()?.replace('T', ' ') ?: return null
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:\\s+(\\d{1,2}):(\\d{2})(?::\\d{2})?)?$").find(t) ?: return null
        return try {
            val date = java.time.LocalDate.parse(m.groupValues[1])
            val time = if (m.groupValues[2].isEmpty()) java.time.LocalTime.of(23, 59)
            else java.time.LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt())
            date.atTime(time).atZone(EtlLogic.KST).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    /** 채팅에 보일 한 줄: `장학금 서류 · 11/23(월) 23:59`. */
    fun line(d: MyDeadline): String = "${d.title} · ${NoticeText.dateLabel(d.due)}"

    fun toJson(list: List<MyDeadline>): String = JSONArray().also { arr ->
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("title", d.title)
                    .put("due", d.due.toEpochMilli())
                    .put("memo", d.memo)
                    .put("done", d.done)
                    .put("reminders", JSONArray(d.reminders.map { it.toEpochMilli() }))
            )
        }
    }.toString()

    /** 깨진 항목은 건너뛴다. 전체가 깨져 있으면 빈 목록. */
    fun fromJson(s: String?): List<MyDeadline> {
        if (s.isNullOrEmpty()) return emptyList()
        val arr = try { JSONArray(s) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<MyDeadline>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            val title = o.optString("title", "").trim()
            if (id == 0L || title.isEmpty() || !o.has("due")) continue
            out += MyDeadline(
                id = id,
                title = title.take(TITLE_MAX),
                due = Instant.ofEpochMilli(o.optLong("due")),
                memo = o.optString("memo", "").take(MEMO_MAX),
                done = o.optBoolean("done", false),
                reminders = o.optJSONArray("reminders")?.let { a -> (0 until a.length()).map { Instant.ofEpochMilli(a.optLong(it)) } }.orEmpty(),
            )
        }
        return out.distinctBy { it.id }
    }
}

/** 앱 내부 SharedPreferences에 JSON으로 저장한다. */
class MyDeadlineStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("my_deadlines", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<MyDeadline> = MyDeadlines.fromJson(prefs.getString("items", null))

    @Synchronized
    fun save(list: List<MyDeadline>) {
        prefs.edit().putString("items", MyDeadlines.toJson(list)).apply()
        kr.snu.etlassistant.widget.HomeWidgets.dataChanged(app)
    }

    /** 같은 ID면 바꾸고, 없으면 더한다. */
    @Synchronized
    fun upsert(d: MyDeadline) {
        val list = load().filter { it.id != d.id } + d
        save(list)
    }

    @Synchronized
    fun delete(id: Long) {
        save(load().filter { it.id != id })
    }
}
