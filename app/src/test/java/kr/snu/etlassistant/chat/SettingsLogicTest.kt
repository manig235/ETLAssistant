package kr.snu.etlassistant.chat

import kr.snu.etlassistant.codex.AccountInfo
import kr.snu.etlassistant.codex.SessionState
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
}
