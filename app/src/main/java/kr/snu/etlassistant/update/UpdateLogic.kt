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
)

/**
 * 업데이트 확인의 순수 로직(네트워크·안드로이드 없음, 단위 테스트 대상).
 * 응답은 GitHub이 주는 것이지만 그대로 믿지 않는다: 주소는 이 저장소 Release 페이지일 때만 열고, 길이는 자른다.
 */
object UpdateLogic {
    const val REPO_PAGE = "https://github.com/manig235/ETLAssistant/"
    const val LATEST_API = "https://api.github.com/repos/manig235/ETLAssistant/releases/latest"
    const val NOTES_MAX = 300
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

    /**
     * `releases/latest` 응답을 읽는다. 초안·사전 배포, 읽을 수 없는 태그, 허용되지 않은 주소면 null.
     * apk는 이름이 .apk로 끝나는 첫 asset을 쓴다.
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
        o.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (n.endsWith(".apk", ignoreCase = true)) {
                    apkName = n.take(200)
                    apkSize = a.optLong("size", -1L).takeIf { it > 0 }
                    break
                }
            }
        }
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

    /** 설정 "앱 정보"에 보일 문구. */
    fun availableText(version: String): String = "새 버전 $version${subjectParticle(version)} 있어요"
}
