package kr.snu.etlassistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    private fun release(
        tag: String = "v0.1.8",
        url: String = "https://github.com/manig235/ETLAssistant/releases/tag/v0.1.8",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """[{"name":"youngmin-v0.1.8.apk.sha256","size":86},{"name":"youngmin-v0.1.8.apk","size":164057719}]""",
    ) = """{"tag_name":"$tag","html_url":"$url","name":"영민이 0.1.8","body":"바뀐 점","draft":$draft,"prerelease":$prerelease,"assets":$assets}"""

    @Test fun parseVersion() {
        assertEquals(listOf(0, 1, 8), UpdateLogic.parseVersion("v0.1.8"))
        assertEquals(listOf(0, 1, 8), UpdateLogic.parseVersion("0.1.8"))
        assertEquals(listOf(1, 2), UpdateLogic.parseVersion(" V1.2 "))
        assertNull(UpdateLogic.parseVersion("v0.1.8-beta"))
        assertNull(UpdateLogic.parseVersion("latest"))
        assertNull(UpdateLogic.parseVersion(""))
        assertNull(UpdateLogic.parseVersion("v"))
        assertNull(UpdateLogic.parseVersion("1..2"))
        assertNull(UpdateLogic.parseVersion("1.2.3.4.5"))
        assertNull(UpdateLogic.parseVersion(null))
    }

    @Test fun isNewer() {
        assertTrue(UpdateLogic.isNewer("v0.1.8", "0.1.7"))
        assertTrue(UpdateLogic.isNewer("v0.1.10", "0.1.9")) // 문자열 비교가 아니라 숫자 비교
        assertTrue(UpdateLogic.isNewer("v0.2", "0.1.9"))
        assertTrue(UpdateLogic.isNewer("v0.1.7.1", "0.1.7"))
        assertFalse(UpdateLogic.isNewer("v0.1.7", "0.1.7"))
        assertFalse(UpdateLogic.isNewer("v0.1", "0.1.0")) // 자리수만 다르면 같다
        assertFalse(UpdateLogic.isNewer("v0.1.6", "0.1.7"))
        assertFalse(UpdateLogic.isNewer("nightly", "0.1.7"))
        assertFalse(UpdateLogic.isNewer("v0.1.8", null))
        assertFalse(UpdateLogic.isNewer("v0.1.8", "?"))
    }

    @Test fun parseRelease() {
        val r = UpdateLogic.parseRelease(release())
        assertNotNull(r)
        r!!
        assertEquals("v0.1.8", r.tag)
        assertEquals("0.1.8", r.version)
        assertEquals("youngmin-v0.1.8.apk", r.apkName)
        assertEquals(164057719L, r.apkSize)
        assertEquals("바뀐 점", r.notes)
    }

    @Test fun parseReleaseRejects() {
        assertNull(UpdateLogic.parseRelease(release(draft = true)))
        assertNull(UpdateLogic.parseRelease(release(prerelease = true)))
        assertNull(UpdateLogic.parseRelease(release(tag = "nightly")))
        assertNull(UpdateLogic.parseRelease(release(url = "https://evil.example/manig235/ETLAssistant/releases")))
        assertNull(UpdateLogic.parseRelease("not json"))
        assertNull(UpdateLogic.parseRelease("{}"))
    }

    @Test fun parseReleaseWithoutApk() {
        val r = UpdateLogic.parseRelease(release(assets = "[]"))!!
        assertNull(r.apkName)
        assertNull(r.apkSize)
        assertEquals("눌러서 받아요", UpdateLogic.notificationText(r))
        val noAssets = UpdateLogic.parseRelease("""{"tag_name":"v0.1.8","html_url":"https://github.com/manig235/ETLAssistant/releases/tag/v0.1.8","body":null}""")!!
        assertNull(noAssets.apkName)
        assertEquals("", noAssets.notes)
    }

    @Test fun notesAreTruncated() {
        val long = "가".repeat(1000)
        val json = """{"tag_name":"v0.1.8","html_url":"https://github.com/manig235/ETLAssistant/releases/tag/v0.1.8","body":"$long"}"""
        assertEquals(UpdateLogic.NOTES_MAX, UpdateLogic.parseRelease(json)!!.notes.length)
    }

    @Test fun allowedUrl() {
        assertTrue(UpdateLogic.isAllowedUrl("https://github.com/manig235/ETLAssistant/releases/tag/v0.1.8"))
        assertFalse(UpdateLogic.isAllowedUrl("http://github.com/manig235/ETLAssistant/releases"))
        assertFalse(UpdateLogic.isAllowedUrl("https://github.com/manig235/ETLAssistantX/releases"))
        assertFalse(UpdateLogic.isAllowedUrl("https://github.com/other/ETLAssistant/releases"))
        assertFalse(UpdateLogic.isAllowedUrl("https://github.com/manig235/ETLAssistant/../../other/repo"))
        assertFalse(UpdateLogic.isAllowedUrl("https://github.com/manig235/ETLAssistant/releases\nx"))
        assertFalse(UpdateLogic.isAllowedUrl("https://github.com.evil.example/manig235/ETLAssistant/"))
        assertFalse(UpdateLogic.isAllowedUrl(null))
    }

    @Test fun notifyOncePerVersion() {
        val r = UpdateLogic.parseRelease(release())!!
        assertTrue(UpdateLogic.shouldNotify(r, "0.1.7", null))
        assertTrue(UpdateLogic.shouldNotify(r, "0.1.7", "v0.1.7"))
        assertFalse(UpdateLogic.shouldNotify(r, "0.1.7", "v0.1.8")) // 이미 알림
        assertFalse(UpdateLogic.shouldNotify(r, "0.1.8", null)) // 이미 최신
        assertFalse(UpdateLogic.shouldNotify(r, "0.1.9", null))
    }

    @Test fun startupCheckDue() {
        val h = 60L * 60 * 1000
        assertTrue(UpdateLogic.startupCheckDue(0L, 10 * h))
        assertFalse(UpdateLogic.startupCheckDue(10 * h, 15 * h))
        assertTrue(UpdateLogic.startupCheckDue(10 * h, 16 * h))
        assertTrue(UpdateLogic.startupCheckDue(10 * h, 5 * h)) // 시각이 거꾸로 감
    }

    @Test fun texts() {
        val r = UpdateLogic.parseRelease(release())!!
        assertEquals("새 버전 0.1.8이 나왔어요", UpdateLogic.notificationTitle(r))
        assertEquals("눌러서 받아요 · 약 164MB", UpdateLogic.notificationText(r))
        assertEquals("새 버전 0.1.9가 있어요", UpdateLogic.availableText("0.1.9"))
        assertEquals("새 버전 0.2가 있어요", UpdateLogic.availableText("0.2"))
        assertEquals("새 버전 0.1.10이 있어요", UpdateLogic.availableText("0.1.10"))
        assertNull(UpdateLogic.sizeText(999_999L))
        assertNull(UpdateLogic.sizeText(null))
        assertEquals("약 2MB", UpdateLogic.sizeText(1_600_000L))
    }
}
