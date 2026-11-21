package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.etl.Timetable
import kr.snu.etlassistant.etl.TimetableChangeStore
import kr.snu.etlassistant.etl.TimetableLogic
import kr.snu.etlassistant.etl.TimetableStore
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
            val mine = MyDeadlineStore(app).load()
            if (token.isNullOrEmpty() && mine.isEmpty()) return@guarded skipped(stateStore, now, "건너뜀 · eTL 토큰 없음")
            if (!NotificationPoster.canPost(app)) {
                // 중복 키를 기록하지 않는다: 권한을 줄 때까지 계속 시도한다.
                return@guarded skipped(stateStore, now, "건너뜀 · 알림 권한 없음")
            }
            // 내가 정한 알림 시각(내 마감)은 네트워크 없이 먼저 보낸다: eTL 조회가 실패해도 울려야 한다. 방해 금지·마감 알림 설정과 무관.
            val reminders = MyDeadlines.reminderNotices(mine, now, stateStore.load().fired.keys)
            if (reminders.isNotEmpty()) {
                reminders.forEach { NotificationPoster.post(app, it, settings) }
                stateStore.update { s -> reminders.forEach { n -> n.firedKeys.forEach { s.fired[it] = now.toEpochMilli() } } }
            }
            runCatching { DeadlineAlarm.scheduleEarlier(app, MyDeadlines.nextReminderAt(mine, now, stateStore.load().fired.keys)) }
            val etlSnap = fetchOrEmpty(token, now, announcementDays = 2)
            if (!token.isNullOrEmpty()) runCatching { SnapshotCache(app).save(etlSnap) } // "내 일정" 화면이 바로 보여 준다
            val snapshot = MyDeadlines.merge(etlSnap, mine, now)
            // 토큰이 없을 때(내 마감만 확인) 공지 기준선을 잡으면, 나중에 토큰을 넣었을 때 기존 공지가 한꺼번에 알려진다.
            val plan = NotificationPlanner.plan(snapshot, stateStore.load(), now, settings)
                .let { if (token.isNullOrEmpty()) it.copy(markBaselineDone = false) else it }
            val zone = java.time.ZoneId.systemDefault()
            val local = now.atZone(zone)
            val quiet = Quiet.isQuiet(local.hour * 60 + local.minute, settings)

            // 새 과제·마감 변경·성적(ttokttok-pet watch.js). 방해 금지 중에는 비교하지 않아 끝난 뒤 알린다.
            val changes = if (settings.changeAlerts && !token.isNullOrEmpty() && !quiet) {
                val watchStore = WatchStore(app)
                val (newState, notices) = AssignmentWatch.diff(watchStore.load(), snapshot, now)
                watchStore.save(newState)
                notices
            } else emptyList()

            // 수업 알림: 오늘·내일 시간표(하루 캐시, 보강·휴강 반영).
            var classDays = emptyList<TimetableLogic.Day>()
            if (settings.classAlerts && !token.isNullOrEmpty()) {
                classDays = try {
                    val etl = EtlClient(token)
                    val w = Timetable.cachedOrFetch(etl, TimetableStore(app), now.toEpochMilli())
                    val ch = TimetableChangeStore(app).load()
                    val today = local.toLocalDate()
                    listOf(TimetableLogic.day(w, today, ch), TimetableLogic.day(w, today.plusDays(1), ch))
                } catch (e: EtlAuthException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val firedNow = stateStore.load().fired.keys
            val classes = if (quiet) emptyList() else classDays.firstOrNull()
                ?.let { ClassNotices.plan(it, now, zone, settings.classLeadMinutes, firedNow) }.orEmpty()

            val all = plan.notices + changes + classes
            all.forEach { NotificationPoster.post(app, it, settings) }
            val nowMs = now.toEpochMilli()
            val deadlines = plan.notices.count { it.channel == Channel.DEADLINE }
            val anns = plan.notices.count { it.channel == Channel.ANNOUNCEMENT }
            stateStore.update { s ->
                all.forEach { n -> n.firedKeys.forEach { s.fired[it] = nowMs } }
                plan.silentKeys.forEach { s.fired[it] = nowMs }
                if (plan.markBaselineDone) s.announcementBaselineDone = true
                s.lastCheckAt = nowMs
                s.lastResult = "성공 · 마감 ${deadlines}건, 공지 ${anns}건" +
                    (if (changes.isNotEmpty()) ", 과제·성적 ${changes.size}건" else "") + (if (classes.isNotEmpty()) ", 수업 ${classes.size}건" else "")
                s.prune(nowMs)
            }
            // 다음 마감·수업 알림 시점에 맞춰 한 번 더 확인한다(주기 확인만으로는 확인 간격만큼 늦을 수 있다).
            val after = stateStore.load()
            val nextDeadline = NotificationPlanner.nextDeadlineCheckAt(snapshot, after, now, settings)
            val nextClass = if (settings.classAlerts) ClassNotices.nextAt(classDays, now, zone, settings.classLeadMinutes, after.fired.keys) else null
            val nextReminder = MyDeadlines.nextReminderAt(mine, now, after.fired.keys)
            runCatching { DeadlineAlarm.schedule(app, listOfNotNull(nextDeadline, nextClass, nextReminder).minOrNull()) }
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
            val mine = MyDeadlineStore(app).load()
            if (token.isNullOrEmpty() && mine.isEmpty()) return@guarded skipped(stateStore, now, "건너뜀 · eTL 토큰 없음")
            if (!NotificationPoster.canPost(app)) return@guarded skipped(stateStore, now, "건너뜀 · 알림 권한 없음")
            val etlSnap = fetchOrEmpty(token, now, announcementDays = 1)
            if (!token.isNullOrEmpty()) runCatching { SnapshotCache(app).save(etlSnap) }
            val snapshot = MyDeadlines.merge(etlSnap, mine, now)
            val classes = if (token.isNullOrEmpty()) emptyList() else todayClassLines(app, token, now)
            // 브리핑은 방해 금지와 무관하게 정해진 시각에 보낸다.
            NotificationPoster.post(app, BriefBuilder.build(snapshot, now, settings, todayClasses = classes), settings)
            val nowMs = now.toEpochMilli()
            stateStore.update { s ->
                s.lastBriefAt = nowMs
                s.lastResult = "브리핑 전송"
            }
            log("브리핑 전송")
            Outcome.SUCCESS
        }
    }

    /**
     * 오늘(기기 시간대 날짜) 수업 줄. 시간표는 하루 캐시를 쓰고, 실패해도 브리핑은 그대로 보낸다(수업 섹션만 빠진다).
     * 토큰 거부는 위로 올려 보내 기존 처리(토큰 확인 알림)를 따른다.
     */
    private fun todayClassLines(app: Context, token: String, now: Instant): List<String> = try {
        val etl = EtlClient(token)
        val w = Timetable.cachedOrFetch(etl, TimetableStore(app), now.toEpochMilli())
        val date = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val events = try { Timetable.calendarClasses(etl, w.courses, date) } catch (e: EtlAuthException) { throw e } catch (_: Exception) { emptyList() }
        val day = TimetableLogic.day(w, date, TimetableChangeStore(app).load(), events)
        TimetableLogic.briefLines(day.classes) + day.cancelled.map { "• ${it.start} ${it.course} 휴강" + (it.note?.let { n -> " ($n)" } ?: "") }
    } catch (e: EtlAuthException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** 토큰이 없으면(내 마감만 있는 경우) eTL을 조회하지 않고 빈 결과를 쓴다. */
    private fun fetchOrEmpty(token: String?, now: Instant, announcementDays: Int): Snapshot =
        if (token.isNullOrEmpty()) Snapshot(now, emptyList(), emptyList(), listOf("eTL 토큰 없음"))
        else SnapshotFetcher.fetch(EtlClient(token), now, announcementDays)

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
                        title = "eTL 토큰을 확인해 주세요",
                        text = "토큰이 만료됐거나 올바르지 않아요. 설정에서 다시 저장해 주세요.",
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
                s.lastResult = "실패 · eTL이 토큰을 거부했어요"
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
