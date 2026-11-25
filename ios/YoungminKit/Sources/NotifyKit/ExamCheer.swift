import Foundation
import ETLKit

/// 시험 알림에 붙이는 응원 문구. AI 없이 앱 안 목록에서 고른다.
/// 같은 시험·같은 알림 시점이면 늘 같은 문구가 나온다([pick]). 해요체가 기본이고 "반말로 대화"가 켜져 있으면 반말 목록을 쓴다.
public enum ExamCheer {
    public enum Phase: Int, CaseIterable, Hashable, Sendable { case EVE = 0, DAY, AFTER }

    /// 알림 시각이 시험 날(KST)보다 앞 날이면 전날 문구, 같은 날이면 당일 문구.
    public static func phaseOf(_ at: Date, _ start: Date) -> Phase {
        DayDate(at) < DayDate(start) ? .EVE : .DAY
    }

    /// 시험 ID와 알림 시각(분)으로 정하는 결정적인 번호.
    public static func pick(_ id: Int64, _ at: Date, _ phase: Phase, _ size: Int) -> Int {
        var h = id &* 1_000_003 &+ (at.epochSecond / 60) &* 31 &+ Int64(phase.rawValue) &* 7
        h = h ^ Int64(bitPattern: UInt64(bitPattern: h) >> 29)
        h = h &* -0x4b47d5b1b0c7f7b5
        h = h ^ Int64(bitPattern: UInt64(bitPattern: h) >> 32)
        return Int(floorMod(h, Int64(size)))
    }

    /// 문구 하나. [course]가 비면 "시험"만 쓰고, [location]이 비면 장소 없이 쓴다.
    /// [supplies]가 있으면 당일 문구에서 일반 확인 대신 준비물을 다시 보라는 문구를 쓴다.
    public static func message(_ phase: Phase, _ id: Int64, _ at: Date, _ course: String, _ start: Date, _ location: String, _ supplies: String, casual: Bool) -> String {
        let c = NoticeText.clean(course)
        let subject = c.isEmpty ? "시험" : "\(c) 시험"
        let z = EtlLogic.kstParts(start)
        let time = String(format: "%02d:%02d", z.hour, z.minute)
        let place = NoticeText.clean(location)
        let where_ = place.isEmpty ? "\(time)에" : "\(time) \(place)에서"
        let list: [String]
        switch phase {
        case .EVE: list = casual ? EVE_CASUAL : EVE
        case .DAY: list = !supplies.kIsBlank ? (casual ? DAY_SUPPLIES_CASUAL : DAY_SUPPLIES) : (casual ? DAY_CASUAL : DAY)
        case .AFTER: list = casual ? AFTER_CASUAL : AFTER
        }
        return list[pick(id, at, phase, list.count)].replacingOccurrences(of: "{s}", with: subject).replacingOccurrences(of: "{w}", with: where_)
    }

    // {s} = "자료구조 시험" 또는 "시험", {w} = "10:00 301동 118호에서" 또는 "10:00에"

    public static let EVE = [
        "내일 {s}이에요. 오늘은 일찍 자 두세요. 준비한 만큼 잘 볼 거예요.",
        "내일이 {s}이에요. 마지막으로 핵심만 훑고 푹 쉬어요.",
        "{s}이 내일이에요. 새로운 걸 붙잡기보다 아는 걸 정리해 두면 충분해요.",
        "내일 {s}, 여기까지 온 것만으로도 잘하고 있어요. 오늘은 충분히 자요.",
        "내일 {s}이 있어요. 준비물 미리 챙겨 두고 일찍 쉬어요.",
        "{s} 전날이에요. 무리하지 말고 컨디션부터 챙겨요.",
    ]
    public static let EVE_CASUAL = [
        "내일 {s}이야. 오늘은 일찍 자 둬. 준비한 만큼 잘 볼 거야.",
        "내일이 {s}이야. 마지막으로 핵심만 훑고 푹 쉬어.",
        "{s}이 내일이야. 새로운 걸 붙잡기보다 아는 걸 정리해 두면 충분해.",
        "내일 {s}, 여기까지 온 것만으로도 잘하고 있어. 오늘은 충분히 자.",
        "내일 {s}이 있어. 준비물 미리 챙겨 두고 일찍 쉬어.",
        "{s} 전날이야. 무리하지 말고 컨디션부터 챙겨.",
    ]
    public static let DAY = [
        "{w} 시험이에요. 신분증·필기구 챙기셨죠? 잘 보고 와요.",
        "곧 {s}이에요. {w} 시작해요. 아는 것부터 차근차근 풀어요.",
        "{w} 시험이에요. 조금 일찍 도착해서 숨 한번 고르고 들어가요.",
        "오늘 {s}, {w} 시작이에요. 준비한 만큼 보여 주고 와요.",
        "{w} 시험이에요. 문제는 끝까지 읽고 시간 배분만 신경 써요. 잘 보고 와요.",
        "{w} 시험이에요. 신분증 확인하고, 긴장되면 천천히 숨 쉬어요. 응원할게요.",
    ]
    public static let DAY_CASUAL = [
        "{w} 시험이야. 신분증·필기구 챙겼지? 잘 보고 와.",
        "곧 {s}이야. {w} 시작해. 아는 것부터 차근차근 풀어.",
        "{w} 시험이야. 조금 일찍 도착해서 숨 한번 고르고 들어가.",
        "오늘 {s}, {w} 시작이야. 준비한 만큼 보여 주고 와.",
        "{w} 시험이야. 문제는 끝까지 읽고 시간 배분만 신경 써. 잘 보고 와.",
        "{w} 시험이야. 신분증 확인하고, 긴장되면 천천히 숨 쉬어. 응원할게.",
    ]
    public static let DAY_SUPPLIES = [
        "{w} 시험이에요. 나가기 전에 준비물 한 번 더 확인해요. 잘 보고 와요.",
        "곧 {s}이에요. {w} 시작해요. 준비물 챙겼으면 이제 아는 것부터 차근차근 풀어요.",
        "{w} 시험이에요. 준비물을 가방에 넣었는지 보고, 조금 일찍 도착해요.",
        "오늘 {s}, {w} 시작이에요. 준비물만 챙기면 나머지는 준비한 만큼 보여 주면 돼요.",
        "{w} 시험이에요. 준비물 확인하고, 문제는 끝까지 읽어요. 응원할게요.",
    ]
    public static let DAY_SUPPLIES_CASUAL = [
        "{w} 시험이야. 나가기 전에 준비물 한 번 더 확인해. 잘 보고 와.",
        "곧 {s}이야. {w} 시작해. 준비물 챙겼으면 이제 아는 것부터 차근차근 풀어.",
        "{w} 시험이야. 준비물을 가방에 넣었는지 보고, 조금 일찍 도착해.",
        "오늘 {s}, {w} 시작이야. 준비물만 챙기면 나머지는 준비한 만큼 보여 주면 돼.",
        "{w} 시험이야. 준비물 확인하고, 문제는 끝까지 읽어. 응원할게.",
    ]
    public static let AFTER = [
        "{s} 끝났어요. 수고했어요. 오늘은 좀 쉬어요.",
        "{s} 보느라 수고했어요. 결과는 나중 일이고, 지금은 쉬어요.",
        "{s} 끝! 수고 많았어요. 맛있는 거 먹고 쉬어요.",
        "{s}까지 잘 마쳤어요. 정말 수고했어요.",
        "{s} 끝났어요. 지나간 문제는 잊고 푹 쉬어요. 수고했어요.",
    ]
    public static let AFTER_CASUAL = [
        "{s} 끝났어. 수고했어. 오늘은 좀 쉬어.",
        "{s} 보느라 수고했어. 결과는 나중 일이고, 지금은 쉬어.",
        "{s} 끝! 수고 많았어. 맛있는 거 먹고 쉬어.",
        "{s}까지 잘 마쳤어. 정말 수고했어.",
        "{s} 끝났어. 지나간 문제는 잊고 푹 쉬어. 수고했어.",
    ]
}
