// swift-tools-version: 6.0
// 영민이 iOS의 플랫폼 독립 로직. 안드로이드 패키지 구조(etl → notify → AI 도구)를 그대로 따른다.
// macOS에서도 빌드되므로 `swift test`로 로직 테스트를 바로 돌릴 수 있다.
import PackageDescription

let package = Package(
    name: "YoungminKit",
    defaultLocalization: "ko",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "YoungminKit", targets: ["ETLKit", "NotifyKit", "ChatKit", "AssistantKit"]),
    ],
    targets: [
        // eTL(Canvas) 클라이언트, 조회·판정 로직, 수강편람 시간표 (Kotlin etl 패키지)
        .target(name: "ETLKit"),
        // 내 일정·알림 계획·브리핑 등 알림 도메인 (Kotlin notify 패키지의 순수 로직)
        .target(name: "NotifyKit", dependencies: ["ETLKit"]),
        // 대화 모델·저장, 마크다운·수식 파싱, Responses API 엔진, ChatGPT 로그인(PKCE) (Kotlin chat·codex의 순수 로직)
        .target(name: "ChatKit"),
        // 비서 도구(eTL·내 마감·시간표), 지침, 쓰기 승인 (Kotlin EtlTools·AiWriteApproval·CodexConfig)
        .target(name: "AssistantKit", dependencies: ["ETLKit", "NotifyKit", "ChatKit"]),
        .testTarget(name: "ETLKitTests", dependencies: ["ETLKit"]),
        .testTarget(name: "NotifyKitTests", dependencies: ["NotifyKit"]),
        .testTarget(name: "ChatKitTests", dependencies: ["ChatKit"]),
        .testTarget(name: "AssistantKitTests", dependencies: ["AssistantKit"], resources: [.copy("Fixtures")]),
    ],
    swiftLanguageModes: [.v5]
)
