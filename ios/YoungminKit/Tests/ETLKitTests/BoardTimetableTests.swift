import Foundation
import Testing
@testable import ETLKit

/// Kotlin BoardTimetableTest의 etl_get 경로·가리기·시간표 부분(게시판 BoardLogic은 다른 단계).
struct BoardTimetableTests {
    private let base = EtlClient.BASE_URL

    // ---- etl_get 경로·가리기 -----------------------------------------------------

    @Test func getPathRules() {
        #expect(EtlLogic.isAllowedGetPath("/api/v1/users/self/todo"))
        #expect(EtlLogic.isAllowedGetPath("/api/v1/courses/1/discussion_topics/2/view"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1/users/self/tokens"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1/login/session_token"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1/courses/1/external_tools/sessionless_launch?id=85"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1/courses/1/%2e%2e/x"))
        #expect(!EtlLogic.isAllowedGetPath("/api/v1//x"))
    }

    @Test func unsignMasksSignedLinksAndFeeds() {
        let o = jsonObject("{\"url\":\"\(base)/files/5/download?download_frd=1&verifier=SECRET\",\"nested\":[{\"feed\":\"\(base)/feeds/calendars/user_SECRET2.ics\"}],\"n\":3}")
        let s = EtlLogic.kotlinToString(EtlLogic.unsign(o)!)
        #expect(!s.contains("SECRET"))
        #expect(s.contains("verifier=***"))
        #expect(s.contains("/feeds/calendars/***"))
        #expect(s.contains("\"n\":3"))
    }

    // ---- 시간표 ---------------------------------------------------------------

    private let syllabus = "<iframe class=\"xn-syllabus-iframe\" src=\"https://sugang.snu.ac.kr/sugang/cc/cc103.action?openSchyy=2026&amp;openShtmFg=U000200002&amp;openDetaShtmFg=U000300001&amp;sbjtCd=430.309A&amp;ltNo=001&amp;sbjtSubhCd=000&amp;\"></iframe>"

    @Test func catalogRefFromSyllabus() {
        let ref = TimetableLogic.catalogRef(syllabus)!
        #expect(ref["sbjtCd"] == "430.309A")
        #expect(ref["ltNo"] == "001")
        // Swift 사전은 순서가 없어 키 집합으로 본다(요청 본문은 REF_KEYS 순서로 만든다).
        #expect(Set(ref.keys) == Set(TimetableLogic.REF_KEYS))
        #expect(TimetableLogic.catalogRef("<p>계획서 없음</p>") == nil)
        #expect(TimetableLogic.catalogRef(syllabus.replacingOccurrences(of: "430.309A", with: "430'309")) == nil)
        // sbjtSubhCd가 없으면 000
        #expect(TimetableLogic.catalogRef(syllabus.replacingOccurrences(of: "&amp;sbjtSubhCd=000", with: ""))?["sbjtSubhCd"] == "000")
    }

    @Test func parseMeetingsBothFormats() {
        let summary = jsonObject(#"{"LISTTAB01":{"lsnTmtablFormaSmryCtnt":"월(17:00~18:15)|이론|301-103(무선랜제공)/수(17:00~18:15)|이론|301-103(무선랜제공)"}}"#)
        let m = TimetableLogic.parseMeetings(summary)
        #expect(m.map(\.day) == ["월", "수"])
        #expect(m[0].room == "301-103")
        let fallback = jsonObject(#"{"ltTime":["화(9:30~10:45)","목(9:30~10:45)"],"ltRoom":["43-1-101",""]}"#)
        let f = TimetableLogic.parseMeetings(fallback)
        #expect(f[0].start == "09:30")
        #expect(f[1].room == nil)
        #expect(TimetableLogic.parseMeetings(JSONObject()).isEmpty)
    }

    @Test func professorComesFromSyllabusAndFallsBackToCourseDetails() {
        #expect(TimetableLogic.professor(jsonObject(#"{"LISTTAB03":{"profNm":"<b>홍교수 (대표교수)</b>"},"LISTTAB01":{"profNm":"김교수（담당교수）"}}"#)) == "홍교수")
        #expect(TimetableLogic.professor(jsonObject(#"{"LISTTAB03":{"profNm":" "},"LISTTAB01":{"profNm":"김교수（담당교수）"}}"#)) == "김교수")
        #expect(TimetableLogic.professor(jsonObject(#"{"LISTTAB03":{"profNm":null}}"#)) == nil)
        #expect(TimetableLogic.professor(nil) == nil)
    }

    @Test func creditsComeOnlyFromExplicitCatalogMetadata() {
        #expect(TimetableLogic.credits(jsonObject(#"{"LISTTAB01":{"openPnt":"3"}}"#)) == 3.0)
        #expect(TimetableLogic.credits(jsonObject(#"{"LISTTAB01":{"openPnt":4}}"#)) == 4.0)
        #expect(TimetableLogic.credits(jsonObject(#"{"LISTTAB01":{"openPnt":"미정"}}"#)) == nil)
        #expect(TimetableLogic.credits(jsonObject(#"{"LISTTAB01":{"openPnt":-1}}"#)) == nil)
        #expect(TimetableLogic.credits(JSONObject()) == nil)
    }

    @Test func professorSurvivesCacheAndOldCacheStillShowsMeetings() throws {
        let course = TimetableLogic.CourseMeetings(1, "과목", [TimetableLogic.Meeting("월", "09:00", "10:00", "301-302")], "홍교수(대표교수)", credits: 3.0)
        let current = TimetableLogic.Weekly("1", 1000, [course], [])
        var expected = current
        var cleaned = course
        cleaned.professor = "홍교수"
        expected.courses = [cleaned]
        #expect(TimetableLogic.fromJson(TimetableLogic.toJson(current)) == expected)
        let old = try JSONObject(TimetableLogic.toJson(current)).put("version", 1)
        try old.getJSONArray("courses").getJSONObject(0).remove("professor")
        try old.getJSONArray("courses").getJSONObject(0).remove("credits")
        let restored = TimetableLogic.fromJson(old.toString())!
        #expect(restored.metadataVersion == 1)
        #expect(restored.courses.count == 1)
        #expect(restored.courses[0].meetings == course.meetings)
        #expect(restored.courses[0].professor == nil)
        #expect(restored.courses[0].credits == nil)
    }

    @Test func partialMetadataFailurePreservesKnownMeetingsAndProfessor() {
        let old = TimetableLogic.CourseMeetings(1, "과목", [TimetableLogic.Meeting("월", "09:00", "10:00", "301-302")], "이전 교수")
        let onlyProfessor = TimetableLogic.CatalogCourse([], "새 교수", meetingError: "조회 실패")
        #expect(TimetableLogic.refreshedCourse(1, "과목", onlyProfessor, old).meetings == old.meetings)
        #expect(TimetableLogic.refreshedCourse(1, "과목", onlyProfessor, old).professor == "새 교수")
        #expect(TimetableLogic.refreshedCourse(1, "과목", nil, old) == old)
        let confirmedEmpty = TimetableLogic.CatalogCourse([], nil)
        #expect(TimetableLogic.refreshedCourse(1, "과목", confirmedEmpty, old).meetings.isEmpty)
        #expect(TimetableLogic.refreshedCourse(1, "과목", confirmedEmpty, old).professor == "이전 교수")
    }

    @Test func dayClassesAndCacheRoundTrip() {
        let w = TimetableLogic.Weekly(
            "1,2", 1000,
            [
                TimetableLogic.CourseMeetings(1, "2026-2 전기에너지변환 (001)", [TimetableLogic.Meeting("월", "17:00", "18:15", "301-103")]),
                TimetableLogic.CourseMeetings(2, "2026-2 제어공학개론 (001)", [TimetableLogic.Meeting("월", "09:30", "10:45", nil), TimetableLogic.Meeting("수", "09:30", "10:45", nil)]),
            ],
            ["체력단련: 강의계획서에 수강편람 링크가 없어요"]
        )
        let monday = DayDate.of(2026, 11, 23)
        let ev = TimetableLogic.ClassRow("12:00", "13:00", "제어공학개론", 2, "301-118", "일정", "보강")
        let rows = TimetableLogic.dayClasses(w, monday, [ev])
        #expect(rows.map(\.start) == ["09:30", "12:00", "17:00"])
        #expect(rows[2].course == "전기에너지변환")
        #expect(TimetableLogic.briefLines(rows) == ["• 09:30~10:45 제어공학개론", "• 12:00~13:00 제어공학개론 (301-118) [일정]", "• 17:00~18:15 전기에너지변환 (301-103)"])
        #expect(TimetableLogic.fromJson(TimetableLogic.toJson(w)) == w)
        #expect(TimetableLogic.weekJson(w).optJSONArray("수") != nil)
        #expect(TimetableLogic.weekJson(w).optJSONArray("금") == nil)
    }
}
