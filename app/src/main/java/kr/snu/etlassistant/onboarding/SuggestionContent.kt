package kr.snu.etlassistant.onboarding

import kr.snu.etlassistant.etl.EtlFiles
import kr.snu.etlassistant.etl.EtlLogic
import kr.snu.etlassistant.etl.EtlLogic.str
import kr.snu.etlassistant.etl.HwpText
import kr.snu.etlassistant.etl.Queries
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** null은 조회 실패로 미확인인 상태다. 확인되지 않은 콘텐츠를 추천에 사용하지 않는다. */
internal data class SuggestionContent(val hasMaterials: Boolean?, val hasRecentAssignment: Boolean?)

internal object SuggestionContentLogic {
    fun readableMaterial(file: JSONObject): Boolean {
        if (file.optLong("id") <= 0 || Queries.skipReason(file) != null) return false
        return listOfNotNull(file.str("display_name"), file.str("filename")).any { name ->
            EtlFiles.isPdf(name) || EtlFiles.isOoxml(name) || HwpText.isHwp(name) || EtlFiles.isTextLike(name)
        }
    }

    fun recentAssignment(assignment: JSONObject, now: Instant): Boolean {
        if (assignment.optLong("id") <= 0 || !Queries.isPublished(assignment) ||
            assignment.optBoolean("locked_for_user") || !EtlLogic.isOnline(assignment)) return false
        val earliest = now.minusSeconds(30 * 86400L)
        val due = EtlLogic.parseInstant(assignment.str("due_at"))
        if (due != null) return !due.isBefore(earliest) && !due.isAfter(now.plusSeconds(14 * 86400L))
        val created = EtlLogic.parseInstant(assignment.str("unlock_at"))
            ?: EtlLogic.parseInstant(assignment.str("created_at")) ?: return false
        return !created.isBefore(earliest) && !created.isAfter(now)
    }
}

/** 목록과 메타데이터만 읽는다. 추천을 만들기 위해 파일을 다운로드하거나 제출하지 않는다. */
internal class SuggestionContentLookup(
    private val getAll: (String) -> List<JSONObject>,
    private val getObject: (String) -> JSONObject,
) {
    fun check(courseId: Long, now: Instant): SuggestionContent {
        val assignments = runCatching {
            getAll("/api/v1/courses/$courseId/assignments?order_by=due_at&per_page=100")
                .any { SuggestionContentLogic.recentAssignment(it, now) }
        }.getOrNull()
        return SuggestionContent(materials(courseId), assignments)
    }

    private fun materials(courseId: Long): Boolean? {
        val files = runCatching { getAll("/api/v1/courses/$courseId/files?per_page=100") }.getOrNull()
        if (files?.any(SuggestionContentLogic::readableMaterial) == true) return true
        val modules = runCatching {
            getAll("/api/v1/courses/$courseId/modules?include[]=items&per_page=100")
        }.getOrNull() ?: return null
        var complete = files != null
        val accessible = modules.filter { it.optBoolean("published", true) && !it.optBoolean("locked_for_user") }
        for (module in accessible) {
            val embedded = module.optJSONArray("items")
            if (embedded == null || embedded.length() < module.optInt("items_count", embedded.length())) {
                val items = runCatching {
                    getAll("/api/v1/courses/$courseId/modules/${module.optLong("id")}/items?per_page=100")
                }.getOrNull()
                if (items != null) module.put("items", JSONArray(items)) else complete = false
            }
        }
        val references = Queries.moduleFiles(accessible).distinctBy { it.id }
        // 목록이 큰 과목은 앞의 파일만 확인한다. 확인이 덜 됐으면 '없음'으로 캐시하지 않는다.
        if (references.size > 12) complete = false
        for (reference in references.take(12)) {
            val file = runCatching { getObject("/api/v1/courses/$courseId/files/${reference.id}") }.getOrNull()
            if (file == null) complete = false else if (SuggestionContentLogic.readableMaterial(file)) return true
        }
        return if (complete) false else null
    }
}
