import Foundation
import ETLKit

/// 알림 본문을 눌렀을 때 갈 곳.
public enum NoticeTap: Hashable, Sendable { case CHAT_PREFILL, ETL, AGENDA, CHAT }

public enum NoticeRouting {
    /// 채팅 입력창 채우기(버튼 없이 본문으로) > eTL 주소 > 내 일정(내 마감·시험) > 채팅. 아무것도 없으면 앱(채팅)을 연다.
    public static func tap(_ n: Notice) -> NoticeTap {
        if n.prefill != nil && n.prefillAction == nil { return .CHAT_PREFILL }
        if NoticeText.safeUrl(n.url) != nil { return .ETL }
        if n.agenda { return .AGENDA }
        return .CHAT
    }
}

/// 안드로이드 `NotificationPoster`의 플랫폼 독립 부분: 채널 이름·중요도, 버튼 순서, 잠금화면 문구.
/// 실제 게시(UNUserNotificationCenter)는 iOS 앱이 하고, 게시한 뒤 [NotifyHistory.record]를 부른다.
public enum NotificationPoster {
    /// 중요도를 DEFAULT로 만들었던 옛 채널 ID(알림 기록 표시용).
    public static let LEGACY_ANNOUNCEMENT = "announcement"
    public static let LEGACY_CHANGE = "change"
    public static let LEGACY_UPDATE = "update"

    /// 잠금화면에 내용을 숨길 때 보일 문구([NotifySettings.hideContentOnLockScreen]).
    public static let LOCKSCREEN_TITLE = "영민이 알림"
    public static let LOCKSCREEN_TEXT = "새 알림이 있어요"
    public static let ETL_ACTION = "eTL에서 열기"

    /// 안드로이드 채널 중요도. iOS `interruptionLevel`: high → `.timeSensitive`(또는 `.active`), normal → `.active`, low → `.passive`.
    public enum Importance: Hashable, Sendable { case high, normal, low }

    /// 채널 이름(설정·알림 기록에 보인다).
    public static func channelName(_ c: Channel) -> String {
        switch c {
        case .DEADLINE: return "마감 알림"
        case .ANNOUNCEMENT: return "공지 알림"
        case .BRIEF: return "정기 브리핑"
        case .CHANGE: return "새 과제·마감 변경·성적"
        case .CLASS: return "수업 알림"
        case .UPDATE: return "앱 업데이트"
        case .SUBMIT: return "제출 확인"
        case .SUBMIT_OK: return "정상 제출 확인"
        case .NEW_FILES: return "새 강의자료"
        }
    }

    /// 채널 중요도(HIGH = 팝업, 브리핑·새 강의자료는 알림창에만, 정상 제출은 소리 없이).
    public static func importance(_ c: Channel) -> Importance {
        switch c {
        case .SUBMIT_OK: return .low
        case .BRIEF, .NEW_FILES: return .normal
        default: return .high
        }
    }

    /// 알림 버튼 하나.
    public enum Action: Hashable, Sendable {
        /// "내 일정에 등록"/"시간표에 반영": 초안([ScheduleDraft])을 채운 추가 창을 연다.
        case draft(label: String, json: String)
        /// 채팅 입력창에 [text]를 채운다(보내지 않음).
        case prefill(label: String, text: String)
        /// eTL(LearningX 또는 웹)로 연다.
        case openEtl(url: String)

        public var label: String {
            switch self {
            case .draft(let l, _), .prefill(let l, _): return l
            case .openEtl: return NotificationPoster.ETL_ACTION
            }
        }
    }

    /// 버튼 순서: 내 일정에 등록 / 채팅 채우기 버튼 / eTL에서 열기(최대 3개).
    public static func actions(_ n: Notice) -> [Action] {
        var out: [Action] = []
        if let draft = n.draft { out.append(.draft(label: ScheduleDraft.fromJson(draft)?.actionLabel ?? ScheduleDrafts.ACTION, json: draft)) }
        if let p = n.prefill, let a = n.prefillAction { out.append(.prefill(label: a, text: p)) }
        if let u = NoticeText.safeUrl(n.url) { out.append(.openEtl(url: u)) }
        return out
    }

    /// 알림 요청 식별자로 쓸 값(같은 [Notice.tag]는 같은 알림을 교체한다).
    public static func identifier(_ n: Notice) -> String { n.tag }
}
