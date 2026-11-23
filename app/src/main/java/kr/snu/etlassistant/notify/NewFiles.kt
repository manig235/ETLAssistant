package kr.snu.etlassistant.notify

import android.content.Context
import kr.snu.etlassistant.etl.EtlAuthException
import kr.snu.etlassistant.etl.EtlClient
import kr.snu.etlassistant.etl.EtlException
import kr.snu.etlassistant.etl.EtlLogic.str
import org.json.JSONArray
import org.json.JSONObject

/*
 * 새 강의자료 알림(2026-11-23). 주기 확인의 마지막 단계로 [NewFiles.INTERVAL_MS]마다, 현재 학기 강의를 하나씩 차례로
 * `GET /api/v1/courses/{id}/files?sort=updated_at&order=desc&per_page=30`(첫 쪽만)로 보고, 전에 못 본 파일 ID가 있으면 과목별로 묶어 알린다.
 * 파일은 받지 않는다. 판정은 순수 함수([NewFiles]), 저장은 [NewFilesStore], 실행은 [NewFilesCheck].
 */

/** 과목 하나: 본 파일 ID(최근 것부터, 최대 [NewFiles.SEEN_MAX]개)와 파일 탭이 막혀 건너뛰는 기한(epoch millis, 0이면 없음). */
data class CourseFiles(val seen: List<Long>, val skipUntil: Long = 0L)

data class FilesWatchState(val lastRunAt: Long = 0L, val courses: Map<Long, CourseFiles> = emptyMap()) {
    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("lastRunAt", lastRunAt)
        .put("courses", JSONObject().also { o ->
            courses.forEach { (id, c) -> o.put(id.toString(), JSONObject().put("seen", JSONArray(c.seen)).put("skipUntil", c.skipUntil)) }
        }).toString()

    companion object {
        fun fromJson(s: String?): FilesWatchState = try {
            val o = JSONObject(s ?: return FilesWatchState())
            val courses = mutableMapOf<Long, CourseFiles>()
            o.optJSONObject("courses")?.let { c ->
                c.keys().forEach { k ->
                    val id = k.toLongOrNull() ?: return@forEach
                    val x = c.optJSONObject(k) ?: return@forEach
                    val arr = x.optJSONArray("seen") ?: JSONArray()
                    courses[id] = CourseFiles((0 until arr.length()).map { arr.optLong(it) }, x.optLong("skipUntil"))
                }
            }
            FilesWatchState(o.optLong("lastRunAt"), courses)
        } catch (_: Exception) {
            FilesWatchState()
        }
    }
}

data class FileRef(val id: Long, val name: String)

object NewFiles {
    /** 확인 간격. 주기 확인(1시간)마다가 아니라 4시간에 한 번만 과목별 요청을 보낸다. */
    const val INTERVAL_MS = 4L * 3600 * 1000
    /** 주기 작업이 조금 일찍 돌아도 건너뛰지 않게 둔 여유. */
    private const val SLACK_MS = 10L * 60 * 1000
    const val SEEN_MAX = 500
    /** 파일 탭이 숨겨진 강의(401 권한 없음 등)는 이만큼 건너뛴다. */
    const val SKIP_MS = 7L * 24 * 3600 * 1000
    /** 연결 실패가 이만큼 이어지면 나머지 강의는 이번에 건너뛴다(백그라운드 네트워크 차단 때 강의마다 시간 초과를 기다리지 않게). */
    const val MAX_CONSECUTIVE_NETWORK_FAILURES = 2
    private const val BIG_LINES = 8
    private const val FILE_NAME_MAX = 60

    fun path(courseId: Long) = "/api/v1/courses/$courseId/files?sort=updated_at&order=desc&per_page=30"

    /** 확인할 때가 됐는가. 시계가 크게 뒤로 갔으면(저장 시각이 미래) 바로 돈다. */
    fun due(state: FilesWatchState, nowMs: Long): Boolean =
        state.lastRunAt <= 0 || state.lastRunAt > nowMs + 60_000 || nowMs - state.lastRunAt >= INTERVAL_MS - SLACK_MS

    /** 숨김(`hidden_for_user`)·잠김(`locked_for_user`) 파일은 뺀다. */
    fun parse(items: List<JSONObject>): List<FileRef> = items
        .filter { !it.optBoolean("hidden_for_user", false) && !it.optBoolean("locked_for_user", false) && it.optLong("id") > 0 }
        .map { FileRef(it.optLong("id"), it.str("display_name") ?: it.str("filename") ?: "(이름 없음)") }

    /** 강의 하나를 조회한 결과. */
    sealed class Outcome {
        data class Files(val files: List<FileRef>) : Outcome()
        /** 파일 탭이 숨겨졌거나 권한이 없음(401 권한 없음, 403, 404). */
        object Denied : Outcome()
        /** eTL이 401을 줬는데 권한 문구가 없어 토큰 거부로 보인 경우. */
        object AuthRejected : Outcome()
        /** 연결 실패 등. 기준선을 바꾸지 않는다. */
        object Failed : Outcome()
    }

    /**
     * 강의 하나의 비교. 처음 보는 강의는 기준선만 잡는다(알리지 않음). 새 파일은 조회 순서(최근 갱신 순)대로.
     */
    fun diffCourse(prev: CourseFiles?, files: List<FileRef>): Pair<CourseFiles, List<FileRef>> {
        val ids = files.map { it.id }
        if (prev == null) return CourseFiles(ids.distinct().take(SEEN_MAX)) to emptyList()
        val seen = prev.seen.toHashSet()
        val fresh = files.filter { it.id !in seen }.distinctBy { it.id }
        return CourseFiles((ids + prev.seen).distinct().take(SEEN_MAX)) to fresh
    }

    /** 이번에 조회할 강의(건너뛰는 기한이 지난 것). */
    fun toCheck(state: FilesWatchState, courses: List<CourseRef>, nowMs: Long): List<CourseRef> =
        courses.filter { it.id > 0 }.distinctBy { it.id }.filter { (state.courses[it.id]?.skipUntil ?: 0L) <= nowMs }

    /**
     * 조회 결과를 반영한다. [courses]는 이번 학기 강의 전체(목록에 없는 강의의 기록은 지운다).
     * [muted]인 과목은 알리지 않지만 기준선은 갱신한다(다시 켰을 때 그동안 올라온 자료가 한꺼번에 오지 않게).
     * 401인데 권한 문구가 없던 경우([Outcome.AuthRejected])는 모든 강의가 그랬으면 토큰 문제로 보고 실패로 둔다(7일 건너뛰지 않음).
     * 성공한 강의가 하나도 없으면 [FilesWatchState.lastRunAt]을 바꾸지 않아 다음 확인에서 다시 시도한다.
     */
    fun apply(
        state: FilesWatchState,
        nowMs: Long,
        courses: List<CourseRef>,
        results: Map<Long, Outcome>,
        muted: (Long) -> Boolean = { false },
    ): Pair<FilesWatchState, List<Notice>> {
        val allAuth = results.isNotEmpty() && results.values.all { it == Outcome.AuthRejected }
        val current = courses.map { it.id }.toSet()
        val next = state.courses.filterKeys { it in current || current.isEmpty() }.toMutableMap()
        val notices = mutableListOf<Notice>()
        var anyOk = false
        for (c in courses) {
            when (val r = results[c.id] ?: continue) {
                is Outcome.Files -> {
                    anyOk = true
                    val (rec, fresh) = diffCourse(state.courses[c.id], r.files)
                    next[c.id] = rec
                    if (fresh.isNotEmpty() && !muted(c.id)) notices += notice(c, fresh)
                }
                Outcome.Denied -> {
                    anyOk = true
                    next[c.id] = (state.courses[c.id] ?: CourseFiles(emptyList())).copy(skipUntil = nowMs + SKIP_MS)
                }
                Outcome.AuthRejected -> if (!allAuth) {
                    anyOk = true
                    next[c.id] = (state.courses[c.id] ?: CourseFiles(emptyList())).copy(skipUntil = nowMs + SKIP_MS)
                }
                Outcome.Failed -> {}
            }
        }
        return FilesWatchState(if (anyOk) nowMs else state.lastRunAt, next) to notices
    }

    /** 채팅 입력창에 채워 둘 문구(보내지는 않는다). 파일 이름은 eTL 데이터라 정리하고 자른다. */
    fun prefill(course: CourseRef, files: List<FileRef>): String {
        val names = files.map { NoticeText.safe(it.name, FILE_NAME_MAX) }
        val what = when {
            names.size <= 3 -> names.joinToString(", ")
            else -> "${names.first()} 외 ${names.size - 1}개"
        }
        return "${NoticeText.course(course.name)} 새 강의자료 $what 받아줘"
    }

    /**
     * 과목별 묶음 알림. 누르면 채팅이 열리고 받아 달라는 문구가 입력창에 채워진다. "eTL에서 열기"는 그 과목의 파일 화면.
     */
    fun notice(course: CourseRef, files: List<FileRef>): Notice = Notice(
        channel = Channel.NEW_FILES,
        title = NoticeText.cut("새 강의자료 ${files.size}개 · ${NoticeText.clean(course.name)}", NoticeText.TITLE_MAX),
        text = NoticeText.cut(files.joinToString(", ") { NoticeText.safe(it.name, FILE_NAME_MAX) }, NoticeText.LINE_MAX),
        bigText = if (files.size > 1) NoticeText.joinLimited(files.map { "• " + NoticeText.safe(it.name, FILE_NAME_MAX) }, BIG_LINES) else null,
        url = "${NoticeText.ETL_PREFIX}courses/${course.id}/files",
        tag = "files:${course.id}",
        prefill = prefill(course, files),
    )
}

class NewFilesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notify_files", Context.MODE_PRIVATE)
    @Synchronized fun load(): FilesWatchState = FilesWatchState.fromJson(prefs.getString("state", null))
    @Synchronized fun save(s: FilesWatchState) { prefs.edit().putString("state", s.toJson()).apply() }
}

/** 네트워크 부분: 강의를 하나씩 차례로(병렬 없이) 조회한다. GET만, 첫 쪽만. */
object NewFilesCheck {
    fun fetch(client: EtlClient, courses: List<CourseRef>): Map<Long, NewFiles.Outcome> {
        val out = linkedMapOf<Long, NewFiles.Outcome>()
        var networkFailures = 0
        for (c in courses) {
            if (networkFailures >= NewFiles.MAX_CONSECUTIVE_NETWORK_FAILURES) {
                out[c.id] = NewFiles.Outcome.Failed
                continue
            }
            out[c.id] = try {
                val arr = client.get(NewFiles.path(c.id)) as? JSONArray ?: throw EtlException("목록이 아닌 응답")
                networkFailures = 0
                NewFiles.Outcome.Files(NewFiles.parse((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }))
            } catch (e: EtlAuthException) {
                networkFailures = 0
                NewFiles.Outcome.AuthRejected
            } catch (e: EtlException) {
                when (e.kind) {
                    "forbidden", "http-403", "http-404" -> { networkFailures = 0; NewFiles.Outcome.Denied }
                    "timeout", "network" -> { networkFailures++; NewFiles.Outcome.Failed }
                    else -> NewFiles.Outcome.Failed
                }
            }
        }
        return out
    }
}
