package kr.snu.etlassistant.study

import kr.snu.etlassistant.etl.EtlLogic
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** 공개 기출 자료 도구의 오류. 메시지는 그대로 비서에게 간다(주소의 쿼리·개인 정보는 넣지 않는다). */
class StudyException(message: String) : Exception(message)

/**
 * 공개 기출 자료를 찾는 사이트 허용 목록(한 표). 사이트를 늘릴 때는 [ALL]에 한 줄을 더하고, 검색 응답 해석이 다르면
 * [StudyLogic]에 그 사이트의 갈래를 둔다. 여기 없는 호스트에는 요청하지 않는다(HTTPS만, 리다이렉트도 이 목록 안만).
 *
 * MIT OpenCourseWare(2026-11-27 확인):
 * - 검색: MIT Learn 공개 API `GET https://api.learn.mit.edu/api/v1/learning_resources_search/`(과목),
 *   `GET …/content_file_search/`(과목 안 자료). 로그인·쿠키 없이 JSON. OCW 사이트의 검색 화면도 이 API를 쓴다.
 * - 자료: 과목 페이지·자료 페이지(HTML)와 PDF 모두 `https://ocw.mit.edu/courses/…`에서 바로 온다(CDN 리다이렉트 없음).
 */
object StudySites {
    data class Site(
        val id: String,
        val name: String,
        /** 검색 API 호스트(JSON만 받는다). */
        val searchHosts: Set<String>,
        /** `study_fetch`로 페이지·PDF를 받을 수 있는 호스트. */
        val fetchHosts: Set<String>,
        val license: String,
        val licenseUrl: String,
        /** 출처 표기 형식. {instructor}, {course}, {term}은 비서가 채운다. */
        val attribution: String,
    )

    val MIT_OCW = Site(
        id = "mit_ocw",
        name = "MIT OpenCourseWare",
        searchHosts = setOf("api.learn.mit.edu"),
        fetchHosts = setOf("ocw.mit.edu"),
        license = "CC BY-NC-SA 4.0",
        licenseUrl = "https://creativecommons.org/licenses/by-nc-sa/4.0/",
        attribution = "{instructor}. {course}. {term}. Massachusetts Institute of Technology: MIT OpenCourseWare, https://ocw.mit.edu. License: Creative Commons BY-NC-SA 4.0.",
    )

    val ALL: List<Site> = listOf(MIT_OCW)

    fun byId(id: String?): Site? = ALL.firstOrNull { it.id == (id?.trim()?.lowercase()?.ifEmpty { null } ?: MIT_OCW.id) }

    fun forFetchHost(host: String?): Site? = host?.lowercase()?.let { h -> ALL.firstOrNull { h in it.fetchHosts } }

    fun isSearchHost(host: String?): Boolean = host?.lowercase()?.let { h -> ALL.any { h in it.searchHosts } } == true
}

/** 순수 로직(안드로이드 API 없음): 주소 검사, 검색 주소, 응답 해석, 크기 상한. */
object StudyLogic {
    const val MAX_PDF_BYTES = 30L shl 20
    const val MAX_HTML_BYTES = 2 shl 20
    const val MAX_JSON_BYTES = 2 shl 20
    const val MAX_REDIRECTS = 3
    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 15_000
    const val PDF_READ_TIMEOUT_MS = 30_000
    /** 앱 전체에서 동시에 보내는 공개 자료 요청 수. */
    const val MAX_CONCURRENT = 2
    const val DEFAULT_TEXT_CHARS = 12_000
    const val MAX_TEXT_CHARS = 40_000
    const val MAX_LINKS = 60
    const val DEFAULT_LIMIT = 8
    const val MAX_LIMIT = 20
    const val MAX_FILES = 50
    /** 개인 정보 없는 일반 User-Agent(앱·기기·계정 정보를 넣지 않는다). */
    const val USER_AGENT = "Mozilla/5.0 (compatible; StudyMaterialFetcher/1.0)"

    const val DISABLED_MESSAGE = "공개 기출 자료 찾기가 꺼져 있어요. 사용자에게 앱의 설정 → 대화 → '공개 기출 자료 찾기'를 켜 달라고 안내하세요."

    /** `materials` 인자 → OCW 자료 종류(content_feature_type / course_feature). 빈 목록은 거르지 않음. */
    val MATERIALS: Map<String, List<String>> = linkedMapOf(
        "exams_and_problem_sets" to listOf("Exams", "Exam Solutions", "Supplemental Exam Materials", "Problem Sets", "Problem Set Solutions"),
        "exams" to listOf("Exams", "Exam Solutions", "Supplemental Exam Materials"),
        "problem_sets" to listOf("Problem Sets", "Problem Set Solutions", "Problem-solving Notes"),
        "lecture_notes" to listOf("Lecture Notes"),
        "all" to emptyList(),
    )

    /** 과목 검색에서 거를 때 쓰는 과목 특성(문제가 있는 과목). */
    private val COURSE_FILTERS = mapOf(
        "exams_and_problem_sets" to listOf("Exams", "Problem Sets"),
        "exams" to listOf("Exams"),
        "problem_sets" to listOf("Problem Sets"),
        "lecture_notes" to listOf("Lecture Notes"),
        "all" to emptyList(),
    )

    /** 결과에 보여 줄 과목 특성(시험 자료 만들기에 쓰는 것만). */
    private val SHOWN_FEATURES = listOf("Exams", "Exam Solutions", "Supplemental Exam Materials", "Problem Sets", "Problem Set Solutions",
        "Problem-solving Notes", "Lecture Notes", "Lecture Videos")

    fun materials(arg: String?): String {
        val m = arg?.trim()?.lowercase()?.ifEmpty { null } ?: "exams_and_problem_sets"
        if (m !in MATERIALS) throw StudyException("materials는 ${MATERIALS.keys.joinToString(", ")} 중 하나여야 해요")
        return m
    }

    fun site(arg: String?): StudySites.Site =
        StudySites.byId(arg) ?: throw StudyException("site는 ${StudySites.ALL.joinToString(", ") { it.id }} 중 하나여야 해요")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 과목 검색 주소(MIT Learn 공개 API, GET). */
    fun courseSearchUrl(site: StudySites.Site, query: String, materials: String, limit: Int): String {
        require(site == StudySites.MIT_OCW)
        val q = cleanQuery(query)
        val n = limit.coerceIn(1, MAX_LIMIT)
        val features = COURSE_FILTERS[materials].orEmpty().joinToString("") { "&course_feature=${enc(it)}" }
        return "https://api.learn.mit.edu/api/v1/learning_resources_search/?q=${enc(q)}&offered_by=ocw&resource_type=course$features&limit=$n"
    }

    /** 과목 하나 안의 자료 검색 주소. [courseId]는 과목 검색 결과의 `course_id`. */
    fun fileSearchUrl(site: StudySites.Site, courseId: Long, query: String?, materials: String): String {
        require(site == StudySites.MIT_OCW)
        if (courseId <= 0) throw StudyException("course_id는 study_search 결과의 양의 정수여야 해요")
        val q = query?.let { cleanQuery(it, allowEmpty = true) }.orEmpty()
        val types = MATERIALS[materials].orEmpty().joinToString("") { "&content_feature_type=${enc(it)}" }
        return "https://api.learn.mit.edu/api/v1/content_file_search/?resource_id=$courseId" +
            (if (q.isNotEmpty()) "&q=${enc(q)}" else "") + "$types&limit=$MAX_FILES"
    }

    fun cleanQuery(query: String, allowEmpty: Boolean = false): String {
        val q = query.replace(Regex("[\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
        if (q.isEmpty() && !allowEmpty) throw StudyException("query(찾을 주제, 영어)가 필요해요")
        return q
    }

    /** 영어가 아닌 검색어로 결과가 없을 때의 안내. */
    fun nonEnglishHint(query: String): String? =
        if (query.any { it.code > 0x7f }) "MIT OCW는 영어 자료라 한국어 검색어로는 거의 찾지 못해요. 주제를 영어로 바꿔 다시 찾으세요(예: 회로이론 → circuit analysis)." else null

    /**
     * 받을 주소를 검사해 정리한 주소를 돌려준다. 허용 목록 호스트만, HTTPS만(허용 호스트의 http는 https로 올린다),
     * 사용자 정보·다른 포트는 거부하고 `#` 뒤는 버린다.
     */
    fun checkFetchUrl(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw StudyException("url이 필요해요(study_search 결과의 url)")
        val u = try { URI(text) } catch (_: Exception) { throw StudyException("주소를 읽을 수 없어요") }
        val host = u.host?.lowercase() ?: throw StudyException("주소를 읽을 수 없어요")
        StudySites.forFetchHost(host) ?: throw StudyException(
            "허용된 공개 자료 사이트(${StudySites.ALL.flatMap { it.fetchHosts }.joinToString()})의 주소만 받을 수 있어요")
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") throw StudyException("https 주소만 받을 수 있어요")
        if (u.rawUserInfo != null) throw StudyException("허용되지 않은 주소예요")
        if (u.port != -1 && u.port != 443) throw StudyException("허용되지 않은 주소예요")
        val path = u.rawPath?.ifEmpty { "/" } ?: "/"
        return "https://$host$path" + (u.rawQuery?.let { "?$it" } ?: "")
    }

    /** 리다이렉트 [location]을 [current] 기준으로 풀고 같은 규칙으로 검사한다(허용 목록 밖이면 중단). */
    fun redirectTarget(current: String, location: String?): String {
        val loc = location?.trim()?.ifEmpty { null } ?: throw StudyException("이동할 주소가 없는 응답이에요")
        val abs = try { URI(current).resolve(loc).toString() } catch (_: Exception) { throw StudyException("이동할 주소를 읽을 수 없어요") }
        if (URI(abs).scheme?.lowercase() != "https") throw StudyException("https가 아닌 곳으로 이동하려 해서 멈췄어요")
        return try { checkFetchUrl(abs) } catch (e: StudyException) { throw StudyException("허용 목록 밖으로 이동하려 해서 멈췄어요") }
    }

    enum class Kind { PDF, HTML, OTHER }

    fun kindOf(contentType: String?): Kind {
        val t = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            t == "application/pdf" -> Kind.PDF
            t == "text/html" || t == "application/xhtml+xml" -> Kind.HTML
            else -> Kind.OTHER
        }
    }

    fun looksLikePdf(head: ByteArray): Boolean = head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
        head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()

    /** 작업 폴더에 둘 이름: `ocw-{주소 해시 10자}-{정리한 이름}.pdf`. 같은 주소는 같은 이름이라 다시 받아도 쌓이지 않는다. */
    fun storedName(site: StudySites.Site, url: String, name: String?): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(10)
        val prefix = if (site == StudySites.MIT_OCW) "ocw" else site.id
        var n = EtlLogic.safeName(name, "file").replace(' ', '_')
        if (!n.lowercase().endsWith(".pdf")) n += ".pdf"
        return "$prefix-$hash-$n"
    }

    // ---- 응답 해석 ---------------------------------------------------------

    private fun JSONObject.s(key: String): String? = if (isNull(key)) null else optString(key).trim().ifEmpty { null }

    private fun strings(a: JSONArray?): List<String> =
        (0 until (a?.length() ?: 0)).mapNotNull { a!!.opt(it) as? String }.map { it.trim() }.filter { it.isNotEmpty() }

    private fun licenseOf(site: StudySites.Site, cc: Boolean?): String = if (cc == false) "확인 필요(각 자료 페이지의 라이선스를 보세요)" else site.license

    /** 과목 검색 응답(learning_resources_search) → 비서용 목록. */
    fun parseCourses(site: StudySites.Site, body: String, query: String): JSONObject {
        val root = try { JSONObject(body) } catch (_: Exception) { throw StudyException("${site.name} 검색 응답을 해석할 수 없어요") }
        val results = root.optJSONArray("results") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val id = r.optLong("id", -1)
            if (id <= 0) continue
            val run = r.optJSONArray("runs")?.optJSONObject(0)
            val term = listOfNotNull(run?.s("semester"), run?.opt("year")?.takeIf { it is Number }?.toString()).joinToString(" ").ifEmpty { null }
            val numbers = r.optJSONObject("course")?.optJSONArray("course_numbers")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.s("value") }
            }.orEmpty()
            val features = strings(r.optJSONArray("course_feature"))
            val level = run?.optJSONArray("level")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.s("name") } }.orEmpty()
            val instructors = run?.optJSONArray("instructors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.s("full_name") } }.orEmpty()
            val departments = r.optJSONArray("departments")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.s("name") } }.orEmpty()
            out.put(JSONObject()
                .put("course_id", id)
                .put("title", r.s("title") ?: "(제목 없음)")
                .put("course_numbers", JSONArray(numbers))
                .put("term", term ?: JSONObject.NULL)
                .put("level", JSONArray(level))
                .put("instructors", JSONArray(instructors.take(5)))
                .put("departments", JSONArray(departments))
                .put("url", r.s("url") ?: JSONObject.NULL)
                .put("materials", JSONArray(SHOWN_FEATURES.filter { it in features }))
                .put("has_exam_solutions", "Exam Solutions" in features)
                .put("has_problem_set_solutions", "Problem Set Solutions" in features)
                .put("description", EtlLogic.htmlToText(r.s("description"), 400))
                .put("license", licenseOf(site, if (r.has("license_cc") && !r.isNull("license_cc")) r.optBoolean("license_cc") else null)))
        }
        return JSONObject()
            .put("site", site.name)
            .put("query", query)
            .put("total", root.optInt("count", out.length()))
            .put("courses", out)
            .put("license", site.license)
            .put("license_url", site.licenseUrl)
            .put("next", "자료 목록은 study_search에 course_id를 넣어 다시 부르고, 받을 자료는 study_fetch(url)로 받으세요")
            .apply { if (out.length() == 0) nonEnglishHint(query)?.let { put("hint", it) } }
    }

    /** 과목 안 자료 검색 응답(content_file_search) → 자료 목록. */
    fun parseFiles(site: StudySites.Site, body: String, courseId: Long): JSONObject {
        val root = try { JSONObject(body) } catch (_: Exception) { throw StudyException("${site.name} 자료 목록을 해석할 수 없어요") }
        val results = root.optJSONArray("results") ?: JSONArray()
        val files = JSONArray()
        var course: String? = null
        var term: String? = null
        var numbers: List<String> = emptyList()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val url = r.s("url") ?: continue
            course = course ?: r.s("run_title")
            term = term ?: listOfNotNull(r.s("semester"), r.opt("year")?.takeIf { it is Number }?.toString()).joinToString(" ").ifEmpty { null }
            if (numbers.isEmpty()) numbers = strings(r.optJSONArray("course_number"))
            val contentType = r.s("content_type")
            val format = when {
                contentType == "page" -> "page"
                r.s("file_type") == "application/pdf" || r.s("file_extension") == ".pdf" -> "pdf"
                else -> r.s("file_extension")?.removePrefix(".") ?: contentType ?: "file"
            }
            files.put(JSONObject()
                .put("title", r.s("title") ?: r.s("content_title") ?: "(제목 없음)")
                .put("types", JSONArray(strings(r.optJSONArray("content_feature_type"))))
                .put("format", format)
                .put("fetchable", format == "pdf" || format == "page")
                .put("url", url)
                .put("description", EtlLogic.htmlToText(r.s("description"), 200)))
        }
        return JSONObject()
            .put("site", site.name)
            .put("course_id", courseId)
            .put("course", course ?: JSONObject.NULL)
            .put("course_numbers", JSONArray(numbers))
            .put("term", term ?: JSONObject.NULL)
            .put("total", root.optInt("count", files.length()))
            .put("files", files)
            .put("license", site.license)
            .put("license_url", site.licenseUrl)
            .put("next", "fetchable=true인 자료를 study_fetch(url)로 받으세요. 문제와 풀이(Solutions)는 따로 있는 경우가 많아요")
    }

    data class Link(val text: String, val url: String)

    data class Page(
        val title: String?,
        /** 과목명(배너 또는 제목 줄). */
        val course: String?,
        /** "6.003 | Fall 2011 | Undergraduate" 같은 과목 번호·학기 줄. */
        val numberTerm: String?,
        val instructors: List<String>,
        val licenseUrl: String?,
        val text: String,
        val links: List<Link>,
        /** 자료 페이지가 내려받기로 거는 파일(같은 사이트 안, 절대 주소). */
        val download: String?,
    )

    private val FLAGS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private fun meta(html: String, prop: String): String? =
        Regex("<meta\\s+(?:property|name)=\"${Regex.escape(prop)}\"\\s+content=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.get(1)?.let { EtlLogic.htmlToText(it, 400) }?.ifEmpty { null }

    /** OCW 페이지(HTML) → 제목·과목·학기·라이선스·본문·링크·내려받기 주소. [pageUrl]은 최종(리다이렉트 뒤) 주소. */
    fun parsePage(html: String, pageUrl: String, maxChars: Int = DEFAULT_TEXT_CHARS): Page {
        val ogTitle = meta(html, "og:title")
        val parts = ogTitle?.split(" | ")?.map { it.trim() }.orEmpty()
        val title = parts.firstOrNull()
        val banner = Regex("<span class=\"course-number-term-detail\">(.*?)</span>", FLAGS).find(html)?.groupValues?.get(1)
            ?.let { EtlLogic.htmlToText(it, 200) }?.ifEmpty { null }
        val bannerCourse = Regex("<div id=\"course-banner\".*?<h1>(.*?)</h1>", FLAGS).find(html)?.groupValues?.get(1)
            ?.let { EtlLogic.htmlToText(it, 200) }?.ifEmpty { null }
        val course = bannerCourse ?: parts.getOrNull(1)?.takeIf { parts.size >= 3 }
        val license = Regex("\"license\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        val instructors = Regex("class=\"course-info-instructor[^\"]*\"[^>]*>(.*?)</a>", FLAGS).findAll(html)
            .map { EtlLogic.htmlToText(it.groupValues[1], 100) }.filter { it.isNotEmpty() }.distinct().take(5).toList()
        val section = mainSection(html)
        val links = Regex("<a\\b[^>]*?href=\"([^\"]+)\"[^>]*>(.*?)</a>", FLAGS).findAll(section).mapNotNull { m ->
            val url = absoluteSameSite(pageUrl, m.groupValues[1]) ?: return@mapNotNull null
            Link(EtlLogic.htmlToText(m.groupValues[2], 160).ifEmpty { url.substringAfterLast('/') }, url)
        }.distinctBy { it.url }.take(MAX_LINKS).toList()
        val download = Regex("<a\\s+class=\"download-file\"\\s+href=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.get(1)?.let { absoluteSameSite(pageUrl, it) }
        val cells = section.replace(Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE), " | ")
        val text = EtlLogic.htmlToText(cells, maxChars.coerceIn(500, MAX_TEXT_CHARS))
        return Page(title, course, banner, instructors, license, text, links, download)
    }

    /** 본문 영역: 머리·메뉴·바닥글을 뺀 `main-course-section`(없으면 body) 안, PDF 보기 스크립트 앞까지. */
    private fun mainSection(html: String): String {
        val start = html.indexOf("id=\"main-course-section\"").takeIf { it >= 0 }
            ?: html.indexOf("<main").takeIf { it >= 0 } ?: html.indexOf("<body").coerceAtLeast(0)
        val ends = listOf("class=\"pdf-viewer", "</main>", "id=\"course-info-drawer\"", "<footer")
            .map { html.indexOf(it, start) }.filter { it > start }
        val end = ends.minOrNull() ?: html.length
        return html.substring(start, end)
    }

    /** 페이지 안 링크를 절대 주소로. 허용 목록 밖, 자바스크립트, 페이지 안 앵커는 버린다. */
    fun absoluteSameSite(pageUrl: String, href: String): String? {
        val h = EtlLogic.htmlToText(href, 2000).trim()
        if (h.isEmpty() || h.startsWith("#") || h.startsWith("javascript:", ignoreCase = true) || h.startsWith("mailto:", ignoreCase = true)) return null
        return try {
            val abs = URI(pageUrl).resolve(h.replace(" ", "%20")).toString().substringBefore('#')
            checkFetchUrl(abs)
        } catch (_: Exception) {
            null
        }
    }

    /** 결과의 출처 묶음. */
    fun source(site: StudySites.Site, pageUrl: String, page: Page?, fileUrl: String? = null): JSONObject {
        val term = page?.numberTerm?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val license = when {
            page?.licenseUrl == null || page.licenseUrl.contains("by-nc-sa/4.0") -> site.license
            else -> "확인 필요: ${page.licenseUrl}"
        }
        return JSONObject()
            .put("site", site.name)
            .put("page_url", pageUrl)
            .put("file_url", fileUrl ?: JSONObject.NULL)
            .put("title", page?.title ?: JSONObject.NULL)
            .put("course", page?.course ?: JSONObject.NULL)
            .put("course_number", term?.getOrNull(0) ?: JSONObject.NULL)
            .put("term", term?.getOrNull(1) ?: JSONObject.NULL)
            .put("level", term?.getOrNull(2) ?: JSONObject.NULL)
            .put("instructors", JSONArray(page?.instructors.orEmpty()))
            .put("license", license)
            .put("license_url", page?.licenseUrl ?: site.licenseUrl)
            .put("attribution", site.attribution)
    }
}
