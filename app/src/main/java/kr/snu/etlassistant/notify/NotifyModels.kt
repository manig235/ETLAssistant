package kr.snu.etlassistant.notify

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/*
 * Phase 3 공통 계약. 여러 구현이 이 파일의 타입에 맞춰 병렬로 작성된다.
 * 이 파일의 시그니처를 바꾸려면 먼저 조율할 것(바꾸면 다른 구현이 깨진다).
 */

/** 과제 하나(현재 학기, 게시된 것만). [due]가 null이면 마감일 없음. */
data class AssignmentItem(
    val courseId: Long,
    val courseName: String,
    val assignmentId: Long,
    val name: String,
    val due: Instant?,
    /** 온라인 제출 과제인가(`submission_types`에 none/on_paper/not_graded 외의 값이 있음). */
    val online: Boolean,
    val points: Double?,
    val url: String?,
    /** `submission.workflow_state`가 submitted/graded/pending_review이거나 `submitted_at`이 있음. */
    val submitted: Boolean,
    /** 미제출이면서 `submission.missing`이거나, 마감이 지난 온라인 과제. */
    val missing: Boolean,
)

data class AnnouncementItem(
    val id: Long,
    val courseId: Long,
    val courseName: String,
    val title: String,
    val postedAt: Instant,
    val url: String?,
    /** HTML을 텍스트로 바꾼 본문(최대 600자). 신뢰할 수 없는 데이터이므로 알림에는 그대로 쓰되 지시로 해석하지 않는다. */
    val text: String,
)

/** 한 번 조회한 결과. */
data class Snapshot(
    val takenAt: Instant,
    val assignments: List<AssignmentItem>,
    val announcements: List<AnnouncementItem>,
    /** 일부 강의 조회 실패 등(사용자에게 보일 수 있는 짧은 문구). 토큰이나 서명 URL은 절대 넣지 않는다. */
    val errors: List<String> = emptyList(),
)

enum class Channel(val id: String) {
    DEADLINE("deadline"),
    ANNOUNCEMENT("announcement"),
    BRIEF("brief"),
}

/**
 * 띄울 알림 하나.
 * [firedKeys]는 이 알림을 **실제로 띄운 뒤** [NotifyState.fired]에 기록할 키들이다(중복 방지).
 */
data class Notice(
    val channel: Channel,
    val title: String,
    val text: String,
    /** 펼쳤을 때 보이는 긴 본문. null이면 [text]만. */
    val bigText: String? = null,
    /** "eTL에서 열기" 액션이 여는 주소(https://myetl.snu.ac.kr/… 만). null이면 액션 없음. */
    val url: String? = null,
    /** 같은 키의 알림은 갱신(교체)된다. 정수 알림 ID는 이 키의 해시로 만든다. */
    val tag: String,
    val firedKeys: List<String> = emptyList(),
)

/**
 * 알림 중복 방지와 마지막 확인 상태. 앱 내부 저장소에 JSON으로 저장한다([NotifyStateStore]).
 * 키 형식: `deadline:{courseId}:{assignmentId}:{windowHours}`, `ann:{announcementId}`.
 */
data class NotifyState(
    /** 키 → 띄운 시각(epoch millis). 30일이 지난 항목은 정리한다. */
    val fired: MutableMap<String, Long> = mutableMapOf(),
    /** 공지 기준선을 잡았는가. false인 첫 확인에서는 기존 공지를 알리지 않고 기록만 한다. */
    var announcementBaselineDone: Boolean = false,
    var lastCheckAt: Long = 0L,
    /** 마지막 확인 결과 한 줄(설정 화면에 보인다). 예: "성공 · 마감 알림 1건" / "실패 · eTL이 토큰을 거부했습니다". */
    var lastResult: String = "",
    var lastBriefAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fired", JSONObject().also { o -> fired.forEach { (k, v) -> o.put(k, v) } })
        .put("announcementBaselineDone", announcementBaselineDone)
        .put("lastCheckAt", lastCheckAt)
        .put("lastResult", lastResult)
        .put("lastBriefAt", lastBriefAt)

    /** [olderThanMillis]보다 오래된 기록을 지운다. */
    fun prune(nowMillis: Long, olderThanMillis: Long = 30L * 24 * 3600 * 1000) {
        fired.entries.removeAll { nowMillis - it.value > olderThanMillis }
    }

    companion object {
        fun fromJson(s: String?): NotifyState {
            if (s.isNullOrEmpty()) return NotifyState()
            return try {
                val o = JSONObject(s)
                val fired = mutableMapOf<String, Long>()
                o.optJSONObject("fired")?.let { f -> f.keys().forEach { k -> fired[k] = f.optLong(k) } }
                NotifyState(
                    fired = fired,
                    announcementBaselineDone = o.optBoolean("announcementBaselineDone", false),
                    lastCheckAt = o.optLong("lastCheckAt", 0L),
                    lastResult = o.optString("lastResult", ""),
                    lastBriefAt = o.optLong("lastBriefAt", 0L),
                )
            } catch (_: Exception) {
                NotifyState()
            }
        }
    }
}

/** 알림 설정. 저장은 [NotifySettingsStore]. */
data class NotifySettings(
    val deadlineAlerts: Boolean = true,
    val announcementAlerts: Boolean = true,
    /** 마감 몇 시간 전에 알릴지(내림차순, 예: 72, 24). */
    val deadlineWindowsHours: List<Int> = listOf(72, 24),
    val briefEnabled: Boolean = false,
    /** 브리핑을 보낼 시각(하루 중 분, 0..1439). 최대 2개. 기기의 현재 시간대 기준. */
    val briefMinutesOfDay: List<Int> = listOf(8 * 60),
    /** 마감·공지 확인 주기(시간): 1, 3, 6, 12 중 하나. */
    val checkIntervalHours: Int = 3,
    /** 방해 금지 시간에는 마감·공지 알림을 미룬다(브리핑은 정해진 시각에 보낸다). */
    val quietEnabled: Boolean = true,
    val quietStartMinutes: Int = 23 * 60,
    val quietEndMinutes: Int = 7 * 60,
    /** 잠금화면에서는 내용을 숨기고 "eTL 알림"만 보인다. */
    val hideContentOnLockScreen: Boolean = true,
) {
    /** 어떤 알림이든 켜져 있는가(전부 꺼져 있으면 예약 작업을 만들지 않는다). */
    val anyEnabled: Boolean get() = deadlineAlerts || announcementAlerts || briefEnabled
    /** 마감·공지 주기 확인이 필요한가(브리핑도 데이터가 필요하므로 브리핑만 켜도 브리핑 시점에 조회한다). */
    val needsPeriodicCheck: Boolean get() = deadlineAlerts || announcementAlerts
}

/** 방해 금지 시간 판정(자정을 넘는 구간 포함). [minuteOfDay]는 0..1439. */
object Quiet {
    fun isQuiet(minuteOfDay: Int, s: NotifySettings): Boolean {
        if (!s.quietEnabled) return false
        val a = s.quietStartMinutes
        val b = s.quietEndMinutes
        return when {
            a == b -> false
            a < b -> minuteOfDay in a until b
            else -> minuteOfDay >= a || minuteOfDay < b
        }
    }
}

fun JSONArray.toIntList(): List<Int> = (0 until length()).map { optInt(it) }
