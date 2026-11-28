package kr.snu.youngmini.desktop
import kr.snu.etlassistant.etl.*
import kr.snu.etlassistant.etl.EtlLogic.str
import kr.snu.etlassistant.etl.EtlLogic.strList
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
/** Explicit verification uses eTL's allowed download transport; no local attachment card. */
object SubmissionVerifier {
    fun verify(c: EtlClient, cid: Long, aid: Long): JSONObject {
        val a = c.getObject("/api/v1/courses/$cid/assignments/$aid")
        val s = c.getObject("/api/v1/courses/$cid/assignments/$aid/submissions/self?include[]=submission_comments&include[]=submission_history")
        val now = Instant.now()
        val ev = EtlLogic.evaluateSubmission(a, s, now)
        val allowedTypes = a.strList("submission_types")
        val allowedExt = a.strList("allowed_extensions").map { it.lowercase().trimStart('.') }
        val st = s.str("submission_type")

        val files = JSONArray()
        val attachments = s.optJSONArray("attachments")
        if (attachments != null) {
            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val name = att.str("display_name") ?: att.str("filename") ?: ""
                ev.problems += EtlLogic.checkAttachment(att, allowedExt)
                val row = JSONObject().put("id", att.opt("id")).put("name", name).put("expected_bytes", att.opt("size"))
                val url = att.str("url")
                if (url != null && !att.optBoolean("locked_for_user", false)) {
                    try {
                        val d = c.download(url, java.io.OutputStream.nullOutputStream())
                        row.put("bytes", d.bytes).put("sha256", d.sha256)
                        val expected = if (att.has("size") && !att.isNull("size")) att.optLong("size") else null
                        val ok = expected == null || expected == d.bytes
                        row.put("size_ok", ok)
                        if (!ok) ev.problems += "내려받은 크기가 eTL 기록과 다름: $name"
                    } catch (e: EtlAuthException) {
                        throw e
                    } catch (e: EtlException) {
                        ev.warnings += "다운로드 실패 $name: ${e.message}"
                    }
                }
                files.put(row)
            }
        }
        if (ev.submitted && st == "online_upload" && files.length() == 0) ev.problems += "파일 업로드 과제인데 첨부 파일이 없음"

        val comments = JSONArray()
        val all = s.optJSONArray("submission_comments")
        if (all != null) {
            for (i in maxOf(0, all.length() - 5) until all.length()) {
                val cm = all.optJSONObject(i) ?: continue
                comments.put(JSONObject()
                    .put("author", cm.str("author_name"))
                    .put("at", EtlLogic.fmtKst(EtlLogic.parseInstant(cm.str("created_at"))))
                    .put("text", EtlLogic.htmlToText(cm.str("comment"), 400)))
            }
        }
        val history = s.optJSONArray("submission_history")
        return JSONObject()
            .put("verdict", if (ev.submitted && ev.problems.isEmpty()) "ok" else "problem")
            .put("problems", JSONArray(ev.problems))
            .put("warnings", JSONArray(ev.warnings))
            .put("course_id", cid).put("assignment", a.str("name")).put("url", a.str("html_url"))
            .put("due", EtlLogic.fmtKst(ev.due))
            .put("allowed_types", JSONArray(allowedTypes))
            .put("allowed_extensions", if (allowedExt.isEmpty()) JSONObject.NULL else JSONArray(allowedExt))
            .put("submitted", ev.submitted)
            .put("state", s.str("workflow_state"))
            .put("submitted_at", ev.submittedAt?.let { EtlLogic.fmtKst(it) })
            .put("attempt", s.opt("attempt"))
            .put("attempts_total", history?.length())
            .put("submission_type", st)
            .put("late", s.optBoolean("late", false))
            .put("missing", s.optBoolean("missing", false))
            .put("files", files)
            .put("score", s.opt("score")).put("grade", s.str("grade")).put("points_possible", a.opt("points_possible"))
            .put("comments", comments)
    }
}
