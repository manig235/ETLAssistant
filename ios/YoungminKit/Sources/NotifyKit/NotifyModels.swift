import Foundation
import ETLKit

/*
 * Phase 3 공통 계약(Kotlin `NotifyModels.kt`). 알림·브리핑·내 일정이 함께 쓰는 타입.
 */

/// 과제 하나(현재 학기, 게시된 것만). [due]가 nil이면 마감일 없음.
public struct AssignmentItem: Hashable, Sendable, Codable {
    public var courseId: Int64
    public var courseName: String
    public var assignmentId: Int64
    public var name: String
    public var due: Date?
    /// 온라인 제출 과제인가(`submission_types`에 none/on_paper/not_graded 외의 값이 있음).
    public var online: Bool
    public var points: Double?
    public var url: String?
    /// `submission.workflow_state`가 submitted/graded/pending_review이거나 `submitted_at`이 있음.
    public var submitted: Bool
    /// 미제출이면서 `submission.missing`이거나, 마감이 지난 온라인 과제.
    public var missing: Bool
    /// 채점되었으면 점수. 성적 등록 알림에 쓴다.
    public var score: Double?
    /// 제출 시각(`submission.submitted_at`). 제출 직후 점검([AssignmentWatch])이 새 제출·다시 제출을 알아보는 데 쓴다.
    public var submittedAt: Date?
    /// 제출 정보만으로 한 점검 결과(제출했을 때만). 받아 둔 옛 저장본에서 왔으면 nil일 수 있다.
    public var check: SubmitCheck?

    public init(_ courseId: Int64, _ courseName: String, _ assignmentId: Int64, _ name: String, _ due: Date?, _ online: Bool,
                _ points: Double?, _ url: String?, _ submitted: Bool, _ missing: Bool, _ score: Double? = nil,
                _ submittedAt: Date? = nil, _ check: SubmitCheck? = nil) {
        self.courseId = courseId
        self.courseName = courseName
        self.assignmentId = assignmentId
        self.name = name
        self.due = due
        self.online = online
        self.points = points
        self.url = url
        self.submitted = submitted
        self.missing = missing
        self.score = score
        self.submittedAt = submittedAt
        self.check = check
    }

    public init(courseId: Int64, courseName: String, assignmentId: Int64, name: String, due: Date?, online: Bool, points: Double?,
                url: String?, submitted: Bool, missing: Bool, score: Double? = nil, submittedAt: Date? = nil, check: SubmitCheck? = nil) {
        self.init(courseId, courseName, assignmentId, name, due, online, points, url, submitted, missing, score, submittedAt, check)
    }
}

public struct AnnouncementItem: Hashable, Sendable, Codable {
    public var id: Int64
    public var courseId: Int64
    public var courseName: String
    public var title: String
    public var postedAt: Date
    public var url: String?
    /// HTML을 텍스트로 바꾼 본문. 신뢰할 수 없는 데이터이므로 알림에는 그대로 쓰되 지시로 해석하지 않는다.
    public var text: String

    public init(_ id: Int64, _ courseId: Int64, _ courseName: String, _ title: String, _ postedAt: Date, _ url: String?, _ text: String) {
        self.id = id
        self.courseId = courseId
        self.courseName = courseName
        self.title = title
        self.postedAt = postedAt
        self.url = url
        self.text = text
    }
}

/// 설정 화면 목록의 과목 하나(Kotlin `CourseFilter.kt`의 `CourseRef`).
public struct CourseRef: Hashable, Sendable, Codable {
    public var id: Int64
    public var name: String
    public init(_ id: Int64, _ name: String) {
        self.id = id
        self.name = name
    }
}

/// 한 번 조회한 결과.
public struct Snapshot: Hashable, Sendable {
    public var takenAt: Date
    public var assignments: [AssignmentItem]
    public var announcements: [AnnouncementItem]
    /// 일부 강의 조회 실패 등(사용자에게 보일 수 있는 짧은 문구). 토큰이나 서명 URL은 절대 넣지 않는다.
    public var errors: [String]
    /// 이번에 조회한 현재 학기 강의(새 강의자료 확인에 쓴다). 조회하지 않았으면 비어 있다.
    public var courses: [CourseRef]

    public init(_ takenAt: Date, _ assignments: [AssignmentItem], _ announcements: [AnnouncementItem], _ errors: [String] = [], _ courses: [CourseRef] = []) {
        self.takenAt = takenAt
        self.assignments = assignments
        self.announcements = announcements
        self.errors = errors
        self.courses = courses
    }

    public init(_ takenAt: Date, _ assignments: [AssignmentItem], _ announcements: [AnnouncementItem], errors: [String], courses: [CourseRef] = []) {
        self.init(takenAt, assignments, announcements, errors, courses)
    }
}

/// 알림 채널. 안드로이드는 중요도를 바꿀 때 ID를 바꿨다(`_v2`). iOS에서는 [id]를 알림 범주(`categoryIdentifier`)·
/// 스레드 ID로, [importance]를 `interruptionLevel`로 쓰면 된다.
public enum Channel: String, CaseIterable, Hashable, Sendable, Codable {
    case DEADLINE = "deadline"
    case ANNOUNCEMENT = "announcement_v2"
    case BRIEF = "brief"
    /// 새 과제·마감 변경·성적 등록.
    case CHANGE = "change_v2"
    /// 수업 시작 전 알림.
    case CLASS = "class"
    /// 새 버전 알림(안드로이드 전용, iOS에서는 쓰지 않는다).
    case UPDATE = "update_v2"
    /// 제출 직후 점검에서 문제를 찾았을 때. 팝업(HIGH).
    case SUBMIT = "submit_check"
    /// 정상 제출 확인. 조용히(LOW). 같은 제출은 [SUBMIT]과 같은 키(`submit:…`)라 둘 중 하나만 온다.
    case SUBMIT_OK = "submit_ok"
    /// 새 강의자료. 팝업 없이 알림창에만(DEFAULT).
    case NEW_FILES = "new_files"

    public var id: String { rawValue }
}

/// 띄울 알림 하나.
/// [firedKeys]는 이 알림을 **실제로 띄운 뒤** [NotifyState.fired]에 기록할 키들이다(중복 방지).
public struct Notice: Hashable, Sendable {
    public var channel: Channel
    public var title: String
    public var text: String
    /// 펼쳤을 때 보이는 긴 본문. nil이면 [text]만.
    public var bigText: String?
    /// 본문 터치와 "eTL에서 열기"가 여는 주소(https://myetl.snu.ac.kr/… 만). nil이면 앱으로 이동.
    public var url: String?
    /// 같은 키의 알림은 갱신(교체)된다. iOS에서는 알림 요청 식별자로 쓴다.
    public var tag: String
    public var firedKeys: [String]
    /// 채팅 입력창에 채워 둘 문구(보내지는 않는다).
    public var prefill: String?
    public var prefillAction: String?
    /// 시험·과제 공지의 "내 일정에 등록" 초안([ScheduleDraft.toJson]).
    public var draft: String?
    /// 내 마감·시험 알림: [url]이 없을 때 본문을 누르면 "내 일정"을 연다.
    public var agenda: Bool
    /// 내 일정에서 열 항목([MyDeadlines.itemId], 반복 회차 포함).
    public var mineItemId: Int64?

    public init(_ channel: Channel, _ title: String, _ text: String, bigText: String? = nil, url: String? = nil, tag: String,
                firedKeys: [String] = [], prefill: String? = nil, prefillAction: String? = nil, draft: String? = nil,
                agenda: Bool = false, mineItemId: Int64? = nil) {
        self.channel = channel
        self.title = title
        self.text = text
        self.bigText = bigText
        self.url = url
        self.tag = tag
        self.firedKeys = firedKeys
        self.prefill = prefill
        self.prefillAction = prefillAction
        self.draft = draft
        self.agenda = agenda
        self.mineItemId = mineItemId
    }

    public init(channel: Channel, title: String, text: String, bigText: String? = nil, url: String? = nil, tag: String,
                firedKeys: [String] = [], prefill: String? = nil, prefillAction: String? = nil, draft: String? = nil,
                agenda: Bool = false, mineItemId: Int64? = nil) {
        self.init(channel, title, text, bigText: bigText, url: url, tag: tag, firedKeys: firedKeys, prefill: prefill,
                  prefillAction: prefillAction, draft: draft, agenda: agenda, mineItemId: mineItemId)
    }
}

/// 알림 중복 방지와 마지막 확인 상태. 저장은 [NotifyStateStore].
/// 키 형식: `deadline-m:{courseId}:{assignmentId}:{windowMinutes}`, `ann:{announcementId}`.
/// (예전 형식 `deadline:{courseId}:{assignmentId}:{windowHours}`도 읽을 때는 알린 것으로 인정한다.)
public struct NotifyState: Hashable, Sendable {
    /// 키 → 띄운 시각(epoch millis). 30일이 지난 항목은 정리한다.
    public var fired: [String: Int64]
    /// 공지 기준선을 잡았는가. false인 첫 확인에서는 기존 공지를 알리지 않고 기록만 한다.
    public var announcementBaselineDone: Bool
    public var lastCheckAt: Int64
    /// 마지막 확인 결과 한 줄(설정 화면에 보인다).
    public var lastResult: String
    public var lastBriefAt: Int64

    public init(fired: [String: Int64] = [:], announcementBaselineDone: Bool = false, lastCheckAt: Int64 = 0, lastResult: String = "", lastBriefAt: Int64 = 0) {
        self.fired = fired
        self.announcementBaselineDone = announcementBaselineDone
        self.lastCheckAt = lastCheckAt
        self.lastResult = lastResult
        self.lastBriefAt = lastBriefAt
    }

    public func toJson() -> JSONObject {
        let f = JSONObject()
        for k in fired.keys.sorted() { f.put(k, fired[k]!) }
        return JSONObject()
            .put("fired", f)
            .put("announcementBaselineDone", announcementBaselineDone)
            .put("lastCheckAt", lastCheckAt)
            .put("lastResult", lastResult)
            .put("lastBriefAt", lastBriefAt)
    }

    /// [olderThanMillis]보다 오래된 기록을 지운다.
    public mutating func prune(_ nowMillis: Int64, olderThanMillis: Int64 = 30 * 24 * 3600 * 1000) {
        fired = fired.filter { nowMillis - $0.value <= olderThanMillis }
    }

    public static func fromJson(_ s: String?) -> NotifyState {
        guard let s, !s.isEmpty, let o = try? JSONObject(s) else { return NotifyState() }
        var fired: [String: Int64] = [:]
        if let f = o.optJSONObject("fired") { for k in f.keys() { fired[k] = f.optLong(k) } }
        return NotifyState(
            fired: fired,
            announcementBaselineDone: o.optBoolean("announcementBaselineDone", false),
            lastCheckAt: o.optLong("lastCheckAt", 0),
            lastResult: o.optString("lastResult", ""),
            lastBriefAt: o.optLong("lastBriefAt", 0)
        )
    }
}

/// 알림 설정. 저장은 [NotifySettingsStore].
public struct NotifySettings: Hashable, Sendable {
    public var deadlineAlerts: Bool
    public var announcementAlerts: Bool
    /// 마감 몇 분 전에 알릴지(내림차순). 기본은 3일, 24시간, 3시간, 10분 전.
    public var deadlineWindowsMinutes: [Int]
    public var briefEnabled: Bool
    /// 브리핑을 보낼 시각(하루 중 분, 0..1439). 최대 2개. 기기의 현재 시간대 기준.
    public var briefMinutesOfDay: [Int]
    /// 방해 금지 시간에는 마감·공지 알림을 미룬다(브리핑은 정해진 시각에 보낸다).
    public var quietEnabled: Bool
    public var quietStartMinutes: Int
    public var quietEndMinutes: Int
    /// 잠금화면에서는 내용을 숨기고 "영민이 알림"만 보인다.
    public var hideContentOnLockScreen: Bool
    /// 새 과제, 마감 변경, 성적 등록 알림.
    public var changeAlerts: Bool
    /// 수업 시작 전 알림.
    public var classAlerts: Bool
    public var classLeadMinutes: Int
    /// 제출 직후 점검.
    public var submitAlerts: Bool
    /// 정상 제출도 조용히 알린다. [submitAlerts]가 켜져 있을 때만 쓴다.
    public var submitOkAlerts: Bool
    /// 새 강의자료 알림(4시간마다).
    public var newFileAlerts: Bool
    /// 끝 시각이 있는 시험이 끝나면 "수고했어요" 알림.
    public var examDoneAlerts: Bool
    /// 채팅 화면을 열 때 등록 안 한 시험·과제 공지를 하단 시트로 보여 준다.
    public var pendingSheet: Bool

    public init(deadlineAlerts: Bool = true, announcementAlerts: Bool = true, deadlineWindowsMinutes: [Int] = NotifySettings.DEFAULT_DEADLINE_WINDOWS,
                briefEnabled: Bool = false, briefMinutesOfDay: [Int] = [8 * 60], quietEnabled: Bool = false,
                quietStartMinutes: Int = 23 * 60, quietEndMinutes: Int = 7 * 60, hideContentOnLockScreen: Bool = true,
                changeAlerts: Bool = true, classAlerts: Bool = false, classLeadMinutes: Int = 10, submitAlerts: Bool = true,
                submitOkAlerts: Bool = true, newFileAlerts: Bool = true, examDoneAlerts: Bool = true, pendingSheet: Bool = true) {
        self.deadlineAlerts = deadlineAlerts
        self.announcementAlerts = announcementAlerts
        self.deadlineWindowsMinutes = deadlineWindowsMinutes
        self.briefEnabled = briefEnabled
        self.briefMinutesOfDay = briefMinutesOfDay
        self.quietEnabled = quietEnabled
        self.quietStartMinutes = quietStartMinutes
        self.quietEndMinutes = quietEndMinutes
        self.hideContentOnLockScreen = hideContentOnLockScreen
        self.changeAlerts = changeAlerts
        self.classAlerts = classAlerts
        self.classLeadMinutes = classLeadMinutes
        self.submitAlerts = submitAlerts
        self.submitOkAlerts = submitOkAlerts
        self.newFileAlerts = newFileAlerts
        self.examDoneAlerts = examDoneAlerts
        self.pendingSheet = pendingSheet
    }

    /// 어떤 알림이든 켜져 있는가.
    public var anyEnabled: Bool { deadlineAlerts || announcementAlerts || briefEnabled || changeAlerts || classAlerts || submitAlerts || newFileAlerts }
    /// 주기 확인이 필요한가.
    public var needsPeriodicCheck: Bool { deadlineAlerts || announcementAlerts || changeAlerts || classAlerts || submitAlerts || newFileAlerts }
    /// 제시간 알람(마감·수업 시각에 맞춘 추가 확인)이 필요한가.
    public var needsTimedCheck: Bool { deadlineAlerts || classAlerts }

    /// 마감·공지 주기 확인 간격(시간).
    public static let CHECK_INTERVAL_HOURS = 1

    private static let HOUR = 60
    private static let DAY = 24 * HOUR
    public static let DEFAULT_DEADLINE_WINDOWS: [Int] = [3 * DAY, DAY, 3 * HOUR, 10]
    /// 추가 대화상자의 빠른 버튼(분).
    public static let DEADLINE_WINDOW_PRESETS: [Int] = [10, 30, HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY, 3 * DAY, 7 * DAY]
    public static let MIN_WINDOW_MINUTES = 10
    public static let MAX_WINDOW_MINUTES = 14 * DAY
    public static let MAX_WINDOWS = 12

    /// 저장·표시용으로 정리한다: 범위 안의 값만, 중복 제거, 내림차순, 최대 [MAX_WINDOWS]개. 비면 기본값.
    public static func normalizeWindows(_ minutes: [Int]) -> [Int] {
        let r = Array(distinct(minutes.filter { (MIN_WINDOW_MINUTES...MAX_WINDOW_MINUTES).contains($0) }).sorted(by: >).prefix(MAX_WINDOWS))
        return r.isEmpty ? DEFAULT_DEADLINE_WINDOWS : r
    }

    /// 시점 이름: 60분 미만 `N분`, 48시간 이상이고 하루 단위면 `N일`, 시간 단위면 `N시간`, 그 외 `N시간 M분`.
    public static func windowLabel(_ minutes: Int) -> String {
        if minutes < HOUR { return "\(minutes)분" }
        if minutes % HOUR != 0 { return "\(minutes / HOUR)시간 \(minutes % HOUR)분" }
        if minutes >= 2 * DAY && minutes % DAY == 0 { return "\(minutes / DAY)일" }
        return "\(minutes / HOUR)시간"
    }
}

/// 방해 금지 시간 판정(자정을 넘는 구간 포함). [minuteOfDay]는 0..1439.
public enum Quiet {
    public static func isQuiet(_ minuteOfDay: Int, _ s: NotifySettings) -> Bool {
        if !s.quietEnabled { return false }
        let a = s.quietStartMinutes
        let b = s.quietEndMinutes
        if a == b { return false }
        if a < b { return minuteOfDay >= a && minuteOfDay < b }
        return minuteOfDay >= a || minuteOfDay < b
    }
}

public extension JSONArray {
    /// Kotlin `JSONArray.toIntList()`.
    func toIntList() -> [Int] { (0..<length()).map { optInt($0) } }
}
