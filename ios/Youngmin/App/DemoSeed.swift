import ChatKit
import ETLKit
import SwiftUI
import UIKit

/// DEBUG 전용 시연 데이터. 실제 계정 없이 시뮬레이터에서 채팅 화면을 그럴듯하게 보이게 한다.
/// 환영 단계의 "샘플 데이터로 둘러보기"가 켠다(`UserDefaults` `debug.demo`). 켜져 있으면 배너를 숨기고 답변을 흉내 낸다.
/// 릴리스 빌드에서는 항상 꺼져 있다.
enum DemoSeed {
    static let flagKey = "debug.demo"

    static var isActive: Bool {
        #if DEBUG
        return !StudyDataProfile.isDemo && UserDefaults.standard.bool(forKey: flagKey)
        #else
        return false
        #endif
    }

    static let models: [ModelInfo] = {
        #if DEBUG
        let efforts = [EffortOption("low", "가볍게 생각해요"), EffortOption("medium", "균형 잡힌 기본값이에요"),
                       EffortOption("high", "어려운 문제를 오래 생각해요")]
        return [
            ModelInfo(id: "gpt-5.1", model: "gpt-5.1", displayName: "GPT-5.1", description: "대부분의 질문에 좋아요",
                      isDefault: true, hidden: false, defaultEffort: "medium", efforts: efforts),
            ModelInfo(id: "gpt-5.1-mini", model: "gpt-5.1-mini", displayName: "GPT-5.1 mini", description: "빠르고 가벼워요",
                      isDefault: false, hidden: false, defaultEffort: "low", efforts: efforts),
        ]
        #else
        return []
        #endif
    }()

    #if DEBUG
    /// 시연 모드로 들어간다: 안내 완료 표시 + 대화 몇 개 심기 + 채팅 화면.
    @MainActor
    static func enterDemo(_ app: AppState) {
        UserDefaults.standard.set(true, forKey: flagKey)
        let c = app.chatController()
        c.demoMode = true
        seed(c)
        app.finishOnboarding(prefill: nil, newChat: false)
    }

    @MainActor
    static func seed(_ c: ChatController) {
        let now = ChatController.nowMs()
        let day: Int64 = 24 * 3600 * 1000

        // 지난 대화 두 개(서랍의 날짜 묶음을 보이려고).
        writeChat(c, text: "회로이론 HW5 제출됐는지 확인해줘",
                  answer: "네, **회로이론 HW5**는 11월 23일 22:41에 제출됐어요. 첨부 파일 `hw5.pdf` 1개가 정상적으로 올라가 있어요.",
                  at: now - day)
        writeChat(c, text: "자료구조 기말고사 범위 알려줘",
                  answer: "공지에 따르면 기말고사 범위는 **8주차~14주차**(그래프, 해시, 정렬)예요. 시험은 12월 16일(화) 오후 2시예요.",
                  at: now - 4 * day)

        // 지금 대화.
        let files = makeFiles()
        let id = writeChat(c, text: "이번 주 마감 과제 알려줘", answer: sampleAnswer, files: files, at: now)
        c.openConversation(id)
        c.model.addNotice("내 일정에 '회로이론 중간고사(11/28 10:00)'를 넣었어요")
        c.refreshConversations()
        c.sync(scroll: true)
    }

    @MainActor @discardableResult
    private static func writeChat(_ c: ChatController, text: String, answer: String, files: [Attachment] = [], at: Int64) -> String {
        let id = c.chats.newId()
        let m = ChatModel(c.chats.fileFor(id))
        m.addUser(text)
        m.finishAssistant("demo-\(id)", answer)
        if !files.isEmpty { m.addFiles(files) }
        m.save()
        c.chats.noteUserMessage(id, text, [], now: at)
        return id
    }

    static let sampleAnswer = """
    이번 주 마감은 **3개**예요. 가장 급한 건 내일 마감인 회로이론 HW5예요.

    ## 이번 주 마감
    | 과목 | 과제 | 마감 | 상태 |
    |---|---|---|---|
    | 회로이론 | HW5 | 11/26(수) 23:59 | 미제출 |
    | 자료구조 | 실습 7 | 11/27(목) 18:00 | 미제출 |
    | 글쓰기의 기초 | 에세이 초안 | 11/29(토) 23:59 | 제출함 |

    - 회로이론 HW5는 `RC 회로` 과도응답 문제가 4개예요. 시정수는 $\\tau = RC$로 구하면 돼요.
    - 자료구조 실습 7은 [eTL 과제 페이지](https://myetl.snu.ac.kr/courses/12345/assignments/67890)에서 템플릿을 받아야 해요.

    $$V(t) = V_0 e^{-t/RC}$$

    필요하면 3주차 강의자료도 같이 받아 둘게요.
    """

    /// 시연용 파일: 받은 PDF(있음), 지워진 PDF(다시 받기), 비서가 본 쪽 2장(참고 자료로 접힘).
    private static func makeFiles() -> [Attachment] {
        let dir = AppPaths.directory(EtlDownloads.DIR)
        let pdf = dir.appendingPathComponent("demo-3주차 강의자료.pdf")
        let pdfData = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
            for page in 1...2 {
                ctx.beginPage()
                let title = "회로이론 3주차 · RC 회로 (\(page)/2)" as NSString
                title.draw(at: CGPoint(x: 48, y: 60), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 24)])
                let body = "시정수 τ = RC\n충전: V(t) = V₀(1 − e^(−t/τ))\n방전: V(t) = V₀ e^(−t/τ)" as NSString
                body.draw(in: CGRect(x: 48, y: 120, width: 500, height: 600), withAttributes: [.font: UIFont.systemFont(ofSize: 16)])
            }
        }
        try? pdfData.write(to: pdf)
        var out = [Attachment("3주차 강의자료.pdf", "application/pdf", Int64(pdfData.count), pdf.path, nil,
                              "Documents/etl/3주차 강의자료.pdf", pagePreview: false, referenceOnly: false)]
        out.append(Attachment("2주차 강의자료.pdf", "application/pdf", 1_258_291, dir.appendingPathComponent("demo-missing.pdf").path,
                              nil, "Documents/etl/2주차 강의자료.pdf", pagePreview: false, referenceOnly: false))
        for page in 1...2 {
            let png = dir.appendingPathComponent("demo-page-\(page).png")
            let image = UIGraphicsImageRenderer(size: CGSize(width: 330, height: 466)).image { ctx in
                UIColor.white.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 330, height: 466))
                UIColor(hex: 0x0066FF).setFill()
                ctx.fill(CGRect(x: 24, y: 24, width: 282, height: 10))
                ("RC 회로 \(page)쪽" as NSString).draw(at: CGPoint(x: 24, y: 48),
                                                     withAttributes: [.font: UIFont.boldSystemFont(ofSize: 20), .foregroundColor: UIColor.black])
                UIColor(white: 0.85, alpha: 1).setFill()
                for row in 0..<10 { ctx.fill(CGRect(x: 24, y: 96 + row * 32, width: row % 3 == 2 ? 180 : 282, height: 10)) }
            }
            if let data = image.pngData() {
                try? data.write(to: png)
                out.append(Attachment("3주차 강의자료 \(page)쪽.png", "image/png", Int64(data.count), png.path, pagePreview: true))
            }
        }
        return out
    }

    /// 시연 답변: 활동 줄 → 글 조각 스트리밍. 멈추면 "답변을 멈췄어요".
    @MainActor
    static func runDemoTurn(_ c: ChatController, chatId: String, model m: ChatModel, prompt: String) async {
        let itemId = "demo-\(UUID().uuidString)"
        c.demoSetActivity(chatId, ActivityTracker.THINKING)
        try? await Task.sleep(nanoseconds: 700_000_000)
        if !Task.isCancelled {
            c.demoSetActivity(chatId, "조회하는 중…")
            try? await Task.sleep(nanoseconds: 900_000_000)
        }
        let answer = "샘플 모드라 실제로 조회하지는 않았어요. 이런 모양으로 답해요.\n\n" + sampleAnswer
        if !Task.isCancelled {
            c.demoSetActivity(chatId, nil)
            var index = answer.startIndex
            while index < answer.endIndex && !Task.isCancelled {
                let next = answer.index(index, offsetBy: 6, limitedBy: answer.endIndex) ?? answer.endIndex
                m.appendAssistant(itemId, String(answer[index..<next]))
                c.sync(chatId)
                index = next
                try? await Task.sleep(nanoseconds: 25_000_000)
            }
        }
        if Task.isCancelled {
            m.closeStreaming()
            m.addNotice("답변을 멈췄어요")
        } else {
            m.finishAssistant(itemId, answer)
        }
        c.sync(chatId)
    }
    #else
    @MainActor
    static func runDemoTurn(_ c: ChatController, chatId: String, model m: ChatModel, prompt: String) async {}
    #endif
}
