package kr.snu.etlassistant.chat

import android.content.Context
import android.content.Intent
import kr.snu.etlassistant.etl.TimetableChangeStore
import kr.snu.etlassistant.notify.MyDeadlineStore
import kr.snu.etlassistant.notify.ScheduleDraft

/**
 * 공지 초안([ScheduleDraft])을 여는 곳(2026-11-24): 시험·과제·동영상 강의는 내 일정(내 마감 추가 창), 보강·휴강은 시간표(보강·휴강 추가 창).
 * 알림 버튼, 알림 기록, 채팅의 "등록 안 한 공지" 시트가 함께 쓴다.
 */
object DraftIntents {
    const val EXTRA_DRAFT = "kr.snu.etlassistant.extra.SCHEDULE_DRAFT"

    fun open(context: Context, draftJson: String): Intent {
        val d = ScheduleDraft.fromJson(draftJson)
        val target = if (d?.timetable == true) TimetableActivity::class.java else MyDeadlinesActivity::class.java
        return Intent(context, target).putExtra(EXTRA_DRAFT, draftJson).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /** 같은 공지로 이미 넣었는가(내 마감 또는 시간표 보강·휴강). */
    fun registeredIds(context: Context): Set<Long> =
        MyDeadlineStore(context).load().mapNotNull { it.sourceAnnouncementId }.toSet() +
            TimetableChangeStore(context).load().mapNotNull { it.sourceAnnouncementId }.toSet()
}
