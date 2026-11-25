import Foundation
import Testing
@testable import ETLKit

/// 강의계획서(수강편람 cc103ajax). docx 읽기(EtlFiles.ooxmlText)는 다른 단계라 뺐다.
struct SyllabusTests {

    private let cc103 = jsonObject(#"""
    {"ltTime":["월(17:00~18:15)","수(17:00~18:15)"],"ltRoom":["301-103(무선랜제공)","301-103(무선랜제공)"],"ltType":["이론","이론"],
     "ROWCNT":{"rowCnt":1},
     "LISTTAB01":{"sbjtNm":"전기에너지변환","ltNo":"001","sbjtCd":"430.309A","openPnt":"3","lsnProgLang":"한국어","profNm":"한승용 (대표교수)",
       "stdIntrvPosbTmList":"이메일로 사전약속 후 면담"},
     "LISTTAB03":{"profNm":"한승용 (대표교수)","profEmail":"prof@example.ac.kr","ltPurp":null,"ltEngPurp":"Understand <b>machines</b>","mrksRelevalYnNm":"절대평가",
       "mrksGvMthdFgNm":"A~F","etcMrksApprMthd":null,"teachmAndRefBookList":null,"preCptnSbjtNm":null,"genrAiUtlzKorCtnt":null,"ltPlanDocRemk":"기말고사: 12월 14일(월)<br>중간고사: 10월 19일"},
     "LISTTAB03_LIST_MRKS":{"quiz":40,"mid":20,"final":25,"homeWork":10,"attendance":5,"attitude":0,"etc":0,"finalRemk":"누적 범위","quizRemk":null},
     "LISTTAB03_SUM_OF_MRKS":{"sumOfMrks":100},
     "LISTTAB03_LIST_PLAN":[{"ltPlanCtnt":null,"ltPlanEngCtnt":null,"attendRegulKorCtnt":"수업일수의 1/3을 초과하여 결석하면 성적은 \"F\"",
       "hindStdSuppRemk":"장애학생 지원 안내 문구"}],
     "LISTTAB03_LIST_WEEK_PLAN":[{"ltPlanDocWekClsfSeq":1,"wekClsfLtPlanCtnt":"오리엔테이션"},{"ltPlanDocWekClsfSeq":15,"wekClsfLtPlanCtnt":"기말고사\n(12/14)"}],
     "LISTTAB03_LIST_TEACHM_BOOK":0,
     "LISTTAB03_LIST_ATTACH":[{"korFileNm":null,"korAttachNo":null,"korAttachDetaNo":null,
       "engFileNm":"[430.309A]ElectromechanicalEnergyConversion_Syllabus_20260723_SH.docx (31.73KB)","engAttachNo":"2d1eaf89-7003-489b-b838-a131a2acb691","engAttachDetaNo":0}]}
    """#)

    @Test func syllabusJsonSummarizesCatalog() throws {
        let o = TimetableLogic.syllabusJson(cc103)
        #expect(try o.getString("course") == "전기에너지변환 (001)")
        #expect(try o.getString("professor") == "한승용")
        #expect(try o.getString("goals") == "Understand machines") // 한국어가 없으면 영어
        let grading = try o.getJSONArray("grading")
        #expect(try (0..<grading.length()).map { try grading.getJSONObject($0).getString("item") } == ["출석", "과제", "중간고사", "기말고사", "퀴즈"])
        #expect(try grading.getJSONObject(3).getString("note") == "누적 범위")
        #expect(try o.getString("remarks") == "기말고사: 12월 14일(월)\n중간고사: 10월 19일")
        let weeks = try o.getJSONArray("weekly_plan")
        #expect(try weeks.getJSONObject(1).getInt("week") == 15)
        #expect(try weeks.getJSONObject(1).getString("plan") == "기말고사\n(12/14)")
        #expect(try o.getJSONArray("meetings").getString(0) == "월(17:00~18:15) 301-103(무선랜제공)")
        #expect(try o.getString("office_hours") == "이메일로 사전약속 후 면담")
        #expect(try o.getString("attendance_rule").hasPrefix("수업일수"))
        #expect(!o.toString().contains("장애학생")) // 공통 안내 문구는 뺀다
        // 첨부: 이름은 크기 표기를 떼고, 번호는 결과에 넣지 않는다
        let att = try o.getJSONArray("attachments").getJSONObject(0)
        #expect(try att.getInt("index") == 0)
        #expect(try att.getString("name") == "[430.309A]ElectromechanicalEnergyConversion_Syllabus_20260723_SH.docx")
        #expect(!o.toString().contains("2d1eaf89"))
    }

    @Test func attachmentUrlIsValidated() {
        let all = TimetableLogic.syllabusAttachments(cc103)
        #expect(all.count == 1)
        let a = all[0]
        #expect(a.lang == "en")
        #expect(TimetableLogic.attachmentUrl(a) ==
            "https://shine.snu.ac.kr/com/download/noflt/downloadWebFile.action?cscLocale=ko_KR&attcFileNo=2d1eaf89-7003-489b-b838-a131a2acb691&attcFileDetaNo=0")
        var bad = a
        bad.no = "x&attcFileNo=evil"
        #expect(TimetableLogic.attachmentUrl(bad) == nil)
        var neg = a
        neg.seq = -1
        #expect(TimetableLogic.attachmentUrl(neg) == nil)
        #expect(TimetableLogic.syllabusAttachments(jsonObject("{}")).isEmpty)
        #expect(TimetableLogic.attachmentName("계획서.pdf (1.2 MB)") == "계획서.pdf")
    }

    @Test func catalogRefLinkVariants() {
        let q = "openSchyy=2026&openShtmFg=U000200002&openDetaShtmFg=U000300001&sbjtCd=430.309A&ltNo=001&sbjtSubhCd=000"
        let shuffled = "ltNo=001&sbjtCd=430.309A&openDetaShtmFg=U000300001&openShtmFg=U000200002&openSchyy=2026"
        for html in [
            "<a href=\"http://sugang.snu.ac.kr/sugang/cc/cc101.action?\(q)\">편람</a>",
            "<iframe src='https://sugang.snu.ac.kr/sugang/cc/cc103.action?\(q.replacingOccurrences(of: "&", with: "&#38;"))'></iframe>",
            "<p>https://sugang.snu.ac.kr/sugang/cc/cc103.action?\(q.replacingOccurrences(of: "&", with: "&AMP;")) 참고</p>",
            "<a href=\"https://sugang.snu.ac.kr/sugang/cc/cc103.action?\(shuffled)\">편람</a>",
        ] {
            let ref = TimetableLogic.catalogRef(html)
            #expect(ref?["sbjtCd"] == "430.309A", "\(html)")
            #expect(ref?["sbjtSubhCd"] == "000", "\(html)")
        }
    }

    @Test func fitNameKeepsExtensionWithinBytes() {
        let n = EtlDownloads.fitName("syllabus-" + String(repeating: "가", count: 100) + ".docx", maxBytes: 200)
        #expect(n.utf8.count <= 200)
        #expect(n.hasSuffix(".docx"))
    }
}
