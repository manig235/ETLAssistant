package kr.snu.etlassistant.chat

import kr.snu.etlassistant.etl.EtlLogic
import java.time.Instant
import kr.snu.etlassistant.notify.NotifySettings

/** 알림 설정 화면용 순수 포맷 헬퍼(안드로이드 클래스에 의존하지 않는다). */
object NotifyUiFormat {

    /** 하루 중 분(0..1439)을 `오전 8:00` 또는 24시간제 `08:00`으로. 범위 밖 값은 보정한다. */
    fun formatTime(minuteOfDay: Int, is24h: Boolean): String {
        val m = minuteOfDay.coerceIn(0, 1439)
        val h = m / 60
        val min = m % 60
        if (is24h) return "%02d:%02d".format(h, min)
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%s %d:%02d".format(ampm, h12, min)
    }

    /** 브리핑 시각 목록 정리: 0..1439로 보정, 중복 제거, 오름차순, 최대 2개, 비면 오전 8시. */
    fun normalizeBriefTimes(times: List<Int>): List<Int> =
        times.map { it.coerceIn(0, 1439) }.distinct().sorted().take(2).ifEmpty { listOf(8 * 60) }

    /** 마감 알림 시점 이름(분 단위 값). */
    fun windowLabel(minutes: Int): String = NotifySettings.windowLabel(minutes)

    /** "3일, 24시간, 3시간, 1시간 전에 알려요" */
    fun windowsSummary(minutes: List<Int>): String =
        NotifySettings.normalizeWindows(minutes).joinToString(", ") { windowLabel(it) } + " 전에 알려요"

    fun describeLastCheck(lastCheckAt: Long, lastResult: String): String {
        if (lastCheckAt <= 0L) return "아직 확인한 적이 없습니다"
        val time = EtlLogic.fmtKst(Instant.ofEpochMilli(lastCheckAt))
        val result = lastResult.trim()
        return if (result.isEmpty()) "마지막 확인: $time" else "마지막 확인: $time · $result"
    }

    fun describeLastBrief(lastBriefAt: Long): String {
        if (lastBriefAt <= 0L) return "아직 브리핑을 보낸 적이 없습니다"
        return "마지막 브리핑: " + EtlLogic.fmtKst(Instant.ofEpochMilli(lastBriefAt))
    }
}
