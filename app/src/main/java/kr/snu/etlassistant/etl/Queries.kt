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
                JSONObject().put("assignment_id", s.opt("assignment_id")).put("name", s.opt("name")).put("score", s.opt("score"))
                    .put("points", s.opt("points")).put("due", s.opt("due"))
                    .also { row -> scoreStatsFields(a)?.let { (st, pos) -> row.put("stats", st); pos?.let { row.put("position", it) } } }
            }))
        }
        val out = JSONObject().put("courses", JSONArray(rows))
        val errors = results.mapNotNull { r -> r.error?.let { "${r.courseName}: $it" } }
        if (errors.isNotEmpty()) out.put("errors", JSONArray(errors))
        return out
    }

    /** 채점된 과제의 점수 분포(eTL score_statistics)와 내 점수 위치 어림. 분포가 없으면 null. */
    private fun scoreStatsFields(a: JSONObject): Pair<JSONObject, String?>? {
        val st = ScoreStats.parse(a.obj("score_statistics")) ?: return null
        val my = a.obj("submission")?.takeIf { isGraded(a) }?.optDouble("score")?.takeIf { it.isFinite() }
        return ScoreStats.statsJson(st) to ScoreStats.position(st, my)?.label
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

    /** 공지 종류(알림 배지와 "내 일정에 등록"/"시간표에 반영" 초안이 함께 쓴다). */
    enum class AnnCategory(val badge: String) {
        EXAM("시험 공지"), ASSIGNMENT("과제 공지"), VIDEO("동영상 강의"), MAKEUP("보강 공지"), CANCEL("휴강 공지");

        val label: String get() = badge.removeSuffix(" 공지")
        val timetable: Boolean get() = this == MAKEUP || this == CANCEL
    }

    /**
     * 공지 종류. 우선순위는 시험 > 과제 > 동영상 강의 > 보강 > 휴강이고 제목에 있는 말이 먼저다
     * (제목: 시험 → 과제 → 동영상 → 본문까지 보강 → 휴강 → 본문의 시험·과제·동영상). 해당 없으면 null.
     */
    fun announcementCategory(title: String?, text: String?): AnnCategory? {
        val s = "${title ?: ""} ${text ?: ""}"
        val exam = examMention(title, text)
        val assign = exam == ExamMention.NONE && isAssignmentAnnouncement(title, text)
        val video = exam == ExamMention.NONE && !assign && isVideoLecture(title, text)
        val t = title.orEmpty()
        return when {
            exam == ExamMention.TITLE -> AnnCategory.EXAM
            assign && ASSIGN.containsMatchIn(t) -> AnnCategory.ASSIGNMENT
            video && VIDEO.containsMatchIn(t) -> AnnCategory.VIDEO
            "보강" in s -> AnnCategory.MAKEUP
            Regex("휴강|결강").containsMatchIn(s) -> AnnCategory.CANCEL
            exam == ExamMention.BODY -> AnnCategory.EXAM
            assign -> AnnCategory.ASSIGNMENT
            video -> AnnCategory.VIDEO
            else -> null
        }
    }

    /** 알림 제목 배지: "시험 공지", "과제 공지", "동영상 강의", "보강 공지", "휴강 공지", 그 밖은 "새 공지". */
    fun announcementBadge(title: String?, text: String?): String = announcementCategory(title, text)?.badge ?: "새 공지"

    private val VIDEO = Regex("동영상\\s*강의|녹화\\s*강의|영상\\s*강의|\\bVOD\\b|온라인\\s*강의|사전\\s*녹화|강의\\s*영상|녹화본", RegexOption.IGNORE_CASE)
    /** 동영상 강의를 시청하라는 말(단순 자료 링크 공지를 거른다). */
    private val VIDEO_ACTION = Regex("시청|기한|기간|까지|대체|출석\\s*인정|수강\\s*기간", RegexOption.IGNORE_CASE)
    /** 그날 대면 수업이 없다는 단서. */
    private val NO_CLASS = Regex("대면\\s*수업\\s*(은|이)?\\s*(없|휴강|대체)|대체\\s*(합니다|해요|됩니다|수업)|수업\\s*(은|이)?\\s*없|휴강", RegexOption.IGNORE_CASE)

    /** 동영상 강의 공지인가: 동영상 강의 말과 시청·기한·대체 같은 말이 함께 있을 때(시험·과제 공지가 아닐 때). */
    fun isVideoLecture(title: String?, text: String?): Boolean {
        val s = "${title.orEmpty()} ${text.orEmpty()}"
        return VIDEO.containsMatchIn(s) && VIDEO_ACTION.containsMatchIn(s) && !EXAM_RESULT.containsMatchIn(title.orEmpty())
    }

    /** 동영상 강의 공지에 "그날 대면 수업 없음/대체" 단서가 있는가. */
    fun replacesClass(text: String?): Boolean = NO_CLASS.containsMatchIn(text.orEmpty())

    enum class ExamMention { NONE, TITLE, BODY }

    private val EXAM = Regex("시험|중간\\s*고사|기말\\s*고사|퀴즈|\\bquiz(zes)?\\b|\\bexam(s|ination)?\\b|\\bmid-?terms?\\b|\\bfinals\\b", RegexOption.IGNORE_CASE)
    /** 시험 결과 공지("중간고사 성적 공개")를 가르는 말. */
    private val EXAM_RESULT = Regex("성적|점수|채점|해설|이의\\s*신청|결과|답안|\\bgrades?\\b|\\bscores?\\b|\\bresults?\\b", RegexOption.IGNORE_CASE)
    /** 일정을 알리는 말. "안내"·"공지"는 성적 안내에도 쓰여서 넣지 않는다. */
    private val EXAM_SCHEDULE = Regex("일정|일시|날짜|장소|고사장|강의실|범위|시간|실시|시행|응시|\\bdate\\b|\\btime\\b|\\broom\\b|\\blocation\\b|\\bschedule\\b", RegexOption.IGNORE_CASE)

    /**
     * 시험 공지인가(키워드만 본다; 날짜는 뽑지 않는다). 제목에 시험 말이 있으면 시험 공지(제목이 성적·채점 같은 결과 공지이고
     * 일정 말이 없으면 제외). 본문에만 있으면 본문에 일정 말(일시·장소·범위 등)도 있을 때만(과제 공지의 "시험에 반영" 같은 것을 거른다).
     */
    fun examMention(title: String?, text: String?): ExamMention {
        val t = title.orEmpty()
        val b = text.orEmpty()
        if (EXAM.containsMatchIn(t)) {
            return if (EXAM_RESULT.containsMatchIn(t) && !EXAM_SCHEDULE.containsMatchIn(t)) ExamMention.NONE else ExamMention.TITLE
        }
        if (EXAM.containsMatchIn(b) && EXAM_SCHEDULE.containsMatchIn(b) && !EXAM_RESULT.containsMatchIn(t)) return ExamMention.BODY
        return ExamMention.NONE
    }

    fun isExamAnnouncement(title: String?, text: String?): Boolean = examMention(title, text) != ExamMention.NONE

    /** 과제 공지의 말. "제출"·"마감"은 과제 말과 함께 볼 때만 쓴다. */
    private val ASSIGN = Regex("과제|보고서|레포트|리포트|프로젝트|\\bhomework\\b|\\bhw\\s*#?\\d*\\b|\\bassignments?\\b|\\bprojects?\\b|\\breport\\b", RegexOption.IGNORE_CASE)
    private val ASSIGN_DUE = Regex("제출|마감|기한|까지|\\bdue\\b|\\bdeadline\\b|\\bsubmit", RegexOption.IGNORE_CASE)

    /**
     * 과제 공지인가(2026-11-24, "내 일정에 등록" 버튼). 시험 공지가 아니고, 제목에 과제 말이 있거나(성적·채점 같은 결과 공지는 제외)
     * 본문에 과제 말과 제출·마감 말이 함께 있을 때. 키워드만 본다.
     */
    fun isAssignmentAnnouncement(title: String?, text: String?): Boolean {
        if (isExamAnnouncement(title, text)) return false
        val t = title.orEmpty()
        val b = text.orEmpty()
        // "과제 성적/점수/채점/해설/정답 공개" 같은 결과 공지는 뺀다.
        if (EXAM_RESULT.containsMatchIn(t) || Regex("정답").containsMatchIn(t)) return false
        if (ASSIGN.containsMatchIn(t)) return true
        return ASSIGN.containsMatchIn(b) && ASSIGN_DUE.containsMatchIn(b)
    }

    /** 성적 공지의 말(제목). */
    private val GRADE_TITLE = Regex("성적|점수|채점|결과|통계|분포|\\bgrades?\\b|\\bscores?\\b|\\bresults?\\b|\\bstatistics\\b", RegexOption.IGNORE_CASE)
    /** 점수 분포를 말하는 말. */
    private val STATS_WORDS = Regex("평균|중앙값|중간값|표준\\s*편차|사분위|분포|히스토그램|최고점|최저점|통계|\\bQ[123]\\b|\\bmedian\\b|\\bmean\\b|\\baverage\\b|\\bavg\\b|\\bstd\\b|\\bstdev\\b|\\bhistogram\\b|\\bdistribution\\b|\\bstatistics\\b|\\bquartiles?\\b", RegexOption.IGNORE_CASE)

    /**
     * 점수 분포가 실린 성적 공지인가(2026-11-28, "통계 저장" 버튼). 제목이 성적·점수·결과 공지이고, 제목이나 본문에
     * 평균·중앙값·Q1/Q3·표준편차·분포 같은 말이 있을 때. 키워드만 본다(숫자는 비서가 사용자 요청으로 읽는다).
     */
    fun isScoreStatsAnnouncement(title: String?, text: String?): Boolean {
        val t = title.orEmpty()
        if (!GRADE_TITLE.containsMatchIn(t)) return false
        return STATS_WORDS.containsMatchIn(t) || STATS_WORDS.containsMatchIn(text.orEmpty())
    }

    /** 성적 공지이거나 제목이 시험 이름(중간고사·Exam 1 등)이고, 분포 말이 있는가(기기 안 읽기·"통계 저장" 버튼의 대상). */
    fun mayHaveScoreStats(title: String?, text: String?): Boolean =
        isScoreStatsAnnouncement(title, text) ||
            // 제목이 시험 이름뿐("Exam 1")이면 분포 말이 두 가지 이상일 때만("평균 소요 시간" 같은 문장 하나로는 아니다).
            (ScoreStatsParser.examKey(title) != null && !isExamAnnouncement(title, text) &&
                STATS_WORDS.findAll("${title.orEmpty()} ${text.orEmpty()}").map { it.value.lowercase() }.toSet().size >= 2)

    /** 준비물·지참물·허용 물품을 말하는 줄(2026-11-24). 키워드만 보고 날짜나 물품을 해석하지 않는다. */
    private val SUPPLIES = Regex("준비물|지참|가져\\s*오|가져\\s*와|챙겨|허용|불허|사용\\s*(가능|불가)|계산기|신분증|학생증|사인펜|요약지|치팅|cheat\\s*sheet|\\bbring\\b|\\ballowed\\b|\\bcalculators?\\b", RegexOption.IGNORE_CASE)

    /**
     * 시험 공지 본문에서 준비물 관련 줄을 최대 [maxLines]줄, 줄마다 [maxChars]자로 뽑는다(알림 본문에 덧붙인다).
     * 줄바꿈이 없는 긴 본문은 문장(". ", "다.") 단위로 나눠 본다. 같은 줄은 한 번만.
     */
    fun suppliesLines(text: String?, maxLines: Int = 2, maxChars: Int = 60): List<String> {
        val parts = text.orEmpty().split('\n').flatMap { it.split(Regex("(?<=[.!?。])\\s+")) }
        return parts.map { it.replace(Regex("\\s+"), " ").trim().trimStart('-', '*', '•', '·', '※', ' ') }
            .filter { it.isNotEmpty() && SUPPLIES.containsMatchIn(it) }
            .distinct().take(maxLines)
            .map { if (it.length > maxChars) it.take(maxChars - 1) + "…" else it }
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
    fun linkedFiles(html: String?, courseId: Long): List<Long> = linkedFileUrls(html, courseId).keys.toList()

    /** 서명 URL은 다운로드에만 내부적으로 사용하고 도구 결과에는 파일 ID만 노출한다. */
    fun linkedFileUrls(html: String?, courseId: Long): Map<Long, String> {
        val urls = linkedMapOf<Long, String>()
        val attrs = Regex("\\b(?:href|src|data-api-endpoint)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))", RegexOption.IGNORE_CASE)
        for (m in attrs.findAll(html ?: return emptyMap())) {
            val raw = BoardLogic.decodeAttr(m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: continue).trim()
            val url = try { URL(URL(EtlClient.BASE_URL + "/"), raw) } catch (_: Exception) { continue }
            if ("${url.protocol}://${url.authority}" != EtlClient.BASE_URL || url.userInfo != null) continue
            val scoped = Regex("^/(?:api/v1/)?courses/(\\d+)/files/(\\d+)(?:/(?:download|preview))?/?$").find(url.path)
            val global = Regex("^/(?:api/v1/)?files/(\\d+)(?:/(?:download|preview))?/?$").find(url.path)
            val previewCourse = Regex("^/courses/(\\d+)/files/?$").find(url.path)
            val preview = if ((previewCourse?.groupValues?.get(1)?.toLongOrNull() == courseId) || url.path == "/files") {
                url.query?.split('&')?.firstOrNull { it.substringBefore('=') == "preview" }?.substringAfter('=')
            } else null
            val id = if (scoped != null && scoped.groupValues[1].toLongOrNull() == courseId) scoped.groupValues[2] else global?.groupValues?.get(1) ?: preview
            id?.toLongOrNull()?.takeIf { it > 0 }?.let { fid ->
                val query = url.query?.let { "?$it" }.orEmpty()
                urls.putIfAbsent(fid, "${EtlClient.BASE_URL}/files/$fid/download$query")
            }
        }
        return urls
    }

    fun linkedFileName(html: String?, fileId: Long, courseId: Long): String? {
        for (anchor in Regex("<a\\b([^>]*)>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).findAll(html.orEmpty())) {
            if (fileId !in linkedFileUrls("<a ${anchor.groupValues[1]}>", courseId)) continue
            val text = EtlLogic.htmlToText(anchor.groupValues[2], 200).trim()
            val name = Regex("^(.+\\.(?:xlsx|xls|csv|tsv|pdf|docx|pptx|hwp|hwpx|txt|zip))\\b", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            if (name != null) return EtlLogic.safeName(name, "file-$fileId")
        }
        return null
    }

    data class FileRef(val id: Long, val meta: JSONObject?, val source: String, val labels: Map<String, String>, val title: String? = null, val sourceId: Long? = null) {
        fun downloadArgs(courseId: Long): JSONObject? {
            val type = if (source == "submission") "assignment" else source
            if (sourceId == null || sourceId <= 0 || type !in setOf("announcement", "assignment", "module")) return null
            return JSONObject().put("file_id_or_url", id.toString()).put("course_id", courseId)
                .put("source_type", type).put("source_id", sourceId)
        }
    }

    /** 과제 설명·첨부, 내 제출 첨부, 공지 본문·첨부에 걸린 파일. */
    fun attachedFiles(assignments: List<JSONObject>, announcements: List<JSONObject>, courseId: Long): List<FileRef> {
        val out = mutableListOf<FileRef>()
        fun add(item: JSONObject?, source: String, sourceId: Long, labels: Map<String, String>, html: String?) {
            val atts = mutableListOf<JSONObject>()
            item?.optJSONArray("attachments")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { atts += it } }
            item?.optJSONObject("attachment")?.let { atts += it }
            for (f in atts) f.optLong("id").takeIf { it > 0 }?.let { out += FileRef(it, f, source, labels, sourceId = sourceId) }
            for (id in linkedFiles(html, courseId)) out += FileRef(id, null, source, labels, sourceId = sourceId)
        }
        for (a in assignments) {
            if (!isPublished(a) || a.optBoolean("locked_for_user")) continue
            val labels = a.str("name")?.let { mapOf("assignment" to it) } ?: emptyMap()
            add(a, "assignment", a.optLong("id"), labels, a.str("description"))
            add(a.obj("submission"), "submission", a.optLong("id"), labels, null)
        }
        for (n in announcements) {
            if (!isPublished(n) || n.optBoolean("locked_for_user")) continue
            add(n, "announcement", n.optLong("id"), n.str("title")?.let { mapOf("announcement" to it) } ?: emptyMap(), n.str("message"))
        }
        return out
    }

    /** 모듈 항목 중 File: (파일 ID, 제목, 모듈 이름). */
    fun moduleFiles(modules: List<JSONObject>): List<FileRef> = modules.filter {
        isPublished(it) && !it.optBoolean("locked_for_user") && it.optString("state") != "locked"
    }.flatMap { m ->
        val items = m.optJSONArray("items") ?: JSONArray()
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("type") == "File" && it.optLong("content_id") > 0 && isPublished(it) }
            .map { FileRef(it.optLong("content_id"), null, "module", m.str("name")?.let { n -> mapOf("module" to n) } ?: emptyMap(), it.str("title"), m.optLong("id")) }
    }

    fun folderNames(folders: List<JSONObject>): Map<Long, String?> = folders.associate { f ->
        val full = f.str("full_name") ?: f.str("name") ?: ""
        f.optLong("id") to full.replace(Regex("^course files/?"), "").ifEmpty { null }
    }

    /** 파일 탭의 숨김은 다운로드 거부 사유가 아니다. 실제 열람 잠금·동영상만 건너뛴다. */
    fun skipReason(f: JSONObject?): String? {
        if (f == null) return "파일 정보를 읽지 못했습니다. 받기 요청 때 다시 확인합니다."
        val type = (f.str("content-type") ?: f.str("content_type") ?: "").lowercase()
        return when {
            type.startsWith("video/") || f.optString("mime_class") == "video" -> "동영상은 받지 않습니다. eTL에서 보세요."
            f.optBoolean("locked_for_user", false) -> "잠긴 파일입니다. 열리는 시각 이후에 받을 수 있습니다."
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
        val listedById = listed.orEmpty().associateBy { it.optLong("id") }
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
            refs[r.id] = if (prev == null) r else {
                val origin = if (prev.downloadArgs(courseId) != null) prev else r
                origin.copy(meta = prev.meta ?: r.meta, labels = r.labels + prev.labels)
            }
        }
        for (ref in refs.values) {
            val existing = rows[ref.id]
            if (existing != null) {
                ref.labels.forEach { (k, v) -> if (v.isNotBlank() && !existing.has(k)) existing.put(k, v) }
            } else {
                val f = details[ref.id] ?: ref.meta
                rows[ref.id] = row(f, ref.id, f?.str("display_name") ?: f?.str("filename") ?: ref.title ?: "파일 ${ref.id}", ref.source, ref.labels)
            }
            ref.downloadArgs(courseId)?.let { args ->
                val r = rows.getValue(ref.id)
                r.put("download_args", args)
                val f = listedById[ref.id] ?: details[ref.id] ?: ref.meta
                val skip = skipReason(f)
                r.remove("skip")
                if (skip != null) r.put("skip", skip)
                r.put("downloaded", skip == null && ref.id in saved)
            }
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
