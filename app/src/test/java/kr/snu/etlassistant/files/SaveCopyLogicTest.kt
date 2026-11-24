package kr.snu.etlassistant.files

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveCopyLogicTest {
    @Test fun labelDependsOnDrive() {
        assertEquals("드라이브에 저장", SaveCopyLogic.label(true))
        assertEquals("다른 곳에 저장", SaveCopyLogic.label(false))
    }

    @Test fun mimeKeepsKnownTypeAndGuessesByExtension() {
        assertEquals("application/pdf", SaveCopyLogic.mimeFor("a.bin", "application/pdf"))
        assertEquals("application/pdf", SaveCopyLogic.mimeFor("요약.PDF", "application/octet-stream"))
        assertEquals("application/x-hwp", SaveCopyLogic.mimeFor("과제.hwp", null))
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", SaveCopyLogic.mimeFor("7주차.pptx", ""))
        assertEquals("image/jpeg", SaveCopyLogic.mimeFor("사진.jpeg", "  "))
        assertEquals("application/octet-stream", SaveCopyLogic.mimeFor("이상한파일", null))
        assertEquals("application/octet-stream", SaveCopyLogic.mimeFor("x.unknown", "not a mime"))
    }

    @Test fun fileNameIsSanitized() {
        assertEquals("c.pdf", SaveCopyLogic.fileName("a/b/c.pdf"))
        assertEquals("a_b.pdf", SaveCopyLogic.fileName("a:b.pdf"))
        assertEquals("파일", SaveCopyLogic.fileName(" "))
    }
}
