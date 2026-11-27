package kr.snu.etlassistant.etl

import org.json.JSONObject

/** 파일 탭에서 숨긴 파일도 실제로 열 수 있는 공지·과제·모듈에 연결되어 있으면 읽을 수 있다. */
object LinkedFileAccess {
    fun verify(
        fileId: Long,
        courseId: Long,
        sourceType: String,
        sourceId: Long,
        getObject: (String) -> JSONObject,
        getAll: (String) -> List<JSONObject>,
    ) {
        if (fileId <= 0 || courseId <= 0 || sourceId <= 0) throw EtlException("파일과 출처 ID는 양의 정수여야 합니다")
        val base = "/api/v1/courses/$courseId"
        val path = when (sourceType) {
            "announcement" -> "$base/discussion_topics/$sourceId"
            "assignment" -> "$base/assignments/$sourceId?include[]=submission"
            "module" -> "$base/modules/$sourceId"
            else -> throw EtlException("source_type은 announcement, assignment, module 중 하나여야 합니다")
        }
        val source = getObject(path)
        if (source.optLong("id") != sourceId || !Queries.isPublished(source) || source.optBoolean("locked_for_user")) {
            throw EtlException("파일이 연결된 자료를 열람할 수 없습니다")
        }
        val linked = when (sourceType) {
            "announcement" -> source.optBoolean("is_announcement") &&
                Queries.attachedFiles(emptyList(), listOf(source), courseId).any { it.id == fileId }
            "assignment" -> Queries.attachedFiles(listOf(source), emptyList(), courseId).any { it.id == fileId }
            else -> {
                if (source.optString("state") == "locked") throw EtlException("파일이 연결된 모듈이 잠겨 있습니다")
                getAll("$base/modules/$sourceId/items?per_page=100").any {
                    it.optString("type") == "File" && it.optLong("content_id") == fileId && Queries.isPublished(it)
                }
            }
        }
        if (!linked) throw EtlException("해당 자료에 파일 ID $fileId 인 첨부·링크가 없습니다. etl_files 또는 게시글에서 출처를 다시 확인하세요.")
    }
}
