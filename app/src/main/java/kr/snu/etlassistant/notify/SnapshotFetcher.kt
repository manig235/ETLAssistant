package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.EtlLogic.str
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * eTL을 조회해 [Snapshot]을 만든다. 네트워크는 [EtlClient]만 쓴다(GET 전용).
 * [EtlTools]의 digest와 같은 의미를 따르되, 알림용으로 필터 없이 전체 과제·공지를 돌려준다.
 * JSON → 모델 변환은 네트워크 없이 테스트할 수 있도록 순수 함수로 분리했다.
 */
object SnapshotFetcher {

    private const val COURSES_PATH = "/api/v1/courses?enrollment_state=active&include[]=total_scores&include[]=term&per_page=100"
    private const val POOL_SIZE = 6

    /**
     * [EtlAuthException]은 그대로 던진다(토큰 거부는 사용자에게 알려야 한다).
     * 그 외 [EtlException]은 해당 강의(또는 공지)만 건너뛰고 [Snapshot.errors]에 모은다.
     */
    fun fetch(client: EtlClient, now: Instant, announcementDays: Int = 2): Snapshot {
        val courses = EtlLogic.currentCourses(client.getAll(COURSES_PATH))
        val errors = mutableListOf<String>()

        // ---- 과제 (강의별 병렬) ----
        val assignments = mutableListOf<AssignmentItem>()
        val pool = Executors.newFixedThreadPool(POOL_SIZE)
        try {
            val futures = courses.map { course ->
                val cid = course.optLong("id")
                pool.submit(Callable<List<JSONObject>> {
                    client.getAll("/api/v1/courses/$cid/assignments?include[]=submission&order_by=due_at&per_page=100")
                })
            }
            courses.forEachIndexed { i, course ->
                val name = course.str("name").orEmpty()
                try {
                    val items = futures[i].get()
                    assignments += mapAssignments(course.optLong("id"), name, items, now)
                } catch (e: ExecutionException) {
                    when (val cause = e.cause) {
                        is EtlAuthException -> throw cause
                        is EtlException -> errors += "$name: ${cause.message}"
                        // 다른 예외의 내용은 노출하지 않는다.
                        else -> errors += "$name: 과제 조회 실패"
                    }
                }
            }
        } finally {
            pool.shutdownNow()
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

        return Snapshot(now, sortAssignments(assignments), announcements, errors)
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
