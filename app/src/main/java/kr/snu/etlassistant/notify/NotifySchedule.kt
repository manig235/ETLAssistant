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

    /**
     * 이미 예약해 둔 브리핑([storedTargetMillis])을 그대로 둘지. 프로세스가 뜰 때마다 [NotifyScheduler.apply]가 불리는데,
     * 브리핑 작업 자신이 프로세스를 띄운 경우 예약을 새로 계산하면(최소 1분 앞) 오늘 것이 취소되고 내일로 밀린다.
     * 그래서 같은 시각(분) 설정의 예약이 아직 유효하면(너무 늦지 않았고 이상하게 먼 미래도 아니면) 건드리지 않는다.
     */
    fun keepBriefTarget(
        storedTargetMillis: Long?,
        minuteOfDay: Int,
        nowMillis: Long,
        zone: java.time.ZoneId,
        maxLateMillis: Long = MAX_LATE_MILLIS,
    ): Boolean {
        val t = storedTargetMillis ?: return false
        if (nowMillis - t > maxLateMillis) return false // 이미 지나 보낼 수 없는 예약
        if (t - nowMillis > 2 * 24 * HOUR_MILLIS) return false // 시계가 뒤로 간 경우 등
        val local = java.time.Instant.ofEpochMilli(t).atZone(zone)
        return local.hour * 60 + local.minute == minuteOfDay.coerceIn(0, 1439)
    }

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

    // ---- 마감 알람(권한 없는 알람의 지연 줄이기) ----------------------------------

    /**
     * 마지막 알람을 목표 시각에 직접 거는 거리. 시스템 지연은 남은 시간의 75%라 최대 45초.
     * 삼성 기기는 권한 없는 알람을 거의 항상 창 끝에 울린다(2026-11-21 실측: 3분 39초 앞에 건 마지막 알람이 2분 44초 늦게 울림).
     * 처음에는 8분이었는데, 그러면 "내가 정한 알림"이 최대 6분 늦었다.
     */
    const val ALARM_FINAL_MILLIS = 60_000L
    /** 시스템이 권한 없는 알람을 늦출 수 있는 최대 폭. */
    private const val ALARM_MAX_WINDOW_MILLIS = HOUR_MILLIS

    /** 재부팅·시간 변경 뒤 저장된 알람 목표를 어떻게 다시 걸지. */
    enum class Rearm { NONE, ARM, FIRE_NOW }

    /** 목표가 없으면 NONE, 아직 오지 않았으면 ARM(다시 다가가기), 꺼져 있던 사이 지났으면 FIRE_NOW(바로 확인해 늦게라도 보낸다). */
    fun rearmAction(targetMillis: Long, nowMillis: Long): Rearm = when {
        targetMillis <= 0L -> Rearm.NONE
        targetMillis > nowMillis -> Rearm.ARM
        else -> Rearm.FIRE_NOW
    }

    /**
     * 권한 없는 알람(`setAndAllowWhileIdle`)은 [trigger, trigger + min(남은 시간 × 0.75, 1시간)] 사이 아무 때나 울린다.
     * 목표가 멀면 중간 알람으로 다가가며(그때는 네트워크 없이 다시 걸기만 한다) 마지막 몇 분 안에서만 목표에 맞춘다.
     * 중간 알람은 지연이 최대여도 목표를 넘지 않게 잡는다.
     * @return (다음 알람 시각, 이 알람이 목표 시각의 확인인가)
     */
    fun nextAlarm(nowMillis: Long, targetMillis: Long): Pair<Long, Boolean> {
        val left = targetMillis - nowMillis
        return when {
            left <= ALARM_FINAL_MILLIS -> targetMillis to true
            // 멀면 지연 폭(최대 1시간)보다 조금 더 앞에 건다: 가장 늦어도 목표 5분 전.
            left > 2 * ALARM_MAX_WINDOW_MILLIS -> (targetMillis - ALARM_MAX_WINDOW_MILLIS - 5 * 60_000L) to false
            // 가까우면 남은 시간의 절반 뒤: 가장 늦어도 now + 0.5 × 1.75 × left < 목표.
            else -> (nowMillis + left / 2) to false
        }
    }
}
