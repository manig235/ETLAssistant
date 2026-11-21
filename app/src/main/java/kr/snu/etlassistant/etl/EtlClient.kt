package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * eTL 오류. [kind]는 로그·판정용 짧은 분류(`timeout`, `network`, `http-404`, `forbidden`, `auth`, `parse`, `error`)이며
 * 토큰이나 URL을 담지 않는다.
 */
open class EtlException(message: String, val kind: String = "error") : Exception(message)

class EtlAuthException(message: String) : EtlException(message, "auth")

/**
 * eTL(Canvas) 읽기 전용 클라이언트. **GET만** 보낸다.
 *
 * - 토큰은 [origin]에만 보낸다. 페이지 링크나 리다이렉트가 다른 곳을 가리키면 토큰 없이 처리하거나 중단한다.
 * - 오류 메시지에는 경로(쿼리 제외)와 예외 클래스 이름만 넣는다. 토큰이나 서명 URL은 절대 넣지 않는다.
 * - JSON 요청은 앱 전체에서 동시에 [MAX_CONCURRENT]개까지만 보낸다([gate]). 비서가 여러 조회를 한꺼번에 보내거나
 *   알림 확인과 겹쳐도 eTL에 몰리지 않게 한다(동시 6개가 모두 20초 타임아웃으로 실패한 일이 있었다).
 */
class EtlClient(token: String, private val origin: String = BASE_URL) {

    private val tokenValue: String

    init {
        if (!TOKEN_RE.matches(token)) throw EtlException("eTL 토큰이 없거나 형식이 올바르지 않아요")
        tokenValue = token
    }

    data class Download(val bytes: Long, val sha256: String)

    /** `/api/v1/...` 경로 하나를 GET한다. 결과는 JSONObject 또는 JSONArray. */
    fun get(path: String): Any = requestJson(resolveApi(path)).first

    fun getObject(path: String): JSONObject =
        get(path) as? JSONObject ?: throw EtlException("객체가 아닌 응답: ${pathOnly(path)}")

    /** Link 헤더의 rel="next"를 따라 최대 [MAX_PAGES]쪽까지 모은다. */
    fun getAll(path: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var url = resolveApi(path)
        repeat(MAX_PAGES) {
            val (data, link) = requestJson(url)
            val arr = data as? JSONArray ?: throw EtlException("목록이 아닌 응답: ${pathOnly(url)}")
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += it }
            val next = nextLink(link) ?: return out
            val abs = try {
                URL(URL(url), next).toString()
            } catch (_: Exception) {
                throw EtlException("다음 페이지 링크를 해석할 수 없어요")
            }
            if (!abs.startsWith("$origin/")) throw EtlException("다음 페이지 링크가 eTL 밖을 가리켜요") // 토큰 유출 방지
            url = abs
        }
        return out
    }

    /**
     * eTL 파일 URL을 [out]으로 내려받는다. 리다이렉트는 직접 따라가며,
     * Authorization은 eTL origin 요청에만 붙인다(서명된 스토리지 URL에는 붙이지 않는다).
     */
    fun download(rawUrl: String, out: OutputStream, maxBytes: Long = MAX_DOWNLOAD): Download {
        var url = URL(URL("$origin/"), rawUrl).toString()
        if (!url.startsWith("$origin/")) throw EtlException("다운로드 주소가 eTL이 아니에요")
        val shownPath = URL(url).path
        repeat(MAX_REDIRECTS + 1) {
            val onEtl = url.startsWith("$origin/")
            val c = open(url, withToken = onEtl, json = false, readTimeoutMs = DOWNLOAD_READ_TIMEOUT_MS)
            try {
                val code = try {
                    c.responseCode
                } catch (e: IOException) {
                    throw EtlException("다운로드 실패: $shownPath (${e.javaClass.simpleName})", ioKind(e))
                }
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw EtlException("리다이렉트 위치가 없어요: $shownPath")
                    val next = URL(URL(url), loc).toString()
                    if (!next.startsWith("https://")) throw EtlException("https가 아닌 리다이렉트를 거부했어요: $shownPath")
                    url = next
                    return@repeat
                }
                if (code == 401 && onEtl) throw EtlAuthException(AUTH_REJECTED)
                if (code !in 200..299) throw EtlException("다운로드 실패($code): $shownPath", "http-$code")
                val md = MessageDigest.getInstance("SHA-256")
                var n = 0L
                try {
                    c.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            n += r
                            if (n > maxBytes) throw EtlException("파일이 ${maxBytes shr 20}MB보다 커요: $shownPath")
                            md.update(buf, 0, r)
                            out.write(buf, 0, r)
                        }
                    }
                } catch (e: IOException) {
                    throw EtlException("다운로드 실패: $shownPath (${e.javaClass.simpleName})", ioKind(e))
                }
                return Download(n, md.digest().joinToString("") { "%02x".format(it) })
            } finally {
                c.disconnect()
            }
        }
        throw EtlException("리다이렉트가 너무 많아요: $shownPath")
    }

    // ---- 내부 ---------------------------------------------------------------

    private fun resolveApi(path: String): String {
        if (!path.startsWith("/api/v1/")) throw EtlException("/api/v1/ 경로만 요청할 수 있어요")
        return origin + path
    }

    private fun open(url: String, withToken: Boolean, json: Boolean, readTimeoutMs: Int = READ_TIMEOUT_MS): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = readTimeoutMs
        c.requestMethod = "GET"
        if (json) c.setRequestProperty("Accept", "application/json")
        if (withToken) c.setRequestProperty("Authorization", "Bearer $tokenValue")
        return c
    }

    private fun requestJson(url: String): Pair<Any, String?> {
        gate.acquire()
        try {
            return requestJsonUngated(url)
        } finally {
            gate.release()
        }
    }

    private fun requestJsonUngated(url: String): Pair<Any, String?> {
        val path = pathOnly(url)
        val c = open(url, withToken = true, json = true)
        try {
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                throw EtlException("eTL 연결 실패: $path (${e.javaClass.simpleName})", ioKind(e))
            }
            if (code == 401) {
                val text = try {
                    c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty().lowercase()
                } catch (_: IOException) {
                    ""
                }
                // 서버가 준 오류 문구 일부를 덧붙인다(토큰 원문은 제거). 판정 규칙을 맞추는 데 쓴다.
                val hint = errorHint(text)
                if (isPermissionDenied(text)) throw EtlException("권한 없음(401): $path$hint", "forbidden")
                throw EtlAuthException(AUTH_REJECTED + hint + " [$path]")
            }
            if (code !in 200..299) throw EtlException("eTL 요청 실패($code): $path", "http-$code")
            val body = try {
                c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            } catch (e: IOException) {
                throw EtlException("eTL 응답 읽기 실패: $path (${e.javaClass.simpleName})", ioKind(e))
            }
            val link = c.headerFields.entries
                .firstOrNull { it.key.equals("Link", ignoreCase = true) }?.value?.joinToString(",")
            return parseBody(body, path) to link
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://myetl.snu.ac.kr"
        const val MAX_PAGES = 20
        const val MAX_REDIRECTS = 5
        /** 연결·응답 대기. 응답 없는 요청을 20초씩 기다리던 것을 줄였다(비서의 답이 그만큼 늦어진다). */
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        /** 파일 내려받기는 크고 느릴 수 있어 읽기 대기를 길게 둔다. */
        const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
        /** 앱 전체에서 동시에 보내는 eTL JSON 요청 수. */
        const val MAX_CONCURRENT = 3
        private val gate = java.util.concurrent.Semaphore(MAX_CONCURRENT, true)

        /** 같은 동시 요청 한도 안에서 [block]을 실행한다(게시판처럼 이 클라이언트 밖에서 eTL 호스트에 보내는 요청용). */
        fun <T> gated(block: () -> T): T {
            gate.acquire()
            try {
                return block()
            } finally {
                gate.release()
            }
        }

        fun ioKind(e: IOException): String = if (e is java.net.SocketTimeoutException) "timeout" else "network"
        const val MAX_DOWNLOAD = 300L shl 20
        private const val AUTH_REJECTED = "eTL이 토큰을 거부했어요(401): 만료됐거나 올바르지 않아요"
        private val TOKEN_RE = Regex("[\\u0021-\\u007e]+")
        private val XSSI = Regex("^\\s*while\\(1\\);")

        /**
         * 401 본문이 "토큰 문제"가 아니라 "이 동작/자원에 대한 권한 없음"인지.
         * eTL은 오류를 한국어로도 돌려주므로(예: "사용자에게 이 동작을 수행할 권한이 없음") 두 언어를 모두 본다.
         * 본문에 token/토큰이 들어 있으면 토큰 문제로 본다.
         */
        fun isPermissionDenied(lowerBody: String): Boolean {
            val mentionsToken = "token" in lowerBody || "토큰" in lowerBody
            val mentionsDenied = "not authorized" in lowerBody || "권한" in lowerBody
            return mentionsDenied && !mentionsToken
        }

        /** 오류 본문에서 message 문구만 뽑아 160자까지. 토큰이 섞여 있으면 가린다. */
        fun errorHint(lowerBody: String): String {
            val m = Regex("\"message\"\\s*:\\s*\"([^\"]{1,160})").find(lowerBody)?.groupValues?.get(1)
                ?: lowerBody.trim().take(80).takeIf { it.isNotEmpty() && !it.startsWith("<") }
                ?: return ""
            return " — 서버 메시지: $m"
        }

        fun parseBody(body: String, path: String): Any {
            val s = XSSI.replace(body, "").trim()
            try {
                return if (s.startsWith("[")) JSONArray(s) else JSONObject(s)
            } catch (_: JSONException) {
                throw EtlException("읽을 수 없는 eTL 응답: $path", "parse")
            }
        }

        fun pathOnly(url: String): String = try {
            if (url.startsWith("/")) url.substringBefore('?') else URL(url).path
        } catch (_: Exception) {
            "?"
        }

        /** Link 헤더에서 rel="next" 주소를 꺼낸다. */
        fun nextLink(header: String?): String? {
            if (header.isNullOrEmpty()) return null
            for (part in header.split(",")) {
                val m = Regex("\\s*<([^>]*)>(.*)").find(part) ?: continue
                if (Regex("rel\\s*=\\s*\"?([^\";]*\\bnext\\b[^\";]*)\"?", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[2])) {
                    return m.groupValues[1]
                }
            }
            return null
        }
    }
}
