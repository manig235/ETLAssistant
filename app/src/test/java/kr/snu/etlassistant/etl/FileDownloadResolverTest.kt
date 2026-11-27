package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FileDownloadResolverTest {
    private val base = EtlClient.BASE_URL
    private fun meta(url: String = "$base/files/42/download") = JSONObject().put("id", 42)
        .put("display_name", "성적.xlsx").put("url", url).put("hidden_for_user", true)
    private fun denied(): Nothing = throw EtlException("denied", "forbidden")
    private fun resolver(get: (String) -> JSONObject, all: (String) -> List<JSONObject> = { error("unexpected list") },
        board: (Long, Long, Long, Long) -> JSONObject = { _, _, _, _ -> error("unexpected board") }) = FileDownloadResolver(get, all, board)
    private fun fails(block: () -> Unit) {
        try { block(); fail("accepted inaccessible resource") } catch (_: EtlException) { }
    }

    @Test fun hiddenFileReadableByIdDoesNotNeedAnnouncementRecognition() {
        var downloads = 0
        val result = resolver({ assertEquals("/api/v1/files/42", it); meta() })
            .retrieve(42, FileDownloadResolver.Context(1, "announcement", 7)) {
                downloads++
                assertTrue(it.getBoolean("hidden_for_user"))
                "readable"
            }
        assertEquals("readable", result)
        assertEquals(1, downloads)
    }

    @Test fun inaccessibleGlobalMetadataFallsBackToCourseFile() {
        val paths = mutableListOf<String>()
        val result = resolver({ path ->
            paths += path
            if (path == "/api/v1/files/42") denied() else meta()
        }).retrieve(42, FileDownloadResolver.Context(1)) { it.getString("display_name") }
        assertEquals("성적.xlsx", result)
        assertEquals(listOf("/api/v1/files/42", "/api/v1/courses/1/files/42"), paths)
    }

    @Test fun failedDownloadTriesAnnouncementAttachmentInsteadOfGivingUp() {
        val bad = "$base/files/42/download?verifier=expired"
        val good = "$base/files/42/download?verifier=fresh"
        val ann = JSONObject().put("id", 7).put("context_code", "course_1")
            .put("attachments", JSONArray().put(meta(good))) // is_announcement가 없는 실제 응답 변형
        val downloads = mutableListOf<String>()
        val result = resolver({ meta(bad) }, { listOf(ann) }).retrieve(42, FileDownloadResolver.Context(1)) {
            val url = it.getString("url")
            downloads += url
            if (url == bad) denied()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(bad, good), downloads) // 같은 실패 URL은 과목 경로에서도 반복하지 않는다.
    }

    @Test fun announcementBodyLinksWorkWithoutAnnouncementFlagOrExplicitSourceId() {
        val ann = JSONObject().put("id", 7).put("message", """<a href="/courses/1/files?preview=42&amp;verifier=fresh">성적.xlsx</a>""")
        val result = resolver({ denied() }, { listOf(ann) }).retrieve(42, FileDownloadResolver.Context(1)) { it }
        assertEquals("성적.xlsx", result.getString("display_name"))
        assertEquals("$base/files/42/download?preview=42&verifier=fresh", result.getString("url"))
    }

    @Test fun learningXAttachmentWorksWhenBothCanvasMetadataEndpointsDenyAccess() {
        var calls = 0
        val result = resolver({ denied() }, board = { course, board, post, file ->
            assertEquals(listOf(1L, 2L, 3L, 42L), listOf(course, board, post, file))
            calls++
            meta()
        }).retrieve(42, FileDownloadResolver.Context(1, boardId = 2, postId = 3)) { "board-readable" }
        assertEquals("board-readable", result)
        assertEquals(1, calls)
    }

    @Test fun actualFileLockAndInvalidAuthenticationStopAlternateRequests() {
        fails { resolver({ meta().put("locked_for_user", true) }).retrieve(42, FileDownloadResolver.Context(1)) { error("must not download") } }
        fails { resolver({ throw EtlAuthException("expired") }).retrieve(42, FileDownloadResolver.Context(1)) { error("must not download") } }
        fails { resolver({ throw EtlException("timeout", "timeout") }).retrieve(42, FileDownloadResolver.Context(1)) { error("must not download") } }
    }

    @Test fun attachmentMetadataKeepsNamesSizesAndInternalRelativeDownloadLinks() {
        val raw = JSONObject().put("filename", "성적.xlsx").put("filesize", 100)
            .put("url", "/files/42/download?verifier=fresh")
        val normalized = FileDownloadResolver.attachmentMetadata(raw, 42)!!
        assertEquals(42L, normalized.getLong("id"))
        assertEquals("성적.xlsx", normalized.getString("display_name"))
        assertEquals(100L, normalized.getLong("size"))
        assertEquals("$base/files/42/download?verifier=fresh", normalized.getString("url"))
        assertNull(FileDownloadResolver.attachmentMetadata(JSONObject(raw.toString()).put("url", "https://evil.example/files/42"), 42))
    }

    @Test fun unrelatedForeignAndInaccessiblePostLinksDoNotBecomeDownloadCandidates() {
        val normal = JSONObject().put("id", 7).put("context_code", "course_1").put("message", "<a href='/files/42'>성적.xlsx</a>")
        val invalid = listOf(
            JSONObject(normal.toString()).put("context_code", "course_2"),
            JSONObject(normal.toString()).put("published", false),
            JSONObject(normal.toString()).put("locked_for_user", true),
            JSONObject(normal.toString()).put("message", "<a href='/courses/2/files/42'>다른 과목</a>"),
            JSONObject(normal.toString()).put("message", "<a href='https://evil.example/files/42'>외부</a>"),
            JSONObject(normal.toString()).put("message", "<a href='/files/99'>다른 파일</a>"),
        )
        for (source in invalid) fails {
            resolver({ denied() }, { listOf(source) }).retrieve(42, FileDownloadResolver.Context(1)) { error("must not download") }
        }
    }
}
