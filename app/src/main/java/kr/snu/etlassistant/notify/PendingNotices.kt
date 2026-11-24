package kr.snu.etlassistant.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * 아직 넣지 않은 시험·과제·동영상 강의·보강·휴강 공지(2026-11-24, 사용자 요청). 알림 확인 때 받은 공지 중 그런 공지만 작게 남겨 두고(최대 50개, 14일),
 * 채팅 화면을 열 때 하단 시트로 보여 준다. 화면을 열 때 네트워크 요청은 하지 않는다.
 */
data class PendingNotice(
    val id: Long,
    val courseId: Long,
    val courseName: String,
    val title: String,
    val postedAt: Long,
    val kind: MyKind,
    /** [ScheduleDraft.toJson]. */
    val draft: String,
    /** "일정 정리 부탁"으로 입력창에 채울 문구. */
    val prefill: String,
)

object PendingNoticesLogic {
    const val MAX = 50
    val KEEP: Duration = Duration.ofDays(14)

    /** 공지 목록에서 시험·과제 공지를 골라 저장 목록에 더한다(같은 ID는 새 값으로, 14일·50개). */
    fun add(list: List<PendingNotice>, anns: List<AnnouncementItem>, now: Instant): List<PendingNotice> {
        val fresh = anns.mapNotNull { a ->
            val d = ScheduleDrafts.fromAnnouncement(a) ?: return@mapNotNull null
            PendingNotice(a.id, a.courseId, a.courseName, a.title, a.postedAt.toEpochMilli(), d.kind, d.toJson(), d.prefill)
        }
        val ids = fresh.map { it.id }.toSet()
        val cutoff = now.minus(KEEP).toEpochMilli()
        return (fresh + list.filter { it.id !in ids }).filter { it.postedAt >= cutoff }.sortedByDescending { it.postedAt }.take(MAX)
    }

    /**
     * 보여 줄 공지: 최근 14일, 같은 공지로 넣은 내 마감·시간표 보강·휴강이 없고([registered]), 무시하지 않았고,
     * 숨긴 과목·알림 끈 과목이 아닌 것. 최신순.
     */
    fun targets(list: List<PendingNotice>, registered: Set<Long>, ignored: Set<Long>, excludedCourse: (Long) -> Boolean, now: Instant): List<PendingNotice> {
        val cutoff = now.minus(KEEP).toEpochMilli()
        return list.filter { it.postedAt >= cutoff && it.id !in registered && it.id !in ignored && !excludedCourse(it.courseId) }
            .sortedByDescending { it.postedAt }
    }

    /** 자동으로 띄울까: 켜져 있고, 업데이트 팝업이 뜨는 실행이 아니고, 아직 본 적 없는 공지가 있을 때만. */
    fun shouldAutoShow(targets: List<PendingNotice>, seen: Set<Long>, enabled: Boolean, updatePromptShowing: Boolean): Boolean =
        enabled && !updatePromptShowing && targets.any { it.id !in seen }

    fun toJson(list: List<PendingNotice>): String = JSONArray().also { a ->
        list.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("cid", p.courseId).put("course", p.courseName).put("title", p.title).put("at", p.postedAt)
                .put("kind", p.kind.id).put("draft", p.draft).put("prefill", p.prefill))
        }
    }.toString()

    fun fromJson(s: String?): List<PendingNotice> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            PendingNotice(o.optLong("id"), o.optLong("cid"), o.optString("course"), o.optString("title"), o.optLong("at"),
                MyKind.parse(o.optString("kind")) ?: MyKind.DEADLINE, o.optString("draft"), o.optString("prefill"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun ids(s: String?): Set<Long> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.optLong(it) }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

class PendingNoticeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pending_notices", Context.MODE_PRIVATE)

    @Synchronized fun load(): List<PendingNotice> = PendingNoticesLogic.fromJson(prefs.getString("items", null))

    @Synchronized fun record(anns: List<AnnouncementItem>, now: Instant) {
        if (anns.isEmpty()) return
        prefs.edit().putString("items", PendingNoticesLogic.toJson(PendingNoticesLogic.add(load(), anns, now))).apply()
    }

    fun ignored(): Set<Long> = PendingNoticesLogic.ids(prefs.getString("ignored", null))
    fun seen(): Set<Long> = PendingNoticesLogic.ids(prefs.getString("seen", null))

    /** 무시: 다시 보이지 않는다. */
    @Synchronized fun ignore(id: Long) {
        prefs.edit().putString("ignored", JSONArray((ignored() + id).toList().takeLast(200)).toString()).apply()
    }

    /** 본 것으로 표시(새 공지가 생길 때까지 자동으로 다시 뜨지 않는다). */
    @Synchronized fun markSeen(ids: Collection<Long>) {
        prefs.edit().putString("seen", JSONArray((seen() + ids).toList().takeLast(200)).toString()).apply()
    }

    /** 지금 보여 줄 공지(숨긴 과목·알림 끈 과목 제외). */
    fun targets(context: Context, now: Instant = Instant.now()): List<PendingNotice> {
        val courses = CourseFilterStore(context).load()
        return PendingNoticesLogic.targets(load(), kr.snu.etlassistant.chat.DraftIntents.registeredIds(context), ignored(), { courses.muted(it) }, now)
    }
}
