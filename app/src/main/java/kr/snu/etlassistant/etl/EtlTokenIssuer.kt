package kr.snu.etlassistant.etl

import kr.snu.etlassistant.onboarding.EtlLoginLogic
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱 안 로그인 화면에서 사용자가 방금 로그인한 eTL 세션으로 액세스 토큰 하나를 만든다.
 *
 * 앱이 eTL에 보내는 쓰기 요청은 이 [create]의 `POST /api/v1/users/self/tokens` 하나뿐이고(CLAUDE.md 보안 규칙의 예외),
 * 주소는 고정이다. 세션 쿠키는 인자로만 받아 이 요청에만 쓰고 저장하지 않는다. 리다이렉트는 따라가지 않는다.
 * 쿠키·토큰·응답 본문은 결과의 오류 문구나 로그에 넣지 않는다.
 */
object EtlTokenIssuer {

    sealed class Result {
        /** 만든 토큰. 메모리에만 두고 곧바로 확인·저장한다. */
        class Issued(val token: String) : Result() {
            override fun toString(): String = "Issued(***)"
        }

        /** 세션이 아직 로그인 상태가 아니다(로그인 중간 단계). 조용히 기다린다. [code]는 로그용. */
        data class NotLoggedIn(val code: Int) : Result()

        /** 토큰을 만들지 못했다. [code]는 HTTP 상태, 네트워크 오류면 -1. */
        data class Failed(val code: Int, val message: String, val suggestPaste: Boolean) : Result()
    }

    /**
     * 세션이 로그인 상태인지 `users/self`로 확인한다(토큰 없이 세션 쿠키만, GET).
     * @return 로그인 상태면 null, 아니면 [Result.NotLoggedIn](네트워크 오류면 [Result.Failed])
     */
    fun checkSession(cookieHeader: String): Result? {
        val r = try {
            http(EtlLoginLogic.SELF_URL, "GET", cookieHeader, null, null)
        } catch (e: IOException) {
            return Result.Failed(-1, EtlLoginLogic.issueErrorMessage(-1), false)
        }
        return if (r.code == 200 && EtlLoginLogic.isSelf(r.body)) null else Result.NotLoggedIn(r.code)
    }

    /**
     * 토큰을 하나 만든다. [checkSession]으로 로그인 상태를 확인한 뒤에만 부른다.
     * [purpose]는 eTL 설정의 토큰 목록에 보일 이름([EtlLoginLogic.tokenPurpose]).
     */
    fun create(cookieHeader: String, purpose: String): Result {
        val csrf = EtlLoginLogic.csrfToken(cookieHeader)
            ?: return Result.Failed(0, EtlLoginLogic.issueErrorMessage(0), false)
        val r = try {
            http(EtlLoginLogic.TOKENS_URL, "POST", cookieHeader, csrf, EtlLoginLogic.tokenForm(purpose))
        } catch (e: IOException) {
            return Result.Failed(-1, EtlLoginLogic.issueErrorMessage(-1), false)
        }
        if (r.code !in 200..299) {
            return Result.Failed(r.code, EtlLoginLogic.issueErrorMessage(r.code), EtlLoginLogic.suggestsPaste(r.code))
        }
        val token = EtlLoginLogic.parseIssuedToken(r.body)
            ?: return Result.Failed(r.code, EtlLoginLogic.issueErrorMessage(0), false)
        return Result.Issued(token)
    }

    private class Resp(val code: Int, val body: String)

    private fun http(url: String, method: String, cookie: String, csrf: String?, form: String?): Resp {
        val u = URL(url)
        // 고정 주소만. 혹시 호출 경로가 바뀌어도 eTL 밖으로 쿠키를 보내지 않는다.
        if (url != EtlLoginLogic.SELF_URL && url != EtlLoginLogic.TOKENS_URL) throw IOException("unexpected url")
        if (method == "POST" && url != EtlLoginLogic.TOKENS_URL) throw IOException("unexpected post")
        return EtlClient.gated {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = EtlClient.CONNECT_TIMEOUT_MS
                c.readTimeout = EtlClient.READ_TIMEOUT_MS
                c.useCaches = false
                c.requestMethod = method
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Cookie", cookie)
                if (csrf != null) {
                    c.setRequestProperty("X-CSRF-Token", csrf)
                    c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                }
                if (form != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val body = try {
                    (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readLimited(MAX_BODY) }.orEmpty()
                } catch (_: IOException) {
                    ""
                }
                Resp(code, body)
            } finally {
                c.disconnect()
            }
        }
    }

    private fun InputStream.readLimited(max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val r = read(buf, 0, minOf(buf.size, max - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private const val MAX_BODY = 256 * 1024
}
