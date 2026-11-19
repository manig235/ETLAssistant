package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.phase0.CodexRunner
import java.time.Instant

/**
 * 워커와 설정 화면의 "지금 확인"이 함께 쓰는 실행기. AI(Codex)는 쓰지 않고 [SnapshotFetcher]와
 * [NotificationPlanner]/[BriefBuilder]만 쓴다. 토큰·서명 URL·응답 본문은 로그와 결과 문구에 남기지 않는다.
 */
object NotifyRunner {
    enum class Outcome { SUCCESS, RETRY, SKIPPED, FAILED }

    private const val AUTH_KEY = "authfail"

    fun runCheck(context: Context, now: Instant = Instant.now()): Outcome {
        val app = context.applicationContext
        val stateStore = NotifyStateStore(app)
        return guarded(app, stateStore, now) {
            val settings = NotifySettingsStore(app).load()
            NotificationPoster.ensureChannels(app)
            val token = EtlService.tokens(app).load()
            if (token.isNullOrEmpty()) return@guarded skipped(stateStore, now, "건너뜀 · eTL 토큰 없음")
            if (!NotificationPoster.canPost(app)) {
                // 중복 키를 기록하지 않는다: 권한을 줄 때까지 계속 시도한다.
                return@guarded skipped(stateStore, now, "건너뜀 · 알림 권한 없음")
            }
            val snapshot = SnapshotFetcher.fetch(EtlClient(token), now, announcementDays = 2)
            val plan = NotificationPlanner.plan(snapshot, stateStore.load(), now, settings)
            plan.notices.forEach { NotificationPoster.post(app, it, settings) }
            val nowMs = now.toEpochMilli()
            val deadlines = plan.notices.count { it.channel == Channel.DEADLINE }
            val anns = plan.notices.count { it.channel == Channel.ANNOUNCEMENT }
            stateStore.update { s ->
                plan.notices.forEach { n -> n.firedKeys.forEach { s.fired[it] = nowMs } }
                plan.silentKeys.forEach { s.fired[it] = nowMs }
                if (plan.markBaselineDone) s.announcementBaselineDone = true
                s.lastCheckAt = nowMs
                s.lastResult = "성공 · 마감 알림 ${deadlines}건, 공지 알림 ${anns}건"
                s.prune(nowMs)
            }
            log("확인 완료: 마감 $deadlines, 공지 $anns, 조회 오류 ${snapshot.errors.size}")
            Outcome.SUCCESS
        }
    }

    fun runBrief(context: Context, now: Instant = Instant.now(), scheduledAtMillis: Long? = null): Outcome {
        val app = context.applicationContext
        val stateStore = NotifyStateStore(app)
        return guarded(app, stateStore, now) {
            if (scheduledAtMillis != null && NotifySchedule.isStale(scheduledAtMillis, now.toEpochMilli())) {
                return@guarded skipped(stateStore, now, "건너뜀 · 예정 시각이 지남")
            }
            val settings = NotifySettingsStore(app).load()
            NotificationPoster.ensureChannels(app)
            val token = EtlService.tokens(app).load()
            if (token.isNullOrEmpty()) return@guarded skipped(stateStore, now, "건너뜀 · eTL 토큰 없음")
            if (!NotificationPoster.canPost(app)) return@guarded skipped(stateStore, now, "건너뜀 · 알림 권한 없음")
            val snapshot = SnapshotFetcher.fetch(EtlClient(token), now, announcementDays = 1)
            // 브리핑은 방해 금지와 무관하게 정해진 시각에 보낸다.
            NotificationPoster.post(app, BriefBuilder.build(snapshot, now, settings), settings)
            val nowMs = now.toEpochMilli()
            stateStore.update { s ->
                s.lastBriefAt = nowMs
                s.lastResult = "브리핑 전송"
            }
            log("브리핑 전송")
            Outcome.SUCCESS
        }
    }

    /** 공통 예외 처리: 어떤 예외도 밖으로 새지 않는다. */
    private fun guarded(app: Context, stateStore: NotifyStateStore, now: Instant, block: () -> Outcome): Outcome {
        return try {
            block()
        } catch (e: EtlAuthException) {
            handleAuthFailure(app, stateStore, now)
        } catch (e: Throwable) {
            // 토큰·서명 URL·본문이 새지 않도록 EtlException의 message(가공된 문구)나 클래스명만 쓴다.
            val reason = when (e) {
                is EtlException -> e.message?.take(80)?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                else -> e.javaClass.simpleName
            }
            runCatching {
                stateStore.update { s ->
                    s.lastCheckAt = now.toEpochMilli()
                    s.lastResult = "실패 · $reason"
                }
            }
            log("실패: $reason")
            Outcome.RETRY
        }
    }

    private fun handleAuthFailure(app: Context, stateStore: NotifyStateStore, now: Instant): Outcome {
        val nowMs = now.toEpochMilli()
        runCatching {
            val last = stateStore.load().fired[AUTH_KEY]
            var posted = false
            if (NotifySchedule.authFailureDue(last, nowMs) && NotificationPoster.canPost(app)) {
                NotificationPoster.ensureChannels(app)
                NotificationPoster.post(
                    app,
                    Notice(
                        channel = Channel.DEADLINE,
                        title = "eTL 토큰 확인이 필요합니다",
                        text = "토큰이 만료되었거나 올바르지 않습니다. 설정에서 다시 저장하세요.",
                        tag = "auth",
                        firedKeys = listOf(AUTH_KEY),
                    ),
                    NotifySettingsStore(app).load(),
                )
                posted = true
            }
            stateStore.update { s ->
                if (posted) s.fired[AUTH_KEY] = nowMs
                s.lastCheckAt = nowMs
                s.lastResult = "실패 · eTL이 토큰을 거부했습니다"
            }
        }
        log("실패: 토큰 거부")
        return Outcome.FAILED
    }

    private fun skipped(stateStore: NotifyStateStore, now: Instant, result: String): Outcome {
        stateStore.update { s ->
            s.lastCheckAt = now.toEpochMilli()
            s.lastResult = result
        }
        log(result)
        return Outcome.SKIPPED
    }

    private fun log(msg: String) {
        runCatching { CodexRunner.log.line("[notify] $msg") }
    }
}
