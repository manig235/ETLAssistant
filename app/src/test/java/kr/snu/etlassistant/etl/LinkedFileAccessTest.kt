package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinkedFileAccessTest {
    private val announcement = JSONObject().put("id", 7).put("is_announcement", true)
        .put("message", """<a href="/courses/1/files/42/download?verifier=private">점수.xlsx</a>""")

    private fun verify(source: JSONObject = announcement, type: String = "announcement", file: Long = 42) {
        LinkedFileAccess.verify(file, 1, type, 7, {
            assertEquals(if (type == "announcement") "/api/v1/courses/1/discussion_topics/7"
                else "/api/v1/courses/1/assignments/7?include[]=submission", it)
            source
        }, { error("unexpected list request") })
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("accepted inaccessible or unrelated file") } catch (_: EtlException) { }
    }

    @Test fun accessibleAnnouncementProvesLinkedFile() = verify()

    @Test fun unrelatedFileAndForeignLinksDoNotProveAccess() {
        rejected { verify(file = 43) }
        rejected { verify(JSONObject(announcement.toString()).put("message", """<a href="https://other.example/files/42">x</a>""")) }
        rejected { verify(JSONObject(announcement.toString()).put("message", """<a href="/courses/2/files/42">x</a>""")) }
    }

    @Test fun inaccessibleSourcesCannotUnlockFiles() {
        rejected { verify(JSONObject(announcement.toString()).put("published", false)) }
        rejected { verify(JSONObject(announcement.toString()).put("locked_for_user", true)) }
        rejected { verify(JSONObject(announcement.toString()).put("is_announcement", false)) }
        rejected { verify(JSONObject(announcement.toString()).put("id", 8)) }
        rejected { LinkedFileAccess.verify(42, 1, "../files", 7, { error("must not request") }, { error("must not request") }) }
    }

    @Test fun assignmentAndOwnSubmissionAttachmentsProveAccess() {
        verify(JSONObject().put("id", 7).put("description", "<a href='/files/42'>x</a>"), "assignment")
        verify(JSONObject().put("id", 7).put("submission", JSONObject().put("attachments",
            JSONArray().put(JSONObject().put("id", 42)))), "assignment")
    }

    @Test fun moduleMustBeAccessibleAndContainTheFile() {
        val module = JSONObject().put("id", 7).put("state", "unlocked")
        val item = JSONObject().put("type", "File").put("content_id", 42)
        val getAll = { path: String ->
            assertEquals("/api/v1/courses/1/modules/7/items?per_page=100", path)
            listOf(item)
        }
        LinkedFileAccess.verify(42, 1, "module", 7, { module }, getAll)
        rejected { LinkedFileAccess.verify(43, 1, "module", 7, { module }, getAll) }
        module.put("state", "locked")
        rejected { LinkedFileAccess.verify(42, 1, "module", 7, { module }, { error("must not read locked module") }) }
    }
}
