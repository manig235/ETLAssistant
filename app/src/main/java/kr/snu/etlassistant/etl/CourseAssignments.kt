package kr.snu.etlassistant.etl

import kr.snu.etlassistant.etl.EtlLogic.str
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * 현재 학기 강의와 강의별 과제 조회. 비서 도구(etl_digest, etl_assignments)와 알림(SnapshotFetcher)이 함께 쓴다.
 * 동시 요청 수는 [EtlClient]의 공용 제한을 따른다.
 */
object CourseAssignments {

    const val COURSES_PATH = "/api/v1/courses?enrollment_state=active&include[]=total_scores&include[]=term&per_page=100"

    fun assignmentsPath(courseId: Long) =
        "/api/v1/courses/$courseId/assignments?include[]=submission&order_by=due_at&per_page=100"

    /** 강의 하나의 결과. 조회에 실패하면 [assignments]는 비고 [error]에 짧은 이유가 들어간다. */
    data class CourseResult(
        val course: JSONObject,
        val assignments: List<JSONObject>,
        val error: String?,
        /** 연결 끊김·시간 초과처럼 다시 시도해 볼 만한 실패인가. */
        val retryable: Boolean = false,
    ) {
        val courseId: Long get() = course.optLong("id")
        val courseName: String get() = course.str("name").orEmpty()
    }

    fun currentCourses(client: EtlClient): List<JSONObject> = EtlLogic.currentCourses(client.getAll(COURSES_PATH))

    /**
     * 강의별 과제를 병렬로 모은다. [EtlAuthException](토큰 거부)은 그대로 던지고, 그 밖의 실패는 그 강의만 [CourseResult.error]로 남긴다.
     * 결과 순서는 [courses] 순서와 같다.
     */
    fun fetch(client: EtlClient, courses: List<JSONObject>): List<CourseResult> {
        if (courses.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(courses.size, EtlClient.MAX_CONCURRENT))
        try {
            val futures = courses.map { course ->
                pool.submit(Callable { client.getAll(assignmentsPath(course.optLong("id"))) })
            }
            return courses.mapIndexed { i, course ->
                try {
                    CourseResult(course, futures[i].get(), null)
                } catch (e: ExecutionException) {
                    when (val cause = e.cause) {
                        is EtlAuthException -> throw cause
                        is EtlException -> CourseResult(
                            course, emptyList(), cause.message ?: "과제 조회 실패",
                            retryable = cause.kind == "timeout" || cause.kind == "network",
                        )
                        // 다른 예외의 내용은 노출하지 않는다.
                        else -> CourseResult(course, emptyList(), "과제 조회 실패")
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
