package kr.snu.etlassistant.update

import org.json.JSONObject
import java.net.URI

/** GitHub Release 하나에서 업데이트 알림에 필요한 것만 추린 값. */
data class ReleaseInfo(
    /** 태그 그대로(예: "v0.1.8"). 알림 중복 방지 키로도 쓴다. */
    val tag: String,
    /** 화면에 보일 버전(예: "0.1.8"). */
    val version: String,
    /** Release 페이지 주소. [UpdateLogic.isAllowedUrl]을 통과한 것만 들어온다. */
    val htmlUrl: String,
    val name: String,
    /** 릴리스 본문 앞부분([UpdateLogic.NOTES_MAX]자까지). */
    val notes: String,
    val apkName: String?,
    val apkSize: Long?,
) {
    /** 확인한 버전의 APK 직접 다운로드 주소. 이전에 저장한 결과에는 파일 이름이 없어 배포 규칙으로 복원한다. */
    val downloadUrl: String? get() = UpdateLogic.downloadUrl(tag, apkName)
}

/**
 * 업데이트 확인의 순수 로직(네트워크·안드로이드 없음, 단위 테스트 대상).
 * 응답은 GitHub이 주는 것이지만 그대로 믿지 않는다: APK 주소는 이 저장소의 태그·파일 이름으로 만들고, 길이는 자른다.
 */
object UpdateLogic {
    const val REPO_PAGE = "https://github.com/manig235/ETLAssistant/"
    const val LATEST_API = "https://api.github.com/repos/manig235/ETLAssistant/releases/latest"
    /** 본문은 앱 안 팝업 요약([promptSummary])에 쓰므로 바뀐 점 몇 줄이 들어갈 만큼 받는다. */
    const val NOTES_MAX = 2000
    private const val MAX_PARTS = 4

    /** "v0.1.8", "0.1.8", "V1.2" → 숫자 목록. 숫자와 점 외의 글자(예: "-beta")가 있거나 비어 있으면 null. */
    fun parseVersion(raw: String?): List<Int>? {
        val s = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (s.isEmpty()) return null
        val parts = s.split('.')
        if (parts.size > MAX_PARTS) return null
        return parts.map { p ->
            if (p.isEmpty() || p.length > 6 || !p.all { it in '0'..'9' }) return null
            p.toInt()
        }
    }

    /** 자리수가 달라도 빈 자리는 0으로 보고 비교한다(0.1 == 0.1.0). */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** [latest]가 [current]보다 새 버전인가. 둘 중 하나라도 읽을 수 없으면 false(잘못 알리지 않는다). */
    fun isNewer(latest: String?, current: String?): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return false
        return compare(l, c) > 0
    }

    /** 이 저장소의 Release 페이지(https://github.com/manig235/ETLAssistant/…)일 때만 true. */
    fun isAllowedUrl(url: String?): Boolean {
        if (url == null || !url.startsWith(REPO_PAGE) || url.any { it.isWhitespace() || it.code < 0x20 }) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
            (uri.rawPath ?: "").startsWith("/manig235/ETLAssistant/") && !(uri.rawPath ?: "").contains("..")
    }

    private fun isApkName(name: String): Boolean = name.length <= 200 && !name.contains("..") &&
        Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk", RegexOption.IGNORE_CASE).matches(name)

    /** Release 페이지를 거치지 않고 확인한 태그에 고정된 APK를 연다. */
    fun downloadUrl(tag: String, apkName: String?): String? {
        if (tag != tag.trim() || parseVersion(tag) == null) return null
        val version = tag.removePrefix("v").removePrefix("V")
        val name = if (apkName == null || apkName == "youngmin.apk") "youngmin-v$version.apk" else apkName
        if (!isApkName(name)) return null
        return "${REPO_PAGE}releases/download/$tag/$name"
    }

    /** 외부로 여는 업데이트 링크는 이 저장소의 APK asset 주소만 허용한다. */
    fun isAllowedDownloadUrl(url: String?): Boolean {
        if (!isAllowedUrl(url)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val prefix = "/manig235/ETLAssistant/releases/download/"
        val path = uri.rawPath ?: return false
        if (!path.startsWith(prefix)) return false
        val parts = path.removePrefix(prefix).split('/')
        return parts.size == 2 && parts[0] == parts[0].trim() && parseVersion(parts[0]) != null &&
            isApkName(parts[1]) && "${REPO_PAGE}releases/download/${parts[0]}/${parts[1]}" == url
    }

    /**
     * `releases/latest` 응답을 읽는다. 초안·사전 배포, 읽을 수 없는 태그, 허용되지 않은 주소면 null.
     * 버전이 붙은 배포 규칙의 asset만 쓴다. 버전 없는 별칭이 먼저 와도 선택하지 않는다.
     */
    fun parseRelease(json: String): ReleaseInfo? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val tag = o.optString("tag_name", "").trim()
        if (parseVersion(tag) == null) return null
        val url = o.optString("html_url", "")
        if (!isAllowedUrl(url)) return null
        var apkName: String? = null
        var apkSize: Long? = null
        val expectedApk = "youngmin-v${tag.removePrefix("v").removePrefix("V")}.apk"
        o.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (n == expectedApk) {
                    apkName = n
                    apkSize = a.optLong("size", -1L).takeIf { it > 0 }
                    break
                }
            }
        }
        if (apkName == null) return null
        return ReleaseInfo(
            tag = tag.take(40),
            version = tag.removePrefix("v").removePrefix("V"),
            htmlUrl = url,
            name = (if (o.isNull("name")) "" else o.optString("name", "")).take(100),
            notes = (if (o.isNull("body")) "" else o.optString("body", "")).take(NOTES_MAX),
            apkName = apkName,
            apkSize = apkSize,
        )
    }

    /** 알림을 띄울지: 지금 버전보다 새롭고, 이 태그로 아직 알리지 않았을 때만(버전마다 한 번). */
    fun shouldNotify(info: ReleaseInfo, currentVersion: String?, notifiedTag: String?): Boolean =
        isNewer(info.tag, currentVersion) && info.tag != notifiedTag

    /** 앱 시작 때 확인할 때가 됐는가(마지막 시도 후 [intervalMs]가 지났거나 시각이 거꾸로 갔으면). */
    fun startupCheckDue(lastCheckMs: Long, nowMs: Long, intervalMs: Long = 6L * 60 * 60 * 1000): Boolean =
        lastCheckMs <= 0L || nowMs - lastCheckMs >= intervalMs || nowMs < lastCheckMs

    /** 164_057_719 → "약 164MB"(1MB = 1,000,000바이트, 반올림). 1MB 미만이나 알 수 없으면 null. */
    fun sizeText(bytes: Long?): String? {
        if (bytes == null || bytes < 1_000_000L) return null
        return "약 ${(bytes + 500_000L) / 1_000_000L}MB"
    }

    /** 숫자로 끝나는 버전 뒤의 조사 "이/가"(2·4·5·9로 끝나면 "가": 이·사·오·구). */
    fun subjectParticle(version: String): String = when (version.lastOrNull()) {
        '2', '4', '5', '9' -> "가"
        else -> "이"
    }

    fun notificationTitle(info: ReleaseInfo): String = "새 버전 ${info.version}${subjectParticle(info.version)} 나왔어요"

    fun notificationText(info: ReleaseInfo): String =
        listOfNotNull("눌러서 받아요", sizeText(info.apkSize)).joinToString(" · ")

    /** 앱 안 팝업에서 "나중에"를 누른(또는 받기로 연) 뒤 같은 버전을 다시 띄우지 않는 시간. */
    const val PROMPT_SNOOZE_MS = 24L * 60 * 60 * 1000
    private const val SUMMARY_LINES = 4
    private const val SUMMARY_LINE_MAX = 60

    /**
     * 앱 안 팝업을 띄울지. 지금 버전보다 새롭고, 이 태그를 [PROMPT_SNOOZE_MS] 안에 미뤄 두지 않았을 때만.
     * 시각이 거꾸로 갔으면(미룬 시각이 미래) 미룸이 끝난 것으로 본다.
     */
    fun shouldPrompt(info: ReleaseInfo?, currentVersion: String?, snoozedTag: String?, snoozedAtMs: Long, nowMs: Long): Boolean {
        if (info == null || !isNewer(info.tag, currentVersion)) return false
        if (info.tag != snoozedTag) return true
        return nowMs < snoozedAtMs || nowMs - snoozedAtMs >= PROMPT_SNOOZE_MS
    }

    /**
     * 릴리스 본문에서 팝업에 보일 "바뀐 점" 몇 줄을 뽑는다(마크다운 기호는 지운다).
     * "## 바뀐 점" 절이 있으면 그 절의 목록만, 없으면 본문 전체의 목록을 본다. 목록 항목이 `**요약.**`으로 시작하면 그 요약만 쓴다.
     * 목록이 없으면 제목이 아닌 첫 줄 하나. 링크 주소는 보이지 않는다(글자만 남긴다).
     */
    fun promptSummary(notes: String?): List<String> {
        val lines = notes.orEmpty().replace("\r", "").lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("#") && it.contains("바뀐 점") }
        val section = if (start >= 0) {
            val rest = lines.drop(start + 1)
            rest.takeWhile { !it.trimStart().startsWith("#") }
        } else lines
        val bullets = section.map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }
        val picked = if (bullets.isNotEmpty()) bullets.map { bulletSummary(it.drop(2)) }
        else section.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(1).map { plain(it) }
        return picked.filter { it.isNotBlank() }.take(SUMMARY_LINES).map { clip(it) }
    }

    private fun bulletSummary(item: String): String {
        val t = item.trim()
        if (t.startsWith("**")) {
            val end = t.indexOf("**", 2)
            if (end > 2) return plain(t.substring(2, end)).trimEnd('.', ' ')
        }
        return plain(t)
    }

    /** 마크다운 강조·코드·링크 기호를 지운 글자. */
    internal fun plain(s: String): String =
        s.replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
            .replace("**", "").replace("__", "").replace("`", "")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun clip(s: String): String = if (s.length <= SUMMARY_LINE_MAX) s else s.take(SUMMARY_LINE_MAX - 1).trimEnd() + "…"

    /** 앱 안 팝업 본문: 바뀐 점 요약 + 크기. */
    fun promptBody(info: ReleaseInfo, summary: List<String>): String =
        (summary.map { "· $it" } + listOfNotNull(sizeText(info.apkSize)?.let { "크기 $it" })).joinToString("\n")

}
