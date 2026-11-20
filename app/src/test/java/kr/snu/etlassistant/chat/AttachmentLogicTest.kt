package kr.snu.etlassistant.chat

import kr.snu.etlassistant.chat.AttachmentLogic.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentLogicTest {

    @Test fun promptWithoutAttachmentsIsUnchanged() {
        assertEquals("안녕", AttachmentLogic.promptText("안녕", emptyList(), 0))
    }

    @Test fun promptListsFilesAndImages() {
        val p = AttachmentLogic.promptText(
            "이거 봐줘",
            listOf(Triple("etl-files/uploads/1-보고서.pdf", "application/pdf", 1_300_000L)),
            imageCount = 2,
        )
        assertTrue(p.startsWith("이거 봐줘\n\n[사용자가 첨부함]"))
        assertTrue(p.contains("사진 2장"))
        assertTrue(p.contains("- etl-files/uploads/1-보고서.pdf (application/pdf, 1.2MB)"))
        assertTrue(p.contains("etl_render_pages"))
    }

    @Test fun emptyTextWithAttachmentGetsDefaultQuestion() {
        val p = AttachmentLogic.promptText("  ", emptyList(), 1)
        assertTrue(p.startsWith("첨부한 것을 확인해 줘."))
        assertFalse(p.contains("etl_read_file")) // 사진만 있으면 파일 도구 안내는 붙이지 않는다
    }

    @Test fun uploadNameSwapsExtensionWhenReencoded() {
        assertEquals("7-IMG_1.jpg", AttachmentLogic.uploadName(7, "IMG_1.HEIC", reencoded = true))
        assertEquals("7-a.pdf", AttachmentLogic.uploadName(7, "a.pdf", reencoded = false))
        assertEquals("7-noext.jpg", AttachmentLogic.uploadName(7, "noext", reencoded = true))
    }

    @Test fun reencodeRules() {
        assertTrue(AttachmentLogic.shouldReencode("image/jpeg"))
        assertTrue(AttachmentLogic.shouldReencode("image/heic"))
        assertFalse(AttachmentLogic.shouldReencode("image/gif"))
        assertFalse(AttachmentLogic.shouldReencode("application/pdf"))
    }

    @Test fun sizes() {
        assertEquals("12B", AttachmentLogic.formatSize(12))
        assertEquals("340KB", AttachmentLogic.formatSize(340 * 1024))
        assertEquals("1.5MB", AttachmentLogic.formatSize(1536L * 1024))
    }

    @Test fun kinds() {
        assertEquals(FileKind.PDF, AttachmentLogic.kindOf("a.PDF", "application/octet-stream"))
        assertEquals(FileKind.HWP, AttachmentLogic.kindOf("과제.hwp", "application/x-hwp"))
        assertEquals(FileKind.SLIDES, AttachmentLogic.kindOf("lec.pptx", "x"))
        assertEquals(FileKind.IMAGE, AttachmentLogic.kindOf("p", "image/png"))
        assertEquals(FileKind.CODE, AttachmentLogic.kindOf("main.py", "text/x-python"))
        assertEquals(FileKind.OTHER, AttachmentLogic.kindOf("x.bin", "application/octet-stream"))
    }

    @Test fun sendModeAllowsAttachmentOnly() {
        assertEquals(SendMode.SEND, ChatLogic.sendMode(busy = false, input = " ", hasAttachments = true))
        assertEquals(SendMode.DISABLED, ChatLogic.sendMode(busy = false, input = "안녕", hasAttachments = true, importing = true))
        assertEquals(SendMode.STOP, ChatLogic.sendMode(busy = true, input = "", hasAttachments = true))
    }
}
