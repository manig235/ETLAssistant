package kr.snu.youngmini.desktop

import kr.snu.etlassistant.etl.EtlException
import org.json.JSONArray
import org.json.JSONObject

/** Skills adapted to the app's actual tools, not copied with unavailable host APIs. */
object BundledSkills {
    private val descriptions=linkedMapOf(
        "pdf" to "PDF 생성·내용 읽기·쪽 렌더링·병합·분할·회전·양식 채우기·주석과 최종 검수",
        "documents" to "Word DOCX 읽기·생성·문단 수정·원본 Office 렌더링과 검수",
        "spreadsheets" to "Excel XLSX/CSV 읽기·생성·수식·수정·계산 결과와 출력 검수",
        "presentations" to "PPTX 슬라이드 생성·읽기·텍스트 수정·원본 PowerPoint 렌더링 검수",
        "images" to "사진·PDF 쪽 이미지 확인과 자르기·회전·크기 조정; 생성형 도구는 실제 계정 지원 여부에 따름",
        "data-analysis" to "학습 데이터·실험값·성적 표 정리·수치 요약·CSV/Excel 산출물",
        "study" to "프로젝트 자료·공지·과제·강의계획서·공개 학습자료를 읽고 출처 있는 학습 산출물 만들기"
    )
    fun specs()=listOf(
        JSONObject().put("name","skill_list").put("description","이 앱에서 실행 가능한 학습·파일 작업 스킬 목록과 실제 도구 범위를 확인합니다.").put("inputSchema",JSONObject().put("type","object").put("properties",JSONObject()).put("additionalProperties",false)),
        JSONObject().put("name","skill_read").put("description","작업에 해당하는 스킬 절차/스키마를 읽습니다. name은 skill_list의 이름, reference는 기본 SKILL.md 또는 schema입니다.").put("inputSchema",JSONObject().put("type","object").put("properties",JSONObject().put("name",JSONObject().put("type","string")).put("reference",JSONObject().put("type","string"))).put("required",JSONArray(listOf("name"))).put("additionalProperties",false))
    )
    fun call(name:String,args:JSONObject):JSONObject {
        if(name=="skill_list")return JSONObject().put("skills",JSONArray(descriptions.map {(key,value)->JSONObject().put("name",key).put("description",value)})).put("runtime","이 앱의 고정 도구를 사용해요. Codex 앱의 메일/클라우드/브라우저 커넥터는 포함하지 않아요.")
        val skill=args.getString("name");if(skill !in descriptions)throw EtlException("skill_list의 이름을 사용해 주세요")
        val reference=args.optString("reference","SKILL.md");require(reference in listOf("SKILL.md","schema"))
        val resource=if(reference=="schema")"office-schema.md" else "$skill/SKILL.md"
        val text=javaClass.getResourceAsStream("/skills/$resource")?.use {it.readNBytes(40000).toString(Charsets.UTF_8)}?:throw EtlException("스킬 문서를 찾지 못했어요")
        return JSONObject().put("name",skill).put("reference",reference).put("text",text)
    }
}
