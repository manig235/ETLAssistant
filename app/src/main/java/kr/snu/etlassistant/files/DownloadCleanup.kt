package kr.snu.etlassistant.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/*
 * 받은 eTL 파일 자동 정리(2026-11-24, 사용자 결정).
 * - 대상: 앱이 MediaStore로 폰 `Download/etl/` 아래에 저장한 파일 중 저장한 지 N일(2·7·30) 지난 것. 앱 안 비서용 작업 폴더의
 *   14일 정리([kr.snu.etlassistant.etl.EtlFiles.prune])는 따로 그대로 돈다.
 * - 제외: AI가 만든 파일(`Download/etl/영민이가 만든 파일/`, [GeneratedFiles.isGenerated]), 앱 소유가 아닌 항목(재설치 등으로
 *   소유권이 없으면 권한을 요청하지 않고 건너뜀).
 * - 기간을 고르기 전에는 아무것도 지우지 않는다. 고르는 곳: 첫 실행 안내의 알림 단계, 기존 사용자는 채팅 화면을 처음 열 때 한 번
 *   (닫기만 하면 다음에 한 번 더 묻고, 두 번 닫으면 더 묻지 않고 정리하지 않는다), 설정에서 언제든.
 * - 하루 한 번, 네트워크 없이(주기 확인 작업 끝, 채팅 화면을 열 때). 알림은 보내지 않고 설정 화면에 "최근 정리: 11/24, 3개"만 보인다.
 */

/** 정리 기간. [days]가 null이면 정리하지 않는다. */
enum class CleanupPeriod(val key: String, val days: Int?, val label: String) {
    TWO("2", 2, "2일"),
    SEVEN("7", 7, "7일"),
    THIRTY("30", 30, "30일"),
    NEVER("never", null, "정리 안 함");

    companion object {
        fun of(key: String?): CleanupPeriod? = values().firstOrNull { it.key == key }
        /** 고르기 화면의 기본 선택. */
        val DEFAULT = TWO
    }
}

/** 정리 판정의 순수 부분(JVM 단위 테스트 대상). */
object DownloadCleanupLogic {
    const val DAY_MS = 24L * 3600 * 1000
    /** 하루 한 번: 마지막 실행 뒤 이만큼 지나야 다시 돈다(주기 작업 시각이 조금씩 밀려도 하루를 건너뛰지 않게 20시간). */
    const val MIN_INTERVAL_MS = 20L * 3600 * 1000
    /** 기존 사용자에게 고르기 대화상자를 띄우는 최대 횟수(닫기만 한 경우). */
    const val MAX_ASKS = 2

    /** `Download/etl/` 아래인가(대소문자 무시, 하위 폴더 포함). */
    fun isEtlDownload(relativePath: String?): Boolean {
        val p = (relativePath ?: return false).trim().replace('\\', '/').trimStart('/')
        return p.startsWith("Download/etl/", ignoreCase = true) || p.equals("Download/etl", ignoreCase = true)
    }

    /**
     * 이 항목을 지울지. [addedAtMs]는 저장 시각(MediaStore DATE_ADDED), [period]가 null이거나 [CleanupPeriod.NEVER]면 지우지 않는다.
     * 저장한 지 정확히 N일이 되면 지운다. 저장 시각이 미래(시계가 바뀐 경우)거나 모르면 지우지 않는다.
     */
    fun shouldDelete(relativePath: String?, addedAtMs: Long?, nowMs: Long, period: CleanupPeriod?, generated: Boolean, owned: Boolean = true): Boolean {
        val days = period?.days ?: return false
        if (!owned || generated || !isEtlDownload(relativePath) || GeneratedFilesLogic.isGeneratedPath(relativePath)) return false
        val added = addedAtMs?.takeIf { it > 0 } ?: return false
        val age = nowMs - added
        return age >= 0 && age >= days * DAY_MS
    }

    fun due(lastRunMs: Long, nowMs: Long): Boolean = lastRunMs <= 0 || nowMs < lastRunMs || nowMs - lastRunMs >= MIN_INTERVAL_MS

    /** 기존 사용자에게 고르기 대화상자를 띄울지: 아직 고르지 않았고 닫은 횟수가 [MAX_ASKS] 미만. */
    fun shouldAsk(period: CleanupPeriod?, asks: Int): Boolean = period == null && asks < MAX_ASKS

    /** 설정 화면 표시: "최근 정리: 11/24, 3개". 한 번도 돌지 않았으면 null. */
    fun lastRunLabel(lastRunMs: Long, count: Int, zone: java.time.ZoneId = java.time.ZoneId.of("Asia/Seoul")): String? {
        if (lastRunMs <= 0) return null
        val d = java.time.Instant.ofEpochMilli(lastRunMs).atZone(zone).toLocalDate()
        return "최근 정리: ${d.monthValue}/${d.dayOfMonth}, ${count}개"
    }
}

/** 설정 저장(앱 내부 SharedPreferences) + 정리 실행. */
object DownloadCleanup {
    private const val PREFS = "download_cleanup"
    private const val MAX_CLEANED = 500

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 고른 기간. 아직 고르지 않았으면 null(이때는 지우지 않는다). */
    fun period(context: Context): CleanupPeriod? = CleanupPeriod.of(prefs(context).getString("period", null))

    fun setPeriod(context: Context, p: CleanupPeriod) {
        prefs(context).edit().putString("period", p.key).apply()
        // 바로 한 번 정리해서 고른 결과가 보이게 한다(하루 한 번 제한과 별개).
        Thread({ runCatching { run(context, force = true) } }, "download-cleanup").apply { isDaemon = true }.start()
    }

    fun asks(context: Context): Int = prefs(context).getInt("asks", 0)
    fun markAsked(context: Context) = prefs(context).edit().putInt("asks", asks(context) + 1).apply()

    fun lastRunLabel(context: Context): String? =
        prefs(context).let { DownloadCleanupLogic.lastRunLabel(it.getLong("lastRun", 0), it.getInt("lastCount", 0)) }

    /** 자동 정리로 지운 파일인가(채팅 파일 카드가 "자동 정리된 파일"로 보인다). */
    fun wasCleaned(context: Context, uri: String?): Boolean =
        uri != null && runCatching { prefs(context).getStringSet("cleaned", emptySet())!!.contains(uri) }.getOrDefault(false)

    /** 하루 한 번만 돈다. 다른 스레드에서 부른다(MediaStore 조회). */
    fun runIfDue(context: Context) {
        runCatching { run(context, force = false) }
    }

    /** 지운 개수. 기간을 고르지 않았거나 정리 안 함이면 0이고 기록도 남기지 않는다. */
    @Synchronized fun run(context: Context, force: Boolean): Int {
        val app = context.applicationContext
        val p = prefs(app)
        val period = period(app)
        if (period?.days == null) return 0
        val now = System.currentTimeMillis()
        if (!force && !DownloadCleanupLogic.due(p.getLong("lastRun", 0), now)) return 0
        val cleaned = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 29) cleanMediaStore(app, period, now, cleaned) else cleanLegacy(app, period, now, cleaned)
        val set = LinkedHashSet(p.getStringSet("cleaned", emptySet()).orEmpty()).apply { addAll(cleaned) }
        p.edit()
            .putLong("lastRun", now)
            .putInt("lastCount", cleaned.size)
            .putStringSet("cleaned", if (set.size > MAX_CLEANED) set.toList().takeLast(MAX_CLEANED).toSet() else set)
            .apply()
        return cleaned.size
    }

    private fun cleanMediaStore(app: Context, period: CleanupPeriod, now: Long, out: MutableList<String>) {
        if (Build.VERSION.SDK_INT < 29) return
        val resolver = app.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.OWNER_PACKAGE_NAME,
        )
        // 권한 없이 조회하면 이 앱이 만든 항목만 온다. 그래도 소유자를 한 번 더 확인한다.
        val rows = mutableListOf<Pair<Uri, Boolean>>()
        resolver.query(collection, projection, "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("Download/etl/%"), null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val ownerCol = c.getColumnIndexOrThrow(MediaStore.Downloads.OWNER_PACKAGE_NAME)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                val rel = c.getString(pathCol)
                val added = if (c.isNull(addedCol)) null else c.getLong(addedCol) * 1000
                val owner = c.getString(ownerCol)
                val owned = owner == null || owner == app.packageName
                val generated = GeneratedFiles.isGenerated(app, uri.toString(), rel)
                rows += uri to DownloadCleanupLogic.shouldDelete(rel, added, now, period, generated, owned)
            }
        }
        for ((uri, delete) in rows) {
            if (!delete) continue
            // RecoverableSecurityException(소유권 없음) 등은 조용히 건너뛴다(권한을 요청하지 않는다).
            val ok = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            if (ok) out += uri.toString()
        }
    }

    /** Android 9 이하: 앱 전용 다운로드 폴더(`…/Download/etl/`)를 수정 시각으로 정리한다. */
    private fun cleanLegacy(app: Context, period: CleanupPeriod, now: Long, out: MutableList<String>) {
        val root = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return, "etl")
        root.listFiles().orEmpty().forEach { f ->
            if (!f.isFile) return@forEach // 하위 폴더(영민이가 만든 파일 등)는 건드리지 않는다
            val rel = "Download/etl/"
            val generated = GeneratedFiles.isGenerated(app, f.path, rel)
            if (DownloadCleanupLogic.shouldDelete(rel, f.lastModified(), now, period, generated) && f.delete()) out += f.path
        }
    }
}
