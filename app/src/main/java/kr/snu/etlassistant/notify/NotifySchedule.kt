package kr.snu.etlassistant.notify

import java.time.ZonedDateTime

/** 알림 예약에 쓰는 순수 계산(안드로이드 비의존, 단위 테스트 대상). */
object NotifySchedule {
    /** 브리핑이 예정 시각보다 이만큼 넘게 늦으면 보내지 않는다. */
    const val MAX_LATE_MILLIS = 4L * 3600 * 1000

    /** WorkManager 주기 작업의 최소 flex 간격(5분). */
    const val MIN_FLEX_MILLIS = 5L * 60 * 1000

    private const val HOUR_MILLIS = 3600L * 1000

    /**
     * [now]보다 **최소 [minLeadMillis] 뒤**인 가장 가까운 [minuteOfDay](0..1439)시각까지의 지연(ms).
     * 오늘 그 시각이 충분히 남았으면 오늘, 아니면 내일(그래도 모자라면 그다음 날).
     * 시간대 전환(DST)은 [ZonedDateTime]이 처리한다: 존재하지 않는 시각(봄 전환)은 전환 뒤로 밀리고,
     * 두 번 있는 시각(가을 전환)은 이른 쪽을 쓴다.
     */
    fun nextOccurrenceDelayMillis(now: ZonedDateTime, minuteOfDay: Int, minLeadMillis: Long = 60_000): Long {
        val m = minuteOfDay.coerceIn(0, 1439)
        val nowMs = now.toInstant().toEpochMilli()
        var date = now.toLocalDate()
        // DST로 하루가 23~25시간일 수 있으니 여유 있게 며칠 앞까지만 본다.
        repeat(4) {
            val candidate = ZonedDateTime.of(date, java.time.LocalTime.of(m / 60, m % 60), now.zone)
            val delay = candidate.toInstant().toEpochMilli() - nowMs
            if (delay >= minLeadMillis) return delay
            date = date.plusDays(1)
        }
        return 24L * HOUR_MILLIS // 도달하지 않는다(안전장치)
    }

    /** 브리핑이 예정 시각([scheduledAtMillis])보다 [maxLateMillis]를 넘게 늦게 실행됐는가(그러면 보내지 않는다). */
    fun isStale(scheduledAtMillis: Long, nowMillis: Long, maxLateMillis: Long = MAX_LATE_MILLIS): Boolean =
        nowMillis - scheduledAtMillis > maxLateMillis

    /** 주기 확인의 flex 간격: 주기의 약 1/4, 최소 [MIN_FLEX_MILLIS], 최대 주기 자체. */
    fun flexMillis(intervalHours: Int): Long {
        val interval = intervalHours.coerceAtLeast(1) * HOUR_MILLIS
        return (interval / 4).coerceAtLeast(MIN_FLEX_MILLIS).coerceAtMost(interval)
    }

    /** 같은 [tag]는 같은 정수 알림 ID가 되도록(교체) 하는 해시. 항상 0 이상. */
    fun notificationId(tag: String): Int = tag.hashCode() and Int.MAX_VALUE

    /** 토큰 오류 알림은 마지막으로 띄운 뒤 [intervalMillis](기본 24시간)가 지나야 다시 띄운다. */
    fun authFailureDue(lastFiredMillis: Long?, nowMillis: Long, intervalMillis: Long = 24L * HOUR_MILLIS): Boolean =
        lastFiredMillis == null || nowMillis - lastFiredMillis >= intervalMillis
}
