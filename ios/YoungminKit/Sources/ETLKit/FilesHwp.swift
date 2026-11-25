import Foundation

/// 한글 문서(hwp 5.x, hwpx)에서 **글만** 뽑는다(Kotlin `HwpText`). 외부 라이브러리 없이 직접 읽는다.
/// 서식·그림·수식은 버린다. 표 안의 글은 칸 순서대로 들어간다.
///
/// - hwpx: zip 안의 `Contents/sectionN.xml`(OWPML)에서 `<hp:t>` 글.
/// - hwp 5.x: OLE 복합 문서(CFB)의 `BodyText/SectionN` 스트림(대개 raw deflate 압축)에서 문단 글 레코드(HWPTAG_PARA_TEXT).
///   배포용·암호 문서는 본문이 암호화돼 있어 `PrvText`(앞부분 미리보기)만 돌려준다.
/// - hwp 3.x 등 그 밖의 형식은 [EtlException].
///
/// 받은 파일은 신뢰할 수 없으므로 모든 길이·체인·압축 해제 크기에 상한을 둔다.
public enum HwpText {
    /// [previewOnly]면 본문 대신 앞부분 미리보기(`PrvText`)이고 [note]에 그 이유가 있다.
    public struct Result: Sendable, Hashable {
        public let text: String
        public let previewOnly: Bool
        public let note: String?
        public init(_ text: String, previewOnly: Bool = false, note: String? = nil) {
            self.text = text
            self.previewOnly = previewOnly
            self.note = note
        }
    }

    /// 읽어 들일 파일 크기 상한(메모리에 통째로 올린다).
    public static let MAX_FILE_BYTES = 64 << 20
    /// 압축을 푼 본문(구역 하나, 또는 hwpx XML 하나)의 상한. 압축 폭탄 방지.
    public static let MAX_PART_BYTES = 8 << 20
    /// 압축을 푼 본문 전체의 상한.
    public static let MAX_TOTAL_BYTES = 32 << 20
    static let MAX_SECTIONS = 1000
    /// 이만큼 글을 모으면 나머지 구역은 읽지 않는다(도구는 어차피 앞부분만 돌려준다).
    static let ENOUGH_CHARS = 200_000

    static let HWP_SIGNATURE = Array("HWP Document File".utf8)

    public static func isHwp(_ name: String) -> Bool { ["hwp", "hwpx"].contains(EtlFiles.ext(name)) }

    /// 확장자와 상관없이 내용(시그니처)을 보고 hwp/hwpx를 고른다.
    public static func read(_ f: URL) throws -> Result {
        let size = ((try? FileManager.default.attributesOfItem(atPath: f.path)[.size]) as? NSNumber)?.intValue ?? 0
        if size > MAX_FILE_BYTES { throw EtlException("한글 문서가 너무 커서 읽을 수 없습니다") }
        let head: [UInt8]
        do {
            let h = try FileHandle(forReadingFrom: f)
            defer { try? h.close() }
            head = [UInt8](((try? h.read(upToCount: 32)) ?? nil) ?? Data())
        } catch {
            throw EtlException("한글 문서를 읽지 못했습니다")
        }
        let n = head.count
        if n >= 8 && Array(head[0..<8]) == Cfb.SIGNATURE {
            guard let all = try? Data(contentsOf: f) else { throw EtlException("한글 문서를 읽지 못했습니다") }
            if all.count > MAX_FILE_BYTES { throw EtlException("한글 문서가 너무 커서 읽을 수 없습니다") }
            return try hwp([UInt8](all))
        }
        if n >= 4 && head[0] == UInt8(ascii: "P") && head[1] == UInt8(ascii: "K") { return Result(try hwpx(f)) }
        if n >= 17 && Array(head[0..<17]) == HWP_SIGNATURE {
            throw EtlException("한글 3.0 이전 형식(hwp 3.x)은 읽을 수 없습니다. 폰에 저장해 한글 앱으로 열어 주세요")
        }
        throw EtlException("한글 문서 형식이 아닙니다")
    }

    // MARK: hwpx

    private static let SECTION_XML = KRegex("^Contents/section(\\d+)\\.xml$", .ignoreCase)

    public static func hwpx(_ f: URL) throws -> String {
        var parts: [Int: String] = [:]
        var total = 0
        let zip: ZipArchive
        do { zip = try ZipArchive(contentsOf: f) } catch { throw EtlException("hwpx 파일이 손상돼 읽을 수 없습니다") }
        for e in zip.entries {
            guard let idx = SECTION_XML.find(e.name).flatMap({ Int32($0.groupValues[1]).map(Int.init) }) else { continue }
            if parts.count >= MAX_SECTIONS { break }
            let bytes: Data
            do {
                bytes = try zip.read(e, maxBytes: MAX_PART_BYTES)
            } catch ZipArchive.Failure.tooLarge {
                throw EtlException("한글 문서가 너무 커서 읽을 수 없습니다")
            } catch ZipArchive.Failure.corrupt {
                throw EtlException("hwpx 파일이 손상돼 읽을 수 없습니다")
            } catch {
                throw EtlException("hwpx 파일을 읽지 못했습니다")
            }
            total += bytes.count
            if total > MAX_TOTAL_BYTES { throw EtlException("한글 문서가 너무 커서 읽을 수 없습니다") }
            parts[idx] = owpmlText(String(decoding: bytes, as: UTF8.self))
        }
        if parts.isEmpty { throw EtlException("hwpx 문서에서 본문을 찾지 못했습니다") }
        return tidy(parts.sorted { $0.key < $1.key }.map(\.value).joined(separator: "\n\n"))
    }

    private static let TAG = KRegex("<(/?)(?:[\\w.-]+:)?([\\w.-]+)\\b[^>]*?(/?)>")

    /// OWPML 구역 XML의 글. `<t>` 안의 글만 모으고(수식 스크립트·필드 매개변수 같은 다른 글은 버림),
    /// 문단 끝은 줄, 표 칸 끝은 " | ", 표 행 끝은 줄로 바꾼다.
    public static func owpmlText(_ xml: String) -> String {
        var out = ""
        var inT = 0
        var last = xml.startIndex
        for m in TAG.findAll(xml) {
            if inT > 0 && m.range.lowerBound > last { out += unescape(String(xml[last..<m.range.lowerBound])) }
            last = m.range.upperBound
            let g = m.groupValues
            let closing = g[1] == "/"
            let selfClosing = g[3] == "/"
            switch g[2] {
            case "t": if !selfClosing { inT = closing ? max(0, inT - 1) : inT + 1 }
            case "tab": if inT > 0 || selfClosing { out += "\t" }
            case "lineBreak": out += "\n"
            case "nbSpace", "fwSpace": out += " "
            case "hyphen": out += "-"
            case "p": if closing { out += "\n" }
            case "tc":
                if closing {
                    while out.unicodeScalars.last == "\n" { out.unicodeScalars.removeLast() }
                    out += " | "
                }
            case "tr": if closing { out += "\n" }
            default: break
            }
        }
        return tidy(out.replacingOccurrences(of: " | \n", with: "\n"))
    }

    private static let ENTITY = KRegex("&(#x[0-9a-fA-F]+|#\\d+|lt|gt|quot|apos|amp);")

    private static func unescape(_ s: String) -> String {
        if !s.contains("&") { return s }
        return ENTITY.replace(s) { m in
            let v = m.groupValues[1]
            if v.hasPrefix("#x") { return Int(v.dropFirst(2), radix: 16).map(codePoint) ?? "" }
            if v.hasPrefix("#") { return Int(v.dropFirst(1)).map(codePoint) ?? "" }
            switch v {
            case "lt": return "<"
            case "gt": return ">"
            case "quot": return "\""
            case "apos": return "'"
            default: return "&"
            }
        }
    }

    private static func codePoint(_ cp: Int) -> String {
        guard (1...0x10FFFF).contains(cp), !(0xD800...0xDFFF).contains(cp), let u = Unicode.Scalar(UInt32(cp)) else { return "" }
        return String(Character(u))
    }

    // MARK: hwp 5.x

    public static func hwp(_ bytes: [UInt8]) throws -> Result {
        let cfb = try Cfb(bytes)
        guard let header = try cfb.stream("FileHeader") else { throw EtlException("한글 문서(FileHeader)가 아닙니다") }
        if header.count < 40 || Array(header[0..<17]) != HWP_SIGNATURE { throw EtlException("한글 문서(FileHeader)가 아닙니다") }
        let props = u32(header, 36)
        let compressed = props & 1 != 0
        let encrypted = props & 2 != 0
        let distribution = props & 4 != 0

        var bodyError: EtlException?
        if !encrypted && !distribution {
            do {
                if let text = try bodyText(cfb, compressed), !text.kIsBlank { return Result(text) }
            } catch let e as EtlException {
                bodyError = e
            }
        }
        var preview: String?
        if let raw = ((try? cfb.stream("PrvText")) ?? nil) { preview = prvText(raw) }
        // 빈 표만 있는 문서의 미리보기는 "<><>"뿐이라 쓸모가 없다.
        if let preview, preview.contains(where: { $0.isLetter || $0.isNumber }) {
            let why: String
            if distribution { why = "배포용 문서라 본문이 암호화돼 있어" }
            else if encrypted { why = "암호가 걸린 문서라" }
            else if let bodyError { why = "본문을 읽지 못해(\(bodyError.message))" }
            else { why = "본문에서 글을 찾지 못해" }
            return Result(preview, previewOnly: true, note: "\(why) 문서 앞부분 미리보기만 읽었습니다. 전체 내용은 폰에 저장해 한글 앱으로 열어야 합니다")
        }
        if let bodyError { throw bodyError }
        if encrypted { throw EtlException("암호가 걸린 한글 문서라 읽을 수 없습니다") }
        if distribution { throw EtlException("배포용 한글 문서라 본문을 읽을 수 없습니다(미리보기도 없음)") }
        throw EtlException("한글 문서에 글이 없습니다(그림이나 빈 표만 있을 수 있습니다). 폰에 저장해 직접 여세요")
    }

    private static func bodyText(_ cfb: Cfb, _ compressed: Bool) throws -> String? {
        var sb = ""
        var sbUnits = 0
        var total = 0
        var found = false
        for i in 0..<MAX_SECTIONS {
            guard let raw = try cfb.stream("BodyText/Section\(i)") else { break }
            found = true
            // 너무 큰 구역은 앞부분까지만 푼다(잘린 레코드에서 글 모으기가 멈춘다).
            let data = compressed ? try inflateRaw(raw, MAX_PART_BYTES) : Array(raw.prefix(MAX_PART_BYTES))
            total += data.count
            if sbUnits > 0 { sb += "\n\n"; sbUnits += 2 }
            let t = sectionText(data)
            sb += t
            sbUnits += t.utf16.count
            if total > MAX_TOTAL_BYTES || sbUnits > ENOUGH_CHARS { break }
        }
        return found ? tidy(sb) : nil
    }

    static let HWPTAG_PARA_TEXT = 0x10 + 51

    /// 구역 스트림(압축 해제 후)의 레코드를 따라 문단 글을 모은다. 잘린 레코드에서 멈춘다.
    public static func sectionText(_ data: [UInt8]) -> String {
        var sb: [UInt16] = []
        var p = 0
        while p + 4 <= data.count {
            let h = u32(data, p)
            p += 4
            let tag = h & 0x3FF
            var size = (h >> 20) & 0xFFF
            if size == 0xFFF {
                if p + 4 > data.count { break }
                let big = u32(data, p)
                p += 4
                if big > data.count - p { break }
                size = big
            }
            if size < 0 || size > data.count - p { break }
            if tag == HWPTAG_PARA_TEXT { paraText(data, p, size, &sb) }
            p += size
        }
        return String(decoding: sb, as: UTF16.self)
    }

    /// 문단 글(UTF-16LE). 0~31은 제어문자: 확장·인라인 제어(1~9, 11~12, 14~23)는 8글자(16바이트)를 차지하고,
    /// 문자 제어(0, 10, 13, 24~31)는 1글자다. 9=탭, 10=줄바꿈, 13=문단 끝.
    private static func paraText(_ d: [UInt8], _ off: Int, _ len: Int, _ sb: inout [UInt16]) {
        let start = sb.count
        var i = 0
        while i + 2 <= len {
            let c = Int(d[off + i]) | (Int(d[off + i + 1]) << 8)
            if c < 32 {
                switch c {
                case 9: sb.append(0x09)
                case 10, 13: sb.append(0x0A)
                case 24: sb.append(UInt16(UInt8(ascii: "-")))
                case 30, 31: sb.append(0x20)
                default: break
                }
                i += ((1...9).contains(c) || (11...12).contains(c) || (14...23).contains(c)) ? 16 : 2
            } else {
                sb.append(UInt16(c))
                i += 2
            }
        }
        if sb.count > start && sb[sb.count - 1] != 0x0A { sb.append(0x0A) }
    }

    /// `PrvText`: UTF-16LE 미리보기 글(앞부분만). 표 칸은 `<…>`로 감싸져 있다.
    public static func prvText(_ b: [UInt8]) -> String {
        var units: [UInt16] = []
        units.reserveCapacity(b.count / 2)
        var i = 0
        while i + 1 < b.count {
            let u = UInt16(b[i]) | (UInt16(b[i + 1]) << 8)
            if u != 0 { units.append(u) }
            i += 2
        }
        let s = String(decoding: units, as: UTF16.self)
        return tidy(s.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n"))
    }

    /// raw deflate를 [cap] 바이트 근처까지 푼다(넘으면 거기서 멈춤). 손상이면 [EtlException].
    public static func inflateRaw(_ src: [UInt8], _ cap: Int) throws -> [UInt8] {
        do { return [UInt8](try RawDeflate.inflate(Data(src), cap: cap)) } catch { throw EtlException("한글 문서 본문이 손상돼 읽을 수 없습니다") }
    }

    // MARK: 공통

    private static let TRAILING_SPACE = KRegex("[ \\t]+\\n")
    private static let MANY_NEWLINES = KRegex("\\n{3,}")

    static func tidy(_ s: String) -> String {
        var t = s.replacingOccurrences(of: "\u{000B}", with: "\n")
        t = TRAILING_SPACE.replace(t, "\n")
        t = MANY_NEWLINES.replace(t, "\n\n")
        return t.kTrim
    }

    static func u32(_ b: [UInt8], _ off: Int) -> Int {
        Int(b[off]) | (Int(b[off + 1]) << 8) | (Int(b[off + 2]) << 16) | (Int(b[off + 3]) << 24)
    }

    static func u16(_ b: [UInt8], _ off: Int) -> Int { Int(b[off]) | (Int(b[off + 1]) << 8) }
}

/// 최소한의 OLE 복합 문서(CFB, MS-CFB) 리더(Kotlin `Cfb`). 읽기만 하고, 경로("BodyText/Section0")로 스트림을 꺼낸다.
/// 체인 순환·범위 초과는 [EtlException]으로 끊는다.
public final class Cfb {
    public static let SIGNATURE: [UInt8] = [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]
    private static let END = 0xFFFF_FFFE
    private static let FREE = 0xFFFF_FFFF
    private static let MAX_DIR_ENTRIES = 100_000

    private struct Entry {
        let name: String
        let type: Int
        let left: Int
        let right: Int
        let child: Int
        let start: Int
        let size: Int
    }

    private let b: [UInt8]
    private let sectorSize: Int
    private let miniSectorSize: Int
    private let miniCutoff: Int
    private let fat: [Int]
    private let miniFat: [Int]
    private let entries: [Entry]
    private let miniStream: [UInt8]
    private var paths: [String: Entry] = [:]

    public init(_ b: [UInt8]) throws {
        let u16 = HwpText.u16
        let u32 = HwpText.u32
        if b.count < 512 || Array(b[0..<8]) != Cfb.SIGNATURE { throw Cfb.bad() }
        let shift = u16(b, 0x1E)
        if shift != 9 && shift != 12 { throw Cfb.bad() }
        let sectorSize = 1 << shift
        let miniShift = u16(b, 0x20)
        if miniShift != 6 { throw Cfb.bad() }
        let miniSectorSize = 1 << miniShift
        let miniCutoff = u32(b, 0x38)
        let numFat = u32(b, 0x2C)
        let firstDir = u32(b, 0x30)
        let firstMiniFat = u32(b, 0x3C)
        let numMiniFat = u32(b, 0x40)
        var difat = u32(b, 0x44)
        let numDifat = u32(b, 0x48)
        let totalSectors = max(b.count - sectorSize, 0) / sectorSize
        if numFat > totalSectors || numMiniFat > totalSectors || numDifat > totalSectors { throw Cfb.bad() }

        func offset(_ sector: Int) throws -> Int {
            let off = (sector + 1) * sectorSize
            if sector < 0 || off + sectorSize > b.count { throw Cfb.bad() }
            return off
        }

        // FAT 섹터 목록: 헤더의 109개 + DIFAT 체인.
        var fatSectors: [Int] = []
        for i in 0..<109 {
            let s = u32(b, 0x4C + i * 4)
            if s < totalSectors { fatSectors.append(s) }
        }
        let perDifat = sectorSize / 4 - 1
        var hops = 0
        while difat < totalSectors {
            let h = hops
            hops += 1
            if !(h <= numDifat) { break }
            let off = try offset(difat)
            for i in 0..<perDifat {
                let s = u32(b, off + i * 4)
                if s < totalSectors { fatSectors.append(s) }
            }
            difat = u32(b, off + perDifat * 4)
        }
        if numFat > 0 && fatSectors.count > numFat { fatSectors.removeSubrange(numFat...) }
        let per = sectorSize / 4
        var fat = [Int](repeating: 0, count: fatSectors.count * per)
        for (k, s) in fatSectors.enumerated() {
            let off = try offset(s)
            for i in 0..<per { fat[k * per + i] = u32(b, off + i * 4) }
        }

        let dirBytes = try Cfb.chain(firstDir, fat, sectorSize, b) { try offset($0) }
        var list: [Entry] = []
        var o = 0
        while o + 128 <= dirBytes.count && list.count < Cfb.MAX_DIR_ENTRIES {
            let nameLen = min(max(u16(dirBytes, o + 64), 0), 64)
            let nameBytes = max(nameLen - 2, 0) & ~1
            var units: [UInt16] = []
            var k = 0
            while k + 1 < nameBytes { units.append(UInt16(dirBytes[o + k]) | (UInt16(dirBytes[o + k + 1]) << 8)); k += 2 }
            let name = String(decoding: units, as: UTF16.self)
            let type = Int(dirBytes[o + 66])
            let size = u32(dirBytes, o + 120) // 상위 4바이트는 쓰지 않는다(v3는 0).
            list.append(Entry(name: name, type: type, left: u32(dirBytes, o + 68), right: u32(dirBytes, o + 72), child: u32(dirBytes, o + 76),
                              start: u32(dirBytes, o + 116), size: size))
            o += 128
        }
        guard let root = list.first, root.type == 5 else { throw Cfb.bad() }

        let miniFat: [Int]
        if numMiniFat > 0 && firstMiniFat < totalSectors {
            let mf = try Cfb.chain(firstMiniFat, fat, sectorSize, b) { try offset($0) }
            miniFat = (0..<(mf.count / 4)).map { u32(mf, $0 * 4) }
        } else {
            miniFat = []
        }
        let miniStream: [UInt8]
        if root.start < totalSectors && root.size > 0 {
            let all = try Cfb.chain(root.start, fat, sectorSize, b) { try offset($0) }
            miniStream = all.count > root.size ? Array(all[0..<root.size]) : all
        } else {
            miniStream = []
        }

        self.b = b
        self.sectorSize = sectorSize
        self.miniSectorSize = miniSectorSize
        self.miniCutoff = miniCutoff
        self.fat = fat
        self.miniFat = miniFat
        self.entries = list
        self.miniStream = miniStream
        index(root.child, "")
    }

    /// 스토리지 트리를 따라 "부모/이름" 경로표를 만든다. 형제는 레드블랙 트리(left/right).
    private func index(_ id: Int, _ prefix: String) {
        var seen = Set<Int>()
        var stack: [(Int, String)] = [(id, prefix)]
        while let (i, pre) = stack.popLast() {
            if i >= entries.count || i < 0 || !seen.insert(i).inserted { continue }
            let e = entries[i]
            if e.type == 0 { continue }
            let path = pre + e.name
            if paths[path] == nil { paths[path] = e }
            stack.append((e.left, pre))
            stack.append((e.right, pre))
            if e.type == 1 { stack.append((e.child, path + "/")) }
        }
    }

    /// 경로의 스트림 내용. 없으면 nil.
    public func stream(_ path: String) throws -> [UInt8]? {
        guard let e = paths[path], e.type == 2 else { return nil }
        if e.size > HwpText.MAX_FILE_BYTES { throw Cfb.bad() }
        if e.size == 0 { return [] }
        let data: [UInt8]
        if e.size < miniCutoff {
            let mini = miniSectorSize
            let ms = miniStream
            data = try Cfb.chain(e.start, miniFat, mini, ms) { s in
                let off = s * mini
                if off + mini > ms.count { throw Cfb.bad() }
                return off
            }
        } else {
            let size = sectorSize
            let bytes = b
            data = try Cfb.chain(e.start, fat, size, bytes) { s in
                let off = (s + 1) * size
                if s < 0 || off + size > bytes.count { throw Cfb.bad() }
                return off
            }
        }
        if data.count < e.size { throw Cfb.bad() }
        return Array(data[0..<e.size])
    }

    /// [start]부터 [table]을 따라 섹터를 이어 붙인다. 순환·범위 초과는 손상으로 본다.
    private static func chain(_ start: Int, _ table: [Int], _ unit: Int, _ src: [UInt8], _ locate: (Int) throws -> Int) throws -> [UInt8] {
        var out: [UInt8] = []
        var s = start
        var steps = 0
        let limit = table.count + 1
        while s != END && s != FREE {
            let st = steps
            steps += 1
            if s < 0 || s >= table.count || st > limit { throw bad() }
            let off = try locate(s)
            out.append(contentsOf: src[off..<(off + unit)])
            if out.count > HwpText.MAX_FILE_BYTES { throw bad() }
            s = table[s]
        }
        return out
    }

    private static func bad() -> EtlException { EtlException("한글 문서(OLE) 구조가 손상돼 읽을 수 없습니다") }
}
