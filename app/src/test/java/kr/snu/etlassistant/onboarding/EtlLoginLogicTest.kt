package kr.snu.etlassistant.onboarding

import kr.snu.etlassistant.onboarding.EtlLoginLogic.Nav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtlLoginLogicTest {

    @Test fun fixedEndpointsAreHttpsOnEtlHost() {
        for (u in listOf(EtlLoginLogic.START_URL, EtlLoginLogic.SELF_URL, EtlLoginLogic.TOKENS_URL)) {
            val uri = java.net.URI(u)
            assertEquals("https", uri.scheme)
            assertEquals("myetl.snu.ac.kr", uri.host)
            assertNull(uri.rawQuery)
        }
        assertEquals("/api/v1/users/self/tokens", java.net.URI(EtlLoginLogic.TOKENS_URL).path)
    }

    @Test fun navigationKeepsSnuHttpsInsideAndSendsOthersOut() {
        assertEquals(Nav.LOAD, EtlLoginLogic.navigation("https://myetl.snu.ac.kr/login"))
        assertEquals(Nav.LOAD, EtlLoginLogic.navigation("https://etl.snu.ac.kr/xn-sso/gw.php?a=1"))
        assertEquals(Nav.LOAD, EtlLoginLogic.navigation("https://nsso.snu.ac.kr/sso/usr/snu/mfa/login/view"))
        assertEquals(Nav.LOAD, EtlLoginLogic.navigation("HTTPS://NSSO.SNU.AC.KR/x"))
        // 서울대 밖 https(도움말 등)는 브라우저로.
        assertEquals(Nav.BROWSER, EtlLoginLogic.navigation("https://snu.gov-dooray.com/share/pages/x"))
        assertEquals(Nav.BROWSER, EtlLoginLogic.navigation("https://evilsnu.ac.kr/"))
        assertEquals(Nav.BROWSER, EtlLoginLogic.navigation("https://snu.ac.kr.evil.com/"))
        // 사용자 정보가 든 주소, http, 로컬 스킴은 막는다.
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation("https://user:pw@nsso.snu.ac.kr/"))
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation("http://devnsso.snu.ac.kr/sso/common/koNotice.jsp"))
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation("file:///data/data/x"))
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation("content://kr.snu.etlassistant.phase0.files/x"))
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation("javascript:alert(1)"))
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation("data:text/html,hi"))
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation("about:blank"))
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation(""))
        assertEquals(Nav.BLOCK, EtlLoginLogic.navigation("noscheme"))
        // 인증 앱 호출은 다른 앱으로.
        assertEquals(Nav.EXTERNAL_APP, EtlLoginLogic.navigation("passnismartloginapp://slnapp/qrlogin?data=abc"))
        assertEquals(Nav.EXTERNAL_APP, EtlLoginLogic.navigation("intent://x#Intent;scheme=passni;package=com.example;end"))
    }

    @Test fun snuHostMatchesOnlyTheDomainAndSubdomains() {
        assertTrue(EtlLoginLogic.isSnuHost("snu.ac.kr"))
        assertTrue(EtlLoginLogic.isSnuHost("myetl.snu.ac.kr"))
        assertTrue(EtlLoginLogic.isSnuHost("myetl.snu.ac.kr."))
        assertFalse(EtlLoginLogic.isSnuHost("notsnu.ac.kr"))
        assertFalse(EtlLoginLogic.isSnuHost("snu.ac.kr.example.com"))
    }

    @Test fun loggedInCandidateIsEtlPageOutsideLoginSteps() {
        assertTrue(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr/"))
        assertTrue(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr/?login_success=1"))
        assertTrue(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr/courses/123"))
        assertTrue(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr/loginx")) // /login으로 시작하지만 다른 경로
        assertFalse(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr/login"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr/login/canvas"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr/learningx/login?x=1"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr/logout"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate("https://etl.snu.ac.kr/"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate("http://myetl.snu.ac.kr/"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate("https://myetl.snu.ac.kr:8443/"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate("https://a:b@myetl.snu.ac.kr/"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate("about:blank"))
        assertFalse(EtlLoginLogic.isLoggedInCandidate(null))
    }

    @Test fun csrfTokenIsUrlDecodedCookieValueWithoutPlusToSpace() {
        val header = "log_session_id=abc; _csrf_token=AbC%2Bd%2Fe%3D%3D+x; _normandy_session=zzz"
        assertEquals("AbC+d/e==+x", EtlLoginLogic.csrfToken(header))
        assertEquals("abc", EtlLoginLogic.cookieValue(header, "log_session_id"))
        assertNull(EtlLoginLogic.csrfToken("a=1; b=2"))
        assertNull(EtlLoginLogic.csrfToken(null))
        assertNull(EtlLoginLogic.csrfToken("_csrf_token="))
        assertNull(EtlLoginLogic.csrfToken("_csrf_token=%ZZ")) // 잘못된 인코딩
        assertNull(EtlLoginLogic.csrfToken("_csrf_token=a%0Ab")) // 제어문자는 헤더에 넣지 않는다
        // 이름이 접두어로만 같은 쿠키는 고르지 않는다.
        assertEquals("real", EtlLoginLogic.csrfToken("x_csrf_token=fake; _csrf_token=real"))
    }

    @Test fun percentDecodeHandlesUtf8AndRejectsBrokenEscapes() {
        assertEquals("영민이", EtlLoginLogic.percentDecode("%EC%98%81%EB%AF%BC%EC%9D%B4"))
        assertEquals("a+b", EtlLoginLogic.percentDecode("a+b"))
        assertNull(EtlLoginLogic.percentDecode("abc%2"))
    }

    @Test fun tokenPurposeNamesDevice() {
        assertEquals("영민이 (SM-S901N)", EtlLoginLogic.tokenPurpose("SM-S901N"))
        assertEquals("영민이 (안드로이드)", EtlLoginLogic.tokenPurpose(null))
        assertEquals("영민이 (안드로이드)", EtlLoginLogic.tokenPurpose("  \u0000 "))
        assertEquals("영민이 (Pixel 9 Pro)", EtlLoginLogic.tokenPurpose(" Pixel\t9   Pro\n"))
        assertTrue(EtlLoginLogic.tokenPurpose("x".repeat(100)).length <= "영민이 ()".length + 40)
    }

    @Test fun tokenFormIsUrlEncodedPurposeOnly() {
        val form = EtlLoginLogic.tokenForm("영민이 (SM-S901N)")
        assertTrue(form.startsWith("token%5Bpurpose%5D="))
        assertFalse("expires" in form)
        assertEquals("영민이 (SM-S901N)", java.net.URLDecoder.decode(form.substringAfter('='), "UTF-8"))
        assertFalse(form.substringAfter('=').contains('&'))
    }

    @Test fun parseIssuedTokenTakesFullTokenNotVisibleOne() {
        val full = "1234~" + "a".repeat(64)
        assertEquals(full, EtlLoginLogic.parseIssuedToken("""{"id":5,"purpose":"영민이","token":"$full","visible_token":"1234~aaaa"}"""))
        assertEquals(full, EtlLoginLogic.parseIssuedToken("""while(1);{"token":"$full"}"""))
        // 만든 뒤 다시 조회하면 축약본만 온다.
        assertNull(EtlLoginLogic.parseIssuedToken("""{"id":5,"visible_token":"1234~aaaa"}"""))
        assertNull(EtlLoginLogic.parseIssuedToken("""{"token":"1234~aaaa…"}"""))
        assertNull(EtlLoginLogic.parseIssuedToken("""{"token":"1234~aaaa...xxxxxxxxxxxx"}"""))
        assertNull(EtlLoginLogic.parseIssuedToken("""{"token":"1234~aaaa","visible_token":"1234~aaaa"}"""))
        assertNull(EtlLoginLogic.parseIssuedToken("""{"token":"has space inside token value"}"""))
        assertNull(EtlLoginLogic.parseIssuedToken("""{"token":null}"""))
        assertNull(EtlLoginLogic.parseIssuedToken("<html>login</html>"))
        assertNull(EtlLoginLogic.parseIssuedToken(""))
        assertNull(EtlLoginLogic.parseIssuedToken(null))
    }

    @Test fun selfResponseNeedsUserId() {
        assertTrue(EtlLoginLogic.isSelf("""while(1);{"id":42,"name":"홍길동"}"""))
        assertFalse(EtlLoginLogic.isSelf("""{"errors":[{"message":"user authorization required"}]}"""))
        assertFalse(EtlLoginLogic.isSelf("<html></html>"))
        assertFalse(EtlLoginLogic.isSelf(null))
    }

    @Test fun issueErrorsAreKoreanAndBlockedSuggestsPaste() {
        assertEquals(EtlLoginLogic.BLOCKED, EtlLoginLogic.issueErrorMessage(401))
        assertEquals(EtlLoginLogic.BLOCKED, EtlLoginLogic.issueErrorMessage(403))
        assertTrue(EtlLoginLogic.suggestsPaste(403))
        assertFalse(EtlLoginLogic.suggestsPaste(500))
        assertFalse(EtlLoginLogic.suggestsPaste(-1))
        assertTrue("다시 로그인" in EtlLoginLogic.issueErrorMessage(302))
        assertTrue("네트워크" in EtlLoginLogic.issueErrorMessage(-1))
        for (code in listOf(-1, 0, 302, 401, 403, 422, 500)) {
            val m = EtlLoginLogic.issueErrorMessage(code)
            for (jargon in listOf("CSRF", "POST", "WebView", "HTTP")) assertFalse(m, jargon in m)
        }
    }

    @Test fun browserFallbackOnlyHttps() {
        val ok = "intent://scan#Intent;scheme=passni;package=com.example.app;S.browser_fallback_url=https%3A%2F%2Fnsso.snu.ac.kr%2Fhelp;end"
        assertEquals("https://nsso.snu.ac.kr/help", EtlLoginLogic.browserFallbackUrl(ok))
        val http = "intent://scan#Intent;scheme=passni;S.browser_fallback_url=http%3A%2F%2Fexample.com;end"
        assertNull(EtlLoginLogic.browserFallbackUrl(http))
        val js = "intent://scan#Intent;scheme=passni;S.browser_fallback_url=javascript%3Aalert(1);end"
        assertNull(EtlLoginLogic.browserFallbackUrl(js))
        assertNull(EtlLoginLogic.browserFallbackUrl("passnismartloginapp://slnapp/qrlogin?data=x"))
        assertNull(EtlLoginLogic.browserFallbackUrl("intent://scan#Intent;scheme=passni;end"))
    }

    @Test fun logUrlDropsQueryFragmentAndUserInfo() {
        assertEquals(
            "https://etl.snu.ac.kr/xn-sso/gw.php",
            EtlLoginLogic.logUrl("https://etl.snu.ac.kr/xn-sso/gw.php?callback_url=https%3A%2F%2Fmyetl&code=secret#frag"),
        )
        assertEquals("https://nsso.snu.ac.kr/x", EtlLoginLogic.logUrl("https://u:p@nsso.snu.ac.kr/x"))
        assertEquals("passnismartloginapp:…", EtlLoginLogic.logUrl("passnismartloginapp:opaque-data"))
        assertEquals("(null)", EtlLoginLogic.logUrl(null))
    }
}
