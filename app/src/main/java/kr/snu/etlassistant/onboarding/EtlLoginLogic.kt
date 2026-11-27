package kr.snu.etlassistant.onboarding

import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * "서울대 로그인으로 eTL 연결"의 순수 규칙(JVM 단위 테스트 대상). 안드로이드 API를 쓰지 않는다.
 *
 * 흐름: 앱 안 로그인 화면에서 사용자가 직접 서울대 SSO로 로그인 → eTL(myetl) 화면에 돌아오면 그 세션 쿠키로
 * `GET /api/v1/users/self`(세션 확인) → `POST /api/v1/users/self/tokens`(토큰 하나 만들기) → 그 토큰으로 다시 확인해 저장.
 * POST는 사용자가 연결 버튼을 누르고 방금 로그인한 경우에만, 이 고정 주소 하나로만 보낸다(CLAUDE.md 보안 규칙의 예외).
 */
object EtlLoginLogic {

    const val ORIGIN = "https://myetl.snu.ac.kr"
    const val HOST = "myetl.snu.ac.kr"
    const val START_URL = "$ORIGIN/login"
    const val SELF_URL = "$ORIGIN/api/v1/users/self"
    const val TOKENS_URL = "$ORIGIN/api/v1/users/self/tokens"
    const val CSRF_COOKIE = "_csrf_token"

    /** 로그인 화면(WebView)이 어떤 주소로 이동하려 할 때 할 일. */
    enum class Nav {
        /** 이 화면에서 연다(서울대 https 주소). */
        LOAD,
        /** 다른 앱(Pass-Ni 같은 인증 앱)에 넘긴다. `intent:`·사용자 정의 스킴. */
        EXTERNAL_APP,
        /** 브라우저로 연다(서울대 밖 https 도움말 페이지 등). 로그인 화면에는 서울대 주소만 띄운다. */
        BROWSER,
        /** 열지 않는다(http, file, content, javascript, data 등). */
        BLOCK,
    }

    private val BLOCKED_SCHEMES = setOf(
        "http", "file", "content", "javascript", "data", "blob", "filesystem", "about", "chrome", "view-source", "ws", "wss", "ftp",
    )

    fun navigation(rawUrl: String): Nav {
        val url = rawUrl.trim()
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme.isEmpty() || !SCHEME_RE.matches(scheme)) return Nav.BLOCK
        if (scheme == "https") {
            val host = hostOf(url) ?: return Nav.BLOCK
            if (hasUserInfo(url)) return Nav.BLOCK
            return if (isSnuHost(host)) Nav.LOAD else Nav.BROWSER
        }
        if (scheme in BLOCKED_SCHEMES) return Nav.BLOCK
        return Nav.EXTERNAL_APP
    }

    /** 서울대 주소인가(snu.ac.kr과 그 하위 도메인만). */
    fun isSnuHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == "snu.ac.kr" || h.endsWith(".snu.ac.kr")
    }

    /**
     * 로그인을 마치고 eTL 화면에 돌아온 것 같은 주소인가. eTL 호스트의 https 주소이고 로그인·로그아웃 중간 단계가 아니어야 한다.
     * 맞아도 실제 로그인 여부는 세션으로 `users/self`를 불러 확인한다.
     */
    fun isLoggedInCandidate(rawUrl: String?): Boolean {
        if (rawUrl == null) return false
        val uri = try { URI(rawUrl.trim()) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", true) || !uri.host.equals(HOST, true) || uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val path = (uri.rawPath ?: "").lowercase()
        val transient = listOf("/login", "/learningx/login", "/logout", "/learningx/logout", "/xn-sso", "/passni")
        return transient.none { path == it || path.startsWith("$it/") }
    }

    /** 쿠키 헤더(`a=1; b=2`)에서 [name] 값을 그대로(인코딩된 채로). 없으면 null. */
    fun cookieValue(cookieHeader: String?, name: String): String? {
        if (cookieHeader.isNullOrEmpty()) return null
        for (part in cookieHeader.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * Canvas가 비GET 요청에 요구하는 `X-CSRF-Token` 값: `_csrf_token` 쿠키를 URL 디코딩한 것(웹 화면의 `decodeURIComponent`와 같게,
     * `+`는 공백으로 바꾸지 않는다). 없거나 이상하면 null.
     */
    fun csrfToken(cookieHeader: String?): String? {
        val raw = cookieValue(cookieHeader, CSRF_COOKIE) ?: return null
        val decoded = percentDecode(raw) ?: return null
        return decoded.takeIf { it.isNotEmpty() && it.length <= 512 && it.all { c -> c in '!'..'~' } }
    }

    /** `%XX`만 푼다(UTF-8). 잘못된 인코딩이면 null. */
    fun percentDecode(s: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%') {
                if (i + 2 >= s.length) return null
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16) ?: return null
                out.write(v)
                i += 3
            } else {
                out.write(ch.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /** eTL 설정의 토큰 목록에 보일 이름. "영민이 (SM-S901N)". 기기 이름이 이상하면 "영민이 (안드로이드)". */
    fun tokenPurpose(model: String?): String {
        val clean = (model ?: "").replace(Regex("\\s+"), " ").replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim().take(40)
        return "영민이 (${clean.ifEmpty { "안드로이드" }})"
    }

    /** 토큰 만들기 요청 본문(form). 만료일은 넣지 않는다. */
    fun tokenForm(purpose: String): String = "token%5Bpurpose%5D=" + URLEncoder.encode(purpose, "UTF-8")

    private val XSSI = Regex("^\\s*while\\(1\\);")

    /**
     * 토큰 만들기 응답에서 전체 토큰을 꺼낸다. Canvas는 만들 때 한 번만 `token`에 전체 값을 주고,
     * `visible_token`은 앞부분만 보이는 축약본이라 쓰지 않는다. 전체 값이 없으면 null.
     */
    fun parseIssuedToken(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val json = try { JSONObject(XSSI.replace(body, "").trim()) } catch (_: JSONException) { return null }
        val token = json.optString("token", "").trim()
        if (token.isEmpty() || token == "null") return null
        val visible = json.optString("visible_token", "")
        if (token == visible && visible.isNotEmpty()) return null
        if ("…" in token || "..." in token || "*" in token) return null
        return token.takeIf { OnboardingLogic.isPlausibleEtlToken(it) && it.length in 16..512 }
    }

    /** 세션 확인 응답이 로그인한 사용자인가(`id`가 있음). */
    fun isSelf(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return try {
            JSONObject(XSSI.replace(body, "").trim()).optLong("id", 0L) > 0L
        } catch (_: JSONException) {
            false
        }
    }

    /** 토큰 만들기가 실패했을 때 사용자에게 보일 말. [code]는 HTTP 상태(네트워크 오류면 -1). */
    fun issueErrorMessage(code: Int): String = when (code) {
        401, 403 -> BLOCKED
        in 300..399 -> "로그인이 풀렸어요. 다시 로그인해 주세요"
        -1 -> "eTL에 연결하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요"
        else -> "eTL에서 토큰을 만들지 못했어요. 다시 시도하거나 직접 발급해서 붙여넣어 주세요"
    }

    /** 이 오류는 다시 해도 안 될 가능성이 커서 붙여넣기를 먼저 권하는가. */
    fun suggestsPaste(code: Int): Boolean = code == 401 || code == 403

    const val BLOCKED = "eTL에서 토큰 발급이 막혀 있어요. 직접 발급해서 붙여넣어 주세요"

    /**
     * `intent:` 주소의 `S.browser_fallback_url` 값(https만). 앱이 없을 때 대신 열 주소. 없거나 https가 아니면 null.
     */
    fun browserFallbackUrl(intentUri: String): String? {
        if (!intentUri.startsWith("intent:", ignoreCase = true)) return null
        val extras = intentUri.substringAfter("#Intent;", "")
        if (extras.isEmpty()) return null
        for (part in extras.split(';')) {
            if (!part.startsWith("S.browser_fallback_url=")) continue
            val value = percentDecode(part.substringAfter('=')) ?: return null
            return value.takeIf { navigation(it) == Nav.LOAD || navigation(it) == Nav.BROWSER }
        }
        return null
    }

    /** 로그에 남길 주소: 호스트와 경로만(쿼리·조각·사용자 정보 없이). 해석이 안 되면 스킴만. */
    fun logUrl(rawUrl: String?): String {
        if (rawUrl == null) return "(null)"
        return try {
            val uri = URI(rawUrl.trim())
            if (uri.host == null) "${uri.scheme ?: "?"}:…" else "${uri.scheme}://${uri.host}${uri.rawPath ?: ""}".take(120)
        } catch (_: Exception) {
            rawUrl.substringBefore(':').take(20) + ":…"
        }
    }

    private val SCHEME_RE = Regex("[a-z][a-z0-9+.-]*")

    private fun hostOf(url: String): String? = try {
        URI(url).host?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun hasUserInfo(url: String): Boolean = try {
        URI(url).rawUserInfo != null
    } catch (_: Exception) {
        true
    }
}
