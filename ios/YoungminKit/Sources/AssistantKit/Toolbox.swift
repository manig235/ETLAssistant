import Foundation
import ChatKit
import ETLKit
import NotifyKit

/// 비서 도구 상자(Kotlin `EtlTools` + `EtlService`). **eTL에 대해서는 전부 읽기 전용**(GET만 보낸다).
/// 쓰기 도구(`my_deadline_*`, `timetable_change_*`)는 폰 안의 데이터만 바꾸고, 화면 승인([WriteApprovalGate])을 받은 뒤에만 쓰며,
/// 바꿀 때마다 채팅에 한 줄로 알린다([EtlEvents.notice]).
///
/// 앱 연결: `extension Toolbox: AssistantToolbox {}` — `instructions`, `toolDefinitions`, `run(name:arguments:)`가 앱 계약과 같다.
public final class Toolbox: @unchecked Sendable {
    let tokens: @Sendable () -> String?
    let makeClient: @Sendable (String) throws -> EtlClient
    let approvals: WriteApprovalGate
    let myDeadlines: MyDeadlineStore
    let timetableStore: TimetableStore
    let timetableChanges: TimetableChangeStore
    /// 비서용 작업 폴더(`etl-files`). 결과의 `workspace_path`는 `etl-files/<이름>`이다.
    let workspace: URL
    /// 사용자용 사본 폴더(nil이면 `EtlDownloads.userDirectory` = `Documents/etl`).
    let userDirectory: URL?
    /// 결과의 `saved_to`에 쓰는 사용자 사본 위치 표시(안드로이드 `Download/etl/<이름>` 자리).
    let userFolderLabel: String
    let clock: @Sendable () -> Date

    static let MAX_GET_CHARS = 200_000
    static let DETAIL_LIMIT = 40

    /// - Parameters:
    ///   - tokens: eTL 토큰(기본: Keychain `TokenStore`). 결과에서 이 값은 `***`로 가린다.
    ///   - makeClient: 토큰으로 eTL 클라이언트를 만든다(테스트에서 가짜 세션을 넣는다).
    ///   - approvals: 쓰기 승인 게이트(기본: 앱 전체 [AiWriteApproval.gate]).
    public init(tokens: @escaping @Sendable () -> String? = { TokenStore().load() },
                makeClient: @escaping @Sendable (String) throws -> EtlClient = { try EtlClient($0) },
                approvals: WriteApprovalGate = AiWriteApproval.gate,
                myDeadlines: MyDeadlineStore = MyDeadlineStore(),
                timetableStore: TimetableStore = TimetableStore(),
                timetableChanges: TimetableChangeStore = TimetableChangeStore(),
                workspace: URL = EtlDownloads.workspaceDirectory,
                userDirectory: URL? = nil,
                userFolderLabel: String = "파일 앱 › 영민이 › etl",
                clock: @escaping @Sendable () -> Date = { Date() }) {
        self.tokens = tokens
        self.makeClient = makeClient
        self.approvals = approvals
        self.myDeadlines = myDeadlines
        self.timetableStore = timetableStore
        self.timetableChanges = timetableChanges
        self.workspace = workspace
        self.userDirectory = userDirectory
        self.userFolderLabel = userFolderLabel
        self.clock = clock
    }

    // MARK: 앱 계약

    /// AGENTS.md(말투 지침 제외: 앱의 ChatController가 `ChatSettings.toneInstructions()`를 붙인다).
    private var profileInstructions: String {
        StudyDataProfile.isDemo ? "\n현재 가상 eTL 테스트 모드다. 과목·과제·공지·파일은 가상 데이터이며 실제 학교 정보라고 말하지 않는다. 기존 도구로 조회하고 답한다. 내 마감·시험·휴강 변경은 가상 저장소에만 적용되지만 동일하게 매번 사용자의 변경 승인을 받는다. 실제 캘린더 연결은 이 모드에서 사용하지 않는다.\n" : ""
    }
    public var instructions: String { AssistantInstructions.agents + profileInstructions }

    /// 말투 지침까지 붙인 지침.
    public func instructions(casualTone: Bool) -> String { AssistantInstructions.text(casualTone: casualTone) + profileInstructions }

    /// 등록한 도구 정의(이름·설명·스키마는 안드로이드와 같다).
    public var specs: [ToolSpec] { ToolSpecs.all }

    /// Responses API 함수 도구 정의.
    public var toolDefinitions: [FunctionTool] { ToolSpecs.all.map(\.functionTool) }

    /// 앱 `AssistantToolbox.run(name:arguments:)`와 같은 모양.
    public func run(name: String, arguments: String) async -> ToolOutput { await run(name: name, argumentsJSON: arguments) }

    /// 도구 하나를 실행한다. 결과는 언제나 JSON 글이고(오류는 `{"error": …}`), 모델에게 주기 전에
    /// 서명 링크·비밀 키·eTL 토큰을 가린다([EtlLogic.modelText]).
    public func run(name: String, argumentsJSON: String) async -> ToolOutput {
        let out = await runUnlogged(name: name, argumentsJSON: argumentsJSON)
        // 진단 로그: 도구 이름·인자 키·오류 문구만(인자 값·결과 본문은 남기지 않는다).
        let keys = ((try? JSONObject(argumentsJSON.isEmpty ? "{}" : argumentsJSON))?.keys().sorted() ?? []).joined(separator: ",")
        let error = out.text.hasPrefix("{\"error\"") ? String(out.text.prefix(300)) : "ok"
        DiagLog.logger.notice("tool \(name, privacy: .public)(\(keys, privacy: .public)) -> \(error, privacy: .public)")
        return out
    }

    private func runUnlogged(name: String, argumentsJSON: String) async -> ToolOutput {
        let secrets = [tokens()].compactMap { $0 }
        func fail(_ message: String) -> ToolOutput {
            ToolOutput(text: EtlLogic.modelText(JSONObject().put("error", message), secrets))
        }
        guard ToolSpecs.names.contains(name) else {
            return fail(ToolSpecs.deferred.contains(name) ? "이 도구는 아직 iOS 앱에 없습니다: \(name)" : "알 수 없는 도구입니다: \(name)")
        }
        let raw = argumentsJSON.kt
        let args: JSONObject
        if raw.isEmpty {
            args = JSONObject()
        } else {
            do { args = try JSONObject(raw) } catch { return fail("도구 인자를 JSON 객체로 읽을 수 없습니다") }
        }
        do {
            let out = try await call(name, args)
            // 이미지를 돌려주는 도구(etl_view_image)는 ToolOutput을 바로 돌려준다. 글은 똑같이 가린다.
            if var direct = out as? ToolOutput {
                direct.text = EtlLogic.modelText(direct.text, secrets)
                return direct
            }
            return ToolOutput(text: EtlLogic.modelText(out, secrets))
        } catch let e as EtlException {
            return fail(e.message)
        } catch let e as CatalogError {
            return fail(e.message)
        } catch is CancellationError {
            return fail("요청이 취소되었습니다")
        } catch {
            return fail((error as? LocalizedError)?.errorDescription ?? "도구 실행 중 오류가 났습니다")
        }
    }

    private func call(_ name: String, _ args: JSONObject) async throws -> Any {
        switch name {
        case "etl_digest": return try await digest(client(), min(max(args.optInt("days", 7), 1), 60), min(max(args.optInt("ann_days", 1), 1), 30))
        case "etl_courses": return Queries.coursesResult(try await client().getAll(CourseAssignments.COURSES_PATH))
        case "etl_assignments":
            if args.has("status") { return try await statusAssignments(client(), args) }
            return try await assignments(client(), AssignmentQuery.Range.parse(args.optString("range", "this_week")), args.optInt("days", 7),
                                         args.optBoolean("include_submitted", true), try optId(args, "course_id"))
        case "etl_announcements": return try await announcements(client(), args.optInt("days", Queries.DEFAULT_ANNOUNCEMENT_DAYS), try optId(args, "course_id"))
        case "etl_grades": return try await grades(client(), try optId(args, "course_id"))
        case "etl_files": return try await files(client(), try id(args, "course_id"))
        case "etl_assignment": return try await assignment(client(), try id(args, "course_id"), try id(args, "assignment_id"))
        case "etl_search_files": return try await searchFiles(client(), try id(args, "course_id"), args.optString("term", ""))
        case "etl_download": return try await downloadFile(client(), args.optString("file_id_or_url", ""), args.optBoolean("save_to_phone", true), args)
        case "etl_verify_submission": return try await verify(client(), try id(args, "course_id"), try id(args, "assignment_id"))
        case "my_deadlines": return myDeadlinesList(args)
        case "my_deadline_add": return try await myDeadlineAdd(args)
        case "my_deadline_update": return try await myDeadlineUpdate(args)
        case "my_deadline_delete": return try await myDeadlineDelete(args)
        case "timetable_change_add": return try await timetableChangeAdd(args)
        case "timetable_change_delete": return try await timetableChangeDelete(args)
        case "etl_timetable": return try await timetable(client(), args)
        case "etl_syllabus":
            let attachment: Int? = (args.has("attachment") && !args.isNull("attachment")) ? args.optInt("attachment", -1) : nil
            return try await syllabus(client(), try id(args, "course_id"), attachment)
        case "etl_get": return try await getRaw(client(), args.optString("path", ""), args.optBoolean("all", false))
        default:
            // 2단계 도구 그룹(각자 파일). 맡지 않은 이름이면 nil.
            if let r = try await callFiles(name, args) { return r }
            if let r = try await callBoardPdf(name, args) { return r }
            if let r = try await callCalendar(name, args) { return r }
            throw EtlException("알 수 없는 도구입니다: \(name)")
        }
    }

    // MARK: 공통

    func client() throws -> EtlClient {
        guard let token = tokens() else { throw EtlException("eTL 토큰이 저장되어 있지 않습니다. 앱에서 토큰을 먼저 저장하세요.") }
        return try makeClient(token)
    }

    /// 인자의 양의 정수(숫자 또는 숫자 문자열).
    func id(_ args: JSONObject, _ key: String) throws -> Int64 {
        let n: Int64?
        switch args.opt(key) {
        case let v as Int64: n = v
        case let v as Int: n = Int64(v)
        case let v as Double: n = v.isFinite && abs(v) < 9.2e18 ? Int64(v) : nil
        case let v as String: n = Int64(v.kt)
        default: n = nil
        }
        guard let n, n > 0 else { throw EtlException("\(key) 가 필요합니다(양의 정수)") }
        return n
    }

    func optId(_ args: JSONObject, _ key: String) throws -> Int64? {
        (!args.has(key) || args.isNull(key)) ? nil : try id(args, key)
    }

    /// 문자열 인자(없거나 JSON null이면 nil).
    func str(_ args: JSONObject, _ key: String) -> String? {
        (!args.has(key) || args.isNull(key)) ? nil : args.optString(key)
    }

    func approve<T>(_ operation: String, _ details: String, destructive: Bool = false, _ action: () throws -> T) async throws -> T {
        try await AiWriteApproval.execute(operation, details, destructive: destructive, on: approvals, action)
    }

    /// 작업 폴더 기준 상대 경로(`etl-files/…`, Kotlin `EtlFiles.relPath`).
    func relPath(_ file: URL) -> String {
        let base = workspace.standardizedFileURL.resolvingSymlinksInPath().path
        let full = file.standardizedFileURL.resolvingSymlinksInPath().path
        let rest = full.hasPrefix(base + "/") ? String(full.dropFirst(base.count + 1)) : file.lastPathComponent
        return "\(EtlDownloads.DIR)/\(rest)"
    }
}

// MARK: - Kotlin 문자열 보조

extension String {
    /// Kotlin `trim()`.
    var kt: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Kotlin `take(n)`(UTF-16 단위, 대리쌍은 자르지 않는다).
    func take16(_ n: Int) -> String {
        if utf16.count <= n { return self }
        var out = ""
        var used = 0
        for ch in self {
            let c = ch.utf16.count
            if used + c > n { break }
            out.append(ch)
            used += c
        }
        return out
    }

    /// Kotlin `ifEmpty { null }`.
    var nilIfEmpty: String? { isEmpty ? nil : self }

    /// Kotlin `isBlank()`.
    var isBlankK: Bool { kt.isEmpty }

    /// `java.net.URLEncoder.encode(s, "UTF-8")`(공백은 `+`).
    var formEncoded: String {
        var allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-*_")
        allowed.insert(" ")
        return (addingPercentEncoding(withAllowedCharacters: allowed) ?? "").replacingOccurrences(of: " ", with: "+")
    }
}

/// Kotlin `sortedWith`(안정 정렬).
func stableSorted<T>(_ list: [T], _ less: (T, T) -> Bool) -> [T] {
    list.enumerated().sorted { a, b in
        if less(a.element, b.element) { return true }
        if less(b.element, a.element) { return false }
        return a.offset < b.offset
    }.map(\.element)
}
