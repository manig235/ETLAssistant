package kr.snu.etlassistant.codex

import java.io.File
import java.io.IOException

/**
 * 지운 대화의 Codex 기록(rollout 파일)을 찾아 지운다. 대화 전문과 eTL 도구 결과가 들어 있다.
 *
 * Codex는 스레드마다 `CODEX_HOME/sessions/YYYY/MM/DD/rollout-<시각>-<스레드ID>.jsonl`을 쓰고,
 * `thread/archive`하면 `CODEX_HOME/archived_sessions/`로 옮긴다(압축본은 `.jsonl.zst`).
 * 앱은 `thread/delete`(rollout과 내부 DB 행을 함께 지움)를 먼저 부르고, 그래도 남은 파일을 여기서 지운다.
 * `codex-home` 안의 이 두 폴더만 보고, 심볼릭 링크는 따라가지 않는다.
 */
object ThreadPurge {

    val DIRS = listOf("sessions", "archived_sessions")
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val ROLLOUT = Regex("^rollout-.*-(${UUID.pattern})\\.jsonl(\\.zst)?$")
    private const val MAX_DEPTH = 5

    fun isThreadId(s: String): Boolean = UUID.matches(s)

    /** rollout 파일 이름의 스레드 ID(소문자). rollout 파일이 아니면 null. */
    fun threadIdOf(fileName: String): String? = ROLLOUT.matchEntire(fileName)?.groupValues?.get(1)?.lowercase()

    /** [threadId]의 rollout 파일들. 잘못된 ID면 빈 목록. */
    fun find(codexHome: File, threadId: String): List<File> {
        if (!isThreadId(threadId)) return emptyList()
        val id = threadId.lowercase()
        return rollouts(codexHome).filter { threadIdOf(it.name) == id }
    }

    /** [threadId]의 rollout 파일을 지우고 지운 개수를 돌려준다. */
    fun delete(codexHome: File, threadId: String): Int = find(codexHome, threadId).count { it.delete() }

    /** 보관함(archived_sessions)에 남은 스레드 중 [keep]에 없는 것. 예전 버전이 대화를 지울 때 보관만 해 둔 기록이다. */
    fun archivedOrphans(codexHome: File, keep: Set<String>): Set<String> {
        val k = keep.map { it.lowercase() }.toSet()
        return rollouts(codexHome, listOf("archived_sessions"))
            .mapNotNull { threadIdOf(it.name) }.filter { it !in k }.toSet()
    }

    /** `codex-home/{sessions,archived_sessions}` 아래의 rollout 파일(심볼릭 링크와 바깥 경로 제외). */
    fun rollouts(codexHome: File, dirs: List<String> = DIRS): List<File> {
        val home = try { codexHome.canonicalFile } catch (_: IOException) { return emptyList() }
        val out = mutableListOf<File>()
        for (d in dirs) {
            val root = File(home, d)
            if (!root.isDirectory || isLink(root)) continue
            walk(home, root, 0, out)
        }
        return out
    }

    private fun walk(home: File, dir: File, depth: Int, out: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (isLink(f) || !inside(home, f)) continue
            when {
                f.isDirectory -> walk(home, f, depth + 1, out)
                f.isFile && threadIdOf(f.name) != null -> out += f
            }
        }
    }

    private fun isLink(f: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (_: Exception) {
        true
    }

    private fun inside(home: File, f: File): Boolean = try {
        f.canonicalPath.startsWith(home.path + File.separator)
    } catch (_: IOException) {
        false
    }
}
