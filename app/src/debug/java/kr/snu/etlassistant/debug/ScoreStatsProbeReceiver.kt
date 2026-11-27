package kr.snu.etlassistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kr.snu.etlassistant.etl.CourseAssignments
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.Queries
import kr.snu.etlassistant.etl.ScoreStatsParser
import kr.snu.etlassistant.etl.TokenStore
import java.time.Instant
import java.time.ZonedDateTime

/**
 * 디버그 빌드 전용: eTL이 과제 점수 분포(`include[]=score_statistics`)를 주는지 살펴본다(GET만).
 * 저장된 토큰으로 [EtlClient]를 그대로 쓰며, 로그에는 **집계 사실만** 남긴다: 과목별 통계가 있는 과제 수, 나타난 키 이름,
 * 내 점수(submission.score)가 있는지 여부, 통계가 있는 과제 이름. 점수 값·토큰은 남기지 않는다.
 * 최근 120일 공지 중 분포 관련 낱말이 있는 것은 과목과 제목만 남긴다. 기기 안 읽기([ScoreStatsParser])가 받아들이는지와
 * 읽은 칸 이름(min/mean…), eTL 과제와 맞는지·내 점수가 있는지 여부(값 없이)도 남긴다.
 *
 *   adb shell am broadcast -n kr.snu.etlassistant.phase0/kr.snu.etlassistant.debug.ScoreStatsProbeReceiver [--ez all_terms true]
 *   adb logcat -s ScoreProbe
 */
class ScoreStatsProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val allTerms = intent.getBooleanExtra("all_terms", false)
        val shape = intent.getBooleanExtra("shape", false)
        val app = context.applicationContext
        Thread {
            try {
                probe(app, allTerms, shape)
            } catch (e: Throwable) {
                Log.i(TAG, "error: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun probe(context: Context, allTerms: Boolean, shape: Boolean) {
        val token = TokenStore(context).load()
        if (token.isNullOrEmpty()) { Log.i(TAG, "토큰 없음"); return }
        val client = EtlClient(token)
        val all = client.getAll(CourseAssignments.COURSES_PATH)
        val courses = if (allTerms) all.filter { !it.optBoolean("access_restricted_by_date", false) } else EtlLogic.currentCourses(all)
        Log.i(TAG, "courses=${courses.size} (all=${all.size}, allTerms=$allTerms)")
        val keyTotals = sortedMapOf<String, Int>()
        var withStats = 0
        val mapped = mutableListOf<kr.snu.etlassistant.notify.AssignmentItem>()
        var graded = 0
        for (c in courses) {
            val cid = c.optLong("id")
            val name = c.optString("name")
            val term = c.optJSONObject("term")?.optString("name").orEmpty()
            val list = try {
                client.getAll("/api/v1/courses/$cid/assignments?include[]=submission&include[]=score_statistics&order_by=due_at&per_page=100")
            } catch (e: Exception) {
                Log.i(TAG, "[$term] $name: 실패 ${e.javaClass.simpleName}"); continue
            }
            mapped += kr.snu.etlassistant.notify.SnapshotFetcher.mapAssignments(cid, name, list, Instant.now())
            val stats = list.filter { it.has("score_statistics") && !it.isNull("score_statistics") }
            val gradedHere = list.count { a -> a.optJSONObject("submission")?.let { s -> s.has("score") && !s.isNull("score") } == true }
            graded += gradedHere
            withStats += stats.size
            val keys = sortedSetOf<String>()
            stats.forEach { a -> a.optJSONObject("score_statistics")?.keys()?.forEach { keys += it; keyTotals[it] = (keyTotals[it] ?: 0) + 1 } }
            Log.i(TAG, "[$term] $name: assignments=${list.size} graded(mine)=$gradedHere stats=${stats.size} keys=$keys")
            for (a in stats) {
                val s = a.optJSONObject("submission")
                val mine = s != null && s.has("score") && !s.isNull("score")
                Log.i(TAG, "   · ${a.optString("name")} | myScore=${if (mine) "있음" else "없음"} | points_possible=${if (a.isNull("points_possible")) "없음" else "있음"}")
            }
        }
        Log.i(TAG, "합계: graded(mine)=$graded withStats=$withStats keys=$keyTotals")

        // 공지: 최근 120일(현재 학기 과목만, 한 번에 최대 10과목씩).
        val cur = EtlLogic.currentCourses(all)
        val now = Instant.now()
        val kst = { t: Instant -> ZonedDateTime.ofInstant(t, EtlLogic.KST).toLocalDate().toString() }
        val names = cur.associate { it.optLong("id") to it.optString("name") }
        var annTotal = 0
        var hits = 0
        cur.chunked(10).forEach { chunk ->
            val ctx = chunk.joinToString("&") { "context_codes[]=course_${it.optLong("id")}" }
            val items = try {
                client.getAll("/api/v1/announcements?$ctx&start_date=${kst(now.minusSeconds(120L * 86400))}&end_date=${kst(now.plusSeconds(86400))}&per_page=50")
            } catch (e: Exception) { Log.i(TAG, "공지 실패 ${e.javaClass.simpleName}"); emptyList() }
            annTotal += items.size
            for (a in items) {
                val title = a.optString("title")
                val text = title + "\n" + EtlLogic.htmlToText(a.optString("message"), 20000)
                val found = KEYWORDS.filter { it.containsMatchIn(text) }.map { it.pattern }
                val img = Regex("<img", RegexOption.IGNORE_CASE).containsMatchIn(a.optString("message"))
                val cid = a.optString("context_code").substringAfterLast('_').toLongOrNull()
                if (found.isNotEmpty()) {
                    hits++
                    Log.i(TAG, "공지 · ${names[cid] ?: cid} · $title | 낱말=$found | 이미지=${if (img) "있음" else "없음"}")
                }
                // 기기 안 읽기(ScoreStatsParser): 받아들이는지, 항목·칸 이름, eTL 과제와 맞는지(점수 값은 남기지 않는다).
                val full = ScoreStatsParser.textFromHtml(a.optString("message"))
                if (Queries.mayHaveScoreStats(title, full)) {
                    val r = ScoreStatsParser.parse(title, full)
                    val item = kr.snu.etlassistant.notify.AnnouncementItem(a.optLong("id"), cid ?: 0, names[cid] ?: "", title, now, null, "", true, r)
                    val entries = kr.snu.etlassistant.notify.ScoreStatsAuto.entries(item, mapped, now.toEpochMilli())
                    Log.i(TAG, "읽기 · ${names[cid] ?: cid} · $title | confident=${r.confident}" + (r.reason?.let { " ($it)" } ?: "") +
                        " | 항목=" + r.items.joinToString("; ") { "${it.label ?: "-"}${it.section?.let { s -> "/${s}분반" } ?: ""}:${it.fieldNames}" } +
                        " | 저장 대상=${entries.size} 과제 맞춤=${entries.count { it.assignmentId != null }} 내 점수 있음=${entries.count { it.myScore != null }}")
                    // shape=true: 분포 낱말이 있는 줄의 모양만(숫자는 #으로 가림) 남겨 형식을 본다.
                    // 분포 낱말이 있는 줄만, 숫자는 #으로, 주소·메일이 있는 줄은 뺀다.
                    if (shape) full.split('\n').filter { l -> (KEYWORDS.any { it.containsMatchIn(l) } || '|' in l) && '@' !in l && "http" !in l }.take(25)
                        .forEach { l -> Log.i(TAG, "   모양: " + l.replace(Regex("\\d"), "#").take(160)) }
                }
            }
        }
        Log.i(TAG, "공지 합계: $annTotal 개 중 분포 관련 $hits 개")
    }

    private companion object {
        const val TAG = "ScoreProbe"
        val KEYWORDS = listOf("Q0", "Q1", "Q2", "Q3", "Q4", "average", "deviation", "평균", "표준편차", "히스토그램", "분포", "중앙값", "median", "mean", "std", "사분위", "최고점", "최저점")
            .map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
    }
}
