import Foundation

/// eTL 도구가 앱(채팅 화면)에 알리는 일. 받은 파일, 비서가 바꾼 데이터 알림 등.
/// 스레드에 안전한 작은 이벤트 허브다. 알림은 보낸 스레드(도구 실행 스레드)에서 바로 불리므로 화면 쪽에서 메인 스레드로 넘긴다.
public enum EtlEvents {
    public struct SavedFile: Sendable, Hashable {
        public var name: String
        public var mime: String
        public var size: Int64
        /// 앱 작업 폴더 안의 파일.
        public var file: URL
        /// 사용자용 사본(`Documents/etl/…`)의 URL 문자열. 없으면 nil. (안드로이드: 폰 Download/etl/ 사본의 content URI)
        public var phoneUri: String?
        /// 사용자용 사본 위치 표시.
        public var shownPath: String?
        /// 내용을 확인하기 위한 자료면 채팅에서 접어 둔다.
        public var referenceOnly: Bool

        public init(name: String, mime: String, size: Int64, file: URL, phoneUri: String?, shownPath: String?, referenceOnly: Bool = false) {
            self.name = name
            self.mime = mime
            self.size = size
            self.file = file
            self.phoneUri = phoneUri
            self.shownPath = shownPath
            self.referenceOnly = referenceOnly
        }
    }

    public enum Event: Sendable, Hashable {
        /// 파일을 받았거나(pagePreview = false) 쪽 이미지를 만들었다(pagePreview = true).
        case filesSaved([SavedFile], pagePreview: Bool)
        /// 비서가 폰 안의 데이터(내 마감, 보강·휴강)를 바꿨다. 채팅에 한 줄로 보여 사용자가 바로 알게 한다.
        case notice(String)
        /// 시간표·보강/휴강 같은 앱 데이터가 바뀌었다(안드로이드 `HomeWidgets.dataChanged`). 위젯·화면 갱신용.
        case dataChanged
    }

    /// Kotlin `EtlEvents.Listener`.
    public protocol Listener: AnyObject {
        func onFilesSaved(_ files: [SavedFile], pagePreview: Bool)
        func onNotice(_ text: String)
    }

    /// 구독 해제 토큰. [cancel]을 부르거나 해제(deinit)되면 더 받지 않는다.
    public final class Subscription: @unchecked Sendable {
        fileprivate let id: Int
        private var cancelled = false
        private let lock = NSLock()
        fileprivate init(id: Int) { self.id = id }
        public func cancel() {
            let first = lock.withLock { () -> Bool in
                if cancelled { return false }
                cancelled = true
                return true
            }
            if first { EtlEvents.hub.remove(id) }
        }
        deinit { cancel() }
    }

    private final class Hub: @unchecked Sendable {
        let lock = NSLock()
        var nextId = 0
        var handlers: [(Int, @Sendable (Event) -> Void)] = []
        weak var listener: Listener?

        func add(_ h: @escaping @Sendable (Event) -> Void) -> Int {
            lock.withLock {
                nextId += 1
                handlers.append((nextId, h))
                return nextId
            }
        }
        func remove(_ id: Int) { lock.withLock { handlers.removeAll { $0.0 == id } } }
        func snapshot() -> ([@Sendable (Event) -> Void], Listener?) { lock.withLock { (handlers.map(\.1), listener) } }
    }

    private static let hub = Hub()

    /// 단일 리스너(Kotlin `@Volatile var listener`). 약한 참조로 잡는다.
    public static var listener: Listener? {
        get { hub.lock.withLock { hub.listener } }
        set { hub.lock.withLock { hub.listener = newValue } }
    }

    /// 이벤트를 구독한다. 돌려받은 [Subscription]을 들고 있어야 계속 받는다.
    public static func subscribe(_ handler: @escaping @Sendable (Event) -> Void) -> Subscription {
        Subscription(id: hub.add(handler))
    }

    public static func post(_ event: Event) {
        let (handlers, l) = hub.snapshot()
        for h in handlers { h(event) }
        switch event {
        case .filesSaved(let files, let pagePreview): l?.onFilesSaved(files, pagePreview: pagePreview)
        case .notice(let text): l?.onNotice(text)
        case .dataChanged: break
        }
    }

    public static func fileSaved(_ f: SavedFile) { post(.filesSaved([f], pagePreview: false)) }

    public static func notice(_ text: String) { post(.notice(text)) }

    public static func pagesRendered(_ files: [SavedFile]) {
        if !files.isEmpty { post(.filesSaved(files, pagePreview: true)) }
    }

    /// 앱 데이터가 바뀌었다(위젯 갱신 신호).
    public static func dataChanged() { post(.dataChanged) }
}

/// 지금 도구를 실행하게 한 대화(2026-11-27, 대화마다 따로 답하기). Kotlin `mcp.ToolOrigin`과 같은 역할이다.
///
/// 채팅 화면은 대화마다 턴을 따로 돌리고, 도구 실행기를 `ToolOrigin.$chatId.withValue(대화 ID) { … }`로 감싸 부른다.
/// `EtlEvents.post`는 도구의 Task에서 처리기를 바로 부르므로, 처리기 안에서 이 값을 읽으면 받은 파일·바뀐 데이터 알림이 어느 대화의
/// 것인지 알 수 있다(쓰기 승인 요청도 같은 값을 쓴다). 라우팅에만 쓰고 권한 판단에는 쓰지 않는다.
public enum ToolOrigin {
    @TaskLocal public static var chatId: String?
}

public extension EtlEvents.Listener {
    func onNotice(_ text: String) {}
}
