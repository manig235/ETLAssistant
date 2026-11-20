package kr.snu.etlassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class ChatStoreTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun tempDir(): File = Files.createTempDirectory("chats").toFile()

    // ---- 제목 ------------------------------------------------------------------

    @Test fun titleFromText() {
        assertEquals("이번 주 마감 알려줘", ChatTitles.from("  이번 주\n마감   알려줘 ", emptyList()))
        val long = "가".repeat(60)
        val t = ChatTitles.from(long, emptyList())
        assertEquals(ChatTitles.MAX_LEN, t.length)
        assertTrue(t.endsWith("…"))
    }

    @Test fun titleFromAttachmentsOnly() {
        val img = Attachment("p.jpg", "image/jpeg", 1, "/p")
        val pdf = Attachment("보고서.pdf", "application/pdf", 1, "/r")
        assertEquals("사진 2장", ChatTitles.from("", listOf(img, img)))
        assertEquals("파일: 보고서.pdf", ChatTitles.from(" ", listOf(pdf)))
        assertEquals("파일 2개", ChatTitles.from("", listOf(pdf, pdf)))
        assertEquals("사진 1장, 파일 1개", ChatTitles.from("", listOf(img, pdf)))
        assertEquals(ChatTitles.DEFAULT, ChatTitles.from("", emptyList()))
    }

    // ---- 날짜 묶음 ---------------------------------------------------------------

    @Test fun dateGroups() {
        // 2026-11-21(토) 00:30 KST
        val now = Instant.parse("2026-11-20T15:30:00Z").toEpochMilli()
        fun at(iso: String) = Instant.parse(iso).toEpochMilli()
        assertEquals(ChatTitles.Group.TODAY, ChatTitles.groupOf(at("2026-11-20T15:10:00Z"), now, seoul)) // 00:10 KST 오늘
        assertEquals(ChatTitles.Group.YESTERDAY, ChatTitles.groupOf(at("2026-11-20T14:50:00Z"), now, seoul)) // 23:50 KST 어제
        assertEquals(ChatTitles.Group.WEEK, ChatTitles.groupOf(at("2026-11-15T03:00:00Z"), now, seoul)) // 6일 전
        assertEquals(ChatTitles.Group.OLDER, ChatTitles.groupOf(at("2026-11-14T03:00:00Z"), now, seoul)) // 7일 전
        val list = listOf(
            ChatMeta("a", "a", 0, at("2026-11-10T00:00:00Z")),
            ChatMeta("b", "b", 0, at("2026-11-20T15:20:00Z")),
            ChatMeta("c", "c", 0, at("2026-11-20T15:25:00Z")),
        )
        val g = ChatTitles.grouped(list, now, seoul)
        assertEquals(listOf(ChatTitles.Group.TODAY, ChatTitles.Group.OLDER), g.map { it.first })
        assertEquals(listOf("c", "b"), g[0].second.map { it.id }) // 최근 순
    }

    // ---- 색인 ------------------------------------------------------------------

    @Test fun indexLifecycle() {
        val dir = tempDir()
        try {
            val s = ChatStore(dir)
            s.load()
            val id = s.newId()
            assertNull(s.get(id)) // 빈 대화는 목록에 없다
            s.noteUserMessage(id, "첫 질문", emptyList(), 1000)
            s.noteUserMessage(id, "두 번째 질문", emptyList(), 2000)
            assertEquals("첫 질문", s.get(id)!!.title) // 제목은 첫 메시지
            assertEquals(2000, s.get(id)!!.updatedAt)
            s.setThread(id, "thr-1")
            s.rename(id, "  내   대화 ")
            // 다시 읽어도 그대로
            val s2 = ChatStore(dir).also { it.load() }
            val m = s2.get(id)!!
            assertEquals("내 대화", m.title)
            assertTrue(m.renamed)
            assertEquals("thr-1", m.threadId)
            // 삭제하면 색인과 파일이 사라진다
            s2.fileFor(id).writeText("[]")
            assertEquals("thr-1", s2.delete(id)!!.threadId)
            assertFalse(s2.fileFor(id).exists())
            assertTrue(ChatStore(dir).also { it.load() }.list().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun unsafeIdsInIndexAreIgnored() {
        val dir = tempDir()
        try {
            File(dir, ChatStore.INDEX).writeText("""[{"id":"../../x","title":"t","createdAt":1,"updatedAt":1},{"id":"ok-1","title":"t","createdAt":1,"updatedAt":1}]""")
            val s = ChatStore(dir).also { it.load() }
            assertEquals(listOf("ok-1"), s.list().map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 예전 형식 옮기기 -----------------------------------------------------------

    @Test fun migratesLegacySingleChat() {
        val root = tempDir()
        try {
            val legacy = File(root, "chat.json")
            ChatModel(legacy).apply {
                addUser("예전 질문")
                finishAssistant("a", "예전 답변")
            }
            val s = ChatStore(File(root, "chats")).also { it.load() }
            val id = s.migrateLegacy(legacy, "old-thread", 5000)
            assertNotNull(id)
            assertFalse(legacy.exists())
            val m = s.get(id!!)!!
            assertEquals("예전 질문", m.title)
            assertEquals("old-thread", m.threadId)
            val moved = ChatModel(s.fileFor(id)).also { it.load() }
            assertEquals(listOf("예전 질문", "예전 답변"), moved.messages.map { it.text })
            // 이미 목록이 있으면 다시 옮기지 않는다
            assertNull(s.migrateLegacy(legacy, null, 6000))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun emptyLegacyIsDropped() {
        val root = tempDir()
        try {
            val legacy = File(root, "chat.json").apply { writeText("[]") }
            val s = ChatStore(File(root, "chats")).also { it.load() }
            assertNull(s.migrateLegacy(legacy, "t", 1))
            assertTrue(s.list().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun switchToLoadsOtherConversation() {
        val root = tempDir()
        try {
            val a = File(root, "a.json"); val b = File(root, "b.json")
            ChatModel(a).addUser("A의 질문")
            val m = ChatModel(b)
            m.switchTo(a)
            assertEquals(listOf("A의 질문"), m.messages.map { it.text })
            m.switchTo(File(root, "none.json"))
            assertTrue(m.messages.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
