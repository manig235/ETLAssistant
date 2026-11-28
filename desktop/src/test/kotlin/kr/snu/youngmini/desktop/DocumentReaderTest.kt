package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlException
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class DocumentReaderTest {
    @Test fun pdfTextAndPageImagesWorkWithoutSendingTheOriginalDocument() {
        val root = Files.createTempDirectory("youngmini-pdf").toFile()
        try {
            val dir = File(root, "etl-files").apply { mkdirs() }
            val target = File(dir, "1-lecture.pdf")
            PDDocument().use { document ->
                repeat(8) { index ->
                    val page = PDPage()
                    document.addPage(page)
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText(); stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                        stream.newLineAtOffset(50f, 700f); stream.showText("Lecture page ${index + 1}: eigenvalues"); stream.endText()
                    }
                }
                document.save(target)
            }
            val reader = DocumentReader(root)
            val first = reader.read("etl-files/1-lecture.pdf")
            assertEquals(8, first.getInt("page_count"))
            assertEquals(7, first.getInt("next_page"))
            assertTrue(first.getString("text").contains("Lecture page 6"))
            assertFalse(first.getString("text").contains("Lecture page 7"))
            val next = reader.read("etl-files/1-lecture.pdf", page = 7)
            assertTrue(next.getString("text").contains("Lecture page 8"))
            assertTrue(next.isNull("next_page"))
            val images = reader.render("etl-files/1-lecture.pdf", "1,8")
            assertEquals(2, images.images.size)
            assertTrue(Base64.getDecoder().decode(images.images[0].data).take(4) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte()))
            assertThrows(EtlException::class.java) { reader.read("../secret.txt") }
        } finally { root.deleteRecursively() }
    }

    @Test fun longTextCanBeReadInChunksAndTraversalIsRejected() {
        val root = Files.createTempDirectory("youngmini-text").toFile()
        try {
            File(root, "etl-files").mkdirs()
            File(root, "etl-files/1-notes.txt").writeText("abcdef")
            File(root, "secrets.txt").writeText("must not be read")
            val reader = DocumentReader(root)
            val first = reader.read("etl-files/1-notes.txt", limit = 3)
            assertEquals("abc", first.getString("text"))
            assertEquals(3, first.getInt("next_offset"))
            assertEquals("def", reader.read("etl-files/1-notes.txt", offset = 3).getString("text"))
            for (path in listOf("etl-files/../secrets.txt", "../secrets.txt", File(root, "secrets.txt").path, "C:\\Windows\\win.ini"))
                assertThrows(EtlException::class.java) { reader.read(path) }
        } finally { root.deleteRecursively() }
    }
}
