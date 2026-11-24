package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LearningXLinkTest {
    @Test fun preservesContentPathsAndEncodedQueries() {
        val paths = listOf(
            "",
            "courses/305930",
            "courses/305930/assignments/123",
            "courses/305930/discussion_topics/456",
            "courses/305930/files/789?wrap=1#preview",
        )
        paths.forEach { path ->
            assertEquals("canvas-student://myetl.snu.ac.kr/$path", LearningXLink.fromWebUrl("https://myetl.snu.ac.kr/$path"))
        }
    }

    @Test fun routesBoardRedirectToSupportedToolAndPreservesPostQuery() {
        val url = "https://myetl.snu.ac.kr/learningx/redirect/courses/305930/external_tools/85?post_id=779536&title=%ED%95%99%EC%8A%B5#post"
        assertEquals("canvas-student://myetl.snu.ac.kr/courses/305930/external_tools/85?post_id=779536&title=%ED%95%99%EC%8A%B5#post", LearningXLink.fromWebUrl(url))
        assertTrue(LearningXLink.opensBoardList(url))
        assertFalse(LearningXLink.opensBoardList("https://myetl.snu.ac.kr/courses/305930/assignments/1"))
        assertFalse(LearningXLink.opensBoardList("https://evil.example/learningx/redirect/courses/305930/external_tools/85"))
    }

    @Test fun normalizesEtlHostAndDefaultPortsWithoutChangingEncodedContent() {
        for (base in listOf("https://MYETL.SNU.AC.KR:443", "http://myetl.snu.ac.kr:80", "http://myetl.snu.ac.kr")) {
            assertEquals("canvas-student://myetl.snu.ac.kr/courses/1?title=a%2Fb#preview",
                LearningXLink.fromWebUrl("$base/courses/1?title=a%2Fb#preview"))
        }
        assertEquals("https://myetl.snu.ac.kr/", LearningXLink.webUrl("https://myetl.snu.ac.kr"))
        assertEquals("canvas-student://myetl.snu.ac.kr/", LearningXLink.fromWebUrl("https://myetl.snu.ac.kr"))
    }

    @Test fun rejectsForeignHostsAndNonEtlSchemes() {
        listOf(
            null, "", "https://evil.example/courses/1",
            "https://myetl.snu.ac.kr.evil.example/courses/1",
            "https://myetl.snu.ac.kr@evil.example/courses/1",
            "https://myetl.snu.ac.kr:8443/courses/1",
            "http://myetl.snu.ac.kr:443/courses/1",
            "https://user@myetl.snu.ac.kr/courses/1",
            "https://myetl.snu.ac.kr/courses/%invalid",
            "intent://myetl.snu.ac.kr/courses/1",
            "canvas-student://myetl.snu.ac.kr/courses/1",
            "https://myetl.snu.ac.kr/courses/1\n/2",
        ).forEach { assertNull(LearningXLink.fromWebUrl(it)) }
    }
}
