package kr.snu.etlassistant.notify

import kr.snu.etlassistant.etl.EtlLogic
import java.time.Instant

/**
 * 시험 알림에 붙이는 응원 문구(2026-11-24, 사용자 요청). AI 없이 앱 안 목록에서 고른다.
 * 같은 시험·같은 알림 시점이면 늘 같은 문구가 나온다([pick]): 알림을 다시 게시해도 문구가 바뀌지 않는다.
 * 해요체가 기본이고 설정의 "반말로 대화"가 켜져 있으면 반말 목록을 쓴다. 이모지는 넣지 않는다.
 * 본문 앞은 정보(시각·장소·준비물, [MyDeadlines.examInfo])이고 이 문구는 그 뒤에 붙는다.
 */
object ExamCheer {
    enum class Phase { EVE, DAY, AFTER }

    /** 알림 시각이 시험 날(KST)보다 앞 날이면 전날 문구, 같은 날이면 당일 문구. */
    fun phaseOf(at: Instant, start: Instant): Phase =
        if (at.atZone(EtlLogic.KST).toLocalDate().isBefore(start.atZone(EtlLogic.KST).toLocalDate())) Phase.EVE else Phase.DAY

    /** 시험 ID와 알림 시각(분)으로 정하는 결정적인 번호. */
    fun pick(id: Long, at: Instant, phase: Phase, size: Int): Int {
        var h = id * 1_000_003L + (at.epochSecond / 60) * 31L + phase.ordinal * 7L
        h = h xor (h ushr 29)
        h *= -0x4b47d5b1b0c7f7b5L
        h = h xor (h ushr 32)
        return Math.floorMod(h, size.toLong()).toInt()
    }

    /**
     * 문구 하나. [course]가 비면 "시험"만 쓰고, [location]이 비면 장소 없이 쓴다.
     * [supplies]가 있으면 당일 문구에서 "신분증·필기구 챙기셨죠?" 같은 일반 확인 대신 준비물을 다시 보라는 문구를 쓴다(준비물은 정보 부분에 있다).
     */
    fun message(phase: Phase, id: Long, at: Instant, course: String, start: Instant, location: String, supplies: String, casual: Boolean): String {
        val subject = NoticeText.clean(course).let { if (it.isEmpty()) "시험" else "$it 시험" }
        val z = start.atZone(EtlLogic.KST)
        val time = "%02d:%02d".format(z.hour, z.minute)
        val place = NoticeText.clean(location)
        val where = if (place.isEmpty()) "${time}에" else "$time ${place}에서"
        val list = when (phase) {
            Phase.EVE -> if (casual) EVE_CASUAL else EVE
            Phase.DAY -> when {
                supplies.isNotBlank() -> if (casual) DAY_SUPPLIES_CASUAL else DAY_SUPPLIES
                else -> if (casual) DAY_CASUAL else DAY
            }
            Phase.AFTER -> if (casual) AFTER_CASUAL else AFTER
        }
        return list[pick(id, at, phase, list.size)].replace("{s}", subject).replace("{w}", where)
    }

    // {s} = "자료구조 시험" 또는 "시험", {w} = "10:00 301동 118호에서" 또는 "10:00에"

    val EVE = listOf(
        "내일 {s}이에요. 오늘은 일찍 자 두세요. 준비한 만큼 잘 볼 거예요.",
        "내일이 {s}이에요. 마지막으로 핵심만 훑고 푹 쉬어요.",
        "{s}이 내일이에요. 새로운 걸 붙잡기보다 아는 걸 정리해 두면 충분해요.",
        "내일 {s}, 여기까지 온 것만으로도 잘하고 있어요. 오늘은 충분히 자요.",
        "내일 {s}이 있어요. 준비물 미리 챙겨 두고 일찍 쉬어요.",
        "{s} 전날이에요. 무리하지 말고 컨디션부터 챙겨요.",
    )
    val EVE_CASUAL = listOf(
        "내일 {s}이야. 오늘은 일찍 자 둬. 준비한 만큼 잘 볼 거야.",
        "내일이 {s}이야. 마지막으로 핵심만 훑고 푹 쉬어.",
        "{s}이 내일이야. 새로운 걸 붙잡기보다 아는 걸 정리해 두면 충분해.",
        "내일 {s}, 여기까지 온 것만으로도 잘하고 있어. 오늘은 충분히 자.",
        "내일 {s}이 있어. 준비물 미리 챙겨 두고 일찍 쉬어.",
        "{s} 전날이야. 무리하지 말고 컨디션부터 챙겨.",
    )
    val DAY = listOf(
        "{w} 시험이에요. 신분증·필기구 챙기셨죠? 잘 보고 와요.",
        "곧 {s}이에요. {w} 시작해요. 아는 것부터 차근차근 풀어요.",
        "{w} 시험이에요. 조금 일찍 도착해서 숨 한번 고르고 들어가요.",
        "오늘 {s}, {w} 시작이에요. 준비한 만큼 보여 주고 와요.",
        "{w} 시험이에요. 문제는 끝까지 읽고 시간 배분만 신경 써요. 잘 보고 와요.",
        "{w} 시험이에요. 신분증 확인하고, 긴장되면 천천히 숨 쉬어요. 응원할게요.",
    )
    val DAY_CASUAL = listOf(
        "{w} 시험이야. 신분증·필기구 챙겼지? 잘 보고 와.",
        "곧 {s}이야. {w} 시작해. 아는 것부터 차근차근 풀어.",
        "{w} 시험이야. 조금 일찍 도착해서 숨 한번 고르고 들어가.",
        "오늘 {s}, {w} 시작이야. 준비한 만큼 보여 주고 와.",
        "{w} 시험이야. 문제는 끝까지 읽고 시간 배분만 신경 써. 잘 보고 와.",
        "{w} 시험이야. 신분증 확인하고, 긴장되면 천천히 숨 쉬어. 응원할게.",
    )
    val DAY_SUPPLIES = listOf(
        "{w} 시험이에요. 나가기 전에 준비물 한 번 더 확인해요. 잘 보고 와요.",
        "곧 {s}이에요. {w} 시작해요. 준비물 챙겼으면 이제 아는 것부터 차근차근 풀어요.",
        "{w} 시험이에요. 준비물을 가방에 넣었는지 보고, 조금 일찍 도착해요.",
        "오늘 {s}, {w} 시작이에요. 준비물만 챙기면 나머지는 준비한 만큼 보여 주면 돼요.",
        "{w} 시험이에요. 준비물 확인하고, 문제는 끝까지 읽어요. 응원할게요.",
    )
    val DAY_SUPPLIES_CASUAL = listOf(
        "{w} 시험이야. 나가기 전에 준비물 한 번 더 확인해. 잘 보고 와.",
        "곧 {s}이야. {w} 시작해. 준비물 챙겼으면 이제 아는 것부터 차근차근 풀어.",
        "{w} 시험이야. 준비물을 가방에 넣었는지 보고, 조금 일찍 도착해.",
        "오늘 {s}, {w} 시작이야. 준비물만 챙기면 나머지는 준비한 만큼 보여 주면 돼.",
        "{w} 시험이야. 준비물 확인하고, 문제는 끝까지 읽어. 응원할게.",
    )
    val AFTER = listOf(
        "{s} 끝났어요. 수고했어요. 오늘은 좀 쉬어요.",
        "{s} 보느라 수고했어요. 결과는 나중 일이고, 지금은 쉬어요.",
        "{s} 끝! 수고 많았어요. 맛있는 거 먹고 쉬어요.",
        "{s}까지 잘 마쳤어요. 정말 수고했어요.",
        "{s} 끝났어요. 지나간 문제는 잊고 푹 쉬어요. 수고했어요.",
    )
    val AFTER_CASUAL = listOf(
        "{s} 끝났어. 수고했어. 오늘은 좀 쉬어.",
        "{s} 보느라 수고했어. 결과는 나중 일이고, 지금은 쉬어.",
        "{s} 끝! 수고 많았어. 맛있는 거 먹고 쉬어.",
        "{s}까지 잘 마쳤어. 정말 수고했어.",
        "{s} 끝났어. 지나간 문제는 잊고 푹 쉬어. 수고했어.",
    )
}
