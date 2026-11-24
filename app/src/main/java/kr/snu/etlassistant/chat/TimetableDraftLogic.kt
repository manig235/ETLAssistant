package kr.snu.etlassistant.chat

import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.Queries.AnnCategory
import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.notify.ScheduleDraft
import java.time.LocalDate
import java.time.LocalTime

/** 보강·휴강 공지 초안 → 시간표 보강·휴강 추가 창의 처음 값(순수 함수, 2026-11-24). */
object TimetableDraftLogic {
    data class Prefill(
        val kind: String,
        val course: TimetableLogic.CourseMeetings?,
        val courseText: String,
        val date: LocalDate?,
        val start: LocalTime?,
        val end: LocalTime?,
        val note: String,
    )

    /** 과목은 시간표의 과목과 이름이 맞으면 그 과목으로, 아니면 글로. 보강의 시각은 공지에서 하나만 찾았을 때만. */
    fun prefill(d: ScheduleDraft, courses: List<TimetableLogic.CourseMeetings>): Prefill {
        val kind = if (d.category == AnnCategory.MAKEUP) "보강" else "휴강"
        val course = courses.firstOrNull { TimetableLogic.sameCourse(d.course, it.name) }
        val start = d.start?.atZone(EtlLogic.KST)?.toLocalTime()?.takeIf { kind == "보강" }
        val end = d.end?.atZone(EtlLogic.KST)?.toLocalTime()?.takeIf { kind == "보강" }
        return Prefill(kind, course, d.course, d.day ?: d.start?.atZone(EtlLogic.KST)?.toLocalDate(), start, end, d.memo.take(100))
    }
}
