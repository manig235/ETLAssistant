package kr.snu.etlassistant.etl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 성적 통계 도구(2026-11-28): `score_stats`(읽기, 폰 안만), `score_stats_save`·`score_stats_delete`(폰 안 쓰기).
 * eTL에는 아무것도 보내지 않는다. 쓰기는 비서 쓰기 권한(성적 통계)과 확인 창([AiWriteApproval])을 거치고 채팅에 한 줄을 남긴다.
 */
class ScoreStatsTools(private val context: Context) {

    companion object {
        const val READ = "score_stats"
        const val SAVE = "score_stats_save"
        const val DELETE = "score_stats_delete"
        val NAMES = setOf(READ, SAVE, DELETE)

        private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
        private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
        private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
        private fun spec(name: String, description: String, props: JSONObject, required: List<String>, readOnly: Boolean) = JSONObject()
            .put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
            .put("annotations", JSONObject().put("readOnlyHint", readOnly).put("destructiveHint", false).put("openWorldHint", false))

        fun specs(untrusted: String, writeRule: String): List<JSONObject> = listOf(
            spec(READ, "폰에 저장된 성적 통계(시험·과제 점수 분포)와 내 점수 위치 어림. eTL이 준 분포(서울대 eTL은 최저·최고·평균만)와 공지에서 읽어 저장한 분포(사분위·표준편차 포함 가능)를 합쳐 과목별로 돌려준다. " +
                "\"중간고사 나 어느 정도야?\" 같은 질문에 먼저 쓰고, 없으면 etl_grades를 본다. position은 범위 어림일 뿐 정확한 등수가 아니다(사분위 사이는 고르게 퍼졌다고 가정, 평균·표준편차만 있으면 정규분포 가정). 네트워크 요청 없음. $untrusted",
                JSONObject().put("course_id", int("이 과목만(선택)")), emptyList(), readOnly = true),
            spec(SAVE, "시험·과제 점수 분포를 폰의 성적 통계에 저장한다(eTL은 바뀌지 않음). 공지 본문이나 히스토그램 그림(etl_view_image)에서 읽은 숫자를 사용자가 저장해 달라고 할 때 쓴다. $writeRule " +
                "읽은 값이 불확실하면(그림이 흐림 등) 저장 전에 사용자에게 확인한다. 점수는 모두 같은 기준(배점)으로: 공지가 100점 환산이면 my_score·points_possible도 그 기준으로 바꾼다. " +
                "eTL 과제와 같은 시험이면 assignment_id를 주면 eTL의 최저·최고·평균·내 점수와 합쳐 보인다(같은 배점일 때만). 같은 과목·제목이나 id를 주면 고친다.",
                JSONObject()
                    .put("title", str("시험·과제 이름(${ScoreStats.MAX_TITLE}자 이내). 예: 중간고사. 새로 저장할 때 필수(id로 고칠 때는 생략 가능)"))
                    .put("course_id", int("과목 ID(etl_courses). 모르면 course"))
                    .put("course", str("과목명(course_id가 없을 때)"))
                    .put("assignment_id", int("같은 시험의 eTL 과제 ID(etl_grades의 assignment_id, 선택)"))
                    .put("id", str("고칠 저장 항목 id(score_stats의 id, 선택)"))
                    .put("min", num("최저점")).put("max", num("최고점")).put("mean", num("평균"))
                    .put("q1", num("하위 25% 지점(1사분위, Q1)")).put("median", num("중앙값(Q2)")).put("q3", num("상위 25% 지점(3사분위, Q3)"))
                    .put("stddev", num("표준편차")).put("count", int("응시·채점 인원"))
                    .put("my_score", num("내 점수(같은 기준)")).put("points_possible", num("만점(배점)"))
                    .put("source_announcement_id", int("숫자를 읽은 공지의 ID(\"(공지 ID N)\"이 붙은 요청이면 그 N)")),
                emptyList(), readOnly = false),
            spec(DELETE, "저장된 성적 통계 하나를 지운다(id는 score_stats의 id). 사용자가 지워 달라고 했을 때만 쓴다.",
                JSONObject().put("id", str("score_stats의 id")), listOf("id"), readOnly = false),
        )
    }

    private val store = ScoreStatsStore(context)

    fun call(name: String, args: JSONObject): JSONObject = when (name) {
        READ -> read(optLong(args, "course_id"))
        SAVE -> save(args)
        DELETE -> delete(args.optString("id", "").trim())
        else -> throw EtlException("알 수 없는 도구예요")
    }

    private fun read(courseId: Long?): JSONObject {
        val rows = store.rows().filter { courseId == null || it.entry.courseId == courseId }
        return JSONObject().put("count", rows.size).put("items", JSONArray(rows.map(ScoreStats::toolJson)))
            .put("note", "eTL 분포는 앱이 eTL을 확인할 때(한 시간마다, etl_grades 호출 때) 저장돼요. 교수가 분포를 숨기면 eTL에는 없어요.")
    }

    private fun optLong(args: JSONObject, key: String): Long? {
        if (!args.has(key) || args.isNull(key)) return null
        val v = args.opt(key)
        val n = when (v) { is Number -> v.toLong(); is String -> v.trim().toLongOrNull(); else -> null }
        if (n == null || n <= 0) throw EtlException("$key 는 양의 정수여야 해요")
        return n
    }

    private fun optNum(args: JSONObject, key: String): Double? {
        if (!args.has(key) || args.isNull(key)) return null
        val v = args.opt(key)
        val d = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }
        if (d == null || !d.isFinite()) throw EtlException("$key 는 숫자여야 해요")
        return d
    }

    /** 과목 ID로 이름 찾기: 저장된 통계 → 받아 둔 과제 목록 → 시간표. */
    private fun courseName(id: Long): String? =
        store.load().firstOrNull { it.courseId == id && it.courseName.isNotBlank() }?.courseName
            ?: runCatching { kr.snu.etlassistant.notify.SnapshotCache(context).load()?.assignments?.firstOrNull { it.courseId == id }?.courseName }.getOrNull()
            ?: runCatching { TimetableStore(context).load()?.courses?.firstOrNull { it.courseId == id }?.name }.getOrNull()

    private fun save(args: JSONObject): JSONObject {
        val titleArg = args.optString("title", "").trim().replace(Regex("\\s+"), " ").take(ScoreStats.MAX_TITLE)
        val courseId = optLong(args, "course_id")
        val courseText = args.optString("course", "").trim().take(80).takeIf { !args.isNull("course") }.orEmpty()
        val courseArg: String? = courseId?.let(::courseName) ?: courseText.ifEmpty { null } ?: courseId?.let { "과목 $it" }
        val stats = ScoreStatistics(
            min = optNum(args, "min"), max = optNum(args, "max"), mean = optNum(args, "mean"),
            q1 = optNum(args, "q1"), median = optNum(args, "median"), q3 = optNum(args, "q3"), stddev = optNum(args, "stddev"),
            count = optLong(args, "count")?.toInt(),
        )
        val myArg = optNum(args, "my_score")
        val pointsArg = optNum(args, "points_possible")
        val assignmentArg = optLong(args, "assignment_id")
        val annArg = optLong(args, "source_announcement_id")
        val wanted = args.optString("id", "").trim().takeIf { it.isNotEmpty() && !args.isNull("id") }
        if (wanted == null && titleArg.isEmpty()) throw EtlException("title이 필요해요")
        if (wanted == null && courseArg == null) throw EtlException("course_id나 course가 필요해요")
        val all = store.load()
        val existing = when {
            wanted != null -> all.firstOrNull { it.id == wanted && it.source != ScoreSource.ETL }
                ?: throw EtlException("고칠 수 있는 성적 통계 id $wanted 를 찾지 못했어요. score_stats로 확인해 주세요(eTL 항목은 고칠 수 없어요)")
            else -> all.firstOrNull { e ->
                e.source != ScoreSource.ETL && (if (courseId != null) e.courseId == courseId else e.courseName == courseArg) &&
                    (e.title.equals(titleArg, ignoreCase = true) || (assignmentArg != null && e.assignmentId == assignmentArg))
            }
        }
        val title = titleArg.ifEmpty { existing?.title.orEmpty() }
        val course = courseArg ?: existing?.courseName.orEmpty()
        // 고칠 때 주지 않은 값은 그대로 둔다(예: 앱이 공지에서 저장한 항목에 내 점수만 넣기).
        val merged = if (existing != null) stats.fillFrom(existing.stats) else stats
        val my = myArg ?: existing?.myScore
        val points = pointsArg ?: existing?.pointsPossible
        val ann = annArg ?: existing?.sourceAnnouncementId
        ScoreStats.validate(merged, my, points)?.let { throw EtlException(it) }
        val entry = ScoreStatEntry(
            id = existing?.id ?: "manual:${AssistantWriteIds.next()}",
            source = if (ann != null) ScoreSource.ANNOUNCEMENT else ScoreSource.MANUAL,
            courseId = courseId ?: existing?.courseId, courseName = course,
            assignmentId = assignmentArg ?: existing?.assignmentId, title = title, stats = merged,
            myScore = my, pointsPossible = points, fetchedAt = System.currentTimeMillis(), sourceAnnouncementId = ann,
        )
        val preview = preview(entry)
        if (existing == null) {
            AiWriteApproval.execute(context, WriteScope.SCORES, WriteKind.ADD, "성적 통계 저장", preview) { store.upsert(entry) }
        } else {
            AiWriteApproval.execute(context, WriteScope.SCORES, WriteKind.UPDATE, "성적 통계 고치기", "변경 전\n${preview(existing)}\n\n변경 후\n$preview") {
                if (store.load().firstOrNull { it.id == existing.id } != existing) throw EtlException("확인하는 동안 성적 통계가 바뀌었어요. 다시 조회해 주세요.")
                store.upsert(entry)
            }
        }
        val row = ScoreStats.rows(store.load()).firstOrNull { entry.id in it.ids }
        val line = row?.let { ScoreStats.summaryLine(it.entry.stats, it.entry.myScore) }.orEmpty()
        EtlEvents.notice("📊 성적 통계 ${if (existing == null) "저장" else "고침"}: $course $title" + (if (line.isNotEmpty()) " · $line" else "") +
            " · 서랍 → 성적 통계에서 지울 수 있어요")
        return JSONObject().put("ok", true).put("id", entry.id).put("updated", existing != null)
            .put("result", row?.let(ScoreStats::toolJson) ?: JSONObject.NULL)
    }

    private fun delete(id: String): JSONObject {
        if (id.isEmpty()) throw EtlException("id가 필요해요")
        val old = store.load().firstOrNull { it.id == id } ?: throw EtlException("성적 통계 id $id 를 찾지 못했어요. score_stats로 확인해 주세요")
        AiWriteApproval.execute(context, WriteScope.SCORES, WriteKind.DELETE, "성적 통계 삭제", preview(old) +
            if (old.source == ScoreSource.ETL) "\n\neTL에서 온 통계는 지운 뒤 다시 들어오지 않아요." else "") {
            if (store.load().firstOrNull { it.id == id } != old) throw EtlException("확인하는 동안 성적 통계가 바뀌었어요. 다시 조회해 주세요.")
            store.delete(listOf(id))
        }
        EtlEvents.notice("🗑 성적 통계에서 지움: ${old.courseName} ${old.title}")
        return JSONObject().put("ok", true)
    }

    private fun preview(e: ScoreStatEntry): String = buildString {
        appendLine("${e.courseName} · ${e.title}")
        appendLine(ScoreStats.statsText(e.stats))
        e.myScore?.let { appendLine("내 점수: ${ScoreStats.num(it)}" + (e.pointsPossible?.let { p -> " / ${ScoreStats.num(p)}" } ?: "")) }
            ?: e.pointsPossible?.let { appendLine("만점: ${ScoreStats.num(it)}") }
        ScoreStats.position(e.stats, e.myScore)?.let { appendLine("위치: ${it.label} (어림)") }
        appendLine("출처: ${e.source.label}" + (e.sourceAnnouncementId?.let { " (공지 ID $it)" } ?: ""))
        append("이 폰에만 저장돼요.")
    }
}
