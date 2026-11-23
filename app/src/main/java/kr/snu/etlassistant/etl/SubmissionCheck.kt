package kr.snu.etlassistant.etl

import kr.snu.etlassistant.etl.EtlLogic.str
import kr.snu.etlassistant.etl.EtlLogic.strList
import org.json.JSONObject
import java.time.Instant

/**
 * 제출 직후 자동 점검(2026-11-23). 파일을 받지 않고 eTL이 준 제출 정보(메타데이터)만 본다:
 * 늦음, 누락 표시, 허용되지 않은 제출 방식, 0바이트 첨부, 허용되지 않은 확장자, 파일 업로드인데 첨부 없음.
 * 판정은 비서 도구 `etl_verify_submission`과 같은 [EtlLogic.evaluateSubmission]/[EtlLogic.checkAttachment]를 쓴다
 * (그 도구는 여기에 더해 첨부를 실제로 내려받아 크기를 맞춰 본다).
 */
data class SubmitCheck(
    /** 늦은 제출(eTL `late` 표시 또는 마감 뒤 제출). 알림은 보내지 않고 화면에만 보인다. */
    val late: Boolean,
    /** 늦음을 뺀 문제(알림 대상). */
    val problems: List<String>,
    /**
     * 첨부 목록을 확인했는가. 파일 업로드 제출인데 과제 목록 응답에 `attachments` 키가 아예 없으면 false다
     * (이때는 `submissions/self`를 한 번 더 받아 다시 판정한다, [SubmissionCheck.evaluate]).
     */
    val attachmentsKnown: Boolean = true,
)

object SubmissionCheck {
    private val LATE = setOf(EtlLogic.LATE_FLAGGED, EtlLogic.LATE_AFTER_DUE)

    /**
     * [a] = 과제 JSON, [s] = 그 제출(과제 목록의 `submission` 또는 `submissions/self`). 제출하지 않았으면 null.
     */
    fun evaluate(a: JSONObject, s: JSONObject?, now: Instant): SubmitCheck? {
        if (s == null) return null
        val ev = EtlLogic.evaluateSubmission(a, s, now)
        if (!ev.submitted) return null
        val problems = ev.problems.filter { it !in LATE }.toMutableList()
        val late = ev.problems.any { it in LATE }
        val attachments = s.optJSONArray("attachments")
        val upload = s.str("submission_type") == "online_upload"
        if (attachments != null) {
            val allowedExt = allowedExtensions(a)
            for (i in 0 until attachments.length()) {
                attachments.optJSONObject(i)?.let { problems += EtlLogic.checkAttachment(it, allowedExt) }
            }
            if (upload && attachments.length() == 0) problems += NO_ATTACHMENT
        }
        return SubmitCheck(late, problems, attachmentsKnown = attachments != null || !upload)
    }

    const val NO_ATTACHMENT = "파일 업로드 과제인데 첨부 파일이 없음"

    fun allowedExtensions(a: JSONObject): List<String> = a.strList("allowed_extensions").map { it.lowercase().trimStart('.') }

    fun submissionPath(courseId: Long, assignmentId: Long) = "/api/v1/courses/$courseId/assignments/$assignmentId/submissions/self"

    /** "내 일정"의 "제출 확인" 결과. [problems]에는 늦음도 들어간다(직접 확인할 때는 다 보여 준다). */
    data class Live(
        val assignment: String,
        val submitted: Boolean,
        val submittedAt: Instant?,
        val due: Instant?,
        val problems: List<String>,
        val warnings: List<String>,
        val files: List<String>,
        val url: String?,
    ) {
        val ok: Boolean get() = submitted && problems.isEmpty()
    }

    /** 과제와 내 제출을 지금 받아 판정한다(GET 두 번, 파일은 받지 않는다). */
    fun live(client: EtlClient, courseId: Long, assignmentId: Long, now: Instant): Live {
        val a = client.getObject("/api/v1/courses/$courseId/assignments/$assignmentId")
        val s = client.getObject(submissionPath(courseId, assignmentId))
        return liveFrom(a, s, now)
    }

    fun liveFrom(a: JSONObject, s: JSONObject, now: Instant): Live {
        val ev = EtlLogic.evaluateSubmission(a, s, now)
        val problems = ev.problems.toMutableList()
        val files = mutableListOf<String>()
        val attachments = s.optJSONArray("attachments")
        if (attachments != null) {
            val allowedExt = allowedExtensions(a)
            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                problems += EtlLogic.checkAttachment(att, allowedExt)
                files += att.str("display_name") ?: att.str("filename") ?: "(이름 없음)"
            }
        }
        if (ev.submitted && s.str("submission_type") == "online_upload" && files.isEmpty()) problems += NO_ATTACHMENT
        return Live(
            assignment = a.str("name") ?: "(제목 없음)",
            submitted = ev.submitted,
            submittedAt = ev.submittedAt,
            due = ev.due,
            problems = problems,
            warnings = ev.warnings,
            files = files,
            url = a.str("html_url"),
        )
    }
}
