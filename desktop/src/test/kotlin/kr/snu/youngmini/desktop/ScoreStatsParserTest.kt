package kr.snu.youngmini.portable
import kr.snu.etlassistant.etl.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 성적 공지 본문 → 점수 분포(기기 안). 여러 형식, 여러 시험·분반, 날짜·시각 무시, 애매하면 저장하지 않기. */
class ScoreStatsParserTest {

    private fun parse(title: String, body: String) = ScoreStatsParser.parse(title, body)
    private fun one(title: String, body: String): ScoreStatsParser.Item {
        val r = parse(title, body)
        assertTrue("confident 아님: ${r.reason} ${r.items}", r.confident)
        assertEquals(r.items.toString(), 1, r.items.size)
        return r.items.single()
    }

    @Test fun koreanSentence() {
        val it = one("중간고사 결과 공지", "중간고사 평균 62.4점, 표준편차 15.1, 최고점 98점, 최저점 12점입니다. (100점 만점)")
        assertEquals("중간고사", it.label)
        assertEquals("mid", it.key)
        assertEquals(62.4, it.stats.mean!!, 0.0)
        assertEquals(15.1, it.stats.stddev!!, 0.0)
        assertEquals(98.0, it.stats.max!!, 0.0)
        assertEquals(12.0, it.stats.min!!, 0.0)
        assertEquals(100.0, it.points!!, 0.0)
    }

    @Test fun englishLinesWithQuartiles() {
        val it = one("Midterm Grades", "Midterm scores are now available.\nMean: 62.4 / Std: 15.1\nMax: 98, Min: 12\nQ1 48, Q2 63, Q3 77")
        assertEquals("mid", it.key)
        assertEquals(48.0, it.stats.q1!!, 0.0)
        assertEquals(63.0, it.stats.median!!, 0.0)
        assertEquals(77.0, it.stats.q3!!, 0.0)
        assertEquals(15.1, it.stats.stddev!!, 0.0)
    }

    @Test fun outOfAndTitleExamNumber() {
        val it = one("[I2EP] Exam1 성적 공개 및 클레임 공지", "Exam 1 statistics: average 71.3 out of 100, median 73, standard deviation 12.4. Claims until Dec 3.")
        assertEquals("exam1", it.key)
        assertEquals(71.3, it.stats.mean!!, 0.0)
        assertEquals(73.0, it.stats.median!!, 0.0)
        assertEquals(12.4, it.stats.stddev!!, 0.0)
        assertEquals(100.0, it.points!!, 0.0)
    }

    @Test fun slashPointsAndParenPoints() {
        val it = one("Quiz 2 results", "Average 62.4/100, highest 98/100, lowest 20/100")
        assertEquals("quiz2", it.key)
        assertEquals(100.0, it.points!!, 0.0)
        val k = one("중간고사 성적", "평균 62.4점(100점 만점), 최고 95점")
        assertEquals(100.0, k.points!!, 0.0)
        assertEquals(62.4, k.stats.mean!!, 0.0)
    }

    @Test fun koreanQuartileWords() {
        val it = one("기말고사 점수 공개", "기말고사\n- 평균: 58.2\n- 1사분위: 45, 중앙값: 60, 3사분위: 72\n- 최고 96 / 최저 8")
        assertEquals("final", it.key)
        assertEquals(45.0, it.stats.q1!!, 0.0)
        assertEquals(60.0, it.stats.median!!, 0.0)
        assertEquals(72.0, it.stats.q3!!, 0.0)
        val h = one("중간고사 성적", "하위 25% 41점, 중간값 55점, 상위 25% 70점, 평균 54.9점")
        assertEquals(41.0, h.stats.q1!!, 0.0)
        assertEquals(70.0, h.stats.q3!!, 0.0)
    }

    @Test fun multipleExamsInOnePost() {
        val r = parse("시험 성적 안내", "중간고사: 평균 62, 최고 95\n기말고사: 평균 58, 최고 92, 표준편차 14")
        assertTrue(r.reason, r.confident)
        assertEquals(listOf("mid", "final"), r.items.map { it.key })
        assertEquals(58.0, r.items[1].stats.mean!!, 0.0)
        val inline = parse("성적 통계", "중간 평균 62, 최고 95 / 기말 평균 58, 최고 92")
        assertTrue(inline.reason, inline.confident)
        assertEquals(listOf("mid", "final"), inline.items.map { it.key })
    }

    @Test fun sectionsBecomeSeparateItems() {
        val r = parse("중간고사 성적 공지", "1분반 평균 60.2, 표준편차 14\n2분반 평균 63.5, 표준편차 13")
        assertTrue(r.reason, r.confident)
        assertEquals(listOf(1, 2), r.items.map { it.section })
        assertTrue(r.items.all { it.key == "mid" })
    }

    @Test fun htmlTableColumnsAreFields() {
        val html = "<p>중간고사 통계입니다.</p><table><tr><th>구분</th><th>평균</th><th>표준편차</th><th>최고</th><th>최저</th></tr>" +
            "<tr><td>중간고사</td><td>62.4</td><td>15.1</td><td>98</td><td>12</td></tr></table><p>이의신청: 11월 30일 18:00까지</p>"
        val it = one("중간고사 성적", ScoreStatsParser.textFromHtml(html))
        assertEquals(62.4, it.stats.mean!!, 0.0)
        assertEquals(12.0, it.stats.min!!, 0.0)
    }

    @Test fun htmlTableRowsAreFieldsColumnsAreExams() {
        val html = "<table><tr><th></th><th>중간고사</th><th>기말고사</th></tr><tr><td>평균</td><td>62</td><td>58</td></tr>" +
            "<tr><td>최고점</td><td>95</td><td>92</td></tr></table>"
        val r = parse("성적 공개", ScoreStatsParser.textFromHtml(html))
        assertTrue(r.reason, r.confident)
        assertEquals(listOf("mid", "final"), r.items.map { it.key })
        assertEquals(92.0, r.items[1].stats.max!!, 0.0)
    }

    @Test fun markdownAndPlainTables() {
        val md = one("중간고사 결과", "| 구분 | 평균 | 표준편차 |\n|---|---|---|\n| 중간 | 62.4 | 15.1 |")
        assertEquals("mid", md.key)
        assertEquals(15.1, md.stats.stddev!!, 0.0)
        val plain = one("Quiz 2 결과", "평균 표준편차 최고점 최저점\n62.4 15.1 98 12")
        assertEquals("quiz2", plain.key)
        assertEquals(98.0, plain.stats.max!!, 0.0)
    }

    @Test fun datesTimesRoomsAreIgnored() {
        val it = one("중간고사 성적 공개", "시험일: 10월 23일 15:30, 301동 118호\n평균 62.4점, 최고 98점\n이의신청은 11월 30일까지 조교실(302동 310호)로")
        assertEquals(62.4, it.stats.mean!!, 0.0)
        assertEquals(98.0, it.stats.max!!, 0.0)
        assertNull(it.stats.min)
        assertNull(it.points)
    }

    @Test fun countWithPeople() {
        val it = one("Exam 2 grades", "응시 인원 45명, 평균 61.2, 최고 97")
        assertEquals(45, it.stats.count)
        assertEquals("exam2", it.key)
    }

    @Test fun proseMiddleDoesNotSwitchExam() {
        val it = one("기말고사 성적", "수업 중간에 말씀드린 대로 평균 58, 최고 92입니다.")
        assertEquals("final", it.key)
    }

    @Test fun ambiguousCasesAreNotConfident() {
        assertFalse(parse("중간고사 성적", "평균과 표준편차는 각각 62.4, 15.1입니다").confident)
        assertFalse(parse("중간고사 성적", "히스토그램은 첨부 그림을 참고하세요.").confident)
        assertFalse(parse("중간고사 성적", "평균 62점, 최고 95점. 정정합니다: 평균 65점").confident)
        assertFalse(parse("중간고사 성적", "평균 62%, 최고 95%").confident)
        assertFalse(parse("중간고사 성적", "평균 62점입니다.").confident)
        assertFalse(parse("중간고사 성적", "평균(100점 만점 기준) 62.4").confident)
        assertFalse(parse("성적", "평균 62, 최고 95\n평균 70, 최고 99").confident)
        // 순서가 맞지 않는 값(최저 > 최고).
        assertFalse(parse("중간고사 성적", "평균 50, 최고 40, 최저 60").confident)
    }

    @Test fun comparisonSentencesAreSkipped() {
        val r = parse("중간고사 성적", "지난 학기보다 평균이 5점 올랐습니다. 평균 62, 최고 95")
        assertTrue(r.reason, r.confident)
        assertEquals(62.0, r.items.single().stats.mean!!, 0.0)
    }

    /** S22 실측 공지 형식(숫자는 바꿈): 평균 / 만점, 표준편차, 이름과 값을 "/"로 묶은 줄. */
    @Test fun slashGroupedFiveNumberSummary() {
        val body = "Dear class,\nThe midterm exam results are now available.\nMidterm Exam Statistics\n- \nAverage: 61.3 / 80\n- \nStandard Deviation: 9.8\n- \n" +
            "Min/Q1/Q2/Q3/Max: 30 / 55 / 62 / 70 / 79\nYou can check your scores by following the steps below.\nEnter your Student ID (ex. 2026-12345)."
        val it = one("Midterm Grades", body)
        assertEquals("mid", it.key)
        assertEquals(61.3, it.stats.mean!!, 0.0)
        assertEquals(80.0, it.points!!, 0.0)
        assertEquals(9.8, it.stats.stddev!!, 0.0)
        assertEquals(30.0, it.stats.min!!, 0.0)
        assertEquals(55.0, it.stats.q1!!, 0.0)
        assertEquals(62.0, it.stats.median!!, 0.0)
        assertEquals(70.0, it.stats.q3!!, 0.0)
        assertEquals(79.0, it.stats.max!!, 0.0)
        val ko = one("중간고사 성적", "평균/표준편차/최고/최저: 62.4/15.1/98/12")
        assertEquals(15.1, ko.stats.stddev!!, 0.0)
        assertEquals(12.0, ko.stats.min!!, 0.0)
    }

    /** S22 실측 형식: "Q0: 105점" … "Q4: 140점"(다섯 수 요약), 뒤에 클레임 일정(날짜·시각·강의실)이 있다. */
    @Test fun q0ToQ4LinesWithClaimSchedule() {
        val body = "Exam1 성적이 공개되었습니다.\n-\nQ1: 105점\n-\nQ2: 118점\n-\nQ3: 127점\n-\nQ4: 140점\n클레임은 아래 시간에 진행됩니다.\n" +
            "11-24(월) 18:00 - 19:00 301동 118호\n11-25(화) 18:00 - 19:00 301동 118호\n해당 시간에 참여하지 못하는 학생은 메일로 연락바랍니다."
        val it = one("[I2EP] Exam1 성적 공개 및 클레임 공지", body)
        assertEquals("exam1", it.key)
        assertEquals(105.0, it.stats.q1!!, 0.0)
        assertEquals(118.0, it.stats.median!!, 0.0)
        assertEquals(127.0, it.stats.q3!!, 0.0)
        assertEquals(140.0, it.stats.max!!, 0.0)
        assertNull(it.stats.mean)
        assertEquals(4, it.fieldCount)
    }

    @Test fun examKeysForAssignmentNames() {
        assertEquals("exam1", ScoreStatsParser.examKey("I2EP_Exam_1")?.first)
        assertEquals("mid", ScoreStatsParser.examKey("중간고사")?.first)
        assertEquals("mid", ScoreStatsParser.examKey("Midterm Exam")?.first)
        assertEquals("hw3", ScoreStatsParser.examKey("HW 3")?.first)
        assertEquals("quiz2", ScoreStatsParser.examKey("2차 퀴즈")?.first)
        assertEquals("final", ScoreStatsParser.examKey("기말고사 (온라인)")?.first)
        assertNull(ScoreStatsParser.examKey("Example problems")?.first)
        assertNull(ScoreStatsParser.examKey("출석"))
    }
}
