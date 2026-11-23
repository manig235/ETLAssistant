package kr.snu.etlassistant.etl

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 강의계획서(수강편람 cc103ajax)와 docx 읽기. 픽스처는 2026-11-21에 받은 실제 공개 응답(430.309A)을 줄이고 주차별 계획을 더한 것. */
class SyllabusTest {

    private val cc103 = JSONObject("""
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
    """)

    @Test fun syllabusJsonSummarizesCatalog() {
        val o = TimetableLogic.syllabusJson(cc103)
        assertEquals("전기에너지변환 (001)", o.getString("course"))
        assertEquals("한승용", o.getString("professor"))
        assertEquals("Understand machines", o.getString("goals")) // 한국어가 없으면 영어
        val grading = o.getJSONArray("grading")
        assertEquals(listOf("출석", "과제", "중간고사", "기말고사", "퀴즈"), (0 until grading.length()).map { grading.getJSONObject(it).getString("item") })
        assertEquals("누적 범위", grading.getJSONObject(3).getString("note"))
        assertEquals("기말고사: 12월 14일(월)\n중간고사: 10월 19일", o.getString("remarks"))
        val weeks = o.getJSONArray("weekly_plan")
        assertEquals(15, weeks.getJSONObject(1).getInt("week"))
        assertEquals("기말고사\n(12/14)", weeks.getJSONObject(1).getString("plan"))
        assertEquals("월(17:00~18:15) 301-103(무선랜제공)", o.getJSONArray("meetings").getString(0))
        assertEquals("이메일로 사전약속 후 면담", o.getString("office_hours"))
        assertTrue(o.getString("attendance_rule").startsWith("수업일수"))
        assertFalse(o.toString().contains("장애학생")) // 공통 안내 문구는 뺀다
        // 첨부: 이름은 크기 표기를 떼고, 번호는 결과에 넣지 않는다
        val att = o.getJSONArray("attachments").getJSONObject(0)
        assertEquals(0, att.getInt("index"))
        assertEquals("[430.309A]ElectromechanicalEnergyConversion_Syllabus_20260723_SH.docx", att.getString("name"))
        assertFalse(o.toString().contains("2d1eaf89"))
    }

    @Test fun attachmentUrlIsValidated() {
        val a = TimetableLogic.syllabusAttachments(cc103).single()
        assertEquals("en", a.lang)
        assertEquals(
            "https://shine.snu.ac.kr/com/download/noflt/downloadWebFile.action?cscLocale=ko_KR&attcFileNo=2d1eaf89-7003-489b-b838-a131a2acb691&attcFileDetaNo=0",
            TimetableLogic.attachmentUrl(a),
        )
        assertNull(TimetableLogic.attachmentUrl(a.copy(no = "x&attcFileNo=evil")))
        assertNull(TimetableLogic.attachmentUrl(a.copy(seq = -1)))
        assertTrue(TimetableLogic.syllabusAttachments(JSONObject("{}")).isEmpty())
        assertEquals("계획서.pdf", TimetableLogic.attachmentName("계획서.pdf (1.2 MB)"))
    }

    @Test fun catalogRefLinkVariants() {
        val q = "openSchyy=2026&openShtmFg=U000200002&openDetaShtmFg=U000300001&sbjtCd=430.309A&ltNo=001&sbjtSubhCd=000"
        val shuffled = "ltNo=001&sbjtCd=430.309A&openDetaShtmFg=U000300001&openShtmFg=U000200002&openSchyy=2026"
        for (html in listOf(
            """<a href="http://sugang.snu.ac.kr/sugang/cc/cc101.action?$q">편람</a>""",
            """<iframe src='https://sugang.snu.ac.kr/sugang/cc/cc103.action?${q.replace("&", "&#38;")}'></iframe>""",
            """<p>https://sugang.snu.ac.kr/sugang/cc/cc103.action?${q.replace("&", "&AMP;")} 참고</p>""",
            """<a href="https://sugang.snu.ac.kr/sugang/cc/cc103.action?$shuffled">편람</a>""",
        )) {
            val ref = TimetableLogic.catalogRef(html)
            assertEquals(html, "430.309A", ref?.get("sbjtCd"))
            assertEquals(html, "000", ref?.get("sbjtSubhCd"))
        }
    }

    private fun zip(f: File, entries: Map<String, String>) {
        ZipOutputStream(f.outputStream()).use { z ->
            for ((k, v) in entries) { z.putNextEntry(ZipEntry(k)); z.write(v.toByteArray()); z.closeEntry() }
        }
    }

    @Test fun docxAndPptxText() {
        val dir = java.nio.file.Files.createTempDirectory("syl").toFile()
        try {
            val docx = File(dir, "계획서.docx")
            zip(docx, mapOf(
                "[Content_Types].xml" to "<Types/>",
                "word/document.xml" to """<w:document><w:body><w:p><w:r><w:t>평가 &amp; 일정</w:t></w:r></w:p>""" +
                    """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>주차</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>내용</w:t></w:r></w:p></w:tc></w:tr>""" +
                    """<w:tr><w:tc><w:p><w:r><w:t>15</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>기말고사</w:t><w:br/><w:t>12/14</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""" +
                    """</w:body></w:document>""",
            ))
            assertEquals("평가 & 일정\n주차 | 내용\n15 | 기말고사\n12/14", EtlFiles.ooxmlText(docx))
            assertTrue(EtlFiles.isOoxml("a.DOCX"))

            val pptx = File(dir, "slides.pptx")
            zip(pptx, mapOf(
                "ppt/slides/slide10.xml" to "<p:sld><a:p><a:r><a:t>열</a:t></a:r></a:p></p:sld>",
                "ppt/slides/slide2.xml" to "<p:sld><a:p><a:r><a:t>둘</a:t></a:r></a:p></p:sld>",
            ))
            assertEquals("--- 슬라이드 2 ---\n둘\n\n--- 슬라이드 10 ---\n열", EtlFiles.ooxmlText(pptx))

            val bad = File(dir, "bad.docx").apply { writeText("not a zip") }
            try { EtlFiles.ooxmlText(bad); throw AssertionError("예외가 나야 한다") } catch (_: EtlException) {}
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun fitNameKeepsExtensionWithinBytes() {
        val n = EtlFiles.fitName("syllabus-" + "가".repeat(100) + ".docx", 200)
        assertTrue(n.toByteArray().size <= 200)
        assertTrue(n.endsWith(".docx"))
    }
}
