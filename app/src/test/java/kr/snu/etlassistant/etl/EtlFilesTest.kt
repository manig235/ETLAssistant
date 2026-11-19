package kr.snu.etlassistant.etl

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EtlFilesTest {

    private lateinit var ws: File

    @Before fun setUp() {
        ws = Files.createTempDirectory("ws").toFile()
        File(ws, "etl-files").mkdirs()
    }

    @After fun tearDown() {
        ws.deleteRecursively()
    }

    private fun rejects(rel: String) {
        try {
            EtlFiles.resolveInside(ws, rel)
            fail("accepted: $rel")
        } catch (_: EtlException) {
        }
    }

    @Test fun storedNameIsStableAndKeepsExtension() {
        assertEquals("123-과제.pdf", EtlFiles.storedName(123, "과제.pdf"))
        assertEquals(EtlFiles.storedName(123, "a.pdf"), EtlFiles.storedName(123, "a.pdf"))
    }

    @Test fun storedNameStaysWithinByteLimitForKorean() {
        val long = "가".repeat(180) + ".pdf" // 180글자 = 540바이트
        val n = EtlFiles.storedName(9415086, long)
        assertTrue(n.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(n.endsWith(".pdf"))
        assertTrue(n.startsWith("9415086-"))
        // 잘린 글자가 깨지지 않는다
        assertFalse(n.contains('�'))
    }

    @Test fun storedNameWithoutExtensionStillFits() {
        val n = EtlFiles.storedName(1, "x".repeat(400))
        assertTrue(n.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test fun resolveInsideAcceptsFilesUnderEtlFiles() {
        val f = File(ws, "etl-files/1-a.txt").apply { writeText("hi") }
        assertEquals(f.canonicalFile, EtlFiles.resolveInside(ws, "etl-files/1-a.txt"))
        assertEquals(f.canonicalFile, EtlFiles.resolveInside(ws, "./etl-files/1-a.txt"))
        assertEquals("etl-files/1-a.txt", EtlFiles.relPath(ws, f))
    }

    @Test fun resolveInsideRejectsEscapes() {
        File(ws, "secret.txt").writeText("x")
        File(ws, "etl-files/ok.txt").writeText("x")
        rejects("")
        rejects("secret.txt")
        rejects("/etc/passwd")
        rejects("etl-files/../secret.txt")
        rejects("etl-files/../../x")
        rejects("etl-files")          // 폴더 자체
        rejects("etl-files/nope.txt") // 없는 파일
        rejects("etl-files/ok.txt\u0000.pdf")
    }

    @Test fun resolveInsideRejectsSymlinkEscape() {
        val outside = File(ws, "outside.txt").apply { writeText("secret") }
        val link = File(ws, "etl-files/link.txt")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: Exception) {
            return // 심볼릭 링크를 못 만드는 환경이면 건너뜀
        }
        rejects("etl-files/link.txt")
    }

    @Test fun parsePages() {
        assertEquals(listOf(1, 2, 3), EtlFiles.parsePages("1-3", 10))
        assertEquals(listOf(1, 3, 5), EtlFiles.parsePages("5,1,3,3", 10))
        assertEquals(listOf(2, 3, 4, 5, 6, 7), EtlFiles.parsePages("2-20", 20)) // 최대 6쪽
        assertEquals(listOf(9, 10), EtlFiles.parsePages("9-50", 10))             // 범위 밖은 버림
        assertEquals(listOf(1, 2, 3), EtlFiles.parsePages(null, 3))              // 비우면 앞쪽부터
        assertEquals(listOf(1, 2), EtlFiles.parsePages("abc", 2))                // 해석 불가 → 기본
        assertEquals(listOf(1, 2, 3, 4, 5, 6), EtlFiles.parsePages("", 99))
        assertEquals(listOf(3, 4), EtlFiles.parsePages("4-3", 10))               // 뒤집힌 범위
        assertEquals(emptyList<Int>(), EtlFiles.parsePages("1", 0))
        assertEquals(listOf(1), EtlFiles.parsePages("0-1", 5))                   // 0쪽은 없음
    }

    @Test fun fileKinds() {
        assertTrue(EtlFiles.isTextLike("a.PY"))
        assertTrue(EtlFiles.isTextLike("notes.md"))
        assertFalse(EtlFiles.isTextLike("a.pdf"))
        assertFalse(EtlFiles.isTextLike("noext"))
        assertTrue(EtlFiles.isPdf("Report.PDF"))
        assertFalse(EtlFiles.isPdf("a.docx"))
    }

    @Test fun pruneDeletesOnlyOldFilesIncludingSubdirs() {
        val dir = File(ws, "etl-files")
        val old = File(dir, "old.pdf").apply { writeText("x"); setLastModified(1_000L) }
        val fresh = File(dir, "fresh.pdf").apply { writeText("x") }
        val sub = File(dir, "render").apply { mkdirs() }
        val oldImg = File(sub, "1-p1.png").apply { writeText("x"); setLastModified(1_000L) }
        val n = EtlFiles.prune(dir, System.currentTimeMillis())
        assertEquals(2, n)
        assertFalse(old.exists())
        assertFalse(oldImg.exists())
        assertTrue(fresh.exists())
    }
}
