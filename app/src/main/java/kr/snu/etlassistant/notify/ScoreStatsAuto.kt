package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.ScoreSource
import kr.snu.etlassistant.etl.ScoreStatEntry
import kr.snu.etlassistant.etl.ScoreStats
import kr.snu.etlassistant.etl.ScoreStatsParser
import kr.snu.etlassistant.etl.ScoreStatsStore

/**
 * 성적 공지 → 성적 통계 자동 저장(2026-11-28, 순수 함수). 공지 본문을 기기 안에서 깔끔하게 읽었을 때만([ScoreStatsParser.Result.confident])
 * 항목을 만들고, 같은 과목의 eTL 과제 이름이 시험 이름과 맞으면(중간고사 ↔ Midterm, Exam 1 ↔ I2EP_Exam_1) 내 점수·배점을 붙인다.
 * 맞는 과제가 없거나 배점이 다르면 내 점수는 비워 두고, 사용자가 성적 통계 화면에서 넣거나 비서가 채운다.
 */
object ScoreStatsAuto {
    private const val EPS = 1e-6
    const val MAX_LINES = 3

    fun id(annId: Long, item: ScoreStatsParser.Item) = "ann:$annId:${item.key ?: "x"}:${item.section ?: 0}"

    /** 과목명 끝의 분반 "(001)" → 1. */
    fun mySection(courseName: String): Int? = Regex("\\((\\d{3})\\)\\s*$").find(courseName)?.groupValues?.get(1)?.toIntOrNull()

    fun entries(a: AnnouncementItem, assignments: List<AssignmentItem>, nowMs: Long): List<ScoreStatEntry> {
        val r = a.parsedStats?.takeIf { it.confident } ?: return emptyList()
        var items = r.items
        // 분반별 통계면 내 분반(과목명의 "(001)")만 남긴다. 내 분반이 없으면 모두 남기고 내 점수는 붙이지 않는다.
        val sections = items.mapNotNull { it.section }.toSet()
        val mine = mySection(a.courseName)
        val sectionKnown = sections.isEmpty() || (mine != null && mine in sections)
        if (sections.isNotEmpty() && mine != null && mine in sections) items = items.filter { it.section == null || it.section == mine }
        val course = assignments.filter { it.courseId == a.courseId }
        return items.map { item ->
            val match = item.key?.let { k -> course.filter { ScoreStatsParser.examKey(it.name)?.first == k }.singleOrNull() }
                ?.takeIf { sectionKnown && compatible(item, it) }
            ScoreStatEntry(
                id = id(a.id, item), source = ScoreSource.ANNOUNCEMENT, courseId = a.courseId, courseName = a.courseName,
                assignmentId = match?.assignmentId,
                title = (item.label ?: cleanTitle(a.title)) + (item.section?.let { " (${it}분반)" } ?: ""),
                stats = item.stats, myScore = match?.score, pointsPossible = item.points ?: match?.points,
                fetchedAt = nowMs, sourceAnnouncementId = a.id,
            )
        }
    }

    /** 공지의 분포와 eTL 과제의 점수가 같은 기준(배점)인가. 공지에 만점이 없으면 분포 값이 eTL 배점을 넘지 않을 때만. */
    fun compatible(item: ScoreStatsParser.Item, a: AssignmentItem): Boolean {
        val p = a.points ?: return item.points == null
        if (item.points != null) return kotlin.math.abs(item.points - p) < EPS
        return listOfNotNull(item.stats.max, item.stats.mean, item.stats.q3, item.stats.median).all { it <= p + EPS }
    }

    private fun cleanTitle(t: String) = t.replace(Regex("^\\s*(?:[\\[(【][^\\])】]{0,30}[\\])】]\\s*)+"), "").trim().take(40).ifEmpty { "성적" }

    /** 공지 알림에 붙일 줄. 예: "통계 저장됨 · 평균 62.4 · 내 점수 71 · 상위 약 30~40%". */
    fun lines(entries: List<ScoreStatEntry>): List<String> = entries.take(MAX_LINES).map { e ->
        val head = if (entries.size > 1) "통계 저장됨 · ${e.title}" else "통계 저장됨"
        val mean = e.stats.mean?.let { "평균 ${ScoreStats.num(it)}" } ?: e.stats.median?.let { "중간 ${ScoreStats.num(it)}" }
        val rest = if (e.myScore != null) ScoreStats.summaryLine(e.stats, e.myScore)
            else listOfNotNull(mean, "내 점수를 넣으면 위치를 알려 드려요").joinToString(" · ")
        "$head · $rest"
    }

    /** 확인 작업: 새로 읽힌 공지 통계를 저장한다(이미 있거나 사용자가 지운 항목은 건드리지 않는다). */
    fun store(store: ScoreStatsStore, announcements: List<AnnouncementItem>, assignments: List<AssignmentItem>, nowMs: Long) {
        val all = announcements.flatMap { entries(it, assignments, nowMs) }
        if (all.isNotEmpty()) store.addNew(all)
    }
}
