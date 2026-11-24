package kr.snu.etlassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareIntentLogicTest {
    private val send = ShareIntentLogic.ACTION_SEND
    private val multi = ShareIntentLogic.ACTION_SEND_MULTIPLE
    private val own = "kr.snu.etlassistant"

    @Test fun singleFile() {
        val c = ShareIntentLogic.parse(send, listOf("content://com.android.providers.downloads/1"), null, null, own)!!
        assertEquals(listOf("content://com.android.providers.downloads/1"), c.uris)
        assertEquals(0, c.dropped)
        assertNull(c.text)
    }

    @Test fun multipleFilesAreCappedAndDeduplicated() {
        val uris = (1..7).map { "content://media/external/$it" } + "content://media/external/1"
        val c = ShareIntentLogic.parse(multi, uris, null, null, own)!!
        assertEquals(AttachmentLogic.MAX_PENDING, c.uris.size)
        assertEquals(7 - AttachmentLogic.MAX_PENDING, c.dropped)
    }

    @Test fun textAndLinksGoToInput() {
        assertEquals("https://example.com/a", ShareIntentLogic.parse(send, emptyList(), " https://example.com/a ", null, own)!!.text)
        assertEquals("기사 제목\nhttps://n.news/1", ShareIntentLogic.parse(send, listOf(null), "https://n.news/1", "기사 제목", own)!!.text)
        // 제목이 본문에 이미 있으면 겹쳐 쓰지 않는다
        assertEquals("기사 제목 https://n.news/1", ShareIntentLogic.parse(send, emptyList(), "기사 제목 https://n.news/1", "기사 제목", own)!!.text)
        // 파일과 함께 온 제목만은 넣지 않는다
        assertNull(ShareIntentLogic.parse(send, listOf("content://x/1"), null, "메일 제목", own)!!.text)
        assertEquals(ShareIntentLogic.MAX_TEXT_CHARS, ShareIntentLogic.parse(send, emptyList(), "가".repeat(9000), null, own)!!.text!!.length)
    }

    @Test fun rejectsFileUrisOwnProviderAndOtherActions() {
        val c = ShareIntentLogic.parse(multi, listOf("file:///data/data/kr.snu.etlassistant/files/a", "content://kr.snu.etlassistant.files/w/b",
            "CONTENT://KR.SNU.ETLASSISTANT.files/x", "content://other.app/ok"), null, null, own)!!
        assertEquals(listOf("content://other.app/ok"), c.uris)
        assertNull(ShareIntentLogic.parse("android.intent.action.VIEW", listOf("content://x/1"), "t", null, own))
        assertNull(ShareIntentLogic.parse(null, emptyList(), "t", null, own))
        val empty = ShareIntentLogic.parse(send, emptyList(), "  ", null, own)!!
        assertEquals(emptyList<String>(), empty.uris)
        assertNull(empty.text)
    }
}
