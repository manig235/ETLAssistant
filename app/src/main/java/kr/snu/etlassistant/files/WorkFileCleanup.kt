package kr.snu.etlassistant.files

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kr.snu.etlassistant.etl.EtlFiles
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 앱 전용 작업 사본만 정리한다. 다운로드 폴더의 사용자 사본과 인증 폴더는 대상이 아니다. */
object WorkFiles {
    fun prune(root: File, now: Long): Int {
        if (Files.isSymbolicLink(root.toPath())) return 0
        var count = 0
        root.listFiles()?.forEach { file ->
            if (Files.isSymbolicLink(file.toPath())) return@forEach
            if (file.isDirectory) count += prune(file, now)
            else if (now - file.lastModified() >= TimeUnit.DAYS.toMillis(EtlFiles.KEEP_DAYS.toLong()) && file.delete()) count++
        }
        return count
    }

    /** 읽기에 실패하면 null: 다른 대화가 참조하는지 알 수 없으므로 삭제하지 않는다. */
    fun attachments(chat: File): Set<String>? = try {
        val messages = JSONArray(chat.readText())
        buildSet {
            for (i in 0 until messages.length()) {
                val files = messages.optJSONObject(i)?.optJSONArray("attachments") ?: continue
                for (j in 0 until files.length()) {
                    files.optJSONObject(j)?.optString("path")?.takeIf { it.isNotBlank() && it != "null" }?.let { add(it) }
                }
            }
        }
    } catch (_: Exception) { null }

    /** 다른 대화에서 참조하지 않는 첨부 사본만 지운다. 경로 탈출과 심볼릭 링크를 따라가지 않는다. */
    fun deleteUnshared(root: File, candidates: Set<String>, otherChats: List<File>): Int {
        val references = mutableSetOf<String>()
        for (chat in otherChats) references += attachments(chat) ?: return 0
        val base = root.absoluteFile.toPath().normalize()
        if (Files.isSymbolicLink(base)) return 0
        return candidates.count { path ->
            try {
                val file = File(path).absoluteFile.toPath().normalize()
                if (!file.startsWith(base) || file == base) return@count false
                var cursor = file
                while (cursor != base) {
                    if (Files.isSymbolicLink(cursor)) return@count false
                    cursor = cursor.parent ?: return@count false
                }
                if (references.any { File(it).absoluteFile.toPath().normalize() == file }) return@count false
                Files.isRegularFile(file) && Files.deleteIfExists(file)
            } catch (_: Exception) { false }
        }
    }
}

object WorkFileCleanup {
    fun root(context: Context) = File(context.filesDir, "workspace/${EtlFiles.DIR}")
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "private-work-file-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WorkFileCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }
    fun run(context: Context) = WorkFiles.prune(root(context), System.currentTimeMillis())
}

class WorkFileCleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        WorkFileCleanup.run(applicationContext)
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
