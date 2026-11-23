package kr.snu.etlassistant.onboarding

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SuggestionContentTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private fun assignment(due: Instant? = now) = JSONObject().put("id", 1).put("published", true)
        .put("due_at", due?.toString() ?: JSONObject.NULL).put("submission_types", org.json.JSONArray(listOf("online_upload")))
    private fun file(name: String = "lecture.pdf") = JSONObject().put("id", 5).put("display_name", name)

    @Test fun recentAssignmentsRespectWindowAndVisibleOnlineSubmission() {
        assertTrue(SuggestionContentLogic.recentAssignment(assignment(now.minusSeconds(30 * 86400L)), now))
        assertFalse(SuggestionContentLogic.recentAssignment(assignment(now.minusSeconds(30 * 86400L + 1)), now))
        assertTrue(SuggestionContentLogic.recentAssignment(assignment(now.plusSeconds(14 * 86400L)), now))
        assertFalse(SuggestionContentLogic.recentAssignment(assignment(now.plusSeconds(14 * 86400L + 1)), now))
        assertFalse(SuggestionContentLogic.recentAssignment(assignment().put("published", false), now))
        assertFalse(SuggestionContentLogic.recentAssignment(assignment().put("locked_for_user", true), now))
        assertFalse(SuggestionContentLogic.recentAssignment(assignment().put("submission_types", org.json.JSONArray(listOf("on_paper"))), now))
    }

    @Test fun undatedAssignmentsNeedRecentCreationAndGradeUpdatesDoNotMakeOldWorkRecent() {
        assertTrue(SuggestionContentLogic.recentAssignment(assignment(null).put("created_at", now.minusSeconds(86400).toString()), now))
        assertFalse(SuggestionContentLogic.recentAssignment(assignment(null), now))
        assertFalse(SuggestionContentLogic.recentAssignment(assignment(null).put("created_at", now.minusSeconds(40 * 86400L).toString())
            .put("updated_at", now.toString()), now))
        assertFalse(SuggestionContentLogic.recentAssignment(assignment(null).put("created_at", now.toString())
            .put("unlock_at", now.plusSeconds(86400).toString()), now))
    }

    @Test fun onlyReadableAccessibleDocumentsQualifyAsMaterials() {
        for (name in listOf("lecture.pdf", "slides.PPTX", "notes.hwpx", "reading.docx"))
            assertTrue(SuggestionContentLogic.readableMaterial(file(name)))
        assertFalse(SuggestionContentLogic.readableMaterial(file().put("hidden_for_user", true)))
        assertFalse(SuggestionContentLogic.readableMaterial(file().put("locked_for_user", true)))
        assertFalse(SuggestionContentLogic.readableMaterial(file().put("content-type", "video/mp4")))
        assertFalse(SuggestionContentLogic.readableMaterial(file("video.mp4")))
        assertFalse(SuggestionContentLogic.readableMaterial(file("icon.png")))
    }

    @Test fun hiddenFileTabCanStillQualifyThroughAnAccessibleModuleDocument() {
        val module = JSONObject("""{"id":2,"items_count":1,"items":[{"type":"File","content_id":5,"title":"자료"}]}""")
        val calls = mutableListOf<String>()
        val lookup = SuggestionContentLookup(getAll = { path ->
            calls += path
            when {
                "/assignments?" in path -> listOf(assignment())
                "/modules?" in path -> listOf(module)
                else -> throw IllegalStateException("file tab unavailable")
            }
        }, getObject = { path -> calls += path; file() })
        assertEquals(SuggestionContent(true, true), lookup.check(10, now))
        assertTrue(calls.contains("/api/v1/courses/10/files/5"))
        assertTrue(calls.none { "download" in it || "submission" in it })
    }

    @Test fun incompleteEmbeddedModulesLoadTheirRemainingItemsBeforeGivingUp() {
        val module = JSONObject("""{"id":2,"items_count":2,"items":[]}""")
        val item = JSONObject("""{"type":"File","content_id":5}""")
        var loadedItems = false
        val lookup = SuggestionContentLookup({ path ->
            when {
                "/modules/2/items?" in path -> { loadedItems = true; listOf(item) }
                "/modules?" in path -> listOf(module)
                else -> emptyList()
            }
        }, { file() })
        assertEquals(SuggestionContent(true, false), lookup.check(10, now))
        assertTrue(loadedItems)
    }

    @Test fun failedRequestsStayUnknownAndAnEmptySuccessfulListIsNotEvidenceOfContent() {
        val failed = SuggestionContentLookup({ throw IllegalStateException() }, { throw IllegalStateException() })
        assertEquals(SuggestionContent(null, null), failed.check(10, now))
        val empty = SuggestionContentLookup({ emptyList() }, { throw AssertionError("no file metadata needed") })
        assertEquals(SuggestionContent(false, false), empty.check(10, now))
        val listed = SuggestionContentLookup({ path -> if ("/files?" in path) listOf(file()) else emptyList() },
            { throw AssertionError("listed metadata is enough") })
        assertEquals(SuggestionContent(true, false), listed.check(10, now))
    }
}
