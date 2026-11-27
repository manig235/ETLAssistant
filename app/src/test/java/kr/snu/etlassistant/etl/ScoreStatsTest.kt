package kr.snu.etlassistant.etl

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 성적 통계: score_statistics 파싱, 위치 어림(사분위·정규분포·평균만), 동점·잘못된 값, 저장 형식과 합치기, 인자 검사. */
class ScoreStatsTest {

    private fun st(min: Double? = null, max: Double? = null, mean: Double? = null, q1: Double? = null, median: Double? = null, q3: Double? = null, sd: Double? = null) =
        ScoreStatistics(min, max, mean, q1, median, q3, sd)

    // ---- 파싱 -----------------------------------------------------------------

    @Test fun parsesOldCanvasMinMaxMean() {
        val s = ScoreStats.parse(JSONObject().put("min", 12.5).put("max", 98).put("mean", 61.25))!!
        assertEquals(12.5, s.min!!, 0.0)
        assertEquals(98.0, s.max!!, 0.0)
        assertEquals(61.25, s.mean!!, 0.0)
        assertFalse(s.hasQuartiles)
    }

    @Test fun parsesNewCanvasQuartiles() {
        val s = ScoreStats.parse(JSONObject().put("min", 0).put("max", 100).put("mean", 60).put("lower_q", 40).put("median", 62).put("upper_q", 80))!!
        assertEquals(40.0, s.q1!!, 0.0)
        assertEquals(62.0, s.median!!, 0.0)
        assertEquals(80.0, s.q3!!, 0.0)
        assertTrue(s.hasQuartiles)
    }

    @Test fun emptyOrNullStatisticsAreNull() {
        assertNull(ScoreStats.parse(null))
        assertNull(ScoreStats.parse(JSONObject()))
        assertNull(ScoreStats.parse(JSONObject().put("min", JSONObject.NULL)))
    }

    // ---- 위치 어림 --------------------------------------------------------------

    @Test fun meanOnlySaysDifferenceNotPercent() {
        val p = ScoreStats.position(st(min = 10.0, max = 95.0, mean = 71.0), 78.0)!!
        assertEquals(ScorePosition.Kind.MEAN, p.kind)
        assertEquals("평균보다 7점 높아요", p.label)
        assertNull(p.topLow)
        assertFalse("%" in p.label)
        assertEquals("평균보다 3.5점 낮아요", ScoreStats.position(st(mean = 71.5), 68.0)!!.label)
        assertEquals("평균과 같아요", ScoreStats.position(st(mean = 71.0), 71.0)!!.label)
    }

    @Test fun meanOnlyMentionsMaxOrMinTie() {
        assertEquals("최고점과 같아요 · 평균보다 25점 높아요", ScoreStats.position(st(min = 10.0, max = 95.0, mean = 70.0), 95.0)!!.label)
        assertEquals("최저점과 같아요 · 평균보다 60점 낮아요", ScoreStats.position(st(min = 10.0, max = 95.0, mean = 70.0), 10.0)!!.label)
    }

    @Test fun noScoreOrNoReferenceGivesNull() {
        assertNull(ScoreStats.position(st(mean = 70.0), null))
        assertNull(ScoreStats.position(st(), 50.0))
    }

    @Test fun quartileInterpolationGivesTenPercentBand() {
        val s = st(min = 0.0, q1 = 40.0, median = 60.0, q3 = 80.0, max = 100.0)
        // 70은 중앙값(50%)과 Q3(75%)의 가운데 → 하위 62.5% → 상위 37.5% → 30~40%.
        val p = ScoreStats.position(s, 70.0)!!
        assertEquals(ScorePosition.Kind.QUARTILES, p.kind)
        assertEquals(30, p.topLow)
        assertEquals(40, p.topHigh)
        assertEquals("상위 약 30~40%", p.label)
        // 90 → 하위 87.5% → 상위 12.5% → 10~20%.
        assertEquals("상위 약 10~20%", ScoreStats.position(s, 90.0)!!.label)
        // 99 → 상위 1.25% → 0~10%.
        assertEquals("상위 약 10% 안", ScoreStats.position(s, 99.0)!!.label)
        // 10 → 하위 6.25% → 상위 93.75% → 90~100 → 하위 약 10% 안.
        assertEquals("하위 약 10% 안", ScoreStats.position(s, 10.0)!!.label)
    }

    @Test fun tiesAcrossEqualQuartilesWidenTheBand() {
        // 동점이 많아 Q3 = 최고 = 100: 100점은 하위 75~100% → 상위 0~25% → "상위 약 30% 안".
        val s = st(min = 20.0, q1 = 60.0, median = 80.0, q3 = 100.0, max = 100.0)
        val p = ScoreStats.position(s, 100.0)!!
        assertEquals(0, p.topLow)
        assertEquals(30, p.topHigh)
        assertEquals("상위 약 30% 안", p.label)
        // 중앙값 = Q1 = 70인 점수 70: 하위 25~50% → 상위 50~75% → 50~80%.
        val flat = ScoreStats.position(st(min = 0.0, q1 = 70.0, median = 70.0, q3 = 90.0, max = 100.0), 70.0)!!
        assertEquals(50, flat.topLow)
        assertEquals(80, flat.topHigh)
    }

    @Test fun missingEndsUseWholeOuterQuarter() {
        // 최저·최고 없이 Q1·Q3만: Q3보다 높으면 상위 0~25% → 상위 약 30% 안.
        val s = st(q1 = 40.0, median = 55.0, q3 = 70.0)
        val p = ScoreStats.position(s, 90.0)!!
        assertEquals(0, p.topLow)
        assertEquals(30, p.topHigh)
        val low = ScoreStats.position(s, 10.0)!!
        assertEquals(70, low.topLow)
        assertEquals(100, low.topHigh)
        assertEquals("하위 약 30% 안", low.label)
    }

    @Test fun inconsistentQuartilesFallBackToMean() {
        val p = ScoreStats.position(st(min = 50.0, q1 = 40.0, median = 30.0, mean = 45.0), 60.0)!!
        assertEquals(ScorePosition.Kind.MEAN, p.kind)
    }

    @Test fun normalApproximationOnlyWithStddevAndLabelled() {
        // 평균 60, 표준편차 10, 점수 70 → z=1 → 하위 84.1% → 상위 15.9% → 10~20%.
        val p = ScoreStats.position(st(mean = 60.0, sd = 10.0), 70.0)!!
        assertEquals(ScorePosition.Kind.NORMAL, p.kind)
        assertEquals("상위 약 10~20% (정규분포 가정)", p.label)
        assertEquals(0.8413, ScoreStats.normalCdf(1.0), 1e-3)
        assertEquals(0.5, ScoreStats.normalCdf(0.0), 1e-6)
        assertEquals(0.1587, ScoreStats.normalCdf(-1.0), 1e-3)
    }

    @Test fun quartilesWinOverNormal() {
        val p = ScoreStats.position(st(min = 0.0, q1 = 40.0, median = 60.0, q3 = 80.0, max = 100.0, mean = 60.0, sd = 10.0), 70.0)!!
        assertEquals(ScorePosition.Kind.QUARTILES, p.kind)
    }

    @Test fun summaryLineMatchesAlertFormat() {
        assertEquals("평균 71 · 내 점수 78 · 평균보다 7점 높아요", ScoreStats.summaryLine(st(min = 10.0, max = 95.0, mean = 71.0), 78.0))
        assertEquals("평균 60 · 내 점수 70 · 상위 약 30~40%",
            ScoreStats.summaryLine(st(min = 0.0, q1 = 40.0, median = 60.0, q3 = 80.0, max = 100.0, mean = 60.0), 70.0))
        assertEquals("평균 61.3", ScoreStats.summaryLine(st(mean = 61.25), null))
    }

    @Test fun statsTextUsesPlainWords() {
        assertEquals("최저 0 · 하위 25% 40 · 중간 60 · 상위 25% 80 · 최고 100 · 평균 58.4",
            ScoreStats.statsText(st(min = 0.0, q1 = 40.0, median = 60.0, q3 = 80.0, max = 100.0, mean = 58.44)))
    }

    @Test fun barPositions() {
        val b = ScoreStats.bar(st(min = 20.0, max = 100.0, mean = 60.0), 80.0, 100.0)!!
        assertEquals(0.5f, b.mean!!, 1e-4f)
        assertEquals(0.75f, b.my!!, 1e-4f)
        assertTrue(b.hasMin && b.hasMax)
        // 최저·최고가 없으면 0~만점.
        val p = ScoreStats.bar(st(mean = 50.0), 25.0, 100.0)!!
        assertEquals(0.5f, p.mean!!, 1e-4f)
        assertNull(ScoreStats.bar(st(min = 5.0, max = 5.0), null, null))
    }

    // ---- 검사 ----------------------------------------------------------------

    @Test fun validateRejectsBadNumbers() {
        assertNull(ScoreStats.validate(st(min = 0.0, q1 = 40.0, median = 60.0, q3 = 80.0, max = 100.0, mean = 59.0, sd = 15.0), 70.0, 100.0))
        assertNotNull(ScoreStats.validate(st(), 70.0, 100.0))
        assertNotNull(ScoreStats.validate(st(q1 = 80.0, q3 = 40.0), null, null))
        assertNotNull(ScoreStats.validate(st(min = 10.0, max = 50.0, mean = 70.0), null, null))
        assertNotNull(ScoreStats.validate(st(mean = 70.0, sd = 0.0), null, null))
        assertNotNull(ScoreStats.validate(st(mean = -1.0), null, null))
        assertNotNull(ScoreStats.validate(st(mean = 60.0, max = 120.0), 70.0, 100.0))
        assertNotNull(ScoreStats.validate(st(mean = 60.0), 70.0, 0.0))
    }

    // ---- 합치기·저장 ----------------------------------------------------------------

    private fun etl(aid: Long, mean: Double = 70.0, my: Double? = 78.0, at: Long = 1L) =
        ScoreStats.fromEtl(5, "제어공학개론", aid, "중간고사", st(min = 10.0, max = 95.0, mean = mean), my, 100.0, at)!!

    @Test fun rowsMergeAnnouncementQuartilesIntoEtlEntry() {
        val ann = ScoreStatEntry("manual:1", ScoreSource.ANNOUNCEMENT, 5, "제어공학개론", 7, "중간고사",
            st(q1 = 55.0, median = 70.0, q3 = 82.0), null, null, 2L, sourceAnnouncementId = 99)
        val rows = ScoreStats.rows(listOf(etl(7), ann, etl(8)))
        assertEquals(2, rows.size)
        val merged = rows.first { "manual:1" in it.ids }
        assertEquals(listOf("manual:1", "etl:5:7"), merged.ids)
        assertEquals("eTL+공지", merged.sourceLabel)
        assertEquals(10.0, merged.entry.stats.min!!, 0.0)
        assertEquals(55.0, merged.entry.stats.q1!!, 0.0)
        assertEquals(78.0, merged.entry.myScore!!, 0.0)
        assertEquals(ScorePosition.Kind.QUARTILES, ScoreStats.position(merged.entry.stats, merged.entry.myScore)!!.kind)
    }

    @Test fun mergeEtlReplacesSameIdKeepsOthersAndRespectsDeleted() {
        val manual = ScoreStatEntry("manual:1", ScoreSource.MANUAL, null, "물리", null, "기말", st(mean = 50.0), 60.0, 100.0, 1L)
        var state = ScoreStats.State(listOf(etl(7, mean = 60.0), manual), hidden = setOf("etl:5:9"))
        state = ScoreStats.mergeEtl(state, listOf(etl(7, mean = 65.0, at = 5L), etl(9)))
        assertEquals(2, state.items.size)
        assertEquals(65.0, state.items.first { it.id == "etl:5:7" }.stats.mean!!, 0.0)
        assertTrue(state.items.any { it.id == "manual:1" })
        assertFalse(state.items.any { it.id == "etl:5:9" })
    }

    @Test fun jsonRoundTrip() {
        val e = ScoreStatEntry("manual:3", ScoreSource.ANNOUNCEMENT, 5, "회로", 11, "Exam 1",
            ScoreStatistics(1.0, 99.0, 55.5, 40.0, 56.0, 70.0, 12.0, 42), 61.0, 100.0, 123L, 77)
        val back = ScoreStats.fromJson(ScoreStats.toJson(ScoreStats.State(listOf(e, etl(7)), setOf("etl:1:2"))))
        assertEquals(listOf(e, etl(7)), back.items)
        assertEquals(setOf("etl:1:2"), back.hidden)
        assertEquals(ScoreStats.State(), ScoreStats.fromJson("not json"))
    }

    @Test fun trimDropsOldestEtlFirst() {
        val many = (1..(ScoreStats.MAX_ITEMS + 5)).map { etl(it.toLong(), at = it.toLong()) } +
            ScoreStatEntry("manual:1", ScoreSource.MANUAL, null, "x", null, "y", st(mean = 1.0), null, null, 0L)
        val kept = ScoreStats.trim(many)
        assertEquals(ScoreStats.MAX_ITEMS, kept.size)
        assertTrue(kept.any { it.id == "manual:1" })
        assertFalse(kept.any { it.id == "etl:5:1" })
    }

    @Test fun toolJsonHasPositionAndNoExtraNames() {
        val o = ScoreStats.toolJson(ScoreStats.rows(listOf(etl(7))).single())
        assertEquals("평균보다 8점 높아요", o.getString("position"))
        assertEquals("eTL", o.getString("source"))
        assertEquals(setOf("min", "max", "mean"), o.getJSONObject("stats").keys().asSequence().toSet())
    }
}
