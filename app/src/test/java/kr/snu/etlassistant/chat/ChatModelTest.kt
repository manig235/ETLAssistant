package kr.snu.etlassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatModelTest {

    private class Recorder : ChatModel.Listener {
        val added = mutableListOf<Int>()
        val changed = mutableListOf<Int>()
        var resets = 0
        override fun onAdded(index: Int) { added += index }
        override fun onChanged(index: Int) { changed += index }
        override fun onReset() { resets++ }
    }

    @Test fun streamingDeltasBuildOneAssistantMessage() {
        val m = ChatModel(null)
        val r = Recorder().also { m.listener = it }
        m.addUser("안녕")
        m.appendAssistant("a1", "이번 ")
        m.appendAssistant("a1", "주 마감")
        assertEquals(2, m.messages.size)
        assertEquals("이번 주 마감", m.messages[1].text)
        assertEquals("streaming", m.messages[1].status)
        assertEquals(listOf(0, 1), r.added)
        assertEquals(listOf(1), r.changed)
    }

    @Test fun finishReplacesTextWithFinalBody() {
        val m = ChatModel(null)
        m.appendAssistant("a1", "부분")
        m.finishAssistant("a1", "최종 본문")
        assertEquals("최종 본문", m.messages[0].text)
        assertEquals("done", m.messages[0].status)
    }

    @Test fun finishWithoutDeltasStillCreatesMessageUnlessEmpty() {
        val m = ChatModel(null)
        m.finishAssistant("a1", "한 번에 온 답")
        m.finishAssistant("a2", "")
        assertEquals(1, m.messages.size)
        assertEquals("한 번에 온 답", m.messages[0].text)
    }

    @Test fun separateItemsStaySeparate() {
        val m = ChatModel(null)
        m.appendAssistant("a1", "첫째")
        m.appendAssistant("a2", "둘째")
        assertEquals(listOf("첫째", "둘째"), m.messages.map { it.text })
        assertEquals(listOf(Role.ASSISTANT, Role.ASSISTANT), m.messages.map { it.role })
    }

    @Test fun closeStreamingClosesUnfinishedAssistantMessage() {
        val m = ChatModel(null)
        m.appendAssistant("a1", "중간에 끊긴")
        m.closeStreaming()
        assertEquals("done", m.messages[0].status)
    }

    @Test fun saveAndLoadKeepOnlyUserAndAssistant() {
        val f = File.createTempFile("chat", ".json")
        try {
            val m = ChatModel(f)
            m.addUser("질문")
            m.addNotice("안내")
            m.appendAssistant("a1", "답")
            m.finishAssistant("a1", "답변입니다")
            val loaded = ChatModel(f).also { it.load() }
            assertEquals(listOf(Role.USER, Role.ASSISTANT), loaded.messages.map { it.role })
            assertEquals("답변입니다", loaded.messages[1].text)
            assertEquals("done", loaded.messages[1].status)
        } finally {
            f.delete()
        }
    }

    @Test fun savedHistoryIsCapped() {
        val f = File.createTempFile("chat", ".json")
        try {
            val m = ChatModel(f)
            repeat(ChatModel.MAX_SAVED + 30) { m.addUser("m$it") }
            val loaded = ChatModel(f).also { it.load() }
            assertEquals(ChatModel.MAX_SAVED, loaded.messages.size)
            assertEquals("m30", loaded.messages.first().text)
        } finally {
            f.delete()
        }
    }

    @Test fun clearResetsAndPersistsEmpty() {
        val f = File.createTempFile("chat", ".json")
        try {
            val m = ChatModel(f)
            val r = Recorder().also { m.listener = it }
            m.addUser("x")
            m.clear()
            assertTrue(m.messages.isEmpty())
            assertEquals(1, r.resets)
            assertTrue(ChatModel(f).also { it.load() }.messages.isEmpty())
        } finally {
            f.delete()
        }
    }

    @Test fun corruptFileIsIgnored() {
        val f = File.createTempFile("chat", ".json")
        try {
            f.writeText("{not json")
            val m = ChatModel(f).also { it.load() }
            assertFalse(m.messages.isNotEmpty())
        } finally {
            f.delete()
        }
    }

    // ---- 첨부 ------------------------------------------------------------------

    private fun att(name: String, path: String = "/w/etl-files/$name", mime: String = "application/pdf") =
        Attachment(name, mime, 10, path)

    @Test fun filesMergeIntoPreviousFilesRowAndSkipDuplicates() {
        val m = ChatModel(null)
        m.addUser("받아줘")
        m.addFiles(listOf(att("a.pdf")))
        m.addFiles(listOf(att("b.pdf"), att("a.pdf")))
        assertEquals(listOf(Role.USER, Role.FILES), m.messages.map { it.role })
        assertEquals(listOf("a.pdf", "b.pdf"), m.messages[1].attachments.map { it.name })
        // 사이에 답변이 끼면 새 묶음
        m.finishAssistant("x", "받았어요")
        m.addFiles(listOf(att("c.pdf")))
        assertEquals(Role.FILES, m.messages.last().role)
        assertEquals(1, m.messages.last().attachments.size)
    }

    @Test fun attachmentsSurviveSaveAndLoad() {
        val f = File.createTempFile("chat", ".json")
        try {
            val m = ChatModel(f)
            m.addUser("", listOf(Attachment("p.jpg", "image/jpeg", 5, "/w/p.jpg")))
            m.addFiles(listOf(Attachment("강의.pdf", "application/pdf", 9, "/w/x.pdf", "content://media/1", "Download/etl/강의.pdf")))
            m.addFiles(listOf(Attachment("강의 1쪽", "image/png", 3, "/w/r.png", pagePreview = true)))
            val loaded = ChatModel(f).also { it.load() }
            assertEquals(listOf(Role.USER, Role.FILES), loaded.messages.map { it.role })
            assertEquals("p.jpg", loaded.messages[0].attachments.single().name)
            val files = loaded.messages[1].attachments
            assertEquals("content://media/1", files[0].uri)
            assertEquals("Download/etl/강의.pdf", files[0].shownPath)
            assertTrue(files[1].pagePreview)
            assertEquals(null, files[1].uri)
        } finally {
            f.delete()
        }
    }
}
