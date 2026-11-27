package kr.snu.etlassistant.etl

import org.json.JSONObject
import java.time.LocalDate

/** 파일 탭 노출 여부 대신 서버가 허용하는 파일·과목·게시물 경로를 순서대로 확인한다. */
class FileDownloadResolver(
    private val getObject: (String) -> JSONObject,
    private val getAll: (String) -> List<JSONObject>,
    private val boardAttachment: (Long, Long, Long, Long) -> JSONObject,
) {
    data class Context(val courseId: Long? = null, val sourceType: String? = null, val sourceId: Long? = null,
        val boardId: Long? = null, val postId: Long? = null)

    fun <T : Any> retrieve(fileId: Long, context: Context, receive: (JSONObject) -> T): T {
        require(fileId > 0)
        val triedUrls = hashSetOf<String>()
        fun deliver(meta: JSONObject?): T? {
            if (meta == null) return null
            if (meta.optBoolean("locked_for_user")) throw EtlException("파일 $fileId 은(는) 현재 열람이 잠겨 있습니다")
            val url = meta.optString("url").takeIf { it.isNotBlank() } ?: return null
            if (!triedUrls.add(url)) return null
            return try { receive(meta) }
            catch (e: EtlAuthException) { throw e }
            catch (e: EtlException) {
                if (e.kind !in setOf("forbidden", "http-401", "http-403", "http-404")) throw e
                null
            }
        }
        // hidden_for_user는 파일 탭에서 보이는가를 뜻한다. 단건 조회·실제 다운로드의 권한은 서버가 판정한다.
        deliver(attempt { getObject("/api/v1/files/$fileId") })?.let { return it }
        val cid = context.courseId ?: throw EtlException("파일 단건 조회를 못 했습니다. 공지·게시판에서 얻은 course_id와 첨부 정보를 함께 넘겨 다른 경로로 다시 시도하세요.")
        require(cid > 0)
        deliver(attempt { getObject("/api/v1/courses/$cid/files/$fileId") })?.let { return it }

        if (context.boardId != null && context.postId != null) {
            deliver(attempt { boardAttachment(cid, context.boardId, context.postId, fileId) })?.let { return it }
        }
        val sid = context.sourceId
        if (sid != null && sid > 0) {
            val path = when (context.sourceType) {
                "announcement" -> "/api/v1/courses/$cid/discussion_topics/$sid"
                "assignment" -> "/api/v1/courses/$cid/assignments/$sid?include[]=submission"
                else -> null
            }
            if (path != null) attempt { getObject(path) }?.let { source ->
                if (source.optLong("id") == sid) for (meta in fromSource(source, fileId, cid)) deliver(meta)?.let { return it }
            }
        }
        // 공지 단건 응답에서 공지 구분 필드나 본문이 빠져도 과목 공지 목록은 파일 링크를 줄 수 있다.
        val end = LocalDate.now(EtlLogic.KST).plusDays(1)
        val announcements = attempt {
            getAll("/api/v1/announcements?context_codes[]=course_$cid&start_date=1970-01-01&end_date=$end&per_page=100")
        }.orEmpty()
        for (source in announcements) {
            if (source.has("context_code") && source.optString("context_code") != "course_$cid") continue
            for (meta in fromSource(source, fileId, cid)) deliver(meta)?.let { return it }
        }
        throw EtlException("파일 단건·과목 파일·공지 경로에서 다운로드 정보를 찾지 못했습니다. etl_boards → etl_board_posts → etl_board_post로 게시판 첨부도 확인한 뒤 course_id, board_id, post_id와 함께 다시 시도하세요.")
    }

    private fun <T> attempt(block: () -> T): T? = try { block() }
    catch (e: EtlAuthException) { throw e }
    catch (e: EtlException) {
        // 연결 자체가 끊겼거나 한도에 걸린 경우에는 같은 서버에 요청을 늘리지 않는다.
        if (e.kind !in setOf("forbidden", "http-401", "http-403", "http-404", "parse", "error")) throw e
        null
    }

    private fun fromSource(source: JSONObject, fid: Long, cid: Long): List<JSONObject> {
        if (!Queries.isPublished(source) || source.optBoolean("locked_for_user")) return emptyList()
        val candidates = mutableListOf<JSONObject>()
        val attachments = mutableListOf<JSONObject>()
        source.optJSONArray("attachments")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(attachments::add) }
        source.optJSONObject("attachment")?.let(attachments::add)
        source.optJSONObject("submission")?.let { sub ->
            sub.optJSONArray("attachments")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(attachments::add) }
        }
        for (attachment in attachments) if (attachment.optLong("id") == fid) {
            attachmentMetadata(attachment, fid)?.let(candidates::add)
        }
        val html = source.optString("message").ifBlank { source.optString("description") }
        Queries.linkedFileUrls(html, cid)[fid]?.let { linked ->
            val name = Queries.linkedFileName(html, fid, cid)
            candidates += JSONObject().put("id", fid).put("url", linked).put("display_name", name ?: "file-$fid")
        }
        return candidates
    }

    companion object {
        /** 서버가 열람 가능한 게시물의 첨부로 돌려준 다운로드 정보. 서명 URL은 내부에서만 쓴다. */
        fun attachmentMetadata(attachment: JSONObject, fid: Long): JSONObject? {
            val raw = attachment.optString("url").takeIf { it.isNotBlank() } ?: return null
            val url = runCatching { java.net.URL(java.net.URL(EtlClient.BASE_URL + "/"), raw).toString() }.getOrNull() ?: return null
            if (!EtlClient.sameOrigin(url, EtlClient.BASE_URL)) return null
            val out = JSONObject().put("id", fid).put("url", url)
                .put("display_name", attachment.optString("display_name").ifBlank { attachment.optString("filename", "file-$fid") })
                .put("locked_for_user", attachment.optBoolean("locked_for_user"))
            for (key in listOf("size", "updated_at", "content-type")) {
                if (attachment.has(key) && !attachment.isNull(key)) out.put(key, attachment.get(key))
            }
            if (!out.has("size") && attachment.has("filesize") && !attachment.isNull("filesize")) out.put("size", attachment.get("filesize"))
            return out
        }
    }
}
