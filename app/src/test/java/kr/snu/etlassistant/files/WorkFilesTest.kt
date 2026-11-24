package kr.snu.etlassistant.files

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kr.snu.etlassistant.chat.ChatStore

class WorkFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun chat(file: File, vararg paths: String) = file.apply {
        writeText(JSONArray().put(JSONObject().put("attachments", JSONArray(paths.map { JSONObject().put("path", it) }))).toString())
    }

    @Test fun expiredCopiesRemovedWithoutFollowingSymlinks() {
        val root = temp.newFolder("files")
        val old = File(root, "old").apply { writeText("old"); setLastModified(1) }
        val fresh = File(root, "fresh").apply { writeText("new") }
        val outside = temp.newFolder("outside")
        val secret = File(outside, "auth.json").apply { writeText("test-only"); setLastModified(1) }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertEquals(1, WorkFiles.prune(root, System.currentTimeMillis()))
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(secret.exists())
    }

    @Test fun deleteConversationRemovesOnlyUnsharedPrivateAttachments() {
        val root = temp.newFolder("work")
        val unique = File(root, "unique.pdf").apply { writeText("unique") }
        val shared = File(root, "shared.pdf").apply { writeText("shared") }
        val external = temp.newFile("phone-copy.pdf")
        val dir = temp.newFolder("chats")
        val store = ChatStore(dir, root)
        store.noteUserMessage("a", "a", emptyList(), 1)
        store.noteUserMessage("b", "b", emptyList(), 2)
        chat(store.fileFor("a"), unique.path, shared.path, external.path)
        chat(store.fileFor("b"), shared.path)
        store.delete("a")
        assertFalse(unique.exists()); assertTrue(shared.exists()); assertTrue(external.exists())
        store.delete("b")
        assertFalse(shared.exists())
    }

    @Test fun corruptOtherChatKeepsCopiesAndTraversalCannotDeleteSecrets() {
        val root = temp.newFolder("work")
        val candidate = File(root, "a").apply { writeText("a") }
        val corrupt = temp.newFile("corrupt.json").apply { writeText("[") }
        assertEquals(0, WorkFiles.deleteUnshared(root, setOf(candidate.path), listOf(corrupt)))
        val secret = temp.newFile("auth.json")
        assertEquals(0, WorkFiles.deleteUnshared(root, setOf(File(root, "../auth.json").path), emptyList()))
        assertTrue(secret.exists())
    }

    @Test fun retentionBoundaryIsFourteenDays() {
        val root = temp.newFolder("work")
        val f = File(root, "old").apply { writeText("a"); setLastModified(1000) }
        val limit = 1000 + TimeUnit.DAYS.toMillis(14)
        assertEquals(0, WorkFiles.prune(root, limit - 1))
        assertEquals(1, WorkFiles.prune(root, limit))
        assertFalse(f.exists())
    }
}
