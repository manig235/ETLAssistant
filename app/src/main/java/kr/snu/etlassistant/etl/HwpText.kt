package kr.snu.etlassistant.etl

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * 한글 문서(hwp 5.x, hwpx)에서 **글만** 뽑는다. 외부 라이브러리 없이 직접 읽는다(안드로이드 API에도 의존하지 않는다).
 * 서식·그림·수식은 버린다. 표 안의 글은 칸 순서대로 들어간다.
 *
 * - hwpx: zip 안의 `Contents/sectionN.xml`(OWPML)에서 `<hp:t>` 글.
 * - hwp 5.x: OLE 복합 문서(CFB)의 `BodyText/SectionN` 스트림(대개 raw deflate 압축)에서 문단 글 레코드(HWPTAG_PARA_TEXT).
 *   배포용·암호 문서는 본문이 암호화돼 있어 `PrvText`(앞부분 미리보기)만 돌려준다.
 * - hwp 3.x 등 그 밖의 형식은 [EtlException].
 *
 * 받은 파일은 신뢰할 수 없으므로 모든 길이·체인·압축 해제 크기에 상한을 둔다.
 */
object HwpText {

    /** [previewOnly]면 본문 대신 앞부분 미리보기(`PrvText`)이고 [note]에 그 이유가 있다. */
    data class Result(val text: String, val previewOnly: Boolean = false, val note: String? = null)

    /** 읽어 들일 파일 크기 상한(메모리에 통째로 올린다). */
    const val MAX_FILE_BYTES = 64 shl 20
    /** 압축을 푼 본문(구역 하나, 또는 hwpx XML 하나)의 상한. 압축 폭탄 방지. */
    const val MAX_PART_BYTES = 8 shl 20
    /** 압축을 푼 본문 전체의 상한. */
    const val MAX_TOTAL_BYTES = 32 shl 20
    private const val MAX_SECTIONS = 1000
    /** 이만큼 글을 모으면 나머지 구역은 읽지 않는다(도구는 어차피 앞부분만 돌려준다). */
    private const val ENOUGH_CHARS = 200_000

    fun isHwp(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("hwp", "hwpx")

    /** 확장자와 상관없이 내용(시그니처)을 보고 hwp/hwpx를 고른다. */
    fun read(f: File): Result {
        if (f.length() > MAX_FILE_BYTES) throw EtlException("한글 문서가 너무 커서 읽을 수 없습니다")
        val head = ByteArray(32)
        val n = f.inputStream().use { it.read(head) }
        return when {
            n >= 8 && head.copyOfRange(0, 8).contentEquals(Cfb.SIGNATURE) -> hwp(f.readBytes())
            n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Result(hwpx(f))
            n >= 17 && String(head, 0, 17, Charsets.US_ASCII) == "HWP Document File" ->
                throw EtlException("한글 3.0 이전 형식(hwp 3.x)은 읽을 수 없습니다. 폰에 저장해 한글 앱으로 열어 주세요")
            else -> throw EtlException("한글 문서 형식이 아닙니다")
        }
    }

    // ---- hwpx -------------------------------------------------------------

    fun hwpx(f: File): String {
        val parts = sortedMapOf<Int, String>()
        var total = 0L
        try {
            ZipFile(f).use { zip ->
                for (e in zip.entries()) {
                    val idx = Regex("^Contents/section(\\d+)\\.xml$", RegexOption.IGNORE_CASE).find(e.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (parts.size >= MAX_SECTIONS) break
                    val bytes = zip.getInputStream(e).use { readCapped(it, MAX_PART_BYTES.toLong()) }
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw EtlException("한글 문서가 너무 커서 읽을 수 없습니다")
                    parts[idx] = owpmlText(bytes.toString(Charsets.UTF_8))
                }
            }
        } catch (e: ZipException) {
            throw EtlException("hwpx 파일이 손상돼 읽을 수 없습니다")
        } catch (e: java.io.IOException) {
            throw EtlException("hwpx 파일을 읽지 못했습니다")
        }
        if (parts.isEmpty()) throw EtlException("hwpx 문서에서 본문을 찾지 못했습니다")
        return tidy(parts.values.joinToString("\n\n"))
    }

    private val TAG = Regex("<(/?)(?:[\\w.-]+:)?([\\w.-]+)\\b[^>]*?(/?)>")

    /**
     * OWPML 구역 XML의 글. `<t>` 안의 글만 모으고(수식 스크립트·필드 매개변수 같은 다른 글은 버림),
     * 문단 끝은 줄, 표 칸 끝은 " | ", 표 행 끝은 줄로 바꾼다.
     */
    fun owpmlText(xml: String): String {
        val out = StringBuilder()
        var inT = 0
        var last = 0
        for (m in TAG.findAll(xml)) {
            if (inT > 0 && m.range.first > last) out.append(unescape(xml.substring(last, m.range.first)))
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.groupValues[3] == "/"
            when (m.groupValues[2]) {
                "t" -> if (!selfClosing) inT = if (closing) maxOf(0, inT - 1) else inT + 1
                "tab" -> if (inT > 0 || selfClosing) out.append('\t')
                "lineBreak" -> out.append('\n')
                "nbSpace", "fwSpace" -> out.append(' ')
                "hyphen" -> out.append('-')
                "p" -> if (closing) out.append('\n')
                "tc" -> if (closing) {
                    while (out.endsWith("\n")) out.setLength(out.length - 1)
                    out.append(" | ")
                }
                "tr" -> if (closing) out.append('\n')
            }
        }
        return tidy(out.toString().replace(" | \n", "\n"))
    }

    private fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|lt|gt|quot|apos|amp);").replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v.startsWith("#x") -> v.substring(2).toIntOrNull(16)?.let { cp -> codePoint(cp) } ?: ""
                v.startsWith("#") -> v.substring(1).toIntOrNull()?.let { cp -> codePoint(cp) } ?: ""
                v == "lt" -> "<"; v == "gt" -> ">"; v == "quot" -> "\""; v == "apos" -> "'"
                else -> "&"
            }
        }
    }

    private fun codePoint(cp: Int): String =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else ""

    // ---- hwp 5.x ----------------------------------------------------------

    fun hwp(bytes: ByteArray): Result {
        val cfb = Cfb(bytes)
        val header = cfb.stream("FileHeader") ?: throw EtlException("한글 문서(FileHeader)가 아닙니다")
        if (header.size < 40 || String(header, 0, 17, Charsets.US_ASCII) != "HWP Document File") throw EtlException("한글 문서(FileHeader)가 아닙니다")
        val props = u32(header, 36)
        val compressed = props and 1L != 0L
        val encrypted = props and 2L != 0L
        val distribution = props and 4L != 0L

        var bodyError: EtlException? = null
        if (!encrypted && !distribution) {
            try {
                val text = bodyText(cfb, compressed)
                if (text != null && text.isNotBlank()) return Result(text)
            } catch (e: EtlException) {
                bodyError = e
            }
        }
        val preview = try { cfb.stream("PrvText")?.let { prvText(it) } } catch (_: EtlException) { null }
        // 빈 표만 있는 문서의 미리보기는 "<><>"뿐이라 쓸모가 없다.
        if (preview != null && preview.any { it.isLetterOrDigit() }) {
            val why = when {
                distribution -> "배포용 문서라 본문이 암호화돼 있어"
                encrypted -> "암호가 걸린 문서라"
                bodyError != null -> "본문을 읽지 못해(${bodyError.message})"
                else -> "본문에서 글을 찾지 못해"
            }
            return Result(preview, previewOnly = true, note = "$why 문서 앞부분 미리보기만 읽었습니다. 전체 내용은 폰에 저장해 한글 앱으로 열어야 합니다")
        }
        bodyError?.let { throw it }
        throw EtlException(when {
            encrypted -> "암호가 걸린 한글 문서라 읽을 수 없습니다"
            distribution -> "배포용 한글 문서라 본문을 읽을 수 없습니다(미리보기도 없음)"
            else -> "한글 문서에 글이 없습니다(그림이나 빈 표만 있을 수 있습니다). 폰에 저장해 직접 여세요"
        })
    }

    private fun bodyText(cfb: Cfb, compressed: Boolean): String? {
        val sb = StringBuilder()
        var total = 0L
        var found = false
        for (i in 0 until MAX_SECTIONS) {
            val raw = cfb.stream("BodyText/Section$i") ?: break
            found = true
            // 너무 큰 구역은 앞부분까지만 푼다(잘린 레코드에서 글 모으기가 멈춘다).
            val data = if (compressed) inflateRaw(raw, MAX_PART_BYTES) else raw.copyOf(minOf(raw.size, MAX_PART_BYTES))
            total += data.size
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(sectionText(data))
            if (total > MAX_TOTAL_BYTES || sb.length > ENOUGH_CHARS) break
        }
        return if (found) tidy(sb.toString()) else null
    }

    private const val HWPTAG_PARA_TEXT = 0x10 + 51

    /** 구역 스트림(압축 해제 후)의 레코드를 따라 문단 글을 모은다. 잘린 레코드에서 멈춘다. */
    fun sectionText(data: ByteArray): String {
        val sb = StringBuilder()
        var p = 0
        while (p + 4 <= data.size) {
            val h = u32(data, p)
            p += 4
            val tag = (h and 0x3FF).toInt()
            var size = ((h ushr 20) and 0xFFF).toInt()
            if (size == 0xFFF) {
                if (p + 4 > data.size) break
                val big = u32(data, p)
                p += 4
                if (big > data.size - p) break
                size = big.toInt()
            }
            if (size < 0 || size > data.size - p) break
            if (tag == HWPTAG_PARA_TEXT) paraText(data, p, size, sb)
            p += size
        }
        return sb.toString()
    }

    /**
     * 문단 글(UTF-16LE). 0~31은 제어문자: 확장·인라인 제어(1~9, 11~12, 14~23)는 8글자(16바이트)를 차지하고,
     * 문자 제어(0, 10, 13, 24~31)는 1글자다. 9=탭, 10=줄바꿈, 13=문단 끝.
     */
    private fun paraText(d: ByteArray, off: Int, len: Int, sb: StringBuilder) {
        val start = sb.length
        var i = 0
        while (i + 2 <= len) {
            val c = (d[off + i].toInt() and 0xFF) or ((d[off + i + 1].toInt() and 0xFF) shl 8)
            if (c < 32) {
                when (c) {
                    9 -> sb.append('\t')
                    10 -> sb.append('\n')
                    13 -> sb.append('\n')
                    24 -> sb.append('-')
                    30, 31 -> sb.append(' ')
                }
                i += if (c in 1..9 || c in 11..12 || c in 14..23) 16 else 2
            } else {
                sb.append(c.toChar())
                i += 2
            }
        }
        if (sb.length > start && sb[sb.length - 1] != '\n') sb.append('\n')
    }

    /** `PrvText`: UTF-16LE 미리보기 글(앞부분만). 표 칸은 `<…>`로 감싸져 있다. */
    fun prvText(b: ByteArray): String {
        val s = String(b, 0, b.size and 1.inv(), Charsets.UTF_16LE).replace("\u0000", "")
        return tidy(s.replace("\r\n", "\n").replace('\r', '\n'))
    }

    /** raw deflate를 [cap] 바이트 근처까지 푼다(넘으면 거기서 멈춤). 손상이면 [EtlException]. */
    fun inflateRaw(src: ByteArray, cap: Int): ByteArray {
        val inf = Inflater(true)
        try {
            // nowrap 모드는 끝에 여분 바이트 하나가 필요할 수 있다.
            inf.setInput(src.copyOf(src.size + 1))
            val out = ByteArrayOutputStream(minOf(cap, maxOf(1024, src.size * 4)))
            val buf = ByteArray(1 shl 16)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                }
                out.write(buf, 0, n)
                if (out.size() >= cap) break // 앞부분만 쓴다
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw EtlException("한글 문서 본문이 손상돼 읽을 수 없습니다")
        } finally {
            inf.end()
        }
    }

    // ---- 공통 -------------------------------------------------------------

    private fun tidy(s: String): String {
        var t = s.replace('\u000B', '\n')
        t = Regex("[ \\t]+\\n").replace(t, "\n")
        t = Regex("\\n{3,}").replace(t, "\n\n")
        return t.trim()
    }

    private fun readCapped(input: java.io.InputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) throw EtlException("한글 문서가 너무 커서 읽을 수 없습니다")
        }
        return out.toByteArray()
    }

    internal fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    internal fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}

/**
 * 최소한의 OLE 복합 문서(CFB, MS-CFB) 리더. 읽기만 하고, 경로("BodyText/Section0")로 스트림을 꺼낸다.
 * 체인 순환·범위 초과는 [EtlException]으로 끊는다.
 */
class Cfb(private val b: ByteArray) {
    companion object {
        val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        private const val END = 0xFFFFFFFEL
        private const val FREE = 0xFFFFFFFFL
        private const val MAX_DIR_ENTRIES = 100_000
    }

    private class Entry(val name: String, val type: Int, val left: Long, val right: Long, val child: Long, val start: Long, val size: Long)

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: LongArray
    private val miniFat: LongArray
    private val entries: List<Entry>
    private val miniStream: ByteArray
    private val paths = HashMap<String, Entry>()

    init {
        if (b.size < 512 || !b.copyOfRange(0, 8).contentEquals(SIGNATURE)) bad()
        val shift = HwpText.u16(b, 0x1E)
        if (shift != 9 && shift != 12) bad()
        sectorSize = 1 shl shift
        val miniShift = HwpText.u16(b, 0x20)
        if (miniShift != 6) bad()
        miniSectorSize = 1 shl miniShift
        miniCutoff = HwpText.u32(b, 0x38)
        val numFat = HwpText.u32(b, 0x2C)
        val firstDir = HwpText.u32(b, 0x30)
        val firstMiniFat = HwpText.u32(b, 0x3C)
        val numMiniFat = HwpText.u32(b, 0x40)
        var difat = HwpText.u32(b, 0x44)
        val numDifat = HwpText.u32(b, 0x48)
        val totalSectors = ((b.size - sectorSize).coerceAtLeast(0) / sectorSize).toLong()
        if (numFat > totalSectors || numMiniFat > totalSectors || numDifat > totalSectors) bad()

        // FAT 섹터 목록: 헤더의 109개 + DIFAT 체인.
        val fatSectors = ArrayList<Long>()
        for (i in 0 until 109) {
            val s = HwpText.u32(b, 0x4C + i * 4)
            if (s < totalSectors) fatSectors += s
        }
        val perDifat = sectorSize / 4 - 1
        var hops = 0
        while (difat < totalSectors && hops++ <= numDifat) {
            val off = offset(difat)
            for (i in 0 until perDifat) {
                val s = HwpText.u32(b, off + i * 4)
                if (s < totalSectors) fatSectors += s
            }
            difat = HwpText.u32(b, off + perDifat * 4)
        }
        if (numFat > 0 && fatSectors.size > numFat) fatSectors.subList(numFat.toInt(), fatSectors.size).clear()
        val per = sectorSize / 4
        fat = LongArray(fatSectors.size * per)
        fatSectors.forEachIndexed { k, s ->
            val off = offset(s)
            for (i in 0 until per) fat[k * per + i] = HwpText.u32(b, off + i * 4)
        }

        val dirBytes = chain(firstDir, fat, sectorSize, b) { offset(it) }
        val list = ArrayList<Entry>()
        var o = 0
        while (o + 128 <= dirBytes.size && list.size < MAX_DIR_ENTRIES) {
            val nameLen = HwpText.u16(dirBytes, o + 64).coerceIn(0, 64)
            val name = String(dirBytes, o, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            val type = dirBytes[o + 66].toInt() and 0xFF
            val size = HwpText.u32(dirBytes, o + 120) // 상위 4바이트는 쓰지 않는다(v3는 0).
            list += Entry(name, type, HwpText.u32(dirBytes, o + 68), HwpText.u32(dirBytes, o + 72), HwpText.u32(dirBytes, o + 76), HwpText.u32(dirBytes, o + 116), size)
            o += 128
        }
        entries = list
        val root = entries.firstOrNull()?.takeIf { it.type == 5 } ?: bad()

        miniFat = if (numMiniFat > 0 && firstMiniFat < totalSectors) {
            val mf = chain(firstMiniFat, fat, sectorSize, b) { offset(it) }
            LongArray(mf.size / 4) { HwpText.u32(mf, it * 4) }
        } else LongArray(0)
        miniStream = if (root.start < totalSectors && root.size > 0) {
            chain(root.start, fat, sectorSize, b) { offset(it) }.let { if (it.size > root.size) it.copyOf(root.size.toInt()) else it }
        } else ByteArray(0)

        index(root.child, "", HashSet())
    }

    /** 스토리지 트리를 따라 "부모/이름" 경로표를 만든다. 형제는 레드블랙 트리(left/right). */
    private fun index(id: Long, prefix: String, seen: MutableSet<Long>) {
        val stack = ArrayDeque<Pair<Long, String>>()
        stack.addLast(id to prefix)
        while (stack.isNotEmpty()) {
            val (i, pre) = stack.removeLast()
            if (i >= entries.size || i < 0 || !seen.add(i)) continue
            val e = entries[i.toInt()]
            if (e.type == 0) continue
            val path = pre + e.name
            paths.putIfAbsent(path, e)
            stack.addLast(e.left to pre)
            stack.addLast(e.right to pre)
            if (e.type == 1) stack.addLast(e.child to "$path/")
        }
    }

    /** 경로의 스트림 내용. 없으면 null. */
    fun stream(path: String): ByteArray? {
        val e = paths[path]?.takeIf { it.type == 2 } ?: return null
        if (e.size > HwpText.MAX_FILE_BYTES) bad()
        if (e.size == 0L) return ByteArray(0)
        val data = if (e.size < miniCutoff) {
            chain(e.start, miniFat, miniSectorSize, miniStream) { s ->
                val off = s * miniSectorSize
                if (off + miniSectorSize > miniStream.size) bad()
                off.toInt()
            }
        } else chain(e.start, fat, sectorSize, b) { offset(it) }
        if (data.size < e.size) bad()
        return data.copyOf(e.size.toInt())
    }

    private fun offset(sector: Long): Int {
        val off = (sector + 1) * sectorSize
        if (sector < 0 || off + sectorSize > b.size) bad()
        return off.toInt()
    }

    /** [start]부터 [table]을 따라 섹터를 이어 붙인다. 순환·범위 초과는 손상으로 본다. */
    private fun chain(start: Long, table: LongArray, unit: Int, src: ByteArray, locate: (Long) -> Int): ByteArray {
        val out = ByteArrayOutputStream()
        var s = start
        var steps = 0
        val limit = table.size + 1
        while (s != END && s != FREE) {
            if (s < 0 || s >= table.size || steps++ > limit) bad()
            out.write(src, locate(s), unit)
            if (out.size() > HwpText.MAX_FILE_BYTES) bad()
            s = table[s.toInt()]
        }
        return out.toByteArray()
    }

    private fun bad(): Nothing = throw EtlException("한글 문서(OLE) 구조가 손상돼 읽을 수 없습니다")
}
