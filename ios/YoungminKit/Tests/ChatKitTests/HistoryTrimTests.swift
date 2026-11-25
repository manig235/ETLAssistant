import Testing
@testable import ChatKit

/// 이미지가 큰 턴이 있어도 그 턴의 답변 글은 기록에 남아야 한다(사진만 글로 바뀐다).
@Test func trimKeepsAnswerTextOfImageHeavyTurn() {
    let big = String(repeating: "A", count: 200_000)
    func user(_ t: String) -> ChatJSON { ["type": "message", "role": "user", "content": [["type": "input_text", "text": .string(t)]]] }
    func answer(_ t: String) -> ChatJSON { ["type": "message", "role": "assistant", "content": [["type": "output_text", "text": .string(t)]]] }
    let images: ChatJSON = ["type": "function_call_output", "call_id": "c1",
                            "output": .array((0..<6).map { _ in ["type": "input_image", "image_url": .string("data:image/jpeg;base64," + big)] })]
    let items: [ChatJSON] = [
        user("요약해줘"), ["type": "function_call", "call_id": "c1", "name": "etl_view_image", "arguments": "{}"], images, answer("세 줄 요약"),
        user("게시판 보여줘"), answer("게시글 0개"),
    ]
    let trimmed = ConversationHistory.trim(items)
    let text = String(decoding: ChatJSON.array(trimmed).serialized(), as: UTF8.self)
    #expect(text.contains("세 줄 요약"))
    #expect(!text.contains(big))
    #expect(text.count < 600_000)
}
