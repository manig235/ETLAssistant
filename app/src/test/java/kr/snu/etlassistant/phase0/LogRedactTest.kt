package kr.snu.etlassistant.phase0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactTest {

    @Test fun masksLoginUrlQuery() {
        val r = LogRedact.redact("[app] 브라우저로 URL 열기: https://auth.openai.com/oauth/authorize?response_type=code&state=abcDEF123&code_challenge=xyz")
        assertEquals("[app] 브라우저로 URL 열기: https://auth.openai.com/oauth/authorize?…", r)
    }

    @Test fun masksCallbackCodeAndKeyValues() {
        val r = LogRedact.redact("GET http://localhost:1455/auth/callback?code=SECRET&state=S2 then code=abc state=def")
        assertFalse(r.contains("SECRET"))
        assertFalse(r.contains("abc"))
        assertFalse(r.contains("def"))
        assertTrue(r.contains("http://localhost:1455/auth/callback?…"))
    }

    @Test fun masksDeviceCodes() {
        assertEquals("코드: ****-****", LogRedact.redact("코드: AB12-CD345"))
        assertEquals("코드: ****-****", LogRedact.redact("코드: ABCD-EFGH"))
        assertEquals("{\"userCode\":\"***\"}", LogRedact.redact("{\"userCode\":\"ABCD-EFGH\"}"))
        // 숫자만 있는 값(날짜·버전 등)은 그대로 둔다.
        assertEquals("2026-1123 UTF-8", LogRedact.redact("2026-1123 UTF-8"))
    }

    @Test fun masksProxyUserinfoAndSecrets() {
        val r = LogRedact.redact("env HTTPS_PROXY=http://u1:p4ss@127.0.0.1:4567 Bearer abcdefghijkl sk-abcdefghijklmnopqrstu eyJhbGciOi.eyJzdWIiOi.sig")
        assertFalse(r.contains("p4ss"))
        assertFalse(r.contains("abcdefghijkl "))
        assertFalse(r.contains("sk-abcdefghijklmnop"))
        assertFalse(r.contains("eyJzdWIiOi"))
        assertTrue(r.contains("http://***@127.0.0.1:4567"))
    }

    @Test fun keepsOrdinaryLines() {
        val s = "[proxy] CONNECT chatgpt.com:443 -> 104.18.32.47 OK"
        assertEquals(s, LogRedact.redact(s))
        val u = "[app] binary: /data/app/x/lib/arm64/libcodex.so"
        assertEquals(u, LogRedact.redact(u))
    }
}
