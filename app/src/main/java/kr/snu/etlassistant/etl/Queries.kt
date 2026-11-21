package kr.snu.etlassistant.etl

import kr.snu.etlassistant.etl.EtlLogic.obj
import kr.snu.etlassistant.etl.EtlLogic.str
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.Collator
import java.time.Instant
import java.util.Locale

/*
 * ttokttok-pet `src/etl/queries.js`(courses, assignments, announcements, grades, api)와
 * `src/etl/files.js`(files 목록)의 판정 로직. 네트워크 없이 테스트할 수 있게 JSON → 결과 변환만 둔다.
 */
object Queries {
    val STATUSES = listOf("upcoming", "missing", "submitted", "all")
    const val LIST_LIMIT = 100
    const val GRADED_LIMIT = 500
    const val ANNOUNCEMENT_LIMIT = 1200
    const val DEFAULT_ANNOUNCEMENT_DAYS = 7
    const val MAX_ANNOUNCEMENT_DAYS = 60

    // ---- 과목 ---------------------------------------------------------------

    /** 이름이 있고 날짜로 닫히지 않은 강의(지난 학기 포함). */
    fun usableCourses(list: List<JSONObject>): List<JSONObject> =
        list.filter { it.str("name") != null && !it.optBoolean("access_restricted_by_date", false) }

    fun courseRow(c: JSONObject): JSONObject {
        val enr = c.optJSONArray("enrollments")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("type") == "student" }
        } ?: JSONObject()
        return JSONObject()
            .put("course_id", c.optLong("id"))
            .put("name", c.str("name"))
            .put("term", c.obj("term")?.str("name") ?: JSONObject.NULL)
            .put("current_score", enr.opt("computed_current_score") ?: JSONObject.NULL)
            .put("current_grade", enr.opt("computed_current_grade") ?: JSONObject.NULL)
    }

    /** 모든 학기 과목. 이번 학기를 먼저 두고 `current`로 표시한다. */
    fun coursesResult(list: List<JSONObject>): JSONObject {
        val current = EtlLogic.currentCourses(list).map { it.optLong("id") }.toSet()
        val rows = usableCourses(list).map { courseRow(it).put("current", it.optLong("id") in current) }
        return JSONObject().put("courses", JSONArray(rows.filter { it.getBoolean("current") } + rows.filterNot { it.getBoolean("current") }))
    }

    /** [course]가 없으면 이번 학기 과목, 있으면 모든 학기에서 그 과목 하나(없으면 오류). */
    fun coursesFor(list: List<JSONObject>, course: Long?): List<JSONObject> {
        if (course == null) return EtlLogic.currentCourses(list)
        val picked = usableCourses(list).filter { it.optLong("id") == course }
        if (picked.isEmpty()) throw EtlException("수강 중인 과목에서 ID $course 를 찾을 수 없습니다. etl_courses로 과목 ID를 확인하세요.")
        return picked
    }

    // ---- 과제 ---------------------------------------------------------------

    fun isGraded(a: JSONObject): Boolean {
        val s = a.obj("submission") ?: return false
        return s.optString("workflow_state") == "graded" && s.has("score") && !s.isNull("score")
    }

    fun isPublished(a: JSONObject): Boolean = !a.has("published") || a.optBoolean("published", true)

    fun matchesStatus(a: JSONObject, status: String, now: Instant): Boolean = when (status) {
        "all" -> true
        "submitted" -> EtlLogic.isSubmitted(a)
        "missing" -> EtlLogic.isMissing(a, now)
        "upcoming" -> !EtlLogic.isSubmitted(a) && EtlLogic.parseInstant(a.str("due_at"))?.let { !it.isBefore(now) } == true
        else -> throw EtlException("알 수 없는 과제 상태입니다: $status (upcoming, missing, submitted, all)")
    }

    /** 과제 하나의 요약(ttokttok-pet과 같은 필드). */
    fun summary(a: JSONObject, course: JSONObject, now: Instant): JSONObject {
        val sub = a.obj("submission") ?: JSONObject()
        val due = EtlLogic.parseInstant(a.str("due_at"))
        return JSONObject()
            .put("course", course.str("name"))
            .put("course_id", course.optLong("id"))
            .put("assignment_id", a.optLong("id"))
            .put("name", a.str("name") ?: JSONObject.NULL)
            .put("due", due?.let { EtlLogic.fmtKst(it) } ?: JSONObject.NULL)
            .put("remaining", EtlLogic.remaining(due, now))
            .put("points", if (!a.has("points_possible") || a.isNull("points_possible")) JSONObject.NULL else a.opt("points_possible"))
            .put("submission_types", JSONArray(a.optJSONArray("submission_types")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList<String>()))
            .put("online_submission", EtlLogic.isOnline(a))
            .put("submitted", EtlLogic.isSubmitted(a))
            .put("missing", sub.optBoolean("missing", false))
            .put("late", sub.optBoolean("late", false))
            .put("graded", sub.optString("workflow_state") == "graded")
            .put("score", if (!sub.has("score") || sub.isNull("score")) JSONObject.NULL else sub.opt("score"))
            .put("url", a.str("html_url") ?: JSONObject.NULL)
    }

    private fun dueOf(a: JSONObject): Instant? = EtlLogic.parseInstant(a.str("due_at"))

    /** 상태(와 upcoming이면 [days]일 이내)로 거르고 마감순(마감 없음은 끝), 최대 [limit]개. */
    fun statusList(results: List<CourseAssignments.CourseResult>, status: String, days: Int?, now: Instant, limit: Int = LIST_LIMIT): JSONObject {
        if (status !in STATUSES) throw EtlException("알 수 없는 과제 상태입니다: $status (upcoming, missing, submitted, all)")
        val horizon = if (status == "upcoming" && days != null && days > 0) now.plusSeconds(days * 86400L) else null
        val picked = results.flatMap { r -> r.assignments.filter(::isPublished).map { r.course to it } }
            .filter { (_, a) -> matchesStatus(a, status, now) && (horizon == null || dueOf(a)?.let { !it.isAfter(horizon) } == true) }
            .sortedWith(compareBy { (_, a) -> dueOf(a) ?: Instant.MAX })
        val out = JSONObject()
            .put("now", EtlLogic.fmtKst(now))
            .put("status", status)
            .put("count", picked.size)
            .put("assignments", JSONArray(picked.take(limit).map { (c, a) -> summary(a, c, now) }))
        val errors = results.mapNotNull { r -> r.error?.let { "${r.courseName}: $it" } }
        if (errors.isNotEmpty()) out.put("errors", JSONArray(errors))
        return out
    }

    /** 과목별 현재 점수와 채점된 과제(마감순). */
    fun gradesResult(courses: List<JSONObject>, results: List<CourseAssignments.CourseResult>, now: Instant): JSONObject {
        val graded = results.flatMap { r -> r.assignments.filter { isPublished(it) && isGraded(it) }.map { r.course to it } }
            .sortedWith(compareBy { (_, a) -> dueOf(a) ?: Instant.MAX }).take(GRADED_LIMIT)
        val byCourse = graded.groupBy { it.first.optLong("id") }
        val rows = courses.map { c ->
            courseRow(c).put("graded_assignments", JSONArray((byCourse[c.optLong("id")] ?: emptyList()).map { (_, a) ->
                val s = summary(a, c, now)
                JSONObject().put("name", s.opt("name")).put("score", s.opt("score")).put("points", s.opt("points")).put("due", s.opt("due"))
            }))
        }
        val out = JSONObject().put("courses", JSONArray(rows))
        val errors = results.mapNotNull { r -> r.error?.let { "${r.courseName}: $it" } }
        if (errors.isNotEmpty()) out.put("errors", JSONArray(errors))
        return out
    }

    // ---- 공지 ---------------------------------------------------------------

    fun announcementRows(raw: List<JSONObject>, names: Map<Long, String>): JSONArray {
        val rows = raw.map { it to EtlLogic.parseInstant(it.str("posted_at")) }
            .sortedByDescending { it.second ?: Instant.MIN }
            .map { (it, posted) ->
                val cid = (it.str("context_code") ?: "course_0").substringAfterLast('_').toLongOrNull() ?: 0L
                JSONObject()
                    .put("course", names[cid] ?: cid.toString())
                    .put("title", it.str("title") ?: JSONObject.NULL)
                    .put("posted", posted?.let { p -> EtlLogic.fmtKst(p) } ?: JSONObject.NULL)
                    .put("author", it.obj("author")?.str("display_name") ?: JSONObject.NULL)
                    .put("text", EtlLogic.htmlToText(it.str("message"), ANNOUNCEMENT_LIMIT))
                    .put("url", it.str("html_url") ?: it.str("url") ?: JSONObject.NULL)
            }
        return JSONArray(rows)
    }

    /** 공지 본문으로 수업 변동 공지를 가린다(알림 제목에 쓴다). */
    fun announcementBadge(title: String?, text: String?): String {
        val s = "${title ?: ""} ${text ?: ""}"
        return when {
            "보강" in s -> "보강 공지"
            Regex("휴강|결강").containsMatchIn(s) -> "휴강 공지"
            else -> "새 공지"
        }
    }

    // ---- etl_get --------------------------------------------------------------

    /** 쪽지를 읽으면 eTL이 읽음으로 바꾸므로 `auto_mark_as_read=false`를 붙인다(보기만 하고 eTL을 바꾸지 않는다). */
    fun readOnlyPath(p: String): String {
        if (!Regex("^/api/v1/conversations/\\d+(\\?|$)").containsMatchIn(p) || Regex("[?&]auto_mark_as_read=").containsMatchIn(p)) return p
        return p + (if ('?' in p) "&" else "?") + "auto_mark_as_read=false"
    }

    /** 응답이 [maxChars]를 넘으면 목록은 들어가는 만큼만 남기고 그 사실을 적는다. 객체면 오류. */
    fun capAnswer(path: String, data: Any, maxChars: Int): JSONObject {
        val size = { v: Any -> v.toString().length }
        if (size(data) <= maxChars) return JSONObject().put("path", path).put("data", data)
        val arr = data as? JSONArray ?: throw EtlException("응답이 너무 큽니다. include[]를 줄이거나 더 좁은 경로로 다시 조회하세요.")
        var lo = 0
        var hi = arr.length()
        val slice = { n: Int -> JSONArray().also { s -> for (i in 0 until n) s.put(arr.opt(i)) } }
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (size(slice(mid)) <= maxChars) lo = mid else hi = mid - 1
        }
        return JSONObject().put("path", path).put("data", slice(lo))
            .put("truncated", JSONObject().put("shown", lo).put("total", arr.length()))
    }

    // ---- 강의자료 목록 ---------------------------------------------------------

    /** HTML 안의 eTL 파일 링크(이 과목 또는 전역 /files/<id>)의 파일 ID들. HTML은 읽기만 한다. */
    fun linkedFiles(html: String?, courseId: Long): List<Long> {
        val ids = linkedSetOf<Long>()
        val attrs = Regex("\\b(?:href|src|data-api-endpoint)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))", RegexOption.IGNORE_CASE)
        for (m in attrs.findAll(html ?: return emptyList())) {
            val raw = (m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: continue).replace("&amp;", "&", ignoreCase = true)
            val url = try { URL(URL(EtlClient.BASE_URL + "/"), raw) } catch (_: Exception) { continue }
            if ("${url.protocol}://${url.authority}" != EtlClient.BASE_URL || url.userInfo != null) continue
            val scoped = Regex("^/(?:api/v1/)?courses/(\\d+)/files/(\\d+)(?:/(?:download|preview))?/?$").find(url.path)
            val global = Regex("^/(?:api/v1/)?files/(\\d+)(?:/(?:download|preview))?/?$").find(url.path)
            val id = if (scoped != null && scoped.groupValues[1].toLongOrNull() == courseId) scoped.groupValues[2] else global?.groupValues?.get(1)
            id?.toLongOrNull()?.takeIf { it > 0 }?.let { ids += it }
        }
        return ids.toList()
    }

    data class FileRef(val id: Long, val meta: JSONObject?, val source: String, val labels: Map<String, String>, val title: String? = null)

    /** 과제 설명·첨부, 내 제출 첨부, 공지 본문·첨부에 걸린 파일. */
    fun attachedFiles(assignments: List<JSONObject>, announcements: List<JSONObject>, courseId: Long): List<FileRef> {
        val out = mutableListOf<FileRef>()
        fun add(item: JSONObject?, source: String, labels: Map<String, String>, html: String?) {
            val atts = mutableListOf<JSONObject>()
            item?.optJSONArray("attachments")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { atts += it } }
            item?.optJSONObject("attachment")?.let { atts += it }
            for (f in atts) f.optLong("id").takeIf { it > 0 }?.let { out += FileRef(it, f, source, labels) }
            for (id in linkedFiles(html, courseId)) out += FileRef(id, null, source, labels)
        }
        for (a in assignments) {
            if (!isPublished(a)) continue
            val labels = a.str("name")?.let { mapOf("assignment" to it) } ?: emptyMap()
            add(a, "assignment", labels, a.str("description"))
            add(a.obj("submission"), "submission", labels, null)
        }
        for (n in announcements) add(n, "announcement", n.str("title")?.let { mapOf("announcement" to it) } ?: emptyMap(), n.str("message"))
        return out
    }

    /** 모듈 항목 중 File: (파일 ID, 제목, 모듈 이름). */
    fun moduleFiles(modules: List<JSONObject>): List<FileRef> = modules.flatMap { m ->
        val items = m.optJSONArray("items") ?: JSONArray()
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("type") == "File" && it.optLong("content_id") > 0 }
            .map { FileRef(it.optLong("content_id"), null, "module", m.str("name")?.let { n -> mapOf("module" to n) } ?: emptyMap(), it.str("title")) }
    }

    fun folderNames(folders: List<JSONObject>): Map<Long, String?> = folders.associate { f ->
        val full = f.str("full_name") ?: f.str("name") ?: ""
        f.optLong("id") to full.replace(Regex("^course files/?"), "").ifEmpty { null }
    }

    /** 받지 않는 이유(없으면 null): 동영상, 잠김, 숨김. 게시판 첨부의 숨김은 다른 경로(etl_download의 게시판 인자)로 받는다. */
    fun skipReason(f: JSONObject?): String? {
        if (f == null) return "파일 정보를 읽지 못했습니다. 받기 요청 때 다시 확인합니다."
        val type = (f.str("content-type") ?: f.str("content_type") ?: "").lowercase()
        return when {
            type.startsWith("video/") || f.optString("mime_class") == "video" -> "동영상은 받지 않습니다. eTL에서 보세요."
            f.optBoolean("locked_for_user", false) -> "잠긴 파일입니다. 열리는 시각 이후에 받을 수 있습니다."
            f.optBoolean("hidden_for_user", false) -> "숨겨진 파일입니다. eTL에서 확인해 주세요."
            else -> null
        }
    }

    /**
     * 과목 파일 목록(받지 않는다): 파일 탭, 모듈, 과제·공지 첨부와 본문 링크, 내 제출 첨부를 합친다.
     * [details]는 파일 탭에 없던 참조 파일의 메타(id → JSON). [saved]는 이미 작업 폴더에 있는 파일 ID.
     */
    fun filesResult(
        courseId: Long,
        courseName: String?,
        listed: List<JSONObject>?,
        folders: List<JSONObject>,
        modules: List<FileRef>,
        attached: List<FileRef>,
        details: Map<Long, JSONObject>,
        saved: Set<Long>,
        errors: List<String>,
    ): JSONObject {
        val folderOf = folderNames(folders)
        val moduleOf = modules.associate { it.id to it.labels["module"] }
        val rows = linkedMapOf<Long, JSONObject>()
        fun row(f: JSONObject?, id: Long, name: String, source: String, labels: Map<String, String?>): JSONObject {
            val o = JSONObject().put("id", id).put("name", name)
                .put("size", if (f != null && f.has("size") && !f.isNull("size")) f.optLong("size") else JSONObject.NULL)
                .put("content_type", f?.str("content-type") ?: f?.str("content_type") ?: JSONObject.NULL)
                .put("updated_at", EtlLogic.parseInstant(f?.str("updated_at"))?.let { EtlLogic.fmtKst(it) } ?: JSONObject.NULL)
                .put("source", source)
            labels.forEach { (k, v) -> if (!v.isNullOrBlank()) o.put(k, v) }
            val skip = skipReason(f)
            if (skip != null) o.put("skip", skip)
            o.put("downloaded", skip == null && id in saved)
            o.put("url", "${EtlClient.BASE_URL}/courses/$courseId/files/$id")
            return o
        }
        for (f in listed.orEmpty()) {
            val id = f.optLong("id").takeIf { it > 0 } ?: continue
            if (id in rows) continue
            rows[id] = row(f, id, f.str("display_name") ?: f.str("filename") ?: "파일 $id", "files",
                mapOf("folder" to folderOf[f.optLong("folder_id")], "module" to moduleOf[id]))
        }
        val refs = linkedMapOf<Long, FileRef>()
        for (m in modules) refs[m.id] = m
        for (r in attached) {
            val prev = refs[r.id]
            refs[r.id] = if (prev == null) r else prev.copy(meta = prev.meta ?: r.meta, labels = r.labels + prev.labels)
        }
        for (ref in refs.values) {
            val existing = rows[ref.id]
            if (existing != null) {
                ref.labels.forEach { (k, v) -> if (v.isNotBlank() && !existing.has(k)) existing.put(k, v) }
                continue
            }
            val f = details[ref.id] ?: ref.meta
            rows[ref.id] = row(f, ref.id, f?.str("display_name") ?: f?.str("filename") ?: ref.title ?: "파일 ${ref.id}", ref.source, ref.labels)
        }
        val order = mapOf("files" to 0, "module" to 0, "assignment" to 0, "announcement" to 0, "submission" to 1)
        val collator = Collator.getInstance(Locale.KOREAN)
        val label = { r: JSONObject -> listOf("folder", "module", "announcement", "assignment").firstNotNullOfOrNull { k -> r.optString(k).takeIf { it.isNotEmpty() } } ?: "" }
        val list = rows.values.sortedWith { x, y ->
            (order[x.optString("source")] ?: 0).compareTo(order[y.optString("source")] ?: 0).takeIf { it != 0 }
                ?: collator.compare(label(x), label(y)).takeIf { it != 0 }
                ?: collator.compare(x.optString("name"), y.optString("name"))
        }
        val out = JSONObject()
            .put("course", courseName ?: JSONObject.NULL)
            .put("course_id", courseId)
            .put("count", list.size)
            .put("downloaded_count", list.count { it.optBoolean("downloaded") })
            .put("files", JSONArray(list))
        if (list.isEmpty()) {
            out.put("note", "이 과목에서 받을 수 있는 파일을 찾지 못했습니다. 자료가 게시판에 있으면 etl_boards로 찾아보고, 주차학습(LearningX)에만 있으면 eTL에서 직접 확인해야 합니다.")
        }
        if (errors.isNotEmpty()) out.put("errors", JSONArray(errors))
        return out
    }
}
