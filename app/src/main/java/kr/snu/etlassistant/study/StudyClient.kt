package kr.snu.etlassistant.study

import kr.snu.etlassistant.phase0.LocalConnectProxy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 공개 기출 자료 사이트([StudySites])에 GET만 보내는 클라이언트.
 *
 * - 허용 목록 호스트·HTTPS만. 리다이렉트는 직접 따라가며 매번 같은 검사를 한다([StudyLogic.redirectTarget], 최대 3번).
 * - 쿠키·토큰·인증 헤더를 보내지 않고(쿠키 저장소를 쓰지 않음), User-Agent는 개인 정보 없는 일반 값이다.
 * - 이름이 사설망·루프백 주소로 풀리면 보내지 않는다(Codex 프록시와 같은 규칙, [LocalConnectProxy.isPublicDestination]).
 * - 응답 크기 상한(JSON·HTML 2MB, PDF 30MB)과 시간 제한, 앱 전체 동시 요청 수([StudyLogic.MAX_CONCURRENT])를 둔다.
 */
object StudyClient {
    private val gate = Semaphore(StudyLogic.MAX_CONCURRENT, true)

    class Text(val url: String, val contentType: String?, val body: String)

    /** 검색 API(JSON)를 GET한다. [url]은 [StudyLogic]이 만든 주소만 쓴다. */
    fun getJson(url: String): String {
        val host = URI(url).host
        if (!url.startsWith("https://") || !StudySites.isSearchHost(host)) throw StudyException("허용되지 않은 검색 주소예요")
        return withGate {
            val c = open(url, "application/json", StudyLogic.READ_TIMEOUT_MS)
            try {
                val code = c.responseCode
                if (code !in 200..299) throw StudyException("검색 응답 오류($code)")
                String(readBounded(c, StudyLogic.MAX_JSON_BYTES.toLong(), "검색 응답이 너무 커요"), Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }
    }

    sealed class Fetched {
        class Page(val url: String, val html: String) : Fetched()
        class Pdf(val url: String, val bytes: Long, val file: File) : Fetched()
    }

    /**
     * [rawUrl]을 받는다. HTML이면 글(2MB까지), PDF면 [pdfTarget]에 쓴다(30MB까지, `%PDF`로 시작해야 함).
     * 그 밖의 형식(zip, 동영상 등)은 받지 않는다.
     */
    fun fetch(rawUrl: String, pdfTarget: File): Fetched {
        var url = StudyLogic.checkFetchUrl(rawUrl)
        return withGate {
            var result: Fetched? = null
            for (hop in 0..StudyLogic.MAX_REDIRECTS) {
                val c = open(url, "text/html,application/pdf;q=0.9", StudyLogic.PDF_READ_TIMEOUT_MS)
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        if (hop == StudyLogic.MAX_REDIRECTS) throw StudyException("이동이 너무 많아요")
                        url = StudyLogic.redirectTarget(url, c.getHeaderField("Location"))
                        continue
                    }
                    if (code == 404) throw StudyException("자료를 찾을 수 없어요(404). study_search로 주소를 다시 확인하세요")
                    if (code !in 200..299) throw StudyException("자료 응답 오류($code)")
                    result = when (StudyLogic.kindOf(c.contentType)) {
                        StudyLogic.Kind.HTML -> Fetched.Page(url, String(readBounded(c, StudyLogic.MAX_HTML_BYTES.toLong(), "페이지가 너무 커요"), Charsets.UTF_8))
                        StudyLogic.Kind.PDF -> Fetched.Pdf(url, savePdf(c, pdfTarget), pdfTarget)
                        StudyLogic.Kind.OTHER -> throw StudyException("PDF나 웹 페이지가 아니라서 받지 않았어요(${c.contentType?.substringBefore(';') ?: "형식 모름"})")
                    }
                    break
                } finally {
                    c.disconnect()
                }
            }
            result ?: throw StudyException("이동이 너무 많아요")
        }
    }

    private fun savePdf(c: HttpURLConnection, target: File): Long {
        val declared = c.contentLengthLong
        if (declared > StudyLogic.MAX_PDF_BYTES) throw StudyException("PDF가 너무 커요(30MB 초과)")
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        var total = 0L
        try {
            FileOutputStream(tmp).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var head: ByteArray? = null
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (head == null) {
                            head = buf.copyOf(n)
                            if (n >= 4 && !StudyLogic.looksLikePdf(head)) throw StudyException("PDF 형식이 아니에요")
                        }
                        total += n
                        if (total > StudyLogic.MAX_PDF_BYTES) throw StudyException("PDF가 너무 커요(30MB 초과)")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) throw StudyException("빈 파일이에요")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return total
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun <T> withGate(block: () -> T): T {
        if (!gate.tryAcquire(30, TimeUnit.SECONDS)) throw StudyException("다른 공개 자료 요청이 끝나지 않았어요. 잠시 뒤 하나씩 다시 시도하세요")
        try {
            return block()
        } catch (e: StudyException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw StudyException("공개 자료 사이트 응답 시간 초과")
        } catch (e: IOException) {
            throw StudyException("공개 자료 사이트 연결 실패(${e.javaClass.simpleName})")
        } finally {
            gate.release()
        }
    }

    private fun open(url: String, accept: String, readTimeoutMs: Int): HttpURLConnection {
        val host = URI(url).host ?: throw StudyException("주소를 읽을 수 없어요")
        val addrs = try { InetAddress.getAllByName(host).toList() } catch (_: IOException) { throw StudyException("공개 자료 사이트 주소를 찾지 못했어요") }
        if (addrs.isEmpty() || addrs.any { !LocalConnectProxy.isPublicDestination(it) }) throw StudyException("사설망 주소로 풀리는 곳에는 요청하지 않아요")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = StudyLogic.CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", StudyLogic.USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun readBounded(c: HttpURLConnection, max: Long, tooBig: String): ByteArray {
        if (c.contentLengthLong > max) throw StudyException(tooBig)
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw StudyException(tooBig)
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
