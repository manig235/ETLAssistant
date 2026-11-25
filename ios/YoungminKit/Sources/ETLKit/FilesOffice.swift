import Foundation

// Kotlin `EtlFiles.ooxmlText`/`xmlText`: docx·pptx의 글만 뽑는다(ZIP은 [ZipArchive]로 직접 읽는다).
extension EtlFiles {
    /// 압축을 풀었을 때 읽을 XML 한 부분의 상한(압축 폭탄 방지).
    static let MAX_XML_BYTES = 8 << 20

    private static let SLIDE = KRegex("^ppt/slides/slide(\\d+)\\.xml$")

    /// docx(word/document.xml), pptx(ppt/slides/slideN.xml, 쪽 순서)의 글만 뽑는다. 문단·표 칸·줄바꿈은 줄로, 슬라이드는
    /// `--- 슬라이드 N ---`으로 나눈다. 서식·그림은 버린다. 형식이 아니면 [EtlException].
    public static func ooxmlText(_ f: URL) throws -> String {
        let isPptx = ext(f.lastPathComponent) == "pptx"
        var parts: [Int: String] = [:]
        let zip: ZipArchive
        do { zip = try ZipArchive(contentsOf: f) } catch { throw EtlException("문서 형식을 읽을 수 없습니다") }
        for e in zip.entries {
            let key: Int?
            if isPptx {
                key = SLIDE.find(e.name).flatMap { Int32($0.groupValues[1]).map(Int.init) }
            } else {
                key = e.name == "word/document.xml" ? 0 : nil
            }
            guard let key else { continue }
            let data: Data
            do {
                data = try zip.read(e, maxBytes: MAX_XML_BYTES)
            } catch ZipArchive.Failure.tooLarge {
                throw EtlException("문서가 너무 커서 읽을 수 없습니다")
            } catch {
                throw EtlException("문서 형식을 읽을 수 없습니다")
            }
            parts[key] = xmlText(String(decoding: data, as: UTF8.self))
        }
        if parts.isEmpty { throw EtlException("문서 형식을 읽을 수 없습니다") }
        let sorted = parts.sorted { $0.key < $1.key }
        return isPptx ? sorted.map { "--- 슬라이드 \($0.key) ---\n\($0.value)" }.joined(separator: "\n\n") : sorted[0].value
    }

    private static let W_TAB = KRegex("<w:tab\\b[^>]*/>")
    private static let BR = KRegex("<(?:w:br|w:cr|a:br)\\b[^>]*/>")
    private static let CELL_END = KRegex("</w:p>\\s*</w:tc>")
    private static let ROW_END = KRegex("</w:tr>")
    private static let PARA_END = KRegex("</(?:w:p|a:p)>")
    private static let ANY_TAG = KRegex("<[^>]+>")
    private static let TRAILING_SPACE = KRegex("[ \\t]+\\n")
    private static let MANY_NEWLINES = KRegex("\\n{3,}")

    /// WordprocessingML/DrawingML의 글: 문단(`</w:p>`, `</a:p>`)·줄바꿈·탭·표 칸을 살리고 태그를 지운다.
    public static func xmlText(_ xml: String) -> String {
        var s = xml
        s = W_TAB.replace(s, "\t")
        s = BR.replace(s, "\n")
        // 표: 칸 안의 마지막 문단은 " | "로, 행 끝은 줄로(칸 안의 다른 문단은 아래에서 줄이 된다).
        s = CELL_END.replace(s, " | ")
        s = ROW_END.replace(s, "\n")
        s = PARA_END.replace(s, "\n")
        s = ANY_TAG.replace(s, "")
        s = s.replacingOccurrences(of: " | \n", with: "\n")
        s = s.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
        s = TRAILING_SPACE.replace(s, "\n")
        s = MANY_NEWLINES.replace(s, "\n\n")
        return s.kTrim
    }
}
