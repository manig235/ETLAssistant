package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.CourseAssignments
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.EtlLogic.obj
import kr.snu.etlassistant.etl.EtlLogic.str
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime

/**
 * eTL을 조회해 [Snapshot]을 만든다. 네트워크는 [EtlClient]만 쓴다(GET 전용).
 * [EtlTools]의 digest와 같은 의미를 따르되, 알림용으로 필터 없이 전체 과제·공지를 돌려준다.
 * JSON → 모델 변환은 네트워크 없이 테스트할 수 있도록 순수 함수로 분리했다.
 */
object SnapshotFetcher {


    /**
     * [EtlAuthException]은 그대로 던진다(토큰 거부는 사용자에게 알려야 한다).
     * 그 외 [EtlException]은 해당 강의(또는 공지)만 건너뛰고 [Snapshot.errors]에 모은다.
     */
    fun fetch(
        client: EtlClient,
        now: Instant,
        announcementDays: Int = 2,
        needsSubmissionDetail: (AssignmentItem) -> Boolean = { false },
    ): Snapshot {
        val courses = CourseAssignments.currentCourses(client)
        val errors = mutableListOf<String>()

        // ---- 과제 (강의별 병렬, 동시 요청 수는 EtlClient가 제한) ----
        val assignments = mutableListOf<AssignmentItem>()
        var detailBudget = MAX_SUBMISSION_DETAIL
        for (r in retryFailed(client, CourseAssignments.fetch(client, courses))) {
            if (r.error != null) errors += "${r.courseName}: ${r.error}"
            else assignments += mapAssignments(r.courseId, r.courseName, r.assignments, now).map { item ->
                if (detailBudget <= 0 || item.check?.attachmentsKnown != false || !needsSubmissionDetail(item)) return@map item
                detailBudget--
                withSubmissionDetail(client, item, r.assignments, now)
            }
        }

        // ---- 공지 ----
        var announcements: List<AnnouncementItem> = emptyList()
        if (courses.isNotEmpty()) {
            try {
                val (start, end) = kstDateRange(now, announcementDays)
                val ctx = courses.joinToString("&") { "context_codes[]=course_${it.optLong("id")}" }
                val items = client.getAll("/api/v1/announcements?$ctx&start_date=$start&end_date=$end&per_page=50")
                val names = courses.associate { it.optLong("id") to it.str("name").orEmpty() }
                announcements = mapAnnouncements(items, names, now, announcementDays)
            } catch (e: EtlAuthException) {
                throw e
            } catch (e: EtlException) {
                errors += "공지: ${e.message}"
            }
        }

        val refs = courses.map { CourseRef(it.optLong("id"), it.str("name").orEmpty()) }
        return Snapshot(now, sortAssignments(assignments), announcements, errors, refs)
    }

    /** 한 번 확인에서 `submissions/self`를 더 받는 과제 수 상한(대개 0~1개). */
    private const val MAX_SUBMISSION_DETAIL = 5

    /**
     * 파일 업로드 제출인데 과제 목록에 첨부 목록이 없을 때, 내 제출(`submissions/self`, GET)을 한 번 받아 다시 판정한다.
     * 실패하면 그대로 둔다(첨부를 모르는 채로 두면 [AssignmentWatch]가 다음 확인에서 다시 시도한다).
     */
    private fun withSubmissionDetail(client: EtlClient, item: AssignmentItem, raw: List<JSONObject>, now: Instant): AssignmentItem {
        val a = raw.firstOrNull { it.optLong("id") == item.assignmentId } ?: return item
        return try {
            val s = client.getObject(kr.snu.etlassistant.etl.SubmissionCheck.submissionPath(item.courseId, item.assignmentId))
            kr.snu.etlassistant.etl.SubmissionCheck.evaluate(a, s, now)
                ?.let { item.copy(check = it, submittedAt = EtlLogic.parseInstant(s.str("submitted_at")) ?: item.submittedAt) } ?: item
        } catch (e: EtlAuthException) {
            throw e
        } catch (_: EtlException) {
            item
        }
    }

    /**
     * 연결 끊김·시간 초과로 실패한 강의만 한 번 더(차례로) 받는다. 백그라운드에서는 동시에 보낸 요청 몇 개가
     * 끊기는 일이 있다. 토큰 거부는 그대로 던진다.
     */
    private fun retryFailed(client: EtlClient, results: List<CourseAssignments.CourseResult>): List<CourseAssignments.CourseResult> =
        results.map { r ->
            if (r.error == null || !r.retryable) return@map r
            try {
                r.copy(assignments = client.getAll(CourseAssignments.assignmentsPath(r.courseId)), error = null, retryable = false)
            } catch (e: EtlAuthException) {
                throw e
            } catch (e: EtlException) {
                r.copy(error = e.message ?: r.error)
            }
        }

    /** 마감 오름차순(마감 없음은 마지막). 안정 정렬이라 같은 마감끼리는 강의 순서가 유지된다. */
    fun sortAssignments(items: List<AssignmentItem>): List<AssignmentItem> =
        items.sortedWith(compareBy { it.due ?: Instant.MAX })

    /** 한 강의의 과제 JSON을 [AssignmentItem]으로 바꾼다. 미게시(`published == false`) 과제는 제외한다. */
    fun mapAssignments(courseId: Long, courseName: String, items: List<JSONObject>, now: Instant): List<AssignmentItem> =
        items.mapNotNull { a ->
            if (a.has("published") && !a.optBoolean("published", true)) return@mapNotNull null
            AssignmentItem(
                courseId = courseId,
                courseName = courseName,
                assignmentId = a.optLong("id"),
                name = a.str("name")?.takeIf { it.isNotBlank() } ?: "(제목 없음)",
                due = EtlLogic.parseInstant(a.str("due_at")),
                online = EtlLogic.isOnline(a),
                points = if (!a.has("points_possible") || a.isNull("points_possible")) null else a.optDouble("points_possible").takeIf { !it.isNaN() },
                url = a.str("html_url"),
                submitted = EtlLogic.isSubmitted(a),
                missing = EtlLogic.isMissing(a, now),
                score = if (kr.snu.etlassistant.etl.Queries.isGraded(a)) a.obj("submission")?.optDouble("score")?.takeIf { !it.isNaN() } else null,
                submittedAt = EtlLogic.parseInstant(a.obj("submission")?.str("submitted_at")),
                check = kr.snu.etlassistant.etl.SubmissionCheck.evaluate(a, a.obj("submission"), now),
            )
        }

    /**
     * 공지 JSON을 [AnnouncementItem]으로 바꾼다(최신순). `posted_at`이 [now] − [announcementDays]일보다 이전이면 제외하고,
     * `posted_at`이 없으면 [now]로 본다. [courseNames]에 없는 강의 ID는 숫자 문자열을 이름으로 쓴다.
     */
    fun mapAnnouncements(
        items: List<JSONObject>,
        courseNames: Map<Long, String>,
        now: Instant,
        announcementDays: Int,
    ): List<AnnouncementItem> {
        val cutoff = now.minusSeconds(announcementDays * 86400L)
        return items
            .map { it to (EtlLogic.parseInstant(it.str("posted_at")) ?: now) }
            .filter { !it.second.isBefore(cutoff) }
            .sortedByDescending { it.second }
            .map { (a, posted) ->
                val cid = (a.str("context_code") ?: "course_0").substringAfterLast('_').toLongOrNull() ?: 0L
                AnnouncementItem(
                    id = a.optLong("id"),
                    courseId = cid,
                    courseName = courseNames[cid] ?: cid.toString(),
                    title = a.str("title")?.takeIf { it.isNotBlank() } ?: "(제목 없음)",
                    postedAt = posted,
                    url = a.str("html_url") ?: a.str("url"),
                    text = EtlLogic.htmlToText(a.str("message"), 600),
                )
            }
    }

    /** 공지 조회 기간: (now − days일, now + 1일)의 KST 날짜 `YYYY-MM-DD` 쌍. */
    fun kstDateRange(now: Instant, days: Int): Pair<String, String> {
        val kstDate = { t: Instant -> ZonedDateTime.ofInstant(t, EtlLogic.KST).toLocalDate().toString() }
        return kstDate(now.minusSeconds(days * 86400L)) to kstDate(now.plusSeconds(86400L))
    }
}
