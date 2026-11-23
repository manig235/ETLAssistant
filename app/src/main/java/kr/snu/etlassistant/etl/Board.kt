package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/*
 * 과목 게시판(LearningX). ttokttok-pet `src/etl/board.js`(etl-board 브랜치)를 옮긴 것이다.
 *
 * eTL 과목 메뉴의 "게시판"은 Canvas 토론이 아니라 같은 호스트의 LTI 도구(LearningX)이고, JSON API가
 * /learningx/api/v1/learningx_board/ 아래에 있다. 이 API는 Canvas 토큰이 아니라 LearningX 자체 토큰
 * (브라우저가 xn_api_token 쿠키로 갖는 JWT, 약 2시간)을 요구한다. 브라우저와 같은 방법으로 받는다.
 *
 *   1. GET  /api/v1/courses/<cid>/tabs                         게시판 탭 → 도구 ID
 *   2. GET  /api/v1/courses/<cid>/external_tools/sessionless_launch?id=<도구>   → 일회용 실행 링크(verifier 포함)
 *   3. GET  그 링크                                             → Canvas가 자동 제출하는 LTI 폼
 *   4. POST 그 폼을 /learningx/lti/… 로                         → Set-Cookie: xn_api_token=<JWT>
 *
 * 4단계가 앱이 eTL에 보내는 **유일한 POST**다: eTL 자체 호스트의 LearningX로, Canvas가 서명한 필드만 담은 고정 LTI 실행.
 * 그 뒤는 전부 GET이다. 실행 링크·폼·LearningX 토큰은 이 파일 밖으로 나가지 않는다(어떤 결과나 오류 문구에도 넣지 않는다).
 * 토큰은 메모리에만 두고([BoardSession]) 만료 조금 전까지 쓴다. 과목과 무관하므로 한 번 실행으로 모든 과목에 쓴다.
 * 글을 읽어도 "읽음" 처리는 하지 않는다(게시판 화면은 그걸 별도 POST로 보내는데, 앱은 보내지 않는다).
 */

/** 순수 로직(네트워크 없음). */
object BoardLogic {
    const val BASE = EtlClient.BASE_URL
    const val LTI_PATH = "/learningx/lti/"
    private val LAUNCH_PATH = Regex("^/courses/\\d+/external_tools/sessionless_launch$")
    private val BOARD_LABEL = Regex("게시판|board", RegexOption.IGNORE_CASE)
    private val TOOL_ID = Regex("^context_external_tool_(\\d+)$")
    const val POST_LIMIT = 8000
    const val COMMENT_LIMIT = 2000
    const val DESCRIPTION_LIMIT = 500

    data class LaunchForm(val action: String, val fields: List<Pair<String, String>>)

    /** 과목 탭 중 "게시판" 외부 도구의 ID. 없으면 null. */
    fun boardToolId(tabs: Any?): Long? {
        val arr = tabs as? JSONArray ?: return null
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("type") != "external") continue
            if (!BOARD_LABEL.containsMatchIn(t.optString("label"))) continue
            TOOL_ID.find(t.optString("id"))?.let { return it.groupValues[1].toLongOrNull() }
        }
        return null
    }

    /** 실행 링크가 eTL의 sessionless_launch 경로인지. */
    fun isLaunchUrl(url: String?): Boolean = try {
        val u = URL(url ?: return false)
        "${u.protocol}://${u.authority}" == BASE && LAUNCH_PATH.matches(u.path) && u.userInfo == null
    } catch (_: Exception) {
        false
    }

    fun decodeAttr(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|#39);", RegexOption.IGNORE_CASE).replace(s) { m ->
        when (val n = m.groupValues[1].lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos", "#39" -> "'"
            else -> {
                val code = if (n[1] == 'x') n.substring(2).toIntOrNull(16) else n.substring(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
            }
        }
    }

    private fun attr(attrs: String, name: String): String? {
        val m = Regex("(?:^|\\s)$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", RegexOption.IGNORE_CASE).find(attrs) ?: return null
        val v = m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: return null
        return decodeAttr(v)
    }

    /** Canvas 실행 페이지에서 eTL 호스트의 LearningX로 POST하는 첫 폼. HTML은 읽기만 한다(실행하지 않는다). */
    fun launchForm(html: String): LaunchForm? {
        for (m in Regex("<form\\b([^>]*)>([\\s\\S]*?)</form>", RegexOption.IGNORE_CASE).findAll(html)) {
            val action = try {
                URL(URL("$BASE/"), attr(m.groupValues[1], "action") ?: "")
            } catch (_: Exception) {
                continue
            }
            if ("${action.protocol}://${action.authority}" != BASE || !action.path.startsWith(LTI_PATH)) continue
            val fields = mutableListOf<Pair<String, String>>()
            for (input in Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(m.groupValues[2])) {
                val name = attr(input.groupValues[1], "name") ?: continue
                fields += name to (attr(input.groupValues[1], "value") ?: "")
            }
            return LaunchForm(action.toString(), fields)
        }
        return null
    }

    /** JWT의 만료 시각(ms). 서명은 LearningX가 확인하므로 payload만 읽는다. */
    fun jwtExpiryMillis(token: String): Long? = try {
        val payload = String(Base64.getUrlDecoder().decode(token.split(".")[1].trimEnd('=')), Charsets.UTF_8)
        val o = JSONObject(payload)
        if (o.has("exp") && !o.isNull("exp")) (o.getDouble("exp") * 1000).toLong() else null
    } catch (_: Exception) {
        null
    }

    /** Set-Cookie 헤더들에서 [name] 쿠키 값. */
    fun cookieValue(setCookies: List<String>, name: String): String? {
        for (c in setCookies) {
            val pair = c.substringBefore(';')
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq).trim() == name) {
                return try {
                    java.net.URLDecoder.decode(pair.substring(eq + 1).trim(), "UTF-8")
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    fun formBody(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    private fun JSONObject.s(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
    private fun author(p: JSONObject?): String? = p?.s("user_name") ?: p?.s("nickname")
    private fun kst(iso: String?): String? = EtlLogic.parseInstant(iso)?.let { EtlLogic.fmtKst(it) }
    private fun validId(v: Long) = v > 0

    /** LearningX가 준 글 링크가 eTL 안이면 그대로, 아니면 [fallback]. */
    fun postLink(p: JSONObject?, fallback: String): String = try {
        val u = URL(p?.s("post_url") ?: return fallback)
        if ("${u.protocol}://${u.authority}" == BASE && u.userInfo == null) u.toString() else fallback
    } catch (_: Exception) {
        fallback
    }

    fun boardsResult(courseId: Long, list: Any?, url: String): JSONObject {
        val rows = JSONArray()
        (list as? JSONArray)?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                rows.put(
                    JSONObject()
                        .put("board_id", b.optLong("id"))
                        .put("title", b.s("title") ?: JSONObject.NULL)
                        .put("anonymous", b.optString("type") == "anonymous")
                        .put("description", EtlLogic.htmlToText(b.s("description"), DESCRIPTION_LIMIT))
                        .put("posts", if (b.has("total_post_count")) b.optInt("total_post_count") else JSONObject.NULL)
                        .put("unread", if (b.has("unread_post_count")) b.optInt("unread_post_count") else JSONObject.NULL)
                        .put("latest_post", kst(b.s("latest_post_created_at")) ?: JSONObject.NULL)
                )
            }
        }
        return JSONObject().put("course_id", courseId).put("boards", rows).put("url", url)
    }

    fun postsResult(courseId: Long, boardId: Long, page: Int, out: Any?, url: String): JSONObject {
        val o = out as? JSONObject ?: JSONObject()
        val items = o.optJSONArray("items") ?: JSONArray()
        val pg = o.optJSONObject("pagination") ?: JSONObject()
        val rows = JSONArray()
        for (i in 0 until items.length()) {
            val p = items.optJSONObject(i) ?: continue
            rows.put(
                JSONObject()
                    .put("post_id", p.optLong("id"))
                    .put("title", p.s("title") ?: JSONObject.NULL)
                    .put("author", author(p) ?: JSONObject.NULL)
                    .put("notice", p.optBoolean("is_notice", false))
                    .put("reply", p.optInt("depth", 0) > 0)
                    .put("secret", p.optBoolean("is_secret", false))
                    .put("attachments", p.optInt("attachment_count", 0))
                    .put("comments", p.optInt("comment_count", 0))
                    .put("views", if (p.has("view_count")) p.optInt("view_count") else JSONObject.NULL)
                    .put("created", kst(p.s("created_at")) ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("course_id", courseId)
            .put("board_id", boardId)
            .put("page", if (pg.has("current_page")) pg.optInt("current_page") else page)
            .put("last_page", if (pg.has("last_page")) pg.optInt("last_page") else JSONObject.NULL)
            .put("total", if (pg.has("total")) pg.optInt("total") else JSONObject.NULL)
            .put("posts", rows)
            .put("url", url)
    }

    /** 첨부 하나: 이름, 크기, Canvas 파일 ID. LearningX가 준 링크(서명 포함)는 넘기지 않는다. */
    private fun attachmentRow(a: JSONObject): JSONObject = JSONObject()
        .put("name", a.s("filename") ?: a.s("display_name") ?: JSONObject.NULL)
        .put("size", if (a.has("filesize")) a.optLong("filesize") else if (a.has("size")) a.optLong("size") else JSONObject.NULL)
        .put("file_id", a.optLong("canvas_file_id").takeIf(::validId) ?: JSONObject.NULL)

    private fun commentRow(c: JSONObject): JSONObject {
        val o = JSONObject()
            .put("author", author(c) ?: JSONObject.NULL)
            .put("text", EtlLogic.htmlToText(c.s("content") ?: c.s("comment") ?: c.s("body"), COMMENT_LIMIT))
            .put("created", kst(c.s("created_at")) ?: JSONObject.NULL)
        val children = c.optJSONArray("children")
        if (children != null && children.length() > 0) {
            o.put("replies", JSONArray().also { r -> for (i in 0 until children.length()) children.optJSONObject(i)?.let { r.put(commentRow(it)) } })
        }
        return o
    }

    private fun neighbour(p: JSONObject?): Any =
        if (p != null && validId(p.optLong("id"))) JSONObject()
            .put("post_id", p.optLong("id"))
            .put("title", p.s("title") ?: JSONObject.NULL)
            .put("author", author(p) ?: JSONObject.NULL)
            .put("created", kst(p.s("created_at")) ?: JSONObject.NULL)
        else JSONObject.NULL

    fun postResult(courseId: Long, boardId: Long, postId: Long, p: JSONObject, url: String): JSONObject {
        val atts = JSONArray()
        p.optJSONArray("attachments")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { atts.put(attachmentRow(it)) } }
        val comments = JSONArray()
        p.optJSONArray("comments")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { comments.put(commentRow(it)) } }
        val id = p.optLong("id").takeIf(::validId) ?: postId
        val out = JSONObject()
            .put("course_id", courseId)
            .put("board_id", boardId)
            .put("post_id", id)
            .put("title", p.s("title") ?: JSONObject.NULL)
            .put("author", author(p) ?: JSONObject.NULL)
            .put("notice", p.optBoolean("is_notice", false))
            .put("created", kst(p.s("created_at")) ?: JSONObject.NULL)
            .put("updated", kst(p.s("updated_at")) ?: JSONObject.NULL)
            .put("views", if (p.has("view_count")) p.optInt("view_count") else JSONObject.NULL)
            .put("text", EtlLogic.htmlToText(p.s("content"), POST_LIMIT))
            .put("attachments", atts)
            .put("comments", comments)
            .put("previous", neighbour(p.optJSONObject("prev_post")))
            .put("next", neighbour(p.optJSONObject("next_post")))
            .put("url", postLink(p, url))
        if (atts.length() > 0) {
            out.put("attachment_note", "첨부파일은 etl_download에 file_id와 함께 course_id=$courseId, board_id=$boardId, post_id=$id 를 넘겨 받는다.")
        }
        return out
    }

    /** 글에 Canvas 파일 [fileId]가 첨부되어 있는가. */
    fun hasAttachment(p: JSONObject, fileId: Long): Boolean {
        val arr = p.optJSONArray("attachments") ?: return false
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optLong("canvas_file_id") == fileId) return true
        return false
    }
}

/**
 * LearningX 토큰을 앱 메모리에만 둔다. [owner]는 eTL 토큰의 해시라서, 토큰이 바뀌면 이전 사람의 LearningX 토큰을 쓰지 않는다.
 */
object BoardSession {
    private var owner: String? = null
    private var token: String? = null
    private var until = 0L
    /** 실행이 겹치지 않게(동시에 여러 요청이 와도 한 번만 실행한다). */
    val launchLock = Any()

    @Synchronized fun get(owner: String, now: Long): String? = if (this.owner == owner && now < until) token else null
    @Synchronized fun set(owner: String, token: String, until: Long) {
        this.owner = owner; this.token = token; this.until = until
    }
    @Synchronized fun drop(owner: String) {
        if (this.owner == owner) { this.owner = null; token = null; until = 0L }
    }
    /** eTL 연결 해제: 누구 것이든 지운다. */
    @Synchronized fun clear() { owner = null; token = null; until = 0L }

    fun ownerOf(etlToken: String): String =
        MessageDigest.getInstance("SHA-256").digest(etlToken.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * 과목 게시판 조회. [etl]은 Canvas API(탭, 실행 링크)에 쓰고, 그 밖의 요청은 eTL 호스트에만 보낸다.
 * 오류 문구에는 경로·실행 링크·토큰을 넣지 않는다. LearningX가 토큰을 거절해도 eTL 토큰 만료(401)로 보이지 않게 한다.
 */
class BoardClient(private val etl: EtlClient, private val owner: String, private val now: () -> Long = System::currentTimeMillis) {

    private data class Course(val id: Long, val tool: Long) {
        val url get() = "${BoardLogic.BASE}/courses/$id/external_tools/$tool"
    }

    private fun open(courseId: Long): Course {
        if (courseId <= 0) throw EtlException("course_id 가 필요합니다(양의 정수)")
        val tabs = try {
            etl.get("/api/v1/courses/$courseId/tabs")
        } catch (e: EtlAuthException) {
            throw e
        } catch (e: EtlException) {
            throw EtlException(NO_BOARD, "no-board")
        }
        val tool = BoardLogic.boardToolId(tabs) ?: throw EtlException(NO_BOARD, "no-board")
        return Course(courseId, tool)
    }

    fun boards(courseId: Long): JSONObject {
        val c = open(courseId)
        return BoardLogic.boardsResult(c.id, api(c, "/courses/${c.id}/boards"), c.url)
    }

    fun posts(courseId: Long, boardId: Long, page: Int, keyword: String): JSONObject {
        if (boardId <= 0) throw EtlException("board_id 가 필요합니다(양의 정수)")
        val c = open(courseId)
        val p = page.coerceIn(1, 1000)
        val q = "page=$p&filter=title&keyword=" + URLEncoder.encode(keyword.take(100), "UTF-8")
        return BoardLogic.postsResult(c.id, boardId, p, api(c, "/courses/${c.id}/boards/$boardId/posts?$q"), c.url)
    }

    fun post(courseId: Long, boardId: Long, postId: Long): JSONObject {
        if (boardId <= 0) throw EtlException("board_id 가 필요합니다(양의 정수)")
        if (postId <= 0) throw EtlException("post_id 가 필요합니다(양의 정수)")
        val c = open(courseId)
        val p = api(c, "/courses/${c.id}/boards/$boardId/posts/$postId") as? JSONObject
            ?: throw EtlException("게시판 응답을 해석할 수 없습니다", "parse")
        return BoardLogic.postResult(c.id, boardId, postId, p, c.url)
    }

    /** 파일 [fileId]가 그 글의 첨부인지 확인하고 글 제목을 돌려준다. 아니면 오류. */
    fun attachmentTitle(courseId: Long, boardId: Long, postId: Long, fileId: Long): String? {
        val c = open(courseId)
        val p = api(c, "/courses/${c.id}/boards/$boardId/posts/$postId") as? JSONObject
            ?: throw EtlException("게시판 응답을 해석할 수 없습니다", "parse")
        if (!BoardLogic.hasAttachment(p, fileId)) {
            throw EtlException("이 게시글에 파일 ID $fileId 인 첨부가 없습니다. etl_board_post의 attachments에서 file_id를 확인하세요.")
        }
        return if (p.has("title") && !p.isNull("title")) p.optString("title") else null
    }

    // ---- LearningX API --------------------------------------------------------

    /** 토큰을 거절하면(400/401) 한 번만 다시 실행한다. */
    private fun api(c: Course, pathAndQuery: String): Any {
        val what = pathAndQuery.substringBefore('?').replace(Regex("\\d+"), "#")
        repeat(2) {
            val xn = token(c)
            val r = http("$API$pathAndQuery", "GET", mapOf("Authorization" to "Bearer $xn", "Accept" to "application/json"), null, what)
            when {
                r.code == 400 || r.code == 401 -> BoardSession.drop(owner)
                r.code == 403 || r.code == 404 -> throw EtlException("게시판에서 이 항목을 찾을 수 없거나 볼 권한이 없습니다(${r.code}).", "http-${r.code}")
                r.code !in 200..299 -> throw EtlException("게시판 요청 실패(${r.code}): $what", "http-${r.code}")
                else -> return try {
                    EtlClient.parseBody(r.body, what)
                } catch (_: EtlException) {
                    throw EtlException("게시판 응답을 해석할 수 없습니다: $what", "parse")
                }
            }
        }
        throw EtlException(REJECTED, "board-rejected")
    }

    private fun token(c: Course): String {
        BoardSession.get(owner, now())?.let { return it }
        synchronized(BoardSession.launchLock) {
            BoardSession.get(owner, now())?.let { return it }
            return launch(c)
        }
    }

    /** 위 2~4단계. */
    private fun launch(c: Course): String {
        val link = try {
            etl.get("/api/v1/courses/${c.id}/external_tools/sessionless_launch?id=${c.tool}&launch_type=course_navigation") as? JSONObject
        } catch (e: EtlAuthException) {
            throw e
        } catch (_: EtlException) {
            throw EtlException(LAUNCH_FAILED, "board-launch")
        }
        val url = link?.optString("url")
        if (!BoardLogic.isLaunchUrl(url)) throw EtlException(LAUNCH_FAILED, "board-launch")
        val form = BoardLogic.launchForm(getPage(url!!)) ?: throw EtlException(LAUNCH_FAILED, "board-launch")
        val r = http(
            form.action, "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "text/html"),
            BoardLogic.formBody(form.fields), "게시판 실행",
        )
        val xn = BoardLogic.cookieValue(r.setCookies, "xn_api_token")
        if (xn == null || !Regex("[\\u0021-\\u007e]+").matches(xn)) throw EtlException(LAUNCH_FAILED, "board-launch")
        val at = now()
        val until = BoardLogic.jwtExpiryMillis(xn)?.let { it - EXPIRY_MARGIN_MS } ?: (at + FALLBACK_TTL_MS)
        if (until <= at) throw EtlException(LAUNCH_FAILED, "board-launch")
        BoardSession.set(owner, xn, until)
        return xn
    }

    /** eTL 호스트 안에서만 리다이렉트를 따라가며 GET한다(토큰 없이: 실행 링크 자체가 일회용 서명을 갖는다). */
    private fun getPage(start: String): String {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val r = http(current, "GET", mapOf("Accept" to "text/html"), null, "게시판 실행")
            if (r.code in 300..399) {
                val next = try { URL(URL(current), r.location ?: "") } catch (_: Exception) { null }
                if (next == null || "${next.protocol}://${next.authority}" != BoardLogic.BASE) throw EtlException(LAUNCH_FAILED, "board-launch")
                current = next.toString()
                return@repeat
            }
            if (r.code !in 200..299 || r.body.length > MAX_HTML_CHARS) throw EtlException(LAUNCH_FAILED, "board-launch")
            return r.body
        }
        throw EtlException(LAUNCH_FAILED, "board-launch")
    }

    private class Resp(val code: Int, val body: String, val location: String?, val setCookies: List<String>)

    /** eTL 호스트로만 보낸다. 오류 문구에는 [what]만 쓴다(URL에는 서명이 있을 수 있다). */
    private fun http(url: String, method: String, headers: Map<String, String>, body: String?, what: String): Resp {
        val u = URL(url)
        if ("${u.protocol}://${u.authority}" != BoardLogic.BASE) throw EtlException(LAUNCH_FAILED, "board-launch")
        return EtlClient.gated {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = EtlClient.CONNECT_TIMEOUT_MS
                c.readTimeout = READ_TIMEOUT_MS
                c.requestMethod = method
                c.useCaches = false
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { s ->
                    val bytes = s.readNBytesCompat(MAX_HTML_CHARS + 1)
                    String(bytes, Charsets.UTF_8)
                }.orEmpty()
                val cookies = c.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
                Resp(code, text, c.getHeaderField("Location"), cookies)
            } catch (e: IOException) {
                throw EtlException(
                    if (e is java.net.SocketTimeoutException) "eTL 응답 시간 초과: $what" else "eTL 연결 실패: $what",
                    EtlClient.ioKind(e),
                )
            } finally {
                c.disconnect()
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 14)
        while (out.size() < max) {
            val r = read(buf, 0, minOf(buf.size, max - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    companion object {
        const val API = "${BoardLogic.BASE}/learningx/api/v1/learningx_board"
        const val MAX_REDIRECTS = 3
        const val MAX_HTML_CHARS = 2 * 1024 * 1024 // 게시글 JSON도 이 한도 안에서 읽는다
        const val READ_TIMEOUT_MS = 20_000
        const val EXPIRY_MARGIN_MS = 5 * 60 * 1000L
        const val FALLBACK_TTL_MS = 30 * 60 * 1000L
        const val NO_BOARD = "이 과목에는 게시판 메뉴가 없습니다. eTL에서 과목 메뉴를 확인해 주세요."
        const val LAUNCH_FAILED = "게시판에 연결하지 못했습니다. 잠시 후 다시 시도하고, 계속되면 eTL에서 게시판을 직접 열어 주세요."
        const val REJECTED = "게시판이 접속을 거절했습니다. 잠시 후 다시 시도하고, 계속되면 eTL에서 게시판을 직접 열어 주세요."
    }
}
