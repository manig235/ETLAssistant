package kr.snu.etlassistant.chat

import kr.snu.etlassistant.codex.AccountInfo
import kr.snu.etlassistant.codex.SessionState
import kr.snu.etlassistant.notify.NotifySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {

    @Test fun formatsRemainingTime() {
        assertEquals("15:00", SettingsLogic.formatRemaining(900))
        assertEquals("0:05", SettingsLogic.formatRemaining(5))
        assertEquals("0:00", SettingsLogic.formatRemaining(-3))
        assertEquals("1:01", SettingsLogic.formatRemaining(61))
    }

    @Test fun remainingSecondsNeverNegative() {
        assertEquals(900, SettingsLogic.remainingSeconds(900_000, 0))
        assertEquals(0, SettingsLogic.remainingSeconds(1_000, 5_000))
    }

    @Test fun briefTimeEditing() {
        // 추가: 첫 시각의 12시간 뒤
        assertEquals(listOf(480, 1200), SettingsLogic.addBriefTime(listOf(480)))
        // 이미 2개면 그대로
        assertEquals(listOf(480, 1200), SettingsLogic.addBriefTime(listOf(480, 1200)))
        // 교체: 같은 시각이면 합치고, 정렬한다
        assertEquals(listOf(480), SettingsLogic.replaceBriefTime(listOf(480, 1200), 1, 480))
        assertEquals(listOf(480, 1300), SettingsLogic.replaceBriefTime(listOf(1200, 480), 1, 1300))
        assertEquals(listOf(480, 1200), SettingsLogic.replaceBriefTime(listOf(480, 1200), 5, 0))
        // 두 번째 삭제: 최소 1개
        assertEquals(listOf(480), SettingsLogic.removeSecondBriefTime(listOf(480, 1200)))
        assertEquals(listOf(480), SettingsLogic.removeSecondBriefTime(emptyList()))
    }

    @Test fun addBriefTimeWrapsPastMidnight() {
        // 20:00 + 12h = 08:00 → 정렬되어 [08:00, 20:00]
        assertEquals(listOf(480, 1200), SettingsLogic.addBriefTime(listOf(1200)))
    }

    @Test fun accountStatusByState() {
        fun s(st: SessionState, a: AccountInfo? = null, d: String? = null) = SettingsLogic.accountStatus(st, d, a)
        assertTrue(s(SessionState.STARTING).loading)
        assertTrue(s(SessionState.READY).loading) // 계정 확인 전
        val failed = s(SessionState.FAILED, d = "boom")
        assertEquals(StatusTone.NEGATIVE, failed.tone)
        assertTrue(failed.text.contains("boom"))
        val chat = s(SessionState.READY, AccountInfo(AccountInfo.Kind.CHATGPT, "a@b.c", "plus"))
        assertEquals("로그인됨 · a@b.c · plus", chat.text)
        assertEquals(StatusTone.POSITIVE, chat.tone)
        assertEquals("로그인됨 · ChatGPT", s(SessionState.READY, AccountInfo(AccountInfo.Kind.CHATGPT)).text)
        assertEquals(StatusTone.POSITIVE, s(SessionState.READY, AccountInfo(AccountInfo.Kind.API_KEY)).tone)
        val none = s(SessionState.READY, AccountInfo(AccountInfo.Kind.NONE))
        assertEquals(StatusTone.NEUTRAL, none.tone)
        assertFalse(none.loading)
    }

    @Test fun etlTokenFormat() {
        assertTrue(SettingsLogic.isPlausibleEtlToken("abcd1234"))
        assertFalse(SettingsLogic.isPlausibleEtlToken("short"))
        assertFalse(SettingsLogic.isPlausibleEtlToken("has space 12345"))
        assertFalse(SettingsLogic.isPlausibleEtlToken("토큰토큰토큰토큰토큰"))
    }

    // ---- 마감 알림 시점(분) -----------------------------------------------------

    private val def = listOf(4320, 1440, 180, 60)

    @Test fun customWindowParsing() {
        assertEquals(SettingsLogic.CustomWindow.Ok(30), SettingsLogic.parseCustomWindow("30", SettingsLogic.WindowUnit.MINUTE, def))
        assertEquals(SettingsLogic.CustomWindow.Ok(300), SettingsLogic.parseCustomWindow(" 5 ", SettingsLogic.WindowUnit.HOUR, def))
        assertEquals(SettingsLogic.CustomWindow.Ok(14 * 1440), SettingsLogic.parseCustomWindow("14", SettingsLogic.WindowUnit.DAY, def))
        assertTrue(SettingsLogic.parseCustomWindow("", SettingsLogic.WindowUnit.HOUR, def) is SettingsLogic.CustomWindow.Error)
        assertTrue(SettingsLogic.parseCustomWindow("5", SettingsLogic.WindowUnit.MINUTE, def) is SettingsLogic.CustomWindow.Error)
        assertTrue(SettingsLogic.parseCustomWindow("15", SettingsLogic.WindowUnit.DAY, def) is SettingsLogic.CustomWindow.Error)
        assertTrue(SettingsLogic.parseCustomWindow("0", SettingsLogic.WindowUnit.HOUR, def) is SettingsLogic.CustomWindow.Error)
        // 이미 있는 값, 개수 제한
        assertTrue(SettingsLogic.parseCustomWindow("1", SettingsLogic.WindowUnit.HOUR, def) is SettingsLogic.CustomWindow.Error)
        val full = (1..12).map { it * 60 }
        assertTrue(SettingsLogic.parseCustomWindow("30", SettingsLogic.WindowUnit.MINUTE, full) is SettingsLogic.CustomWindow.Error)
        assertFalse(SettingsLogic.canAddWindow(full))
        assertTrue(SettingsLogic.canAddWindow(def))
    }

    @Test fun chipsAreOnlyEnabledWindowsNearestFirst() {
        assertEquals(listOf(60, 180, 1440, 4320), SettingsLogic.windowChips(def))
        // 프리셋(6시간 등)이라도 켜져 있지 않으면 칩이 없다
        assertFalse(360 in SettingsLogic.windowChips(def))
    }

    @Test fun removeAddAndReset() {
        // 기본 칩(3시간)도 지울 수 있다
        val noThree = SettingsLogic.removeWindow(def, 180)!!
        assertEquals(listOf(4320, 1440, 60), noThree)
        assertFalse(SettingsLogic.isDefaultWindows(noThree))
        // 다시 추가
        assertEquals(def, SettingsLogic.addWindow(noThree, 180))
        // 직접 값 추가 후 삭제
        val withThirty = SettingsLogic.addWindow(def, 30)
        assertEquals(listOf(4320, 1440, 180, 60, 30), withThirty)
        assertEquals(def, SettingsLogic.removeWindow(withThirty, 30))
        // 마지막 하나는 지우지 않는다
        assertEquals(null, SettingsLogic.removeWindow(listOf(30), 30))
        // 없는 값 지우기는 그대로
        assertEquals(def, SettingsLogic.removeWindow(def, 90))
        // 되돌리기
        assertTrue(SettingsLogic.isDefaultWindows(SettingsLogic.defaultWindows()))
        assertTrue(SettingsLogic.isDefaultWindows(listOf(60, 180, 1440, 4320)))
        // 개수 제한: 꽉 차면 더하지 않는다
        val full = (1..12).map { it * 60 }
        assertEquals(NotifySettings.normalizeWindows(full), SettingsLogic.addWindow(full, 30))
    }
}
