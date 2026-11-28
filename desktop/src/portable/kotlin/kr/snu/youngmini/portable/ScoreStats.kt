// Ported from Android 0.3.8 (025ac84); platform storage is implemented in desktop/.
package kr.snu.youngmini.portable

import kr.snu.etlassistant.etl.*

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * 성적 통계(2026-11-28). 시험·과제의 점수 분포(최저·최고·평균, 있으면 사분위·표준편차)를 과목별로 폰에 저장하고
 * 내 점수가 어디쯤인지 **범위로만** 어림한다(정확한 등수는 말하지 않는다).
 *
 * 출처
 * - eTL: 과제 목록의 `include[]=score_statistics`. 서울대 eTL(옛 Canvas)은 min/max/mean만 준다(S22 실측: 사분위 키 없음).
 *   교수가 분포를 숨겼거나 채점된 학생이 적으면 오지 않는다.
 * - 공지·직접: 공지 본문이나 히스토그램 그림에서 비서가 숫자를 읽어 `score_stats_save`로 저장한다(사용자가 요청할 때만).
 *
 * 순수 로직은 [ScoreStats], 저장은 [ScoreStatsStore](SharedPreferences `score_stats`, eTL 연결 해제 때 지운다).
 */

/** 점수 분포. 모르는 값은 null. 사분위는 [q1](하위 25% 지점)·[median]·[q3](상위 25% 지점). */
data class ScoreStatistics(
    val min: Double? = null,
    val max: Double? = null,
    val mean: Double? = null,
    val q1: Double? = null,
    val median: Double? = null,
    val q3: Double? = null,
    val stddev: Double? = null,
    /** 응시·채점 인원(알면). */
    val count: Int? = null,
) {
    val isEmpty: Boolean get() = listOf(min, max, mean, q1, median, q3, stddev).all { it == null }
    val hasQuartiles: Boolean get() = q1 != null || median != null || q3 != null

    /** [other]의 값으로 빈 칸만 채운다(내 값이 먼저). */
    fun fillFrom(other: ScoreStatistics): ScoreStatistics = ScoreStatistics(
        min ?: other.min, max ?: other.max, mean ?: other.mean, q1 ?: other.q1, median ?: other.median, q3 ?: other.q3,
        stddev ?: other.stddev, count ?: other.count,
    )
}

enum class ScoreSource(val id: String, val label: String) {
    ETL("etl", "eTL"), ANNOUNCEMENT("announcement", "공지"), MANUAL("manual", "직접");

    companion object {
        fun parse(id: String?): ScoreSource = entries.firstOrNull { it.id == id } ?: MANUAL
    }
}

/**
 * 저장된 통계 하나. eTL에서 온 것은 id `etl:{과목}:{과제}`, 비서·직접 저장은 `manual:{숫자}`.
 * [myScore]·[pointsPossible]은 분포와 같은 기준(배점)이어야 한다.
 */
data class ScoreStatEntry(
    val id: String,
    val source: ScoreSource,
    val courseId: Long?,
    val courseName: String,
    val assignmentId: Long?,
    val title: String,
    val stats: ScoreStatistics,
    val myScore: Double?,
    val pointsPossible: Double?,
    val fetchedAt: Long,
    val sourceAnnouncementId: Long? = null,
)

/** 화면·도구에 보일 한 줄: eTL 통계와 같은 과제에 공지에서 읽은 값을 합친 결과. [ids]는 지울 때 함께 지우는 저장 항목. */
data class ScoreRow(val entry: ScoreStatEntry, val ids: List<String>, val sources: List<ScoreSource>) {
    val sourceLabel: String get() = sources.joinToString("+") { it.label }
}

/**
 * 내 점수의 위치 어림.
 * - [Kind.QUARTILES]: 사분위로 구간을 나눠 그 안은 고르게 퍼졌다고 보고 계산(정확한 순위가 아니다).
 * - [Kind.NORMAL]: 평균·표준편차만 있을 때 정규분포로 가정(라벨에 "정규분포 가정"을 붙인다).
 * - [Kind.MEAN]: 평균(과 최저·최고)만 있을 때 평균과의 차이만 말한다.
 * [topLow]~[topHigh]는 "상위 몇 %" 구간(10% 단위). [Kind.MEAN]이면 null.
 */
data class ScorePosition(val kind: Kind, val label: String, val topLow: Int? = null, val topHigh: Int? = null, val diffFromMean: Double? = null) {
    enum class Kind { QUARTILES, NORMAL, MEAN }
}

object ScoreStats {
    private const val EPS = 1e-9

    /** eTL `score_statistics`(min/max/mean, 새 Canvas면 lower_q/median/upper_q). 숫자가 하나도 없으면 null. */
    fun parse(o: JSONObject?): ScoreStatistics? {
        if (o == null) return null
        fun d(k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { it.isFinite() }
        val s = ScoreStatistics(
            min = d("min"), max = d("max"), mean = d("mean"),
            q1 = d("lower_q"), median = d("median"), q3 = d("upper_q"),
            count = if (o.has("count") && !o.isNull("count")) o.optInt("count").takeIf { it > 0 } else null,
        )
        return s.takeIf { !it.isEmpty }
    }

    /** 숫자 표시: 정수면 그대로, 아니면 소수 한 자리(71.25 → 71.3). */
    fun num(d: Double): String {
        val r = Math.round(d * 10) / 10.0
        return if (abs(r - Math.rint(r)) < EPS) Math.rint(r).toLong().toString() else "%.1f".format(r)
    }

    /**
     * 내 점수 [my]의 위치. 사분위 → 평균·표준편차(정규분포 가정) → 평균 순으로 쓸 수 있는 것을 쓴다.
     * 점수나 기준 값이 없으면 null. 값의 순서가 맞지 않는 통계(최저 > 중앙값 등)는 사분위 계산에 쓰지 않는다.
     */
    fun position(s: ScoreStatistics, my: Double?): ScorePosition? {
        if (my == null || !my.isFinite()) return null
        quartileRange(s, my)?.let { (lo, hi) -> return band(ScorePosition.Kind.QUARTILES, lo, hi, "") }
        val sd = s.stddev
        if (s.mean != null && sd != null && sd > EPS) {
            val p = normalCdf((my - s.mean) / sd) * 100
            return band(ScorePosition.Kind.NORMAL, p, p, " (정규분포 가정)")
        }
        val mean = s.mean
        val facts = mutableListOf<String>()
        if (s.max != null && abs(my - s.max) < EPS) facts += "최고점과 같아요"
        else if (s.min != null && abs(my - s.min) < EPS) facts += "최저점과 같아요"
        if (mean != null) {
            val diff = my - mean
            facts += when {
                abs(diff) < 0.05 -> "평균과 같아요"
                diff > 0 -> "평균보다 ${num(diff)}점 높아요"
                else -> "평균보다 ${num(-diff)}점 낮아요"
            }
            return ScorePosition(ScorePosition.Kind.MEAN, facts.joinToString(" · "), diffFromMean = diff)
        }
        return facts.takeIf { it.isNotEmpty() }?.let { ScorePosition(ScorePosition.Kind.MEAN, it.joinToString(" · ")) }
    }

    /**
     * 사분위가 있을 때 내 점수의 "하위 몇 %" 범위(0~100). 점(min,0)…(Q1,25)…(중앙값,50)…(Q3,75)…(max,100) 사이를 직선으로 잇는다.
     * 같은 값이 여러 지점에 있으면(동점이 많음) 그 지점들 전체를 범위로 돌려준다. 알려진 지점 밖이면 그 바깥 구간 전체.
     */
    internal fun quartileRange(s: ScoreStatistics, my: Double): Pair<Double, Double>? {
        if (!s.hasQuartiles) return null
        val knots = listOfNotNull(s.min?.let { it to 0.0 }, s.q1?.let { it to 25.0 }, s.median?.let { it to 50.0 },
            s.q3?.let { it to 75.0 }, s.max?.let { it to 100.0 })
        if (knots.size < 2) return null
        if (knots.zipWithNext().any { (a, b) -> b.first < a.first - EPS }) return null
        val equal = knots.filter { abs(it.first - my) < EPS }
        if (equal.isNotEmpty()) return equal.first().second to equal.last().second
        val first = knots.first()
        val last = knots.last()
        if (my < first.first) return 0.0 to first.second
        if (my > last.first) return last.second to 100.0
        val (a, b) = knots.zipWithNext().first { (a, b) -> my > a.first && my < b.first }
        val p = a.second + (my - a.first) / (b.first - a.first) * (b.second - a.second)
        return p to p
    }

    /** 하위 % 범위를 "상위 약 lo~hi%"(10% 단위)로. */
    private fun band(kind: ScorePosition.Kind, bottomLow: Double, bottomHigh: Double, suffix: String): ScorePosition {
        val tLo = (100 - bottomHigh).coerceIn(0.0, 100.0)
        val tHi = (100 - bottomLow).coerceIn(0.0, 100.0)
        var lo: Int
        var hi: Int
        if (tHi - tLo < EPS) {
            lo = (floor(tLo / 10) * 10).toInt().coerceIn(0, 90)
            hi = lo + 10
        } else {
            lo = (floor(tLo / 10 + EPS) * 10).toInt().coerceIn(0, 90)
            hi = (ceil(tHi / 10 - EPS) * 10).toInt().coerceIn(lo + 10, 100)
        }
        return ScorePosition(kind, bandLabel(lo, hi) + suffix, lo, hi)
    }

    fun bandLabel(lo: Int, hi: Int): String = when {
        lo <= 0 -> "상위 약 ${hi}% 안"
        hi >= 100 && lo >= 50 -> "하위 약 ${100 - lo}% 안"
        else -> "상위 약 $lo~$hi%"
    }

    /** 표준정규 누적분포(Abramowitz–Stegun 7.1.26, 오차 1.5e-7). */
    internal fun normalCdf(z: Double): Double {
        val x = abs(z) / sqrt(2.0)
        val t = 1 / (1 + 0.3275911 * x)
        val erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (z >= 0) (1 + erf) / 2 else (1 - erf) / 2
    }

    /** "최저 12 · 하위 25% 40 · 중간 55 · 상위 25% 70 · 최고 98 · 평균 54.3 · 표준편차 15 · 42명". */
    fun statsText(s: ScoreStatistics): String = listOfNotNull(
        s.min?.let { "최저 ${num(it)}" }, s.q1?.let { "하위 25% ${num(it)}" }, s.median?.let { "중간 ${num(it)}" },
        s.q3?.let { "상위 25% ${num(it)}" }, s.max?.let { "최고 ${num(it)}" }, s.mean?.let { "평균 ${num(it)}" },
        s.stddev?.let { "표준편차 ${num(it)}" }, s.count?.let { "${it}명" },
    ).joinToString(" · ")

    /** 알림 한 줄: "평균 71 · 내 점수 78 · 상위 약 30~40%"(평균만 있으면 "… · 평균보다 7점 높아요"). */
    fun summaryLine(s: ScoreStatistics, my: Double?): String {
        val pos = position(s, my)
        return listOfNotNull(
            s.mean?.let { "평균 ${num(it)}" } ?: s.median?.let { "중간 ${num(it)}" },
            my?.let { "내 점수 ${num(it)}" },
            pos?.label,
        ).joinToString(" · ")
    }

    /** 막대 그림의 위치(0~1). 양 끝은 최저·최고(없으면 0·만점, 그것도 없으면 아는 값의 끝). 그릴 수 없으면 null. */
    data class Bar(val q1: Float?, val median: Float?, val q3: Float?, val mean: Float?, val my: Float?, val hasMin: Boolean, val hasMax: Boolean)

    fun bar(s: ScoreStatistics, my: Double?, points: Double?): Bar? {
        val known = listOfNotNull(s.min, s.q1, s.median, s.q3, s.max, s.mean, my)
        if (known.isEmpty()) return null
        val lo = s.min ?: 0.0.takeIf { known.all { it >= 0 } } ?: known.min()
        val hi = s.max ?: points?.takeIf { p -> known.all { it <= p } } ?: known.max()
        if (hi - lo < EPS) return null
        fun f(x: Double?) = x?.let { ((it - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat() }
        return Bar(f(s.q1), f(s.median), f(s.q3), f(s.mean), f(my), s.min != null, s.max != null)
    }

    /** 과제 목록에서 eTL 통계가 있는 과제만 저장 항목으로. */
    fun fromEtl(courseId: Long, courseName: String, assignmentId: Long, title: String, stats: ScoreStatistics?, my: Double?, points: Double?, nowMs: Long): ScoreStatEntry? =
        stats?.let { ScoreStatEntry(etlId(courseId, assignmentId), ScoreSource.ETL, courseId, courseName, assignmentId, title, it, my, points, nowMs) }

    fun etlId(courseId: Long, assignmentId: Long) = "etl:$courseId:$assignmentId"

    /**
     * 같은 과제(eTL 과목·과제 ID)의 공지·직접 항목은 eTL 항목과 합쳐 한 줄로(공지 값이 먼저, 빈 칸은 eTL 값).
     * 과목 이름순, 같은 과목은 최근 것부터.
     */
    fun rows(entries: List<ScoreStatEntry>): List<ScoreRow> {
        val etl = entries.filter { it.source == ScoreSource.ETL }.associateBy { it.courseId to it.assignmentId }
        val used = mutableSetOf<String>()
        val out = mutableListOf<ScoreRow>()
        for (e in entries.filter { it.source != ScoreSource.ETL }) {
            val base = e.assignmentId?.let { etl[e.courseId to it] }
            if (base == null) { out += ScoreRow(e, listOf(e.id), listOf(e.source)); continue }
            used += base.id
            out += ScoreRow(
                e.copy(stats = e.stats.fillFrom(base.stats), myScore = e.myScore ?: base.myScore,
                    pointsPossible = e.pointsPossible ?: base.pointsPossible, courseName = e.courseName.ifBlank { base.courseName }),
                listOf(e.id, base.id), listOf(base.source, e.source),
            )
        }
        entries.filter { it.source == ScoreSource.ETL && it.id !in used }.forEach { out += ScoreRow(it, listOf(it.id), listOf(it.source)) }
        return out.sortedWith(compareBy<ScoreRow> { it.entry.courseName }.thenByDescending { it.entry.fetchedAt })
    }

    /** 비서 도구 결과 한 줄. */
    fun toolJson(r: ScoreRow): JSONObject {
        val e = r.entry
        val s = e.stats
        val pos = position(s, e.myScore)
        return JSONObject().put("id", r.ids.first()).put("course", e.courseName).put("course_id", e.courseId ?: JSONObject.NULL)
            .put("assignment_id", e.assignmentId ?: JSONObject.NULL).put("title", e.title).put("source", r.sourceLabel)
            .put("stats", statsJson(s))
            .put("my_score", e.myScore ?: JSONObject.NULL).put("points_possible", e.pointsPossible ?: JSONObject.NULL)
            .put("position", pos?.label ?: JSONObject.NULL)
            .put("saved", EtlLogic.fmtKst(java.time.Instant.ofEpochMilli(e.fetchedAt)))
            .apply { e.sourceAnnouncementId?.let { put("source_announcement_id", it) } }
    }

    fun statsJson(s: ScoreStatistics): JSONObject = JSONObject().also { o ->
        s.min?.let { o.put("min", it) }; s.max?.let { o.put("max", it) }; s.mean?.let { o.put("mean", it) }
        s.q1?.let { o.put("q1", it) }; s.median?.let { o.put("median", it) }; s.q3?.let { o.put("q3", it) }
        s.stddev?.let { o.put("stddev", it) }; s.count?.let { o.put("count", it) }
    }

    // ---- 비서 저장(score_stats_save) 인자 검사 ------------------------------------------------

    const val MAX_TITLE = 60

    /** 값의 순서와 범위를 확인한다. 문제가 있으면 한국어 오류 문구, 없으면 null. */
    fun validate(s: ScoreStatistics, my: Double?, points: Double?): String? {
        val all = listOfNotNull(s.min, s.max, s.mean, s.q1, s.median, s.q3, s.stddev, my, points)
        if (all.any { !it.isFinite() || it < 0 }) return "점수는 0 이상의 숫자여야 해요"
        if (s.min == null && s.max == null && s.mean == null && !s.hasQuartiles) return "최저·최고·평균·사분위 중 하나는 있어야 해요"
        val ordered = listOfNotNull(s.min, s.q1, s.median, s.q3, s.max)
        if (ordered.zipWithNext().any { (a, b) -> b < a - EPS }) return "최저 ≤ 하위 25% ≤ 중간 ≤ 상위 25% ≤ 최고 순서가 맞지 않아요"
        if (s.mean != null && ((s.min != null && s.mean < s.min - EPS) || (s.max != null && s.mean > s.max + EPS))) return "평균이 최저·최고 사이에 있어야 해요"
        if (s.stddev != null && s.stddev <= 0) return "표준편차는 0보다 커야 해요"
        if (points != null && points <= 0) return "배점은 0보다 커야 해요"
        if (points != null && listOfNotNull(s.max, my).any { it > points + EPS }) return "점수가 배점보다 커요. 같은 기준(배점)인지 확인해 주세요"
        return null
    }

    // ---- 저장 형식 ------------------------------------------------------------------------

    data class State(val items: List<ScoreStatEntry> = emptyList(), val hidden: Set<String> = emptySet())

    const val MAX_ITEMS = 400

    /** eTL에서 새로 받은 통계를 합친다: 같은 id는 바꾸고, 사용자가 지운 eTL 항목([State.hidden])은 다시 넣지 않는다. 이번에 안 온 항목은 남긴다. */
    fun mergeEtl(state: State, fresh: List<ScoreStatEntry>): State {
        val byId = state.items.associateBy { it.id }.toMutableMap()
        for (e in fresh) if (e.id !in state.hidden) byId[e.id] = e
        return state.copy(items = trim(byId.values.toList()))
    }

    /** 너무 많으면 오래된 eTL 항목부터 뺀다(직접·공지 항목은 남긴다). */
    fun trim(items: List<ScoreStatEntry>): List<ScoreStatEntry> {
        if (items.size <= MAX_ITEMS) return items
        val drop = items.filter { it.source == ScoreSource.ETL }.sortedBy { it.fetchedAt }.take(items.size - MAX_ITEMS).map { it.id }.toSet()
        return items.filter { it.id !in drop }.takeLast(MAX_ITEMS)
    }

    fun toJson(state: State): String = JSONObject().put("v", 1)
        .put("hidden", JSONArray(state.hidden.sorted()))
        .put("items", JSONArray(state.items.map { e ->
            JSONObject().put("id", e.id).put("src", e.source.id).put("cid", e.courseId ?: JSONObject.NULL).put("course", e.courseName)
                .put("aid", e.assignmentId ?: JSONObject.NULL).put("title", e.title).put("stats", statsJson(e.stats))
                .put("my", e.myScore ?: JSONObject.NULL).put("pts", e.pointsPossible ?: JSONObject.NULL).put("at", e.fetchedAt)
                .put("ann", e.sourceAnnouncementId ?: JSONObject.NULL)
        })).toString()

    fun fromJson(raw: String?): State {
        val o = try { JSONObject(raw ?: return State()) } catch (_: Exception) { return State() }
        fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { it.isFinite() }
        fun JSONObject.lng(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k)
        val items = o.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val x = arr.optJSONObject(i) ?: return@mapNotNull null
                val st = x.optJSONObject("stats") ?: JSONObject()
                val stats = ScoreStatistics(st.dbl("min"), st.dbl("max"), st.dbl("mean"), st.dbl("q1"), st.dbl("median"), st.dbl("q3"),
                    st.dbl("stddev"), if (st.has("count")) st.optInt("count") else null)
                val id = x.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ScoreStatEntry(id, ScoreSource.parse(x.optString("src")), x.lng("cid"), x.optString("course"), x.lng("aid"), x.optString("title"),
                    stats, x.dbl("my"), x.dbl("pts"), x.optLong("at"), x.lng("ann"))
            }
        }.orEmpty()
        val hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        return State(items, hidden)
    }
}

/** 폰 안 저장소(SharedPreferences `score_stats`). eTL 연결 해제([EtlDisconnect]) 때 통째로 지운다. */
