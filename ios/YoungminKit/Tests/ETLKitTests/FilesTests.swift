import CoreGraphics
import CoreText
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import ETLKit

// Kotlin HwpTextTest · EtlFilesTest · WorkspaceImageTest + ZIP/docx/pptx/PDF 픽스처(코드에서 만든다).

/// 테스트마다 새 임시 폴더. iOS 배치처럼 `<tmp>/etl-files`가 작업 폴더다.
final class TempWorkspace {
    let base: URL
    var ws: URL { base.appendingPathComponent("etl-files", isDirectory: true) }

    init(makeWorkspace: Bool = true) {
        base = FileManager.default.temporaryDirectory.appendingPathComponent("filestest-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        if makeWorkspace { try? FileManager.default.createDirectory(at: ws, withIntermediateDirectories: true) }
    }

    deinit { try? FileManager.default.removeItem(at: base) }

    @discardableResult
    func write(_ rel: String, _ data: Data, under root: URL? = nil) -> URL {
        let url = (root ?? ws).appendingPathComponent(rel)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url)
        return url
    }

    @discardableResult
    func write(_ rel: String, _ text: String, under root: URL? = nil) -> URL { write(rel, Data(text.utf8), under: root) }
}

private func expectFail(_ part: String, _ block: () throws -> Void, sourceLocation: SourceLocation = #_sourceLocation) {
    do {
        try block()
        Issue.record("expected failure", sourceLocation: sourceLocation)
    } catch let e as EtlException {
        #expect(part.isEmpty || e.message.contains(part), "\(e.message)", sourceLocation: sourceLocation)
    } catch {
        Issue.record("unexpected error \(error)", sourceLocation: sourceLocation)
    }
}

private func utf16le(_ s: String) -> [UInt8] { s.utf16.flatMap { [UInt8($0 & 0xFF), UInt8($0 >> 8)] } }

// MARK: - HWP 픽스처

/// 최소 CFB(v3, 512바이트 섹터) 작성기(Kotlin 테스트와 같다). 4096바이트 미만 스트림은 미니 스트림에 둔다. 경로는 한 단계 스토리지까지.
private func cfb(_ streams: [(String, [UInt8])], corruptFat: Bool = false) -> [UInt8] {
    let ss = 512, mini = 64, cutoff = 4096
    final class E {
        let name: String; let type: Int
        var child = -1, right = -1, start = -2, size = 0
        let data: [UInt8]?
        init(_ name: String, _ type: Int, size: Int = 0, data: [UInt8]? = nil) { self.name = name; self.type = type; self.size = size; self.data = data }
    }
    var entries = [E("Root Entry", 5)]
    var storages: [String: Int] = [:]
    func addChild(_ parent: Int, _ idx: Int) {
        let p = entries[parent]
        if p.child < 0 { p.child = idx; return }
        var c = p.child
        while entries[c].right >= 0 { c = entries[c].right }
        entries[c].right = idx
    }
    for (path, data) in streams {
        let parts = path.split(separator: "/").map(String.init)
        var parent = 0
        if parts.count == 2 {
            if let s = storages[parts[0]] { parent = s } else {
                entries.append(E(parts[0], 1)); addChild(0, entries.count - 1); storages[parts[0]] = entries.count - 1
                parent = entries.count - 1
            }
        }
        entries.append(E(parts.last!, 2, size: data.count, data: data))
        addChild(parent, entries.count - 1)
    }
    var miniBytes: [UInt8] = []
    var miniFat: [Int] = []
    for e in entries where e.type == 2 && e.size < cutoff && e.size > 0 {
        let first = miniFat.count
        let n = (e.size + mini - 1) / mini
        for k in 0..<n { miniFat.append(k == n - 1 ? -2 : first + k + 1) }
        e.start = first
        miniBytes += e.data!
        miniBytes += [UInt8](repeating: 0, count: n * mini - e.size)
    }
    let dirSectors = (entries.count * 128 + ss - 1) / ss
    let miniFatSectors = (miniFat.count * 4 + ss - 1) / ss
    let miniStreamSectors = (miniBytes.count + ss - 1) / ss
    let big = entries.filter { $0.type == 2 && $0.size >= cutoff }
    let bigSectors = big.reduce(0) { $0 + ($1.size + ss - 1) / ss }
    let dataSectors = dirSectors + miniFatSectors + miniStreamSectors + bigSectors
    var fatSectors = 1
    while fatSectors * (ss / 4) < fatSectors + dataSectors { fatSectors += 1 }
    let total = fatSectors + dataSectors
    var fat = [Int](repeating: -1, count: fatSectors * (ss / 4))
    for i in 0..<fatSectors { fat[i] = -3 }
    var next = fatSectors
    func alloc(_ n: Int) -> Int {
        if n == 0 { return -2 }
        let first = next
        for k in 0..<n { fat[first + k] = k == n - 1 ? -2 : first + k + 1 }
        next += n
        return first
    }
    let dirStart = alloc(dirSectors)
    let miniFatStart = alloc(miniFatSectors)
    let miniStreamStart = alloc(miniStreamSectors)
    entries[0].start = miniStreamStart
    entries[0].size = miniBytes.count
    var bigStarts: [(E, Int)] = []
    for e in big { let s = alloc((e.size + ss - 1) / ss); e.start = s; bigStarts.append((e, s)) }
    if corruptFat { fat[dirStart] = dirStart } // 순환 체인
    precondition(next == total)

    var out = [UInt8](repeating: 0, count: (total + 1) * ss)
    func put32(_ off: Int, _ v: Int) { let u = UInt32(truncatingIfNeeded: v); for k in 0..<4 { out[off + k] = UInt8((u >> (8 * UInt32(k))) & 0xFF) } }
    func put16(_ off: Int, _ v: Int) { let u = UInt16(truncatingIfNeeded: v); out[off] = UInt8(u & 0xFF); out[off + 1] = UInt8(u >> 8) }
    for (i, b) in Cfb.SIGNATURE.enumerated() { out[i] = b }
    put16(0x18, 0x3E); put16(0x1A, 3); put16(0x1C, 0xFFFE)
    put16(0x1E, 9); put16(0x20, 6)
    put32(0x2C, fatSectors); put32(0x30, dirStart); put32(0x38, cutoff)
    put32(0x3C, miniFatSectors > 0 ? miniFatStart : -2); put32(0x40, miniFatSectors)
    put32(0x44, -2); put32(0x48, 0)
    for i in 0..<109 { put32(0x4C + i * 4, i < fatSectors ? i : -1) }
    func sec(_ n: Int) -> Int { (n + 1) * ss }
    for i in fat.indices { put32(sec(0) + i * 4, fat[i]) }
    for (i, e) in entries.enumerated() {
        let o = sec(dirStart) + i * 128
        let nm = utf16le(e.name)
        for k in nm.indices { out[o + k] = nm[k] }
        put16(o + 64, nm.count + 2); out[o + 66] = UInt8(e.type); out[o + 67] = 1
        put32(o + 68, -1); put32(o + 72, e.right); put32(o + 76, e.child)
        put32(o + 116, e.start); put32(o + 120, e.size)
    }
    var i = dirSectors * ss / 128 - 1
    while i >= entries.count { // 빈 엔트리
        let o = sec(dirStart) + i * 128
        put32(o + 68, -1); put32(o + 72, -1); put32(o + 76, -1)
        i -= 1
    }
    for (k, v) in miniFat.enumerated() { put32(sec(miniFatStart) + k * 4, v) }
    for k in miniBytes.indices { out[sec(miniStreamStart) + k] = miniBytes[k] }
    for (e, s) in bigStarts { for k in e.data!.indices { out[sec(s) + k] = e.data![k] } }
    return out
}

private func fileHeader(_ props: Int) -> [UInt8] {
    var b = [UInt8](repeating: 0, count: 256)
    for (i, c) in Array("HWP Document File".utf8).enumerated() { b[i] = c }
    for (off, v) in [(32, 0x0501_0000), (36, props)] { for k in 0..<4 { b[off + k] = UInt8((v >> (8 * k)) & 0xFF) } }
    return b
}

private func le32(_ v: Int) -> [UInt8] { (0..<4).map { UInt8((UInt32(truncatingIfNeeded: v) >> (8 * UInt32($0))) & 0xFF) } }

private func record(_ tag: Int, _ body: [UInt8]) -> [UInt8] {
    if body.count < 0xFFF { return le32(tag | (body.count << 20)) + body }
    return le32(tag | (0xFFF << 20)) + le32(body.count) + body
}

/// 문단 글: 일반 글자(String)와 제어문자(Int, 확장 제어는 8글자 차지).
private func para(_ parts: Any...) -> [UInt8] {
    var units: [UInt16] = []
    for p in parts {
        if let s = p as? String { units += Array(s.utf16) }
        if let c = p as? Int {
            units.append(UInt16(c))
            if (1...9).contains(c) || (11...12).contains(c) || (14...23).contains(c) { units += [UInt16](repeating: UInt16(UInt8(ascii: "x")), count: 7) }
        }
    }
    units.append(13)
    return record(67, units.flatMap { [UInt8($0 & 0xFF), UInt8($0 >> 8)] })
}

private func deflateRaw(_ b: [UInt8]) -> [UInt8] { [UInt8](RawDeflate.deflate(Data(b))) }

// MARK: - HwpText

@Suite struct HwpTextTests {
    let t = TempWorkspace()
    private func write(_ name: String, _ bytes: [UInt8]) -> URL { t.write(name, Data(bytes), under: t.base) }

    @Test func readsCompressedSectionsWithControlsAndTable() throws {
        let sec0 = record(66, [UInt8](repeating: 0, count: 22)) + // PARA_HEADER(무시)
            para("기말고사: 12월 15일(월)", 9, "10:00") +
            para(11, "") + // 표 컨트롤
            para("평가", 10, "중간 30%") +
            record(68, [UInt8](repeating: 0, count: 12)) // PARA_CHAR_SHAPE(무시)
        let sec1 = para("2쪽 구역") + para(3, "필드", 4, " 끝")
        let bytes = cfb([
            ("FileHeader", fileHeader(1)),
            ("BodyText/Section0", deflateRaw(sec0)),
            ("BodyText/Section1", deflateRaw(sec1)),
            ("PrvText", utf16le("미리보기")),
        ])
        let r = try HwpText.read(write("a.hwp", bytes))
        #expect(!r.previewOnly)
        #expect(r.text.contains("기말고사: 12월 15일(월)\t10:00"), "\(r.text)")
        #expect(r.text.contains("평가\n중간 30%"))
        #expect(r.text.contains("2쪽 구역"))
        #expect(r.text.contains("필드 끝"))
        #expect(!r.text.contains("x"))
        #expect(!r.text.contains("미리보기"))
    }

    @Test func readsUncompressedLargeStreamOutsideMiniStream() throws {
        let line = String(repeating: "주차별 계획 1주차 강의 소개 ", count: 20)
        let sec = (0..<30).map { para("\($0) \(line)") }.reduce([], +)
        #expect(sec.count > 4096)
        let r = try HwpText.read(write("big.hwp", cfb([("FileHeader", fileHeader(0)), ("BodyText/Section0", sec)])))
        #expect(r.text.hasPrefix("0 주차별 계획"))
        #expect(r.text.contains("29 주차별 계획"))
    }

    @Test func oversizedSectionGivesLeadingTextNotFailure() throws {
        let p = para(String(repeating: "강의계획서 내용 ", count: 60)) // 약 1KB
        var out = para("첫 문단")
        out.reserveCapacity(HwpText.MAX_PART_BYTES + 600 * p.count)
        for _ in 0..<((HwpText.MAX_PART_BYTES / p.count) + 500) { out += p }
        let bytes = cfb([("FileHeader", fileHeader(1)), ("BodyText/Section0", deflateRaw(out)), ("PrvText", utf16le("미리"))])
        let r = try HwpText.read(write("huge.hwp", bytes))
        #expect(!r.previewOnly)
        #expect(r.text.hasPrefix("첫 문단\n강의계획서 내용"))
    }

    @Test func emptyTablePreviewIsNotUsed() {
        let bytes = cfb([("FileHeader", fileHeader(1)), ("BodyText/Section0", deflateRaw(para(11))), ("PrvText", utf16le("<><><>\r\n<>"))])
        expectFail("글이 없습니다") { _ = try HwpText.read(write("empty.hwp", bytes)) }
    }

    @Test func longRecordSizeHeader() throws {
        let long = String(repeating: "가", count: 3000) // 6000바이트 > 0xFFF
        let r = try HwpText.read(write("l.hwp", cfb([("FileHeader", fileHeader(1)), ("BodyText/Section0", deflateRaw(para(long) + para("다음")))])))
        #expect(r.text == long + "\n다음")
    }

    @Test func distributionDocumentFallsBackToPreview() throws {
        let bytes = cfb([
            ("FileHeader", fileHeader(1 | 4)),
            ("ViewText/Section0", [UInt8](repeating: 7, count: 300)),
            ("PrvText", utf16le("<강의계획서><담당교수>\r\n앞부분")),
        ])
        let r = try HwpText.read(write("d.hwp", bytes))
        #expect(r.previewOnly)
        #expect(r.note?.contains("배포용") == true)
        #expect(r.text.contains("앞부분"))
    }

    @Test func encryptedWithoutPreviewFailsClearly() {
        expectFail("암호") { _ = try HwpText.read(write("e.hwp", cfb([("FileHeader", fileHeader(1 | 2)), ("BodyText/Section0", [UInt8](repeating: 0, count: 10))]))) }
    }

    @Test func corruptBodyFallsBackToPreviewWithReason() throws {
        let bytes = cfb([
            ("FileHeader", fileHeader(1)),
            ("BodyText/Section0", [0xFF, 0xFF, 0xFF, 0x13, 0x55]),
            ("PrvText", utf16le("미리보기 글")),
        ])
        let r = try HwpText.read(write("c.hwp", bytes))
        #expect(r.previewOnly)
        #expect(r.note?.contains("본문을 읽지 못해") == true, "\(r.note ?? "")")
    }

    @Test func cyclicFatChainIsRejected() {
        let bytes = cfb([("FileHeader", fileHeader(0)), ("BodyText/Section0", para("x"))], corruptFat: true)
        expectFail("손상") { _ = try HwpText.read(write("cyc.hwp", bytes)) }
    }

    @Test func truncatedFileIsRejected() {
        let bytes = cfb([("FileHeader", fileHeader(1)), ("BodyText/Section0", deflateRaw(para(String(repeating: "본문 ", count: 2000))))])
        for cut in [600, bytes.count / 2, bytes.count - 100] {
            do { _ = try HwpText.read(write("t\(cut).hwp", Array(bytes.prefix(cut)))) } catch is EtlException {} catch { Issue.record("\(error)") }
        }
        expectFail("") { _ = try HwpText.read(write("tiny.hwp", Array(bytes.prefix(100)))) }
    }

    @Test func garbageAndOldFormatsAreRejected() {
        let old = Array("HWP Document File V3.00 \u{1A}\u{01}\u{02}\u{03}\u{04}\u{05}".utf8) + [UInt8](repeating: 0, count: 200)
        expectFail("3.0") { _ = try HwpText.read(write("old.hwp", old)) }
        expectFail("형식") { _ = try HwpText.read(write("x.hwp", [UInt8](repeating: 3, count: 1000))) }
        expectFail("FileHeader") { _ = try HwpText.read(write("n.hwp", cfb([("Other", [UInt8](repeating: 0, count: 10))]))) }
    }

    @Test func recordWalkerStopsOnBadSizes() {
        let ok = para("정상")
        let bad = le32(67 | (0xFFF << 20)) + le32(Int(Int32.max))
        #expect(HwpText.sectionText(ok + bad) == "정상\n")
    }

    // MARK: hwpx

    private func hwpx(_ sections: [(String, String)]) -> URL {
        let zip = ZipWriter.make([("mimetype", Data("application/hwp+zip".utf8))] + sections.map { ($0.0, Data($0.1.utf8)) })
        return t.write("a.hwpx", zip, under: t.base)
    }

    @Test func readsHwpxSectionsInOrderWithTables() throws {
        let s0 = """
            <?xml version="1.0"?><hs:sec xmlns:hs="h" xmlns:hp="p">
            <hp:p><hp:run><hp:t>기말고사 &amp; 과제</hp:t></hp:run></hp:p>
            <hp:p><hp:run><hp:t>날짜<hp:tab/>12/15</hp:t><hp:t>&#xAC00;</hp:t></hp:run></hp:p>
            <hp:p><hp:run><hp:tbl><hp:tr>
              <hp:tc><hp:subList><hp:p><hp:run><hp:t>중간</hp:t></hp:run></hp:p></hp:subList></hp:tc>
              <hp:tc><hp:subList><hp:p><hp:run><hp:t>30%</hp:t></hp:run></hp:p></hp:subList></hp:tc>
            </hp:tr></hp:tbl></hp:run></hp:p>
            <hp:p><hp:run><hp:fieldBegin><hp:parameters><hp:stringParam name="x">숨은값</hp:stringParam></hp:parameters></hp:fieldBegin><hp:t>보임</hp:t><hp:t/></hp:run></hp:p>
            <hp:p><hp:run><hp:equation><hp:script>a over b</hp:script></hp:equation></hp:run></hp:p>
            </hs:sec>
            """
        let s1 = #"<hs:sec xmlns:hp="p"><hp:p><hp:run><hp:t>두 번째<hp:lineBreak/>구역</hp:t></hp:run></hp:p></hs:sec>"#
        let s10 = #"<hs:sec xmlns:hp="p"><hp:p><hp:run><hp:t>열 번째</hp:t></hp:run></hp:p></hs:sec>"#
        let r = try HwpText.read(hwpx([("Contents/section10.xml", s10), ("Contents/section1.xml", s1), ("Contents/section0.xml", s0)]))
        #expect(!r.previewOnly)
        let text = r.text
        #expect(text.hasPrefix("기말고사 & 과제\n날짜\t12/15가"), "\(text)")
        #expect(text.contains("중간 | 30%"))
        #expect(text.contains("보임"))
        #expect(!text.contains("숨은값"))
        #expect(!text.contains("a over b"))
        let a = try #require(text.range(of: "두 번째\n구역")), b = try #require(text.range(of: "열 번째"))
        #expect(a.lowerBound < b.lowerBound)
    }

    @Test func hwpxWithoutSectionsOrBrokenZipFails() throws {
        expectFail("본문") { _ = try HwpText.read(hwpx([("Contents/header.xml", "<x/>")])) }
        let f = hwpx([("Contents/section0.xml", "<hp:p><hp:t>a</hp:t></hp:p>")])
        let broken = t.write("b.hwpx", try Data(contentsOf: f).prefix(40), under: t.base)
        expectFail("") { _ = try HwpText.read(broken) }
    }
}

// MARK: - ZIP · docx · pptx

@Suite struct ZipOfficeTests {
    let t = TempWorkspace()

    @Test func zipRoundTripStoredAndDeflated() throws {
        let big = Data(String(repeating: "강의계획서 ", count: 5000).utf8)
        for deflate in [true, false] {
            let z = try ZipArchive(data: ZipWriter.make([("a.txt", Data("hello".utf8)), ("dir/b.txt", big), ("empty", Data())], deflate: deflate))
            #expect(z.entries.map(\.name) == ["a.txt", "dir/b.txt", "empty"])
            #expect(try z.read(z.entry("a.txt")!, maxBytes: 100) == Data("hello".utf8))
            #expect(try z.read(z.entry("dir/b.txt")!, maxBytes: big.count) == big)
            #expect(try z.read(z.entry("empty")!, maxBytes: 0) == Data())
            #expect(throws: ZipArchive.Failure.tooLarge) { try z.read(z.entry("dir/b.txt")!, maxBytes: big.count - 1) }
        }
        #expect(CRC32.checksum(Data("123456789".utf8)) == 0xCBF4_3926)
    }

    @Test func zipRejectsGarbageAndTruncation() {
        #expect(throws: ZipArchive.Failure.self) { try ZipArchive(data: Data(repeating: 7, count: 100)) }
        #expect(throws: ZipArchive.Failure.self) { try ZipArchive(data: Data()) }
        let z = ZipWriter.make([("a.txt", Data(String(repeating: "x", count: 1000).utf8))])
        #expect(throws: ZipArchive.Failure.self) { try ZipArchive(data: z.prefix(z.count - 10)) }
    }

    @Test func rawDeflateStopsAtCapAndRejectsGarbage() throws {
        let src = Data(String(repeating: "abc", count: 100_000).utf8)
        let packed = RawDeflate.deflate(src)
        #expect(try RawDeflate.inflate(packed, cap: .max) == src)
        let head = try RawDeflate.inflate(packed, cap: 1000)
        #expect(head.count >= 1000 && head.count < src.count)
        #expect(src.prefix(head.count) == head)
        #expect(throws: RawDeflate.Corrupt.self) { try RawDeflate.inflate(Data([0xFF, 0xFF, 0xFF, 0x13, 0x55]), cap: .max) }
    }

    private func docx(_ body: String) -> String {
        #"<?xml version="1.0"?><w:document xmlns:w="w"><w:body>\#(body)</w:body></w:document>"#
    }

    @Test func docxTextKeepsParagraphsTabsAndTables() throws {
        let xml = docx("""
            <w:p><w:r><w:t>기말고사</w:t><w:tab/><w:t>12월 15일</w:t></w:r></w:p>\
            <w:p><w:r><w:t>첫 줄</w:t><w:br/><w:t>둘째 줄 &amp; &lt;표&gt;</w:t></w:r></w:p>\
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>중간</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>30%</w:t></w:r></w:p></w:tc></w:tr>\
            <w:tr><w:tc><w:p><w:r><w:t>기말</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>40%</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            """)
        let f = t.write("1-plan.docx", ZipWriter.make([("[Content_Types].xml", Data("<x/>".utf8)), ("word/document.xml", Data(xml.utf8))]))
        let text = try EtlFiles.ooxmlText(f)
        #expect(text == "기말고사\t12월 15일\n첫 줄\n둘째 줄 & <표>\n중간 | 30%\n기말 | 40%", "\(text)")
    }

    @Test func pptxSlidesInNumericOrder() throws {
        func slide(_ s: String) -> Data { Data(#"<p:sld xmlns:a="a"><a:p><a:r><a:t>\#(s)</a:t></a:r></a:p></p:sld>"#.utf8) }
        let f = t.write("2-deck.pptx", ZipWriter.make([("ppt/slides/slide10.xml", slide("열")), ("ppt/slides/slide2.xml", slide("둘")),
                                                      ("ppt/slides/_rels/slide2.xml.rels", Data("<r/>".utf8)), ("ppt/slides/slide1.xml", slide("하나"))]))
        #expect(try EtlFiles.ooxmlText(f) == "--- 슬라이드 1 ---\n하나\n\n--- 슬라이드 2 ---\n둘\n\n--- 슬라이드 10 ---\n열")
    }

    @Test func ooxmlFailures() {
        let notZip = t.write("x.docx", "plain text")
        expectFail("형식") { _ = try EtlFiles.ooxmlText(notZip) }
        let noBody = t.write("y.docx", ZipWriter.make([("word/styles.xml", Data("<x/>".utf8))]))
        expectFail("형식") { _ = try EtlFiles.ooxmlText(noBody) }
        // 압축 폭탄: 풀면 상한(8MB)을 넘는다.
        let bomb = t.write("z.docx", ZipWriter.make([("word/document.xml", Data(repeating: 0x20, count: EtlFiles.MAX_XML_BYTES + 10))]))
        expectFail("너무 커서") { _ = try EtlFiles.ooxmlText(bomb) }
    }
}

// MARK: - EtlFiles

@Suite struct EtlFilesTests {
    let t = TempWorkspace()

    private func rejects(_ rel: String, sourceLocation: SourceLocation = #_sourceLocation) {
        do {
            _ = try EtlFiles.resolveInside(t.ws, rel)
            Issue.record("accepted: \(rel)", sourceLocation: sourceLocation)
        } catch is EtlException {} catch { Issue.record("\(error)", sourceLocation: sourceLocation) }
    }

    @Test func storedNameIsStableAndKeepsExtension() {
        #expect(EtlFiles.storedName(123, "과제.pdf") == "123-과제.pdf")
        #expect(EtlFiles.storedName(123, "a.pdf") == EtlFiles.storedName(123, "a.pdf"))
        let n = EtlFiles.storedName(9_415_086, String(repeating: "가", count: 180) + ".pdf")
        #expect(n.utf8.count <= 200 && n.hasSuffix(".pdf") && n.hasPrefix("9415086-") && !n.contains("\u{FFFD}"))
        #expect(EtlFiles.storedName(1, String(repeating: "x", count: 400)).utf8.count <= 200)
    }

    @Test func syllabusAttachmentsArePrivateReads() throws {
        let pdf = t.write("syllabus/syllabus-2026-430.309A-001-en-plan.pdf", "x")
        #expect(EtlFiles.isPrivateRead(t.ws, pdf))
        #expect(EtlFiles.isPrivateRead(t.ws, try EtlFiles.resolveInside(t.ws, "etl-files/syllabus/../syllabus/\(pdf.lastPathComponent)")))
        // 이전 빌드가 etl-files/ 바로 아래에 둔 계획서 첨부
        #expect(EtlFiles.isPrivateRead(t.ws, t.write("syllabus-old.pdf", "x")))
        // 일반 eTL 파일, 사용자 첨부, 이름만 비슷한 하위 폴더는 미리보기 대상
        #expect(!EtlFiles.isPrivateRead(t.ws, t.write("123-syllabus.pdf", "x")))
        #expect(!EtlFiles.isPrivateRead(t.ws, t.write("uploads/syllabus-mine.pdf", "x")))
        #expect(!EtlFiles.isPrivateRead(t.ws, t.write("syllabusX/a.pdf", "x")))
    }

    @Test func resolveInsideAcceptsFilesUnderEtlFiles() throws {
        let f = t.write("1-a.txt", "hi")
        let canon = EtlFiles.canonicalPath(f.path)
        #expect(try EtlFiles.resolveInside(t.ws, "etl-files/1-a.txt").path == canon)
        #expect(try EtlFiles.resolveInside(t.ws, "./etl-files/1-a.txt").path == canon)
        #expect(try EtlFiles.resolveInside(t.ws, "  etl-files/1-a.txt ").path == canon)
        #expect(EtlFiles.relPath(t.ws, f) == "etl-files/1-a.txt")
    }

    @Test func resolveInsideRejectsEscapes() {
        t.write("secret.txt", "x", under: t.base)
        t.write("ok.txt", "x")
        rejects("")
        rejects("secret.txt")
        rejects("/etc/passwd")
        rejects(t.ws.appendingPathComponent("ok.txt").path) // 절대 경로
        rejects("etl-files/../secret.txt")
        rejects("etl-files/../../x")
        rejects("etl-files")          // 폴더 자체
        rejects("etl-files/nope.txt") // 없는 파일
        rejects("etl-files/ok.txt\u{0}.pdf")
        expectFail("파일이 없습니다: etl-files/nope.txt") { _ = try EtlFiles.resolveInside(t.ws, "etl-files/nope.txt") }
        expectFail("etl-files/ 안의 파일만") { _ = try EtlFiles.resolveInside(t.ws, "etl-files/../secret.txt") }
        expectFail("허용되지 않은 경로") { _ = try EtlFiles.resolveInside(t.ws, "/etc/passwd") }
    }

    @Test func resolveInsideRejectsSymlinkEscape() throws {
        let outside = t.write("outside.txt", "secret", under: t.base)
        try FileManager.default.createSymbolicLink(at: t.ws.appendingPathComponent("link.txt"), withDestinationURL: outside)
        rejects("etl-files/link.txt")
        let outDir = t.base.appendingPathComponent("outdir", isDirectory: true)
        t.write("s.txt", "secret", under: outDir)
        try FileManager.default.createSymbolicLink(at: t.ws.appendingPathComponent("dirlink"), withDestinationURL: outDir)
        rejects("etl-files/dirlink/s.txt")
    }

    @Test func parsePages() {
        #expect(EtlFiles.parsePages("1-3", 10) == [1, 2, 3])
        #expect(EtlFiles.parsePages("5,1,3,3", 10) == [1, 3, 5])
        #expect(EtlFiles.parsePages("2-20", 20) == [2, 3, 4, 5, 6, 7]) // 최대 6쪽
        #expect(EtlFiles.parsePages("9-50", 10) == [9, 10])             // 범위 밖은 버림
        #expect(EtlFiles.parsePages(nil, 3) == [1, 2, 3])               // 비우면 앞쪽부터
        #expect(EtlFiles.parsePages("abc", 2) == [1, 2])                // 해석 불가 → 기본
        #expect(EtlFiles.parsePages("", 99) == [1, 2, 3, 4, 5, 6])
        #expect(EtlFiles.parsePages("4-3", 10) == [3, 4])               // 뒤집힌 범위
        #expect(EtlFiles.parsePages("1", 0) == [])
        #expect(EtlFiles.parsePages("0-1", 5) == [1])                   // 0쪽은 없음
        #expect(EtlFiles.parsePages(" 2 - 3 , 99999999999", 5) == [2, 3])
    }

    @Test func fileKinds() {
        #expect(EtlFiles.isTextLike("a.PY"))
        #expect(EtlFiles.isTextLike("notes.md"))
        #expect(!EtlFiles.isTextLike("a.pdf"))
        #expect(!EtlFiles.isTextLike("noext"))
        #expect(EtlFiles.isPdf("Report.PDF"))
        #expect(!EtlFiles.isPdf("a.docx"))
        #expect(EtlFiles.isOoxml("a.PPTX") && EtlFiles.isOoxml("b.docx") && !EtlFiles.isOoxml("c.doc"))
        #expect(HwpText.isHwp("계획서.HWP") && HwpText.isHwp("a.hwpx") && !HwpText.isHwp("a.hw"))
    }

    @Test func pruneDeletesOnlyOldFilesIncludingSubdirs() throws {
        let old = t.write("old.pdf", "x")
        let fresh = t.write("fresh.pdf", "x")
        let oldImg = t.write("render/1-p1.png", "x")
        let past = Date(timeIntervalSince1970: 1)
        try FileManager.default.setAttributes([.modificationDate: past], ofItemAtPath: old.path)
        try FileManager.default.setAttributes([.modificationDate: past], ofItemAtPath: oldImg.path)
        #expect(EtlFiles.prune(t.ws, nowMs: Date().epochMilli) == 2)
        #expect(!FileManager.default.fileExists(atPath: old.path))
        #expect(!FileManager.default.fileExists(atPath: oldImg.path))
        #expect(FileManager.default.fileExists(atPath: fresh.path))
    }

    @Test func readTextPrefixCountsUtf16() throws {
        let f = t.write("a.txt", "가나다라마")
        #expect(try EtlFiles.readTextPrefix(f, limit: 3) == ("가나다", true))
        #expect(try EtlFiles.readTextPrefix(f, limit: 5) == ("가나다라마", false))
        #expect(try EtlFiles.readTextPrefix(f, limit: 50) == ("가나다라마", false))
    }
}

// MARK: - PDF

/// CoreGraphics로 만든 PDF(쪽마다 크기와 글).
func makePdf(_ url: URL, _ pages: [(CGSize, String)]) {
    var first = CGRect(origin: .zero, size: pages.first?.0 ?? CGSize(width: 100, height: 100))
    let ctx = CGContext(url as CFURL, mediaBox: &first, nil)!
    for (size, text) in pages {
        var box = CGRect(origin: .zero, size: size)
        let info = [kCGPDFContextMediaBox as String: Data(bytes: &box, count: MemoryLayout<CGRect>.size)] as CFDictionary
        ctx.beginPDFPage(info)
        ctx.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
        ctx.fill(CGRect(x: 10, y: 10, width: 20, height: 20))
        let font = CTFontCreateWithName("Helvetica" as CFString, 18, nil)
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: [kCTFontAttributeName as NSAttributedString.Key: font]))
        ctx.textPosition = CGPoint(x: 10, y: size.height / 2)
        CTLineDraw(line, ctx)
        ctx.endPDFPage()
    }
    ctx.closePDF()
}

func pngSize(_ url: URL) -> (Int, Int)? {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil), let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
    return (img.width, img.height)
}

@Suite struct PdfPagesTests {
    let t = TempWorkspace()

    @Test func countsAndRendersPagesWithAndroidSizes() throws {
        let f = t.ws.appendingPathComponent("7-doc.pdf")
        makePdf(f, [(CGSize(width: 200, height: 100), "Page one"), (CGSize(width: 612, height: 792), "Page two"), (CGSize(width: 100, height: 5000), "tall")])
        #expect(try PdfPages.pageCount(f) == 3)
        let out = t.ws.appendingPathComponent("render")
        let r = try PdfPages.render(f, [3, 1, 2, 9, 0], out, "7")
        #expect(r.map(\.page) == [3, 1, 2])
        // 작은 쪽: max(200*2, 600)=600 → 600×300. 보통 쪽: 1224×1584. 긴 쪽: 600×30000 → 4M 픽셀로 줄임.
        #expect(r[1].width == 600 && r[1].height == 300)
        #expect(r[2].width == 1224 && r[2].height == 1584)
        #expect(r[0].width * r[0].height <= 4_000_000 && r[0].height > r[0].width * 40)
        for x in r {
            #expect(x.file.lastPathComponent == "7-p\(x.page).png")
            let size = try #require(pngSize(x.file))
            #expect(size.0 == x.width && size.1 == x.height)
        }
    }

    @Test func textExtractionUsesPdfKit() throws {
        let f = t.ws.appendingPathComponent("doc.pdf")
        makePdf(f, [(CGSize(width: 300, height: 200), "Midterm exam"), (CGSize(width: 300, height: 200), "Final project")])
        let text = try PdfPages.text(f)
        #expect(text.contains("--- 1쪽 ---"), "\(text)")
        #expect(text.contains("Midterm exam"))
        #expect(text.contains("Final project"))
        #expect(!(try PdfPages.text(f, pages: [2])).contains("Midterm"))
    }

    @Test func badPdfsFailWithFixedMessages() {
        let junk = t.write("junk.pdf", "not a pdf at all")
        expectFail("PDF를 열 수 없습니다") { _ = try PdfPages.pageCount(junk) }
        expectFail("PDF 파일을 열 수 없습니다") { _ = try PdfPages.pageCount(t.ws.appendingPathComponent("missing.pdf")) }
    }
}

// MARK: - WorkspaceImage

private final class FakeCodec: WorkspaceImage.Codec {
    let size: WorkspaceImage.Bounds
    let encoded: WorkspaceImage.Encoded
    var boundsCalls = 0
    var sanitizeCalls = 0
    var sample: Int?
    static let cleanJpeg = Data([0xFF, 0xD8, 0xFF, 0xD9])

    init(_ size: WorkspaceImage.Bounds = .init(200, 100, "image/png"), encoded: WorkspaceImage.Encoded = .init(cleanJpeg, 200, 100)) {
        self.size = size
        self.encoded = encoded
    }
    func bounds(_ bytes: Data) throws -> WorkspaceImage.Bounds { boundsCalls += 1; return size }
    func sanitize(_ bytes: Data, sample: Int) throws -> WorkspaceImage.Encoded { sanitizeCalls += 1; self.sample = sample; return encoded }
}

/// CoreGraphics로 만든 단색 이미지(PNG/JPEG). [properties]로 EXIF·GPS·방향을 넣을 수 있다.
func makeImage(_ w: Int, _ h: Int, type: UTType = .png, properties: [CFString: Any] = [:]) -> Data {
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.setFillColor(CGColor(red: 0.2, green: 0.4, blue: 0.8, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    let out = NSMutableData()
    let dest = CGImageDestinationCreateWithData(out as CFMutableData, type.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, ctx.makeImage()!, properties as CFDictionary)
    _ = CGImageDestinationFinalize(dest)
    return out as Data
}

@Suite struct WorkspaceImageTests {
    let t = TempWorkspace()

    private func image(_ name: String = "page.png", _ bytes: Data = Data("untrusted container".utf8)) -> String {
        t.write(name, bytes)
        return "etl-files/\(name)"
    }

    @Test func cannotReadAbsoluteTraversalPrefixOrSymlinkEscapes() throws {
        let outside = t.base.appendingPathComponent("outside", isDirectory: true)
        let secret = t.write("secret.png", "secret-test-value", under: outside)
        try FileManager.default.createSymbolicLink(at: t.ws.appendingPathComponent("outside.png"), withDestinationURL: secret)
        t.write("etl-files-other/page.png", "secret-test-value", under: t.base)
        for path in [secret.path, "../\(secret.lastPathComponent)", "etl-files-other/page.png", "etl-files/outside.png", "etl-files/../auth.json"] {
            let codec = FakeCodec()
            let result = WorkspaceImage.view(t.ws, path, codec: codec)
            #expect(result.isError)
            #expect(result.images.isEmpty)
            #expect(codec.boundsCalls == 0)
            #expect(!result.text.contains("secret-test-value"))
            #expect(!result.text.contains(path))
        }
    }

    @Test func symbolicCourseworkRootIsRejected() throws {
        let t2 = TempWorkspace(makeWorkspace: false)
        let outside = t2.base.appendingPathComponent("outside", isDirectory: true)
        t2.write("secret.png", "secret-test-value", under: outside)
        try FileManager.default.createSymbolicLink(at: t2.ws, withDestinationURL: outside)
        let codec = FakeCodec()
        #expect(WorkspaceImage.view(t2.ws, "etl-files/secret.png", codec: codec).isError)
        #expect(codec.boundsCalls == 0)
    }

    @Test func oversizedFileIsRejectedBeforeDecode() throws {
        let path = image()
        let h = try FileHandle(forWritingTo: t.ws.appendingPathComponent("page.png"))
        try h.truncate(atOffset: UInt64(WorkspaceImage.MAX_FILE_BYTES + 1))
        try h.close()
        let codec = FakeCodec()
        let r = WorkspaceImage.view(t.ws, path, codec: codec)
        #expect(r.isError && r.text.contains("20MB"))
        #expect(codec.boundsCalls == 0)
    }

    @Test func invalidFormatAndPixelBombAreRejectedBeforeDecode() {
        let path = image()
        for bounds in [WorkspaceImage.Bounds(-1, -1, nil), .init(1, 1, "text/plain"), .init(Int(Int32.max), Int(Int32.max), "image/png"), .init(16_000, 16_000, "image/png")] {
            let codec = FakeCodec(bounds)
            #expect(WorkspaceImage.view(t.ws, path, codec: codec).isError)
            #expect(codec.sanitizeCalls == 0)
        }
    }

    @Test func sampleBoundsDecodedPixelsAndLongestEdge() throws {
        for (width, height) in [(8000, 6000), (16_000, 1), (1400, 2000), (1, 1)] {
            let sample = try WorkspaceImage.sample(.init(width, height, "image/jpeg"))
            let w = Int64((width + sample - 1) / sample), h = Int64((height + sample - 1) / sample)
            #expect(w * h <= WorkspaceImage.MAX_DECODE_PIXELS)
            #expect(max(w, h) <= Int64(WorkspaceImage.MAX_DECODE_EDGE))
        }
    }

    @Test func responseUsesSanitizedPixelsAndNeverOriginalContainerBytes() {
        let path = image("page.png", Data("Exif\u{0}GPS-TEST-METADATA".utf8))
        let codec = FakeCodec()
        let result = WorkspaceImage.view(t.ws, path, codec: codec)
        #expect(!result.isError)
        #expect(codec.sanitizeCalls == 1)
        #expect(result.images.count == 1)
        #expect(result.images.first?.mimeType == "image/jpeg")
        #expect(result.images.first?.data == FakeCodec.cleanJpeg)
        #expect(!result.text.contains("GPS-TEST-METADATA"))
        #expect(result.text == "이미지 200×100. 원본 메타데이터를 제외하고 다시 인코딩했어요.")
    }

    @Test func oversizedCodecOutputAndUnexpectedDecodedDimensionsAreRejected() {
        let path = image()
        for encoded in [WorkspaceImage.Encoded(Data(count: WorkspaceImage.MAX_OUTPUT_BYTES + 1), 200, 100),
                        WorkspaceImage.Encoded(FakeCodec.cleanJpeg, Int(Int32.max), Int(Int32.max))] {
            #expect(WorkspaceImage.view(t.ws, path, codec: FakeCodec(encoded: encoded)).isError)
        }
    }

    private struct ThrowingCodec: WorkspaceImage.Codec {
        struct Boom: Error, CustomStringConvertible { var description: String { "test-token-secret" } }
        func bounds(_ bytes: Data) throws -> WorkspaceImage.Bounds { throw Boom() }
        func sanitize(_ bytes: Data, sample: Int) throws -> WorkspaceImage.Encoded { throw Boom() }
    }

    @Test func decoderExceptionsNeverExposeSourceOrSecretDetails() {
        let result = WorkspaceImage.view(t.ws, image(), codec: ThrowingCodec())
        #expect(result.isError)
        #expect(!result.text.contains("test-token-secret"))
        #expect(!(result.detail ?? "").contains("test-token-secret"))
    }

    // MARK: ImageIO 실제 해독

    @Test func realCodecReencodesPngAsJpegAndStripsMetadata() throws {
        let gps: [CFString: Any] = [kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 37.46, kCGImagePropertyGPSLatitudeRef: "N"],
                                    kCGImagePropertyExifDictionary: [kCGImagePropertyExifUserComment: "GPS-TEST-METADATA"]]
        let path = image("photo.jpg", makeImage(320, 200, type: .jpeg, properties: gps))
        let raw = try Data(contentsOf: t.ws.appendingPathComponent("photo.jpg"))
        let srcProps = CGImageSourceCopyPropertiesAtIndex(CGImageSourceCreateWithData(raw as CFData, nil)!, 0, nil) as? [CFString: Any]
        #expect(srcProps?[kCGImagePropertyGPSDictionary] != nil) // 픽스처에는 GPS가 있다
        let r = WorkspaceImage.view(t.ws, path)
        #expect(!r.isError, "\(r.text)")
        let out = try #require(r.images.first?.data)
        #expect(out.prefix(2) == Data([0xFF, 0xD8]))
        let props = CGImageSourceCopyPropertiesAtIndex(CGImageSourceCreateWithData(out as CFData, nil)!, 0, nil) as? [CFString: Any]
        #expect(props?[kCGImagePropertyGPSDictionary] == nil)
        #expect((props?[kCGImagePropertyExifDictionary] as? [CFString: Any])?[kCGImagePropertyExifUserComment] == nil)
        #expect(props?[kCGImagePropertyPixelWidth] as? Int == 320)
        #expect(r.text == "이미지 320×200. 원본 메타데이터를 제외하고 다시 인코딩했어요.")

        let png = image("p.png", makeImage(64, 32))
        #expect(WorkspaceImage.view(t.ws, png).text.hasPrefix("이미지 64×32."))
    }

    @Test func realCodecAppliesExifOrientationAndDownsamples() throws {
        let rotated = image("r.jpg", makeImage(200, 100, type: .jpeg, properties: [kCGImagePropertyOrientation: 6]))
        #expect(WorkspaceImage.view(t.ws, rotated).text.hasPrefix("이미지 100×200."))
        let big = image("big.png", makeImage(4000, 1000))
        let r = WorkspaceImage.view(t.ws, big)
        #expect(!r.isError)
        #expect(r.text.hasPrefix("이미지 2000×500."), "\(r.text)") // sample 2
        let fake = image("fake.png", Data("not an image".utf8))
        #expect(WorkspaceImage.view(t.ws, fake).text.contains("PNG·JPEG·WebP"))
    }
}
