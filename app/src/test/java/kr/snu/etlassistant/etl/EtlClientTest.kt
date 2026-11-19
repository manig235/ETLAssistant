package kr.snu.etlassistant.etl

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EtlClientTest {

    @Test fun xssiPrefixIsStripped() {
        val obj = EtlClient.parseBody("while(1);{\"id\":1}", "/api/v1/x")
        assertEquals(1, (obj as JSONObject).getInt("id"))
        val arr = EtlClient.parseBody("  while(1);\n[{\"id\":1},{\"id\":2}]", "/api/v1/x")
        assertEquals(2, (arr as JSONArray).length())
        assertEquals(3, (EtlClient.parseBody("{\"id\":3}", "/api/v1/x") as JSONObject).getInt("id"))
    }

    @Test fun garbageBodyThrowsWithoutEchoingBody() {
        try {
            EtlClient.parseBody("<html>SECRET-ish</html>", "/api/v1/x")
            fail()
        } catch (e: EtlException) {
            assertTrue(e.message!!.contains("/api/v1/x"))
            assertTrue(!e.message!!.contains("SECRET"))
        }
    }

    @Test fun nextLinkParsing() {
        val h = "<https://myetl.snu.ac.kr/api/v1/courses?page=1&per_page=100>; rel=\"current\"," +
            "<https://myetl.snu.ac.kr/api/v1/courses?page=2&per_page=100>; rel=\"next\"," +
            "<https://myetl.snu.ac.kr/api/v1/courses?page=9&per_page=100>; rel=\"last\""
        assertEquals("https://myetl.snu.ac.kr/api/v1/courses?page=2&per_page=100", EtlClient.nextLink(h))
        assertNull(EtlClient.nextLink("<https://x/y?page=1>; rel=\"current\", <https://x/y?page=3>; rel=\"last\""))
        assertNull(EtlClient.nextLink(null))
        assertNull(EtlClient.nextLink(""))
    }

    @Test fun malformedTokenRejectedAndNotEchoed() {
        for (bad in listOf("", "has space", "탈취", "a\nb")) {
            try {
                EtlClient(bad)
                fail("accepted: $bad")
            } catch (e: EtlException) {
                assertTrue(!e.message!!.contains(bad) || bad.isEmpty())
            }
        }
        EtlClient("abc~123-OK") // 정상 형식
    }

    @Test fun onlyApiPathsAreRequested() {
        val c = EtlClient("tok-123")
        for (bad in listOf("https://evil.example/api/v1/x", "/login", "api/v1/x", "/api/v2/x")) {
            try {
                c.get(bad)
                fail("accepted: $bad")
            } catch (e: EtlException) {
                assertTrue(!e.message!!.contains("tok-123"))
            }
        }
    }

    @Test fun permissionVersusTokenFor401() {
        // 권한 없음(토큰은 유효): 영어와 한국어 모두
        assertTrue(EtlClient.isPermissionDenied("""{"status":"unauthorized","errors":[{"message":"user not authorized to perform that action"}]}"""))
        assertTrue(EtlClient.isPermissionDenied("""{"errors":[{"message":"사용자에게 이 동작을 수행할 권한이 없음"}]}"""))
        // 토큰 문제
        assertTrue(!EtlClient.isPermissionDenied("""{"errors":[{"message":"invalid access token."}]}"""))
        assertTrue(!EtlClient.isPermissionDenied("""{"errors":[{"message":"유효하지 않은 액세스 토큰입니다"}]}"""))
        assertTrue(!EtlClient.isPermissionDenied(""))
        assertTrue(!EtlClient.isPermissionDenied("unauthorized"))
    }

    @Test fun errorHintExtractsMessageOnly() {
        assertEquals(" — 서버 메시지: 사용자에게 이 동작을 수행할 권한이 없음",
            EtlClient.errorHint("""{"errors":[{"message":"사용자에게 이 동작을 수행할 권한이 없음"}]}"""))
        assertEquals("", EtlClient.errorHint("<html>x</html>"))
        assertEquals("", EtlClient.errorHint(""))
    }

    @Test fun pathOnlyDropsQuery() {
        assertEquals("/files/1/download", EtlClient.pathOnly("https://myetl.snu.ac.kr/files/1/download?verifier=SECRET"))
        assertEquals("/api/v1/x", EtlClient.pathOnly("/api/v1/x?a=b"))
    }
}
