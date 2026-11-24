package kr.snu.etlassistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kr.snu.etlassistant.notify.AssignmentWatch
import kr.snu.etlassistant.notify.MyDeadline
import kr.snu.etlassistant.notify.MyDeadlineStore
import kr.snu.etlassistant.notify.MyDeadlines
import kr.snu.etlassistant.notify.NotificationPoster
import kr.snu.etlassistant.notify.NotifySettingsStore
import kr.snu.etlassistant.notify.Snapshot
import kr.snu.etlassistant.notify.SnapshotCache
import kr.snu.etlassistant.notify.WatchRecord
import kr.snu.etlassistant.notify.WatchStore
import java.time.Instant

/**
 * 디버그 빌드 전용: 확인용 알림을 실제 코드 경로로 띄운다(사용법은 docs/phase3-log.md "확인용 알림").
 *
 *   adb shell am broadcast -n kr.snu.etlassistant.phase0/kr.snu.etlassistant.debug.TestNotifyReceiver \
 *       --es what submit_ok|mine|all|cleanup [--es assignment 305954:392608] [--el mine_id N] [--ez force true] [--ez create_temp true]
 *
 * - submit_ok: 받아 둔 과제 목록(SnapshotCache)의 제출된 과제를 지난 기록에서 "아직 제출 전"으로 바꾼 상태로
 *   [AssignmentWatch.diff]에 넣어 나온 알림을 [NotificationPoster.post]로 띄운다.
 * - mine: 저장된 내 마감(가까운 미완료 회차) 하나에 "지금 울릴 알림 시각"을 임시로 붙여 [MyDeadlines.reminderNotices]로 만든다.
 *   내 마감이 없으면 아무것도 만들지 않는다(create_temp=true일 때만 "테스트 마감 (지워도 돼요)"를 만들고, cleanup으로 지운다).
 *
 * 기준선(WatchStore)과 중복 방지 키(NotifyState)는 저장하지 않는다. 알림 기록(NotifyHistory)에는 post가 평소처럼 남긴다.
 * 네트워크와 eTL 요청은 없다.
 */
class TestNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.getStringExtra("what") ?: "all"
        val out = mutableListOf<String>()
        runCatching {
            if (what == "submit_ok" || what == "all") out += submitOk(context, intent.getStringExtra("assignment"))
            if (what == "mine" || what == "all") out += mine(context, intent.getLongExtra("mine_id", 0L), intent.getBooleanExtra("create_temp", false), intent.getBooleanExtra("force", false))
            if (what == "cleanup") out += cleanup(context)
        }.onFailure { out += "error: ${it.javaClass.simpleName}" }
        resultData = out.joinToString(" | ")
    }

    private fun submitOk(context: Context, wanted: String?): String {
        val cached = SnapshotCache(context).load() ?: return "submit_ok: 받아 둔 과제 목록 없음"
        val a = cached.assignments.firstOrNull { wanted != null && "${it.courseId}:${it.assignmentId}" == wanted }
            ?: cached.assignments.filter { wanted == null && it.submittedAt != null && it.check != null && it.check?.problems.isNullOrEmpty() }
                .maxByOrNull { it.submittedAt!! }
            ?: return "submit_ok: 대상 과제 없음"
        if (a.submittedAt == null || a.check == null) return "submit_ok: ${a.courseId}:${a.assignmentId} 제출·점검 정보 없음"
        val real = WatchStore(context).load()
        val key = "${a.courseId}:${a.assignmentId}"
        // 이 과제만 "지난 확인 때 제출 전"이었던 것처럼(저장하지 않는다).
        val prev = real.copy(
            initialized = true, submitInitialized = true,
            records = real.records + (key to (real.records[key]?.copy(submittedAt = null)
                ?: WatchRecord(a.name, a.courseName, a.due?.toEpochMilli(), a.score, null))),
        )
        val (_, notices) = AssignmentWatch.diff(prev, Snapshot(Instant.now(), listOf(a), emptyList()), Instant.now(),
            changes = false, submits = true, submitOk = true)
        val n = notices.firstOrNull() ?: return "submit_ok: 알림이 만들어지지 않음"
        val ok = NotificationPoster.post(context, n, NotifySettingsStore(context).load())
        return "submit_ok: ${n.channel.id} tag=${n.tag} posted=$ok"
    }

    private fun mine(context: Context, mineId: Long, createTemp: Boolean, force: Boolean): String {
        val now = Instant.now()
        val store = MyDeadlineStore(context)
        var list = store.load()
        var note = ""
        if (list.isEmpty() && createTemp) {
            store.upsert(MyDeadline(id = now.toEpochMilli(), title = TEMP_TITLE, due = now.plusSeconds(2 * 3600)))
            list = store.load()
            note = " (임시 항목 만듦)"
        }
        val candidates = MyDeadlines.active(list, now).filter { !it.done && it.due.isAfter(now) }.sortedBy { it.due }
        val picked = if (mineId != 0L) candidates.firstOrNull { it.id == mineId || MyDeadlines.itemId(it) == mineId } else candidates.firstOrNull()
        // force: 미완료·앞으로의 항목이 없으면 저장된 항목(반복 없는 것 우선)을 알림 안에서만 "2시간 뒤 마감, 미완료"로 바꿔 쓴다(저장하지 않음).
        val stored = if (picked == null && force) list.filter { mineId == 0L || it.id == mineId }.sortedWith(compareBy({ it.repeat != null }, { -it.due.toEpochMilli() })).firstOrNull() else null
        val d = picked ?: stored?.copy(due = now.plusSeconds(2 * 3600), done = false)
            ?: return "mine: 내 마감 ${list.size}개, 알림 보낼 미완료 항목 없음(force=true면 저장된 항목을 알림 안에서만 바꿔 씀)"
        if (stored != null) note += " (force: 저장된 항목을 알림 안에서만 2시간 뒤·미완료로 바꿈)"
        // 반복 회차는 이미 펼친 값이라 repeat를 떼고 넘긴다(occurrence는 남아 itemId가 회차 ID가 된다).
        val probe = d.copy(repeat = null, reminders = listOf(now.minusSeconds(60)))
        val n = MyDeadlines.reminderNotices(listOf(probe), now, emptySet()).firstOrNull() ?: return "mine: 알림이 만들어지지 않음"
        val ok = NotificationPoster.post(context, n, NotifySettingsStore(context).load())
        return "mine: ${n.channel.id} tag=${n.tag} item=${n.mineItemId} exam=${d.isExam} total=${list.size} posted=$ok$note"
    }

    private fun cleanup(context: Context): String {
        val store = MyDeadlineStore(context)
        val temp = store.load().filter { it.title == TEMP_TITLE }
        temp.forEach { store.delete(it.id) }
        return "cleanup: 임시 항목 ${temp.size}개 지움"
    }

    private companion object {
        const val TEMP_TITLE = "테스트 마감 (지워도 돼요)"
    }
}
