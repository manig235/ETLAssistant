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
            // 과목별 알림 끄기·숨기기: 그 과목은 알리지 않고 중복 방지 기록만 남긴다([CourseMute]).
            val courses = CourseFilterStore(app).load()
            val watchStore = WatchStore(app)
            val watchBefore = watchStore.load()
            // 제출 직후 점검: 새 제출인데 과제 목록에 첨부 목록이 없으면 그 제출만 `submissions/self`로 더 받는다.
            val needDetail: (AssignmentItem) -> Boolean = { a ->
                settings.submitAlerts && !courses.muted(a.courseId) && AssignmentWatch.isNewSubmission(watchBefore, a)
            }
            val fetched = fetchWithFallback(app, token, now, announcementDays = 2, needDetail)
            val failure = fetched.failure
            // eTL을 실제로 조회했는가. 아니면(토큰 없음, 연결 실패) 공지 기준선·새 과제/변경 비교를 하지 않는다.
            val etlOk = !token.isNullOrEmpty() && failure == null
            val snapshot = MyDeadlines.merge(fetched.snapshot, mine, now)
            // 토큰이 없을 때(내 마감만 확인) 공지 기준선을 잡으면, 나중에 토큰을 넣었을 때 기존 공지가 한꺼번에 알려진다.
            val plan = CourseMute.plan(snapshot, stateStore.load(), now, settings, courses)
                .let { if (!etlOk) it.copy(markBaselineDone = false) else it }
                .let { p -> fetched.cachedAt?.let { at -> p.copy(notices = p.notices.map { staleMark(it, at, now) }) } ?: p }
            val zone = java.time.ZoneId.systemDefault()
            val local = now.atZone(zone)
            val quiet = Quiet.isQuiet(local.hour * 60 + local.minute, settings)

            // 새 과제·마감 변경·성적(ttokttok-pet watch.js)과 제출 직후 점검. 방해 금지 중에는 비교하지 않아 끝난 뒤 알린다.
            val changes = if ((settings.changeAlerts || settings.submitAlerts) && etlOk && !quiet) {
                val (newState, notices) = AssignmentWatch.diff(
                    watchStore.load(), snapshot, now, courses::muted,
                    changes = settings.changeAlerts, submits = settings.submitAlerts,
                )
                watchStore.save(newState)
                val fired = stateStore.load().fired.keys
                notices.filter { n -> n.firedKeys.none { it in fired } }
            } else emptyList()

            // 수업 알림: 오늘·내일 시간표(하루 캐시, 보강·휴강 반영).
            var classDays = emptyList<TimetableLogic.Day>()
            if (settings.classAlerts && !token.isNullOrEmpty()) {
                classDays = try {
                    val etl = EtlClient(token)
                    val w = Timetable.cachedOrFetch(etl, TimetableStore(app), now.toEpochMilli())
                    val ch = TimetableChangeStore(app).load()
                    val today = local.toLocalDate()
                    listOf(TimetableLogic.day(w, today, ch), TimetableLogic.day(w, today.plusDays(1), ch)).map(courses::alertDay)
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
                val counts = "마감 ${deadlines}건, 공지 ${anns}건" +
                    (if (changes.isNotEmpty()) ", 과제·성적·제출 ${changes.size}건" else "") + (if (classes.isNotEmpty()) ", 수업 ${classes.size}건" else "")
                s.lastResult = when {
                    failure != null -> "실패 · ${reasonOf(failure)}" +
                        (fetched.cachedAt?.let { " · ${SnapshotFallback.ageLabel(it, now)} 받아 둔 과제로 마감 ${deadlines}건" } ?: "")
                    fetched.cachedAt != null -> "성공 · $counts · 일부 과목은 ${SnapshotFallback.ageLabel(fetched.cachedAt, now)} 정보"
                    else -> "성공 · $counts"
                }
                s.prune(nowMs)
            }
            // 다음 마감·수업 알림 시점에 맞춰 한 번 더 확인한다(주기 확인만으로는 확인 간격만큼 늦을 수 있다).
            val after = stateStore.load()
            val nextDeadline = NotificationPlanner.nextDeadlineCheckAt(courses.alertable(snapshot), after, now, settings)
            val nextClass = if (settings.classAlerts) ClassNotices.nextAt(classDays, now, zone, settings.classLeadMinutes, after.fired.keys) else null
            val nextReminder = MyDeadlines.nextReminderAt(mine, now, after.fired.keys)
            runCatching { DeadlineAlarm.schedule(app, listOfNotNull(nextDeadline, nextClass, nextReminder).minOrNull()) }
            logErrors(snapshot.errors, fetched.cachedAt)
            log("확인 완료: 마감 $deadlines, 공지 $anns, 조회 오류 ${snapshot.errors.size}")
            // 연결 실패면 받아 둔 정보로 보낼 것은 보냈지만, 새 공지·변경을 보려고 다시 시도한다(중복은 fired 키가 막는다).
            if (failure != null) Outcome.RETRY else Outcome.SUCCESS
        }
    }

    /**
     * [allowFallback]: 마지막 시도면 true. eTL에 연결하지 못했을 때 그 전 시도는 [Outcome.RETRY]로 다시 시도하고,
     * 마지막 시도에서는 받아 둔 과제 정보([SnapshotFallback])로 "N시간 전 정보" 표시를 붙여 보낸다(받아 둔 게 없으면 포기).
     */
    fun runBrief(context: Context, now: Instant = Instant.now(), scheduledAtMillis: Long? = null, allowFallback: Boolean = true): Outcome {
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
            val fetched = fetchWithFallback(app, token, now, announcementDays = 1)
            fetched.failure?.let { if (!allowFallback || fetched.cachedAt == null) throw it }
            // 일부만 연결 실패: 마지막 시도 전이면 보내지 않고 다시 시도한다(다음 시도는 대개 네트워크가 열린 작업으로 돈다).
            if (fetched.failure == null && !allowFallback && SnapshotFallback.hasConnectionErrors(fetched.snapshot.errors)) {
                logErrors(fetched.snapshot.errors, null)
                log("브리핑 미룸: 일부 조회가 연결 실패라 다시 시도")
                return@guarded Outcome.RETRY
            }
            // 숨긴 과목은 브리핑에서 뺀다(알림만 끈 과목은 그대로 보인다). 오늘 수업은 시간표를 따른다.
            val snapshot = CourseFilterStore(app).load().visible(MyDeadlines.merge(fetched.snapshot, mine, now))
            logErrors(snapshot.errors, fetched.cachedAt)
            val classes = if (token.isNullOrEmpty()) emptyList() else todayClassLines(app, token, now)
            // 브리핑은 방해 금지와 무관하게 정해진 시각에 보낸다.
            NotificationPoster.post(app, BriefBuilder.build(snapshot, now, settings, todayClasses = classes, staleSince = fetched.cachedAt), settings)
            val nowMs = now.toEpochMilli()
            stateStore.update { s ->
                s.lastBriefAt = nowMs
                s.lastResult = if (fetched.cachedAt != null) "브리핑 전송 · ${SnapshotFallback.ageLabel(fetched.cachedAt, now)} 받아 둔 과제 정보" else "브리핑 전송"
            }
            log("브리핑 전송" + (fetched.cachedAt?.let { " (받아 둔 정보: ${SnapshotFallback.ageLabel(it, now)})" } ?: ""))
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

    /**
     * eTL 조회 결과. [snapshot]은 내 마감을 합치기 전이고, 실패한 부분은 받아 둔 과제로 메웠을 수 있다([cachedAt]).
     * [failure]: 강의 목록부터 받지 못한 연결 실패(토큰 거부는 여기 오지 않고 그대로 던진다).
     */
    private class Fetched(val snapshot: Snapshot, val cachedAt: Instant?, val failure: EtlException?)

    /**
     * 토큰이 없으면(내 마감만 있는 경우) eTL을 조회하지 않고 빈 결과를 쓴다. 조회에 성공한 과목은 [SnapshotCache]에 저장하고
     * ("내 일정" 화면과 다음 폴백이 쓴다), 실패한 과목·전체 실패는 저장해 둔 과제로 메운다.
     */
    private fun fetchWithFallback(
        app: Context,
        token: String?,
        now: Instant,
        announcementDays: Int,
        needsSubmissionDetail: (AssignmentItem) -> Boolean = { false },
    ): Fetched {
        if (token.isNullOrEmpty()) return Fetched(Snapshot(now, emptyList(), emptyList(), listOf("eTL 토큰 없음")), null, null)
        val cache = SnapshotCache(app)
        val before = runCatching { cache.load() }.getOrNull()
        val fresh = try {
            SnapshotFetcher.fetch(EtlClient(token), now, announcementDays, needsSubmissionDetail)
        } catch (e: EtlAuthException) {
            throw e
        } catch (e: EtlException) {
            log("eTL 조회 실패: ${reasonOf(e)}")
            val empty = Snapshot(now, emptyList(), emptyList(), listOf("eTL 연결 실패"))
            val r = SnapshotFallback.apply(empty, before, now, totalFailure = true)
            return Fetched(r.snapshot, r.cachedAt, e)
        }
        runCatching { cache.save(fresh) }
        val r = SnapshotFallback.apply(fresh, before, now, totalFailure = false)
        return Fetched(r.snapshot, r.cachedAt, null)
    }

    /** 받아 둔 정보로 만든 마감 알림에 "N시간 전 정보" 줄을 붙인다. */
    private fun staleMark(n: Notice, cachedAt: Instant, now: Instant): Notice =
        if (n.channel != Channel.DEADLINE) n
        else n.copy(bigText = (n.bigText ?: n.text) + "\n\n" + SnapshotFallback.staleLine(cachedAt, now))

    /** 조회 오류를 앱 로그에 남긴다(강의명·경로·예외 클래스만. 토큰·서명 URL은 오류 문구에 들어가지 않는다). */
    private fun logErrors(errors: List<String>, cachedAt: Instant?) {
        if (errors.isEmpty()) return
        log("조회 오류 ${errors.size}건" + (if (cachedAt != null) "(받아 둔 과제로 메움)" else "") + ": " + errors.joinToString(" | ").take(600))
    }

    private fun reasonOf(e: Throwable): String = when (e) {
        is EtlException -> e.message?.take(80)?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        else -> e.javaClass.simpleName
    }

    /** 공통 예외 처리: 어떤 예외도 밖으로 새지 않는다. */
    private fun guarded(app: Context, stateStore: NotifyStateStore, now: Instant, block: () -> Outcome): Outcome {
        return try {
            block()
        } catch (e: EtlAuthException) {
            handleAuthFailure(app, stateStore, now)
        } catch (e: Throwable) {
            // 토큰·서명 URL·본문이 새지 않도록 EtlException의 message(가공된 문구)나 클래스명만 쓴다.
            val reason = reasonOf(e)
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
                        url = "${NoticeText.ETL_PREFIX}profile/settings",
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
