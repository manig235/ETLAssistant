package kr.snu.etlassistant.phase0

/**
 * 로그(화면과 `phase0.log`)에 남기기 전에 비밀이 될 수 있는 부분을 가린다.
 *
 * - URL의 쿼리·조각(`?state=…&code_challenge=…`, 콜백의 `?code=…`)과 사용자 정보(`http://user:pass@`)
 * - 로그인 기기 코드(`ABCD-12345` 꼴), `Bearer` 값, JWT, `sk-` API 키, `code=`·`state=` 같은 값
 *
 * 로그는 개발·진단용이라 경로와 호스트는 남긴다. 순수 함수라 단위 테스트한다.
 */
object LogRedact {

    private val URL_USERINFO = Regex("(?i)\\b(https?|socks5h?)://[^\\s/@:]+:[^\\s/@]*@")
    private val URL_QUERY = Regex("(?i)\\b(https?://[^\\s?#\"'<>]+)[?#][^\\s\"'<>]*")
    private val BEARER = Regex("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
    private val API_KEY = Regex("\\bsk-[A-Za-z0-9_-]{16,}")
    private val KV = Regex("(?i)\\b(code|state|code_verifier|code_challenge|user_code|device_code|access_token|refresh_token|id_token|token)=([^&\\s\"']+)")
    private val JSON_KV = Regex("(?i)(\"(?:userCode|user_code|deviceCode|device_code|authUrl|verificationUrl|access_token|refresh_token|id_token)\"\\s*:\\s*\")[^\"]*\"")
    private val DEVICE_CODE = Regex("\\b[A-Z0-9]{4,5}-[A-Z0-9]{4,6}\\b")

    fun redact(s: String): String {
        if (s.isEmpty()) return s
        var t = s
        t = URL_USERINFO.replace(t) { "${it.groupValues[1]}://***@" }
        t = URL_QUERY.replace(t) { it.groupValues[1] + "?…" }
        t = BEARER.replace(t) { it.groupValues[1] + "***" }
        t = JWT.replace(t, "eyJ***")
        t = API_KEY.replace(t, "sk-***")
        t = KV.replace(t) { it.groupValues[1] + "=***" }
        t = JSON_KV.replace(t) { it.groupValues[1] + "***\"" }
        t = DEVICE_CODE.replace(t) { m -> if (m.value.any { it.isLetter() } && m.value.any { it.isDigit() } || m.value.count { it.isLetter() } >= 6) "****-****" else m.value }
        return t
    }
}
