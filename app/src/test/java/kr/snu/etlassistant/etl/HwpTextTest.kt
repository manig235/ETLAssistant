package kr.snu.etlassistant.etl

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HwpTextTest {

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("hwp").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    // ---- 픽스처 만들기 ------------------------------------------------------

    /** 최소 CFB(v3, 512바이트 섹터) 작성기. 4096바이트 미만 스트림은 미니 스트림에 둔다. 경로는 한 단계 스토리지까지. */
    private fun cfb(streams: Map<String, ByteArray>, corruptFat: Boolean = false): ByteArray {
        val ss = 512
        val mini = 64
        val cutoff = 4096
        class E(val name: String, val type: Int, var child: Int = -1, var right: Int = -1, var start: Int = -2, var size: Int = 0, val data: ByteArray? = null)
        val entries = mutableListOf(E("Root Entry", 5))
        val storages = HashMap<String, Int>()
        fun addChild(parent: Int, idx: Int) {
            val p = entries[parent]
            if (p.child < 0) { p.child = idx; return }
            var c = p.child
            while (entries[c].right >= 0) c = entries[c].right
            entries[c].right = idx
        }
        for ((path, data) in streams) {
            val parts = path.split('/')
            var parent = 0
            if (parts.size == 2) {
                parent = storages.getOrPut(parts[0]) {
                    entries += E(parts[0], 1); addChild(0, entries.size - 1); entries.size - 1
                }
            }
            entries += E(parts.last(), 2, size = data.size, data = data)
            addChild(parent, entries.size - 1)
        }
        // 미니 스트림
        val miniBuf = ByteArrayOutputStream()
        val miniFat = mutableListOf<Int>()
        for (e in entries) if (e.type == 2 && e.size < cutoff && e.size > 0) {
            val first = miniFat.size
            val n = (e.size + mini - 1) / mini
            for (k in 0 until n) miniFat += if (k == n - 1) -2 else first + k + 1
            e.start = first
            miniBuf.write(e.data!!); repeat(n * mini - e.size) { miniBuf.write(0) }
        }
        val miniBytes = miniBuf.toByteArray()
        val dirSectors = (entries.size * 128 + ss - 1) / ss
        val miniFatSectors = (miniFat.size * 4 + ss - 1) / ss
        val miniStreamSectors = (miniBytes.size + ss - 1) / ss
        val bigSectors = entries.filter { it.type == 2 && it.size >= cutoff }.sumOf { (it.size + ss - 1) / ss }
        val dataSectors = dirSectors + miniFatSectors + miniStreamSectors + bigSectors
        var fatSectors = 1
        while (fatSectors * (ss / 4) < fatSectors + dataSectors) fatSectors++
        val total = fatSectors + dataSectors
        val fat = IntArray(fatSectors * (ss / 4)) { -1 }
        for (i in 0 until fatSectors) fat[i] = -3
        var next = fatSectors
        fun alloc(n: Int): Int {
            if (n == 0) return -2
            val first = next
            for (k in 0 until n) fat[first + k] = if (k == n - 1) -2 else first + k + 1
            next += n
            return first
        }
        val dirStart = alloc(dirSectors)
        val miniFatStart = alloc(miniFatSectors)
        val miniStreamStart = alloc(miniStreamSectors)
        entries[0].start = miniStreamStart
        entries[0].size = miniBytes.size
        val bigStarts = entries.filter { it.type == 2 && it.size >= cutoff }.associateWith { alloc((it.size + ss - 1) / ss) }
        bigStarts.forEach { (e, s) -> e.start = s }
        if (corruptFat) fat[dirStart] = dirStart // 순환 체인
        check(next == total)

        val out = ByteBuffer.allocate((total + 1) * ss).order(ByteOrder.LITTLE_ENDIAN)
        out.put(Cfb.SIGNATURE)
        out.putShort(0x18, 0x3E); out.putShort(0x1A, 3); out.putShort(0x1C, 0xFFFE.toShort())
        out.putShort(0x1E, 9); out.putShort(0x20, 6)
        out.putInt(0x2C, fatSectors); out.putInt(0x30, dirStart); out.putInt(0x38, cutoff)
        out.putInt(0x3C, if (miniFatSectors > 0) miniFatStart else -2); out.putInt(0x40, miniFatSectors)
        out.putInt(0x44, -2); out.putInt(0x48, 0)
        for (i in 0 until 109) out.putInt(0x4C + i * 4, if (i < fatSectors) i else -1)
        fun sec(n: Int) = (n + 1) * ss
        for (i in fat.indices) out.putInt(sec(0) + i * 4, fat[i])
        entries.forEachIndexed { i, e ->
            val o = sec(dirStart) + i * 128
            val nm = e.name.toByteArray(Charsets.UTF_16LE)
            for (k in nm.indices) out.put(o + k, nm[k])
            out.putShort(o + 64, (nm.size + 2).toShort()); out.put(o + 66, e.type.toByte()); out.put(o + 67, 1)
            out.putInt(o + 68, -1); out.putInt(o + 72, e.right); out.putInt(o + 76, e.child)
            out.putInt(o + 116, e.start); out.putInt(o + 120, e.size)
        }
        for (i in dirSectors * ss / 128 - 1 downTo entries.size) { // 빈 엔트리
            val o = sec(dirStart) + i * 128
            out.putInt(o + 68, -1); out.putInt(o + 72, -1); out.putInt(o + 76, -1)
        }
        for (i in miniFat.indices) out.putInt(sec(miniFatStart) + i * 4, miniFat[i])
        for (k in miniBytes.indices) out.put(sec(miniStreamStart) + k, miniBytes[k])
        bigStarts.forEach { (e, s) -> for (k in e.data!!.indices) out.put(sec(s) + k, e.data[k]) }
        return out.array()
    }

    private fun fileHeader(props: Int): ByteArray = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("HWP Document File".toByteArray(Charsets.US_ASCII))
        putInt(32, 0x05010000); putInt(36, props)
    }.array()

    private fun record(tag: Int, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        if (body.size < 0xFFF) b.putInt(tag or (body.size shl 20)) else { b.putInt(tag or (0xFFF shl 20)); b.putInt(body.size) }
        b.put(body)
        return b.array().copyOf(b.position())
    }

    /** 문단 글: 일반 글자와 제어문자(확장 제어는 8글자 차지). */
    private fun para(vararg parts: Any): ByteArray {
        val sb = StringBuilder()
        for (p in parts) when (p) {
            is String -> sb.append(p)
            is Int -> { sb.append(p.toChar()); if (p in 1..9 || p in 11..12 || p in 14..23) repeat(7) { sb.append('x') } }
        }
        sb.append('\r')
        return record(67, sb.toString().toByteArray(Charsets.UTF_16LE))
    }

    private fun deflateRaw(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    // ---- hwp 5.x ----------------------------------------------------------

    @Test fun readsCompressedSectionsWithControlsAndTable() {
        val sec0 = record(66, ByteArray(22)) + // PARA_HEADER(무시)
            para("기말고사: 12월 15일(월)", 9, "10:00") +
            para(11, "") + // 표 컨트롤
            para("평가", 10, "중간 30%") +
            record(68, ByteArray(12)) // PARA_CHAR_SHAPE(무시)
        val sec1 = para("2쪽 구역") + para(3, "필드", 4, " 끝")
        val bytes = cfb(mapOf(
            "FileHeader" to fileHeader(1),
            "BodyText/Section0" to deflateRaw(sec0),
            "BodyText/Section1" to deflateRaw(sec1),
            "PrvText" to "미리보기".toByteArray(Charsets.UTF_16LE),
        ))
        val r = HwpText.read(write("a.hwp", bytes))
        assertFalse(r.previewOnly)
        assertTrue(r.text, r.text.contains("기말고사: 12월 15일(월)\t10:00"))
        assertTrue(r.text, r.text.contains("평가\n중간 30%"))
        assertTrue(r.text, r.text.contains("2쪽 구역"))
        assertTrue(r.text, r.text.contains("필드 끝"))
        assertFalse(r.text, r.text.contains("x"))
        assertFalse(r.text, r.text.contains("미리보기"))
    }

    @Test fun readsUncompressedLargeStreamOutsideMiniStream() {
        val line = "주차별 계획 1주차 강의 소개 ".repeat(20)
        val sec = (0 until 30).map { para("$it $line") }.reduce { a, b -> a + b }
        assertTrue(sec.size > 4096)
        val r = HwpText.read(write("big.hwp", cfb(mapOf("FileHeader" to fileHeader(0), "BodyText/Section0" to sec))))
        assertTrue(r.text.startsWith("0 주차별 계획"))
        assertTrue(r.text.contains("29 주차별 계획"))
    }

    @Test fun oversizedSectionGivesLeadingTextNotFailure() {
        val p = para("강의계획서 내용 ".repeat(60)) // 약 1KB
        val out = ByteArrayOutputStream()
        out.write(para("첫 문단"))
        repeat((HwpText.MAX_PART_BYTES / p.size) + 500) { out.write(p) }
        val bytes = cfb(mapOf("FileHeader" to fileHeader(1), "BodyText/Section0" to deflateRaw(out.toByteArray()), "PrvText" to "미리".toByteArray(Charsets.UTF_16LE)))
        val r = HwpText.read(write("huge.hwp", bytes))
        assertFalse(r.previewOnly)
        assertTrue(r.text.startsWith("첫 문단\n강의계획서 내용"))
    }

    @Test fun emptyTablePreviewIsNotUsed() {
        val bytes = cfb(mapOf("FileHeader" to fileHeader(1), "BodyText/Section0" to deflateRaw(para(11)), "PrvText" to "<><><>\r\n<>".toByteArray(Charsets.UTF_16LE)))
        expectFail("글이 없습니다") { HwpText.read(write("empty.hwp", bytes)) }
    }

    @Test fun longRecordSizeHeader() {
        val long = "가".repeat(3000) // 6000바이트 > 0xFFF
        val r = HwpText.read(write("l.hwp", cfb(mapOf("FileHeader" to fileHeader(1), "BodyText/Section0" to deflateRaw(para(long) + para("다음"))))))
        assertEquals(long + "\n다음", r.text)
    }

    @Test fun distributionDocumentFallsBackToPreview() {
        val bytes = cfb(mapOf(
            "FileHeader" to fileHeader(1 or 4),
            "ViewText/Section0" to ByteArray(300) { 7 },
            "PrvText" to "<강의계획서><담당교수>\r\n앞부분".toByteArray(Charsets.UTF_16LE),
        ))
        val r = HwpText.read(write("d.hwp", bytes))
        assertTrue(r.previewOnly)
        assertTrue(r.note!!.contains("배포용"))
        assertTrue(r.text.contains("앞부분"))
    }

    @Test fun encryptedWithoutPreviewFailsClearly() {
        expectFail("암호") { HwpText.read(write("e.hwp", cfb(mapOf("FileHeader" to fileHeader(1 or 2), "BodyText/Section0" to ByteArray(10))))) }
    }

    @Test fun corruptBodyFallsBackToPreviewWithReason() {
        val bytes = cfb(mapOf(
            "FileHeader" to fileHeader(1),
            "BodyText/Section0" to byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x13, 0x55),
            "PrvText" to "미리보기 글".toByteArray(Charsets.UTF_16LE),
        ))
        val r = HwpText.read(write("c.hwp", bytes))
        assertTrue(r.previewOnly)
        assertTrue(r.note!!, r.note!!.contains("본문을 읽지 못해"))
    }

    @Test fun cyclicFatChainIsRejected() {
        val bytes = cfb(mapOf("FileHeader" to fileHeader(0), "BodyText/Section0" to para("x")), corruptFat = true)
        expectFail("손상") { HwpText.read(write("cyc.hwp", bytes)) }
    }

    @Test fun truncatedFileIsRejected() {
        val bytes = cfb(mapOf("FileHeader" to fileHeader(1), "BodyText/Section0" to deflateRaw(para("본문 ".repeat(2000)))))
        for (cut in listOf(600, bytes.size / 2, bytes.size - 100)) {
            try {
                HwpText.read(write("t$cut.hwp", bytes.copyOf(cut)))
            } catch (_: EtlException) {
            }
        }
        expectFail("") { HwpText.read(write("tiny.hwp", bytes.copyOf(100))) }
    }

    @Test fun garbageAndOldFormatsAreRejected() {
        expectFail("3.0") { HwpText.read(write("old.hwp", "HWP Document File V3.00 \u001a\u0001\u0002\u0003\u0004\u0005".toByteArray(Charsets.ISO_8859_1) + ByteArray(200))) }
        expectFail("형식") { HwpText.read(write("x.hwp", ByteArray(1000) { 3 })) }
        expectFail("FileHeader") { HwpText.read(write("n.hwp", cfb(mapOf("Other" to ByteArray(10))))) }
    }

    @Test fun recordWalkerStopsOnBadSizes() {
        val ok = para("정상")
        val bad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(67 or (0xFFF shl 20)).putInt(Int.MAX_VALUE).array()
        assertEquals("정상\n", HwpText.sectionText(ok + bad))
    }

    // ---- hwpx -------------------------------------------------------------

    private fun hwpx(sections: Map<String, String>): File {
        val f = File(dir, "a.hwpx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/hwp+zip".toByteArray()); z.closeEntry()
            for ((n, x) in sections) { z.putNextEntry(ZipEntry(n)); z.write(x.toByteArray()); z.closeEntry() }
        }
        return f
    }

    @Test fun readsHwpxSectionsInOrderWithTables() {
        val s0 = """<?xml version="1.0"?><hs:sec xmlns:hs="h" xmlns:hp="p">
            <hp:p><hp:run><hp:t>기말고사 &amp; 과제</hp:t></hp:run></hp:p>
            <hp:p><hp:run><hp:t>날짜<hp:tab/>12/15</hp:t><hp:t>&#xAC00;</hp:t></hp:run></hp:p>
            <hp:p><hp:run><hp:tbl><hp:tr>
              <hp:tc><hp:subList><hp:p><hp:run><hp:t>중간</hp:t></hp:run></hp:p></hp:subList></hp:tc>
              <hp:tc><hp:subList><hp:p><hp:run><hp:t>30%</hp:t></hp:run></hp:p></hp:subList></hp:tc>
            </hp:tr></hp:tbl></hp:run></hp:p>
            <hp:p><hp:run><hp:fieldBegin><hp:parameters><hp:stringParam name="x">숨은값</hp:stringParam></hp:parameters></hp:fieldBegin><hp:t>보임</hp:t><hp:t/></hp:run></hp:p>
            <hp:p><hp:run><hp:equation><hp:script>a over b</hp:script></hp:equation></hp:run></hp:p>
            </hs:sec>"""
        val s1 = """<hs:sec xmlns:hp="p"><hp:p><hp:run><hp:t>두 번째<hp:lineBreak/>구역</hp:t></hp:run></hp:p></hs:sec>"""
        val s10 = """<hs:sec xmlns:hp="p"><hp:p><hp:run><hp:t>열 번째</hp:t></hp:run></hp:p></hs:sec>"""
        val r = HwpText.read(hwpx(mapOf("Contents/section10.xml" to s10, "Contents/section1.xml" to s1, "Contents/section0.xml" to s0)))
        assertFalse(r.previewOnly)
        val t = r.text
        assertTrue(t, t.startsWith("기말고사 & 과제\n날짜\t12/15가"))
        assertTrue(t, t.contains("중간 | 30%"))
        assertTrue(t, t.contains("보임"))
        assertFalse(t, t.contains("숨은값"))
        assertFalse(t, t.contains("a over b"))
        assertTrue(t, t.indexOf("두 번째\n구역") < t.indexOf("열 번째"))
    }

    @Test fun hwpxWithoutSectionsOrBrokenZipFails() {
        expectFail("본문") { HwpText.read(hwpx(mapOf("Contents/header.xml" to "<x/>"))) }
        val f = hwpx(mapOf("Contents/section0.xml" to "<hp:p><hp:t>a</hp:t></hp:p>"))
        val broken = File(dir, "b.hwpx").apply { writeBytes(f.readBytes().copyOf(40)) }
        expectFail("") { HwpText.read(broken) }
    }

    // ---- 실제 파일(선택) -----------------------------------------------------

    /** HWP_SAMPLE_DIR에 실제 hwp/hwpx를 두면 예외 없이 글이 나오는지 본다(저장소에는 넣지 않는다). */
    @Test fun realSamplesIfProvided() {
        val d = System.getenv("HWP_SAMPLE_DIR")?.let(::File)
        assumeTrue(d != null && d.isDirectory)
        val files = d!!.listFiles { f -> HwpText.isHwp(f.name) }.orEmpty().sortedBy { it.name }
        val report = StringBuilder()
        for (f in files) {
            val r = try { HwpText.read(f) } catch (e: EtlException) { report.append("SAMPLE ${f.name}: FAIL ${e.message}\n"); continue }
            report.append("SAMPLE ${f.name}: ${r.text.length} chars preview=${r.previewOnly} ${r.note ?: ""}\n${r.text.take(400)}\n----\n")
        }
        System.getenv("HWP_SAMPLE_REPORT")?.let { File(it).writeText(report.toString()) }
    }

    private fun expectFail(msgPart: String, block: () -> Unit) {
        try {
            block()
            fail("expected failure")
        } catch (e: EtlException) {
            assertTrue(e.message ?: "", (e.message ?: "").contains(msgPart))
        }
    }
}
