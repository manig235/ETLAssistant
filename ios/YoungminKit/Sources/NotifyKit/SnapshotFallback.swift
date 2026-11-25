import Foundation
import ETLKit

/// 백그라운드 확인에서 eTL 조회가 실패했을 때 마지막으로 성공한 과제 목록([SnapshotCache])으로 메운다(순수 함수).
/// 새 공지·새 과제·마감 변경 감지는 실제로 조회됐을 때만 한다(호출자 몫).
public enum SnapshotFallback {
    /// 이보다 오래된 저장본은 쓰지 않는다(3일).
    public static let MAX_AGE_MS: Int64 = 3 * 24 * 3600 * 1000

    /// [cachedAt]: 저장본을 하나라도 썼으면 그 저장 시각, 안 썼으면 nil.
    public struct Result: Hashable, Sendable {
        public var snapshot: Snapshot
        public var cachedAt: Date?
        public init(_ snapshot: Snapshot, _ cachedAt: Date?) {
            self.snapshot = snapshot
            self.cachedAt = cachedAt
        }
    }

    /// [fresh]: 이번 조회 결과(내 마감을 합치기 전). [totalFailure]면 강의 목록부터 받지 못한 것이다.
    /// 부분 실패면 실패한 강의(오류 문구 `강의명: …`)의 과제만 저장본에서 가져온다. 공지 실패(`공지: …`)는 메우지 않는다.
    public static func apply(_ fresh: Snapshot, _ cached: CachedSnapshot?, _ now: Date, totalFailure: Bool) -> Result {
        guard let c = cached, usable(c, now) else { return Result(fresh, nil) }
        let old = c.assignments.filter { $0.courseId != MyDeadlines.COURSE_ID }.map { refresh($0, now) }
        if totalFailure {
            if old.isEmpty { return Result(fresh, nil) }
            var s = fresh
            s.assignments = SnapshotFetcher.sortAssignments(old)
            return Result(s, Date(epochMilli: c.takenAt))
        }
        let failed = failedCourses(fresh.errors)
        if failed.isEmpty { return Result(fresh, nil) }
        let have = Set(fresh.assignments.map(\.courseId))
        let add = old.filter { failed.contains($0.courseName) && !have.contains($0.courseId) }
        if add.isEmpty { return Result(fresh, nil) }
        var s = fresh
        s.assignments = SnapshotFetcher.sortAssignments(fresh.assignments + add)
        return Result(s, Date(epochMilli: c.takenAt))
    }

    /// 연결 끊김으로 실패한 항목이 있는가(`eTL 연결 실패: …`, 시간 초과 포함).
    public static func hasConnectionErrors(_ errors: [String]) -> Bool { errors.contains { $0.contains("eTL 연결 실패") } }

    public static func usable(_ c: CachedSnapshot, _ now: Date) -> Bool {
        let age = now.epochMilli - c.takenAt
        return c.takenAt > 0 && age >= -60_000 && age <= MAX_AGE_MS
    }

    /// 오류 문구에서 실패한 강의명. 공지·토큰 같은 강의 밖 오류는 뺀다.
    public static func failedCourses(_ errors: [String]) -> Set<String> {
        Set(errors.filter { $0.contains(":") && !$0.hasPrefix("공지:") }.map { $0.substringBefore(":") })
    }

    /// 저장 뒤 마감이 지난 미제출 온라인 과제는 누락으로 본다.
    private static func refresh(_ a: AssignmentItem, _ now: Date) -> AssignmentItem {
        guard !a.missing, !a.submitted, a.online, let due = a.due, due < now else { return a }
        var c = a
        c.missing = true
        return c
    }

    /// `3시간`, `25분`, `2일` (저장본이 얼마나 오래됐는지).
    public static func ageLabel(_ cachedAt: Date, _ now: Date) -> String {
        let mins = max((now.epochMilli - cachedAt.epochMilli) / 60_000, 0)
        if mins < 1 { return "방금" }
        if mins < 60 { return "\(mins)분 전" }
        if mins < 48 * 60 { return "\(mins / 60)시간 전" }
        return "\(mins / (24 * 60))일 전"
    }

    /// 알림·브리핑 끝에 붙이는 한 줄.
    public static func staleLine(_ cachedAt: Date, _ now: Date) -> String {
        "※ eTL에서 지금 받지 못한 과제는 \(ageLabel(cachedAt, now)) 받아 둔 정보예요"
    }
}
