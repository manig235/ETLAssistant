package kr.snu.etlassistant.notify

import java.net.URI

/** LearningX Student 2.0이 받는 Canvas 딥링크. eTL 주소만 받고 쿼리·fragment를 보존한다. */
internal object LearningXLink {
    const val PACKAGE = "com.instructure.candroid.xinics2.production"
    private val BOARD_REDIRECT = Regex("^/learningx/redirect(/courses/[1-9][0-9]*/external_tools/[1-9][0-9]*)$")

    /** eTL 링크만 HTTPS 원문 주소로 정규화한다. 사용자 정보·별도 서버 포트는 허용하지 않는다. */
    fun webUrl(url: String?): String? {
        val text = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.any { Character.isISOControl(it) || Character.isWhitespace(it) }) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
        if (!uri.host.equals("myetl.snu.ac.kr", true) || uri.rawUserInfo != null) return null
        val defaultPort = if (uri.scheme.equals("https", true)) 443 else 80
        if (uri.port != -1 && uri.port != defaultPort) return null
        return "https://myetl.snu.ac.kr" + uri.rawPath.orEmpty().ifEmpty { "/" } +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    }

    /** 게시글용 웹 리다이렉트는 LearningX가 받는 과목의 외부 도구 경로로 바꾼다(현재 앱은 목록을 연다). */
    fun fromWebUrl(url: String?): String? = webUrl(url)?.let { web ->
        val uri = URI(web)
        val board = BOARD_REDIRECT.matchEntire(uri.rawPath)?.groupValues?.get(1)
        val path = board ?: uri.rawPath
        "canvas-student://myetl.snu.ac.kr" + path +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    }

    fun opensBoardList(url: String): Boolean = webUrl(url)?.let {
        BOARD_REDIRECT.matches(URI(it).rawPath)
    } ?: false
}
