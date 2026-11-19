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
        m.updateTool("t1", "etl_digest", "inProgress", null)
        m.appendAssistant("a2", "둘째")
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), m.messages.map { it.role })
    }

    @Test fun toolCallLifecycle() {
        val m = ChatModel(null)
        m.updateTool("t1", "etl_digest", "inProgress", null)
        m.updateTool("t1", "etl_digest", "completed", null)
        assertEquals(1, m.messages.size)
        assertEquals("completed", m.messages[0].status)
        m.updateTool("t2", "etl_get", "failed", "허용되지 않은 경로")
        assertEquals("허용되지 않은 경로", m.messages[1].error)
    }

    @Test fun closeStreamingClosesUnfinishedWork() {
        val m = ChatModel(null)
        m.appendAssistant("a1", "중간에 끊긴")
        m.updateTool("t1", "etl_download", "inProgress", null)
        m.closeStreaming()
        assertEquals("done", m.messages[0].status)
        assertEquals("failed", m.messages[1].status)
        assertEquals("중단됨", m.messages[1].error)
    }

    @Test fun saveAndLoadKeepOnlyUserAndAssistant() {
        val f = File.createTempFile("chat", ".json")
        try {
            val m = ChatModel(f)
            m.addUser("질문")
            m.updateTool("t1", "etl_courses", "completed", null)
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
}
