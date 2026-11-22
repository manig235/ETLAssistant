package kr.snu.etlassistant.notify

import java.time.Instant

/**
 * 백그라운드 확인에서 eTL 조회가 실패했을 때 마지막으로 성공한 과제 목록([SnapshotCache])으로 메운다(순수 함수).
 *
 * 앱이 백그라운드에 있으면 삼성 절전 등으로 eTL 요청이 끊기거나 늦어질 수 있다(앱 안에서는 과제가 보이는데
 * 브리핑에는 "일부 항목을 조회하지 못했어요"만 뜬 사례). 이미 아는 과제의 마감 알림·브리핑은 이 폴백으로 계속 보낸다.
 * 새 공지·새 과제·마감 변경 감지는 실제로 조회됐을 때만 한다(호출자 몫).
 */
object SnapshotFallback {
    /** 이보다 오래된 저장본은 쓰지 않는다(제출 여부·마감 변경을 너무 오래 모르는 상태가 된다). */
    const val MAX_AGE_MS = 3L * 24 * 3600 * 1000

    /** [cachedAt]: 저장본을 하나라도 썼으면 그 저장 시각(알림에 "N시간 전 정보"로 보인다), 안 썼으면 null. */
    data class Result(val snapshot: Snapshot, val cachedAt: Instant?)

    /**
     * [fresh]: 이번 조회 결과(내 마감을 합치기 전). [totalFailure]면 강의 목록부터 받지 못한 것이다.
     * 부분 실패면 실패한 강의(오류 문구 `강의명: …`)의 과제만 저장본에서 가져온다. 공지 실패(`공지: …`)는 메우지 않는다.
     */
    fun apply(fresh: Snapshot, cached: CachedSnapshot?, now: Instant, totalFailure: Boolean): Result {
        val usable = cached?.takeIf { usable(it, now) } ?: return Result(fresh, null)
        val old = usable.assignments.filter { it.courseId != MyDeadlines.COURSE_ID }.map { refresh(it, now) }
        if (totalFailure) {
            if (old.isEmpty()) return Result(fresh, null)
            return Result(fresh.copy(assignments = SnapshotFetcher.sortAssignments(old)), Instant.ofEpochMilli(usable.takenAt))
        }
        val failed = failedCourses(fresh.errors)
        if (failed.isEmpty()) return Result(fresh, null)
        val have = fresh.assignments.map { it.courseId }.toSet()
        val add = old.filter { it.courseName in failed && it.courseId !in have }
        if (add.isEmpty()) return Result(fresh, null)
        return Result(
            fresh.copy(assignments = SnapshotFetcher.sortAssignments(fresh.assignments + add)),
            Instant.ofEpochMilli(usable.takenAt),
        )
    }

    /**
     * 연결 끊김으로 실패한 항목이 있는가(`eTL 연결 실패: …`, 시간 초과 포함). 백그라운드에서는 작업이 끊긴 뒤 몇 초 만에
     * 네트워크가 다시 막혀 요청 일부만 실패하는 일이 있다(삼성 OLAF가 작업을 멈춘 뒤 앱 안에서 이어 돌 때, 2026-11-22 S938N 실측).
     */
    fun hasConnectionErrors(errors: List<String>): Boolean = errors.any { "eTL 연결 실패" in it }

    fun usable(c: CachedSnapshot, now: Instant): Boolean {
        val age = now.toEpochMilli() - c.takenAt
        return c.takenAt > 0 && age >= -60_000L && age <= MAX_AGE_MS
    }

    /** 오류 문구에서 실패한 강의명. 공지·토큰 같은 강의 밖 오류는 뺀다. */
    fun failedCourses(errors: List<String>): Set<String> =
        errors.filter { it.contains(':') && !it.startsWith("공지:") }.map { it.substringBefore(':') }.toSet()

    /** 저장 뒤 마감이 지난 미제출 온라인 과제는 누락으로 본다(조회했다면 eTL도 그렇게 판정한다). */
    private fun refresh(a: AssignmentItem, now: Instant): AssignmentItem =
        if (!a.missing && !a.submitted && a.online && a.due != null && a.due.isBefore(now)) a.copy(missing = true) else a

    /** `3시간`, `25분`, `2일` (저장본이 얼마나 오래됐는지). */
    fun ageLabel(cachedAt: Instant, now: Instant): String {
        val mins = ((now.toEpochMilli() - cachedAt.toEpochMilli()) / 60_000L).coerceAtLeast(0)
        return when {
            mins < 1 -> "방금"
            mins < 60 -> "${mins}분 전"
            mins < 48 * 60 -> "${mins / 60}시간 전"
            else -> "${mins / (24 * 60)}일 전"
        }
    }

    /** 알림·브리핑 끝에 붙이는 한 줄. */
    fun staleLine(cachedAt: Instant, now: Instant): String =
        "※ eTL에서 지금 받지 못한 과제는 ${ageLabel(cachedAt, now)} 받아 둔 정보예요"
}
