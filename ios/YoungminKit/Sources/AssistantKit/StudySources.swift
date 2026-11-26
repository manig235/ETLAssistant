import CryptoKit
import ETLKit
import Foundation

// 시험 자료 만들기용 공개 기출 자료(Kotlin `study/StudySources.kt`). 허용 목록 사이트, 주소 검사, 검색 주소, 응답 해석.
// 네트워크는 `StudyClient.swift`, 도구는 `ToolboxStudy.swift`.

/// 공개 기출 자료를 찾는 사이트 허용 목록(한 표). 사이트를 늘릴 때는 [ALL]에 한 줄을 더하고, 검색 응답 해석이 다르면
/// [StudyLogic]에 그 사이트의 갈래를 둔다. 여기 없는 호스트에는 요청하지 않는다(HTTPS만, 리다이렉트도 이 목록 안만).
///
/// MIT OpenCourseWare(2026-11-27 확인):
/// - 검색: MIT Learn 공개 API `GET https://api.learn.mit.edu/api/v1/learning_resources_search/`(과목),
///   `GET …/content_file_search/`(과목 안 자료). 로그인·쿠키 없이 JSON. OCW 사이트의 검색 화면도 이 API를 쓴다.
/// - 자료: 과목 페이지·자료 페이지(HTML)와 PDF 모두 `https://ocw.mit.edu/courses/…`에서 바로 온다(CDN 리다이렉트 없음).
public enum StudySites {
    public struct Site: Sendable, Hashable {
        public let id: String
        public let name: String
        /// 검색 API 호스트(JSON만 받는다).
        public let searchHosts: Set<String>
        /// `study_fetch`로 페이지·PDF를 받을 수 있는 호스트.
        public let fetchHosts: Set<String>
        public let license: String
        public let licenseUrl: String
        /// 출처 표기 형식. {instructor}, {course}, {term}은 비서가 채운다.
        public let attribution: String
    }

    public static let MIT_OCW = Site(
        id: "mit_ocw",
        name: "MIT OpenCourseWare",
        searchHosts: ["api.learn.mit.edu"],
        fetchHosts: ["ocw.mit.edu"],
        license: "CC BY-NC-SA 4.0",
        licenseUrl: "https://creativecommons.org/licenses/by-nc-sa/4.0/",
        attribution: "{instructor}. {course}. {term}. Massachusetts Institute of Technology: MIT OpenCourseWare, https://ocw.mit.edu. License: Creative Commons BY-NC-SA 4.0."
    )

    public static let ALL: [Site] = [MIT_OCW]

    public static func byId(_ id: String?) -> Site? {
        let key = (id?.kt.lowercased()).flatMap { $0.isEmpty ? nil : $0 } ?? MIT_OCW.id
        return ALL.first { $0.id == key }
    }

    public static func forFetchHost(_ host: String?) -> Site? {
        guard let h = host?.lowercased() else { return nil }
        return ALL.first { $0.fetchHosts.contains(h) }
    }

    public static func isSearchHost(_ host: String?) -> Bool {
        guard let h = host?.lowercased() else { return false }
        return ALL.contains { $0.searchHosts.contains(h) }
    }
}

/// 순수 로직(네트워크 없음): 주소 검사, 검색 주소, 응답 해석, 크기 상한. 오류는 [EtlException](비서에게 그대로 간다).
public enum StudyLogic {
    public static let MAX_PDF_BYTES: Int64 = 30 << 20
    public static let MAX_HTML_BYTES = 2 << 20
    public static let MAX_JSON_BYTES = 2 << 20
    public static let MAX_REDIRECTS = 3
    public static let CONNECT_TIMEOUT_MS = 8_000
    public static let READ_TIMEOUT_MS = 15_000
    public static let PDF_READ_TIMEOUT_MS = 30_000
    /// 앱 전체에서 동시에 보내는 공개 자료 요청 수.
    public static let MAX_CONCURRENT = 2
    public static let DEFAULT_TEXT_CHARS = 12_000
    public static let MAX_TEXT_CHARS = 40_000
    public static let MAX_LINKS = 60
    public static let DEFAULT_LIMIT = 8
    public static let MAX_LIMIT = 20
    public static let MAX_FILES = 50
    /// 개인 정보 없는 일반 User-Agent(앱·기기·계정 정보를 넣지 않는다).
    public static let USER_AGENT = "Mozilla/5.0 (compatible; StudyMaterialFetcher/1.0)"

    public static let DISABLED_MESSAGE = "공개 기출 자료 찾기가 꺼져 있어요. 사용자에게 앱의 설정 → 대화 → '공개 기출 자료 찾기'를 켜 달라고 안내하세요."

    /// `materials` 인자 → OCW 자료 종류(content_feature_type). 빈 목록은 거르지 않음.
    public static let MATERIAL_KEYS = ["exams_and_problem_sets", "exams", "problem_sets", "lecture_notes", "all"]
    public static let MATERIALS: [String: [String]] = [
        "exams_and_problem_sets": ["Exams", "Exam Solutions", "Supplemental Exam Materials", "Problem Sets", "Problem Set Solutions"],
        "exams": ["Exams", "Exam Solutions", "Supplemental Exam Materials"],
        "problem_sets": ["Problem Sets", "Problem Set Solutions", "Problem-solving Notes"],
        "lecture_notes": ["Lecture Notes"],
        "all": [],
    ]

    /// 과목 검색에서 거를 때 쓰는 과목 특성(문제가 있는 과목).
    private static let COURSE_FILTERS: [String: [String]] = [
        "exams_and_problem_sets": ["Exams", "Problem Sets"],
        "exams": ["Exams"],
        "problem_sets": ["Problem Sets"],
        "lecture_notes": ["Lecture Notes"],
        "all": [],
    ]

    /// 결과에 보여 줄 과목 특성(시험 자료 만들기에 쓰는 것만).
    private static let SHOWN_FEATURES = ["Exams", "Exam Solutions", "Supplemental Exam Materials", "Problem Sets", "Problem Set Solutions",
                                         "Problem-solving Notes", "Lecture Notes", "Lecture Videos"]

    public static func materials(_ arg: String?) throws -> String {
        let m = (arg?.kt.lowercased()).flatMap { $0.isEmpty ? nil : $0 } ?? "exams_and_problem_sets"
        guard MATERIALS[m] != nil else { throw EtlException("materials는 \(MATERIAL_KEYS.joined(separator: ", ")) 중 하나여야 해요") }
        return m
    }

    public static func site(_ arg: String?) throws -> StudySites.Site {
        guard let s = StudySites.byId(arg) else {
            throw EtlException("site는 \(StudySites.ALL.map(\.id).joined(separator: ", ")) 중 하나여야 해요")
        }
        return s
    }

    /// Kotlin `URLEncoder.encode(s, "UTF-8").replace("+", "%20")`.
    static func enc(_ s: String) -> String { s.formEncoded.replacingOccurrences(of: "+", with: "%20") }

    /// 과목 검색 주소(MIT Learn 공개 API, GET).
    public static func courseSearchUrl(_ site: StudySites.Site, _ query: String, _ materials: String, _ limit: Int) throws -> String {
        precondition(site == StudySites.MIT_OCW)
        let q = try cleanQuery(query)
        let n = min(max(limit, 1), MAX_LIMIT)
        let features = (COURSE_FILTERS[materials] ?? []).map { "&course_feature=\(enc($0))" }.joined()
        return "https://api.learn.mit.edu/api/v1/learning_resources_search/?q=\(enc(q))&offered_by=ocw&resource_type=course\(features)&limit=\(n)"
    }

    /// 과목 하나 안의 자료 검색 주소. [courseId]는 과목 검색 결과의 `course_id`.
    public static func fileSearchUrl(_ site: StudySites.Site, _ courseId: Int64, _ query: String?, _ materials: String) throws -> String {
        precondition(site == StudySites.MIT_OCW)
        if courseId <= 0 { throw EtlException("course_id는 study_search 결과의 양의 정수여야 해요") }
        let q = try query.map { try cleanQuery($0, allowEmpty: true) } ?? ""
        let types = (MATERIALS[materials] ?? []).map { "&content_feature_type=\(enc($0))" }.joined()
        return "https://api.learn.mit.edu/api/v1/content_file_search/?resource_id=\(courseId)" +
            (q.isEmpty ? "" : "&q=\(enc(q))") + "\(types)&limit=\(MAX_FILES)"
    }

    public static func cleanQuery(_ query: String, allowEmpty: Bool = false) throws -> String {
        var q = KRegex("[\\u0000-\\u001f]").replace(query, " ")
        q = KRegex("\\s+").replace(q, " ").kt.take16(120)
        if q.isEmpty && !allowEmpty { throw EtlException("query(찾을 주제, 영어)가 필요해요") }
        return q
    }

    /// 영어가 아닌 검색어로 결과가 없을 때의 안내.
    public static func nonEnglishHint(_ query: String) -> String? {
        query.unicodeScalars.contains { $0.value > 0x7f }
            ? "MIT OCW는 영어 자료라 한국어 검색어로는 거의 찾지 못해요. 주제를 영어로 바꿔 다시 찾으세요(예: 회로이론 → circuit analysis)." : nil
    }

    /// 받을 주소를 검사해 정리한 주소를 돌려준다. 허용 목록 호스트만, HTTPS만(허용 호스트의 http는 https로 올린다),
    /// 사용자 정보·다른 포트는 거부하고 `#` 뒤는 버린다.
    public static func checkFetchUrl(_ raw: String) throws -> String {
        let text = raw.kt
        if text.isEmpty { throw EtlException("url이 필요해요(study_search 결과의 url)") }
        guard let c = URLComponents(string: text), let rawHost = c.host, !rawHost.isEmpty else { throw EtlException("주소를 읽을 수 없어요") }
        let host = rawHost.lowercased()
        if StudySites.forFetchHost(host) == nil {
            let hosts = StudySites.ALL.flatMap { $0.fetchHosts.sorted() }.joined(separator: ", ")
            throw EtlException("허용된 공개 자료 사이트(\(hosts))의 주소만 받을 수 있어요")
        }
        let scheme = c.scheme?.lowercased()
        if scheme != "https" && scheme != "http" { throw EtlException("https 주소만 받을 수 있어요") }
        if c.user != nil || c.password != nil { throw EtlException("허용되지 않은 주소예요") }
        if let p = c.port, p != 443 { throw EtlException("허용되지 않은 주소예요") }
        let path = c.percentEncodedPath.isEmpty ? "/" : c.percentEncodedPath
        return "https://\(host)\(path)" + (c.percentEncodedQuery.map { "?\($0)" } ?? "")
    }

    /// 리다이렉트 [location]을 [current] 기준으로 풀고 같은 규칙으로 검사한다(허용 목록 밖이면 중단).
    public static func redirectTarget(_ current: String, _ location: String?) throws -> String {
        guard let loc = location?.kt, !loc.isEmpty else { throw EtlException("이동할 주소가 없는 응답이에요") }
        guard let base = URL(string: current), let abs = URL(string: loc, relativeTo: base)?.absoluteURL else {
            throw EtlException("이동할 주소를 읽을 수 없어요")
        }
        if abs.scheme?.lowercased() != "https" { throw EtlException("https가 아닌 곳으로 이동하려 해서 멈췄어요") }
        do { return try checkFetchUrl(abs.absoluteString) } catch { throw EtlException("허용 목록 밖으로 이동하려 해서 멈췄어요") }
    }

    public enum Kind: Sendable { case pdf, html, other }

    public static func kindOf(_ contentType: String?) -> Kind {
        let t = (contentType ?? "").substringBeforeChar(";").kt.lowercased()
        if t == "application/pdf" { return .pdf }
        if t == "text/html" || t == "application/xhtml+xml" { return .html }
        return .other
    }

    public static func looksLikePdf(_ head: Data) -> Bool { head.count >= 4 && head.prefix(4).elementsEqual(Array("%PDF".utf8)) }

    /// 작업 폴더에 둘 이름: `ocw-{주소 해시 10자}-{정리한 이름}.pdf`. 같은 주소는 같은 이름이라 다시 받아도 쌓이지 않는다.
    public static func storedName(_ site: StudySites.Site, _ url: String, _ name: String?) -> String {
        let hash = SHA256.hash(data: Data(url.utf8)).map { String(format: "%02x", $0) }.joined().prefix(10)
        let prefix = site == StudySites.MIT_OCW ? "ocw" : site.id
        var n = EtlLogic.safeName(name, "file").replacingOccurrences(of: " ", with: "_")
        if !n.lowercased().hasSuffix(".pdf") { n += ".pdf" }
        return "\(prefix)-\(hash)-\(n)"
    }

    // MARK: 응답 해석

    private static func s(_ o: JSONObject?, _ key: String) -> String? {
        guard let o, !o.isNull(key) else { return nil }
        let v = o.optString(key).kt
        return v.isEmpty ? nil : v
    }

    private static func strings(_ a: JSONArray?) -> [String] {
        guard let a else { return [] }
        return (0..<a.length()).compactMap { a.opt($0) as? String }.map(\.kt).filter { !$0.isEmpty }
    }

    private static func names(_ a: JSONArray?, _ key: String) -> [String] {
        guard let a else { return [] }
        return (0..<a.length()).compactMap { s(a.optJSONObject($0), key) }
    }

    private static func term(_ o: JSONObject?) -> String? {
        let y = o?.optLong("year", -1) ?? -1
        let t = [s(o, "semester"), y > 0 ? String(y) : nil].compactMap { $0 }.joined(separator: " ")
        return t.isEmpty ? nil : t
    }

    /// 과목 검색 응답(learning_resources_search) → 비서용 목록.
    public static func parseCourses(_ site: StudySites.Site, _ body: String, _ query: String) throws -> JSONObject {
        let root: JSONObject
        do { root = try JSONObject(body) } catch { throw EtlException("\(site.name) 검색 응답을 해석할 수 없어요") }
        let results = root.optJSONArray("results") ?? JSONArray()
        let out = JSONArray()
        for i in 0..<results.length() {
            guard let r = results.optJSONObject(i) else { continue }
            let id = r.optLong("id", -1)
            if id <= 0 { continue }
            let run = r.optJSONArray("runs")?.optJSONObject(0)
            let numbers = names(r.optJSONObject("course")?.optJSONArray("course_numbers"), "value")
            let features = strings(r.optJSONArray("course_feature"))
            let license: String = (r.has("license_cc") && !r.isNull("license_cc") && !r.optBoolean("license_cc"))
                ? "확인 필요(각 자료 페이지의 라이선스를 보세요)" : site.license
            out.put(JSONObject()
                .put("course_id", id)
                .put("title", s(r, "title") ?? "(제목 없음)")
                .put("course_numbers", JSONArray(numbers))
                .put("term", term(run) ?? JSONObject.NULL)
                .put("level", JSONArray(names(run?.optJSONArray("level"), "name")))
                .put("instructors", JSONArray(Array(names(run?.optJSONArray("instructors"), "full_name").prefix(5))))
                .put("departments", JSONArray(names(r.optJSONArray("departments"), "name")))
                .put("url", s(r, "url") ?? JSONObject.NULL)
                .put("materials", JSONArray(SHOWN_FEATURES.filter { features.contains($0) }))
                .put("has_exam_solutions", features.contains("Exam Solutions"))
                .put("has_problem_set_solutions", features.contains("Problem Set Solutions"))
                .put("description", EtlLogic.htmlToText(s(r, "description"), 400))
                .put("license", license))
        }
        let result = JSONObject()
            .put("site", site.name)
            .put("query", query)
            .put("total", root.optInt("count", out.length()))
            .put("courses", out)
            .put("license", site.license)
            .put("license_url", site.licenseUrl)
            .put("next", "자료 목록은 study_search에 course_id를 넣어 다시 부르고, 받을 자료는 study_fetch(url)로 받으세요")
        if out.length() == 0, let hint = nonEnglishHint(query) { result.put("hint", hint) }
        return result
    }

    /// 과목 안 자료 검색 응답(content_file_search) → 자료 목록.
    public static func parseFiles(_ site: StudySites.Site, _ body: String, _ courseId: Int64) throws -> JSONObject {
        let root: JSONObject
        do { root = try JSONObject(body) } catch { throw EtlException("\(site.name) 자료 목록을 해석할 수 없어요") }
        let results = root.optJSONArray("results") ?? JSONArray()
        let files = JSONArray()
        var course: String?
        var termText: String?
        var numbers: [String] = []
        for i in 0..<results.length() {
            guard let r = results.optJSONObject(i), let url = s(r, "url") else { continue }
            course = course ?? s(r, "run_title")
            termText = termText ?? term(r)
            if numbers.isEmpty { numbers = strings(r.optJSONArray("course_number")) }
            let contentType = s(r, "content_type")
            let format: String
            if contentType == "page" {
                format = "page"
            } else if s(r, "file_type") == "application/pdf" || s(r, "file_extension") == ".pdf" {
                format = "pdf"
            } else if let ext = s(r, "file_extension") {
                format = ext.hasPrefix(".") ? String(ext.dropFirst()) : ext
            } else {
                format = contentType ?? "file"
            }
            files.put(JSONObject()
                .put("title", s(r, "title") ?? s(r, "content_title") ?? "(제목 없음)")
                .put("types", JSONArray(strings(r.optJSONArray("content_feature_type"))))
                .put("format", format)
                .put("fetchable", format == "pdf" || format == "page")
                .put("url", url)
                .put("description", EtlLogic.htmlToText(s(r, "description"), 200)))
        }
        return JSONObject()
            .put("site", site.name)
            .put("course_id", courseId)
            .put("course", course ?? JSONObject.NULL)
            .put("course_numbers", JSONArray(numbers))
            .put("term", termText ?? JSONObject.NULL)
            .put("total", root.optInt("count", files.length()))
            .put("files", files)
            .put("license", site.license)
            .put("license_url", site.licenseUrl)
            .put("next", "fetchable=true인 자료를 study_fetch(url)로 받으세요. 문제와 풀이(Solutions)는 따로 있는 경우가 많아요")
    }

    public struct Link: Hashable, Sendable {
        public let text: String
        public let url: String
        public init(_ text: String, _ url: String) { self.text = text; self.url = url }
    }

    public struct Page: Sendable {
        public var title: String?
        /// 과목명(배너 또는 제목 줄).
        public var course: String?
        /// "6.003 | Fall 2011 | Undergraduate" 같은 과목 번호·학기 줄.
        public var numberTerm: String?
        public var instructors: [String]
        public var licenseUrl: String?
        public var text: String
        public var links: [Link]
        /// 자료 페이지가 내려받기로 거는 파일(같은 사이트 안, 절대 주소).
        public var download: String?
    }

    private static func meta(_ html: String, _ prop: String) -> String? {
        guard let m = KRegex("<meta\\s+(?:property|name)=\"\(KRegex.escape(prop))\"\\s+content=\"([^\"]*)\"", .ignoreCase).find(html) else { return nil }
        let v = EtlLogic.htmlToText(m.groupValues[1], 400)
        return v.isEmpty ? nil : v
    }

    private static func firstText(_ pattern: String, _ html: String, _ limit: Int = 200) -> String? {
        guard let m = KRegex(pattern, [.ignoreCase, .dotMatchesAll]).find(html) else { return nil }
        let v = EtlLogic.htmlToText(m.groupValues[1], limit)
        return v.isEmpty ? nil : v
    }

    /// OCW 페이지(HTML) → 제목·과목·학기·라이선스·본문·링크·내려받기 주소. [pageUrl]은 최종(리다이렉트 뒤) 주소.
    public static func parsePage(_ html: String, _ pageUrl: String, maxChars: Int = DEFAULT_TEXT_CHARS) -> Page {
        let parts = meta(html, "og:title")?.components(separatedBy: " | ").map(\.kt) ?? []
        let title = parts.first
        let banner = firstText("<span class=\"course-number-term-detail\">(.*?)</span>", html)
        let bannerCourse = firstText("<div id=\"course-banner\".*?<h1>(.*?)</h1>", html)
        let course = bannerCourse ?? (parts.count >= 3 ? parts[1] : nil)
        let license = KRegex("\"license\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues[1].replacingOccurrences(of: "\\/", with: "/")
        var instructors: [String] = []
        for m in KRegex("class=\"course-info-instructor[^\"]*\"[^>]*>(.*?)</a>", [.ignoreCase, .dotMatchesAll]).findAll(html) {
            let t = EtlLogic.htmlToText(m.groupValues[1], 100)
            if !t.isEmpty && !instructors.contains(t) && instructors.count < 5 { instructors.append(t) }
        }
        let section = mainSection(html)
        var links: [Link] = []
        var seen = Set<String>()
        for m in KRegex("<a\\b[^>]*?href=\"([^\"]+)\"[^>]*>(.*?)</a>", [.ignoreCase, .dotMatchesAll]).findAll(section) {
            guard links.count < MAX_LINKS, let url = absoluteSameSite(pageUrl, m.groupValues[1]), seen.insert(url).inserted else { continue }
            let text = EtlLogic.htmlToText(m.groupValues[2], 160)
            links.append(Link(text.isEmpty ? url.substringAfterLastChar("/") : text, url))
        }
        let download = KRegex("<a\\s+class=\"download-file\"\\s+href=\"([^\"]+)\"", .ignoreCase).find(html)
            .flatMap { absoluteSameSite(pageUrl, $0.groupValues[1]) }
        let cells = KRegex("</t[dh]\\s*>", .ignoreCase).replace(section, " | ")
        let text = EtlLogic.htmlToText(cells, min(max(maxChars, 500), MAX_TEXT_CHARS))
        return Page(title: title, course: course, numberTerm: banner, instructors: instructors, licenseUrl: license,
                    text: text, links: links, download: download)
    }

    /// 본문 영역: 머리·메뉴·바닥글을 뺀 `main-course-section`(없으면 body) 안, PDF 보기 스크립트 앞까지.
    private static func mainSection(_ html: String) -> String {
        let start = html.range(of: "id=\"main-course-section\"")?.lowerBound
            ?? html.range(of: "<main")?.lowerBound ?? html.range(of: "<body")?.lowerBound ?? html.startIndex
        var end = html.endIndex
        let from = start < html.endIndex ? html.index(after: start) : start
        for marker in ["class=\"pdf-viewer", "</main>", "id=\"course-info-drawer\"", "<footer"] {
            if let r = html.range(of: marker, range: from..<html.endIndex), r.lowerBound < end { end = r.lowerBound }
        }
        return String(html[start..<end])
    }

    /// 페이지 안 링크를 절대 주소로. 허용 목록 밖, 자바스크립트, 페이지 안 앵커는 버린다.
    public static func absoluteSameSite(_ pageUrl: String, _ href: String) -> String? {
        let h = EtlLogic.htmlToText(href, 2000).kt
        let lower = h.lowercased()
        if h.isEmpty || h.hasPrefix("#") || lower.hasPrefix("javascript:") || lower.hasPrefix("mailto:") { return nil }
        guard let base = URL(string: pageUrl),
              let abs = URL(string: h.replacingOccurrences(of: " ", with: "%20"), relativeTo: base)?.absoluteURL else { return nil }
        return try? checkFetchUrl(abs.absoluteString.substringBeforeChar("#"))
    }

    /// 결과의 출처 묶음.
    public static func source(_ site: StudySites.Site, _ pageUrl: String, _ page: Page?, _ fileUrl: String? = nil) -> JSONObject {
        let term = page?.numberTerm?.components(separatedBy: "|").map(\.kt).filter { !$0.isEmpty }
        let license: String
        if let l = page?.licenseUrl, !l.contains("by-nc-sa/4.0") { license = "확인 필요: \(l)" } else { license = site.license }
        func at(_ i: Int) -> Any {
            if let t = term, t.indices.contains(i) { return t[i] }
            return JSONObject.NULL
        }
        return JSONObject()
            .put("site", site.name)
            .put("page_url", pageUrl)
            .put("file_url", fileUrl ?? JSONObject.NULL)
            .put("title", page?.title ?? JSONObject.NULL)
            .put("course", page?.course ?? JSONObject.NULL)
            .put("course_number", at(0))
            .put("term", at(1))
            .put("level", at(2))
            .put("instructors", JSONArray(page?.instructors ?? []))
            .put("license", license)
            .put("license_url", page?.licenseUrl ?? site.licenseUrl)
            .put("attribution", site.attribution)
    }

    /// 사설망·루프백·링크 로컬·멀티캐스트 주소가 아닌가(Kotlin `LocalConnectProxy.isPublicDestination`과 같은 규칙). [bytes]는 4바이트(IPv4) 또는 16바이트(IPv6).
    public static func isPublicAddress(_ bytes: [UInt8]) -> Bool {
        if bytes.count == 16 {
            // IPv4 대응 주소(::ffff:a.b.c.d)는 IPv4로 본다.
            if bytes[0..<10].allSatisfy({ $0 == 0 }) && bytes[10] == 0xff && bytes[11] == 0xff { return isPublicAddress(Array(bytes[12..<16])) }
            if bytes.allSatisfy({ $0 == 0 }) { return false }                                // ::
            if bytes[0..<15].allSatisfy({ $0 == 0 }) && bytes[15] == 1 { return false }    // ::1
            if bytes[0] == 0xfe && (bytes[1] & 0xc0) == 0x80 { return false }               // fe80::/10
            if bytes[0] == 0xfe && (bytes[1] & 0xc0) == 0xc0 { return false }               // fec0::/10 (site-local)
            if (bytes[0] & 0xfe) == 0xfc { return false }                                   // fc00::/7
            if bytes[0] == 0xff { return false }                                            // 멀티캐스트
            return true
        }
        guard bytes.count == 4 else { return false }
        let (a, b) = (bytes[0], bytes[1])
        if a == 0 || a == 10 || a == 127 { return false }
        if a == 169 && b == 254 { return false }
        if a == 172 && (16...31).contains(b) { return false }
        if a == 192 && b == 168 { return false }
        if a >= 224 { return false }
        return true
    }
}

extension String {
    /// Kotlin `substringBefore(c)`(AssistantKit 안).
    func substringBeforeChar(_ c: Character) -> String {
        guard let i = firstIndex(of: c) else { return self }
        return String(self[..<i])
    }

    /// Kotlin `substringAfterLast(c)`(AssistantKit 안).
    func substringAfterLastChar(_ c: Character) -> String {
        guard let i = lastIndex(of: c) else { return self }
        return String(self[index(after: i)...])
    }
}
