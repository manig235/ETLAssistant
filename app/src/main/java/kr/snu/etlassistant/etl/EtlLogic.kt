package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** eTL(Canvas) 응답을 해석하는 순수 로직. 안드로이드 API에 의존하지 않아 JVM 단위 테스트가 가능하다. */
object EtlLogic {

    val KST: ZoneOffset = ZoneOffset.ofHours(9)
    private const val WEEKDAYS = "월화수목금토일"
    private val SUBMITTED = setOf("submitted", "graded", "pending_review")
    private val OFFLINE = setOf("none", "on_paper", "not_graded")

    // ---- JSON 보조 ---------------------------------------------------------

    /** org.json의 optString은 JSON null을 "null" 문자열로 돌려주므로 직접 처리한다. */
    fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

    fun JSONObject.obj(key: String): JSONObject? = if (!has(key) || isNull(key)) null else optJSONObject(key)

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    fun JSONObject.strList(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { if (a.isNull(it)) null else a.optString(it) }
    }

    // ---- 학기 ---------------------------------------------------------------

    private val SEASONS = listOf(Regex("1학기") to 1, Regex("하계|여름") to 2, Regex("2학기") to 3, Regex("동계|겨울") to 4)
    private val YEAR = Regex("(\\d{4})\\s*(?:년|-)")

    fun termRank(name: String?): Int? {
        val s = name ?: return null
        val y = YEAR.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val season = SEASONS.firstOrNull { it.first.containsMatchIn(s) }?.second ?: return null
        return y * 10 + season
    }

    /** 가장 최신 학기 강의만 남긴다. 학기를 알 수 없으면 전부 남긴다. */
    fun currentCourses(courses: List<JSONObject>): List<JSONObject> {
        val usable = courses.filter { !it.str("name").isNullOrEmpty() && !it.optBoolean("access_restricted_by_date", false) }
        val ranks = usable.map { termRank(it.obj("term")?.str("name")) }
        val latest = ranks.filterNotNull().maxOrNull() ?: return usable
        return usable.filterIndexed { i, _ -> ranks[i] == latest }
    }

    // ---- 과제 판정 ----------------------------------------------------------

    fun isSubmitted(a: JSONObject): Boolean {
        val sub = a.obj("submission") ?: return false
        return sub.str("workflow_state") in SUBMITTED || !sub.str("submitted_at").isNullOrEmpty()
    }

    fun isOnline(a: JSONObject): Boolean = a.strList("submission_types").any { it !in OFFLINE }

    fun isMissing(a: JSONObject, now: Instant): Boolean {
        if (isSubmitted(a)) return false
        if (a.obj("submission")?.optBoolean("missing", false) == true) return true
        val due = parseInstant(a.str("due_at"))
        return due != null && due.isBefore(now) && isOnline(a)
    }

    // ---- 시간 ---------------------------------------------------------------

    fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrEmpty()) return null
        return try {
            Instant.parse(iso)
        } catch (_: DateTimeParseException) {
            try {
                java.time.OffsetDateTime.parse(iso).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    fun fmtKst(t: Instant?): String {
        if (t == null) return "마감일 없음"
        val d = ZonedDateTime.ofInstant(t, KST)
        val wd = WEEKDAYS[d.dayOfWeek.value - 1]
        return "%02d-%02d(%s) %02d:%02d".format(d.monthValue, d.dayOfMonth, wd, d.hour, d.minute)
    }

    fun remaining(t: Instant?, now: Instant): String {
        if (t == null) return ""
        val secs = t.epochSecond - now.epochSecond
        val a = Math.abs(secs)
        val d = a / 86400
        val h = (a % 86400) / 3600
        val m = (a % 3600) / 60
        val body = if (d > 0) "${d}일 ${h}시간" else if (h > 0) "${h}시간 ${m}분" else "${m}분"
        return body + if (secs >= 0) " 남음" else " 지남"
    }

    // ---- 텍스트 -------------------------------------------------------------

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")

    private fun unescapeHtml(s: String): String = ENTITY.replace(s) { m ->
        val g = m.groupValues[1]
        when {
            g.startsWith("#x") || g.startsWith("#X") -> g.substring(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
            g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { cp(it) } ?: m.value
            else -> when (g) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> m.value
            }
        }
    }

    private fun cp(code: Int): String = if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else ""

    fun htmlToText(html: String?, limit: Int = 600): String {
        if (html.isNullOrEmpty()) return ""
        var s = html
        s = Regex("<(script|style)\\b.*?</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(s, "")
        s = Regex("<\\s*(br|/p|/div|/li|/h\\d|/tr)\\b[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE).replace(s, "- ")
        s = unescapeHtml(Regex("<[^>]+>").replace(s, ""))
        s = Regex("[ \\t\\u00a0]+").replace(s, " ")
        s = Regex(" *\\n *").replace(s, "\n")
        s = Regex("\\n{3,}").replace(s, "\n\n").trim()
        return if (s.length <= limit) s else s.substring(0, limit) + "…"
    }

    // ---- 파일 이름 / 참조 ----------------------------------------------------

    /** 경로 구분자와 제어문자를 없애고, 앞쪽 점을 지우고, 180자로 자른다. */
    fun safeName(name: String?, fallback: String): String {
        var n = (name ?: "").trim()
        n = n.substringAfterLast('/').substringAfterLast('\\')
        n = Regex("[\\u0000-\\u001f/\\\\:*?\"<>|]").replace(n, "_").trimStart('.')
        n = if (n.length > 180) n.substring(0, 180) else n
        return n.ifEmpty { fallback }
    }

    /** "12345", "/courses/1/files/12345", 전체 URL → 12345 */
    fun fileId(ref: String): Long {
        val s = ref.trim()
        if (s.isNotEmpty() && s.all { it.isDigit() }) return s.toLong()
        val m = Regex("/files/(\\d+)").find(s) ?: throw EtlException("파일 ID나 eTL 파일 URL(…/files/<id>)을 주세요")
        return m.groupValues[1].toLong()
    }

    private val SAFE_PATH = Regex("^/api/v1/[\\w\\-./?=&\\[\\]%:,]*$")

    /** etl_get이 허용하는 경로인지. `..`는 막는다(상위 경로로 빠져나가는 것 방지). */
    fun isAllowedGetPath(path: String): Boolean = SAFE_PATH.matches(path) && !path.contains("..")

    // ---- 제출 판정 ----------------------------------------------------------

    data class Evaluation(
        val submitted: Boolean,
        val due: Instant?,
        val submittedAt: Instant?,
        val problems: MutableList<String>,
        val warnings: MutableList<String>,
    )

    /** 첨부 파일 검사를 뺀 제출 판정. a = 과제, s = submissions/self. */
    fun evaluateSubmission(a: JSONObject, s: JSONObject, now: Instant): Evaluation {
        val due = parseInstant(s.str("cached_due_date") ?: a.str("due_at"))
        val subAt = parseInstant(s.str("submitted_at"))
        val allowedTypes = a.strList("submission_types")
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val submitted = s.str("workflow_state") in SUBMITTED || subAt != null
        if (!submitted) {
            if (isOnline(a)) {
                problems += "제출 기록 없음" + if (due != null && due.isBefore(now)) " (마감 지남)" else ""
            } else {
                warnings += "온라인 제출 과제가 아님 (${allowedTypes.joinToString(", ").ifEmpty { "?" }}) — eTL로 확인 불가"
            }
        }
        if (s.optBoolean("late", false)) {
            problems += "지각 제출로 표시됨"
        } else if (submitted && due != null && subAt != null && subAt.isAfter(due)) {
            problems += "마감 후 제출됨"
        }
        if (s.optBoolean("missing", false)) problems += "eTL에 '누락'으로 표시됨"
        if (s.optBoolean("excused", false)) warnings += "면제(excused) 처리됨"
        val st = s.str("submission_type")
        if (submitted && st != null && allowedTypes.isNotEmpty() && st !in allowedTypes) {
            problems += "제출 방식 $st 은(는) 허용되지 않음 (${allowedTypes.joinToString(", ")})"
        }
        return Evaluation(submitted, due, subAt, problems, warnings)
    }

    /** 첨부 하나에 대한 정적 검사(0바이트, 확장자). 문제 문구를 돌려준다. */
    fun checkAttachment(att: JSONObject, allowedExt: List<String>): List<String> {
        val out = mutableListOf<String>()
        val name = att.str("display_name") ?: att.str("filename") ?: ""
        if (att.has("size") && !att.isNull("size") && att.optLong("size", -1) == 0L) out += "빈 파일(0바이트): $name"
        val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        if (allowedExt.isNotEmpty() && ext !in allowedExt) {
            out += "허용되지 않은 확장자 .$ext: $name (허용: ${allowedExt.joinToString(", ")})"
        }
        return out
    }
}
