package kr.snu.etlassistant.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyHistoryLogicTest {
    @Test
    fun channelLabelKnowsCurrentAndLegacyIds() {
        // 팝업으로 올리며 ID를 바꾼 채널: 바꾸기 전에 남은 기록도 같은 이름으로 보여야 한다.
        assertEquals("공지", NotifyHistoryLogic.channelLabel(Channel.ANNOUNCEMENT.id))
        assertEquals("공지", NotifyHistoryLogic.channelLabel("announcement"))
        assertEquals("과제·성적", NotifyHistoryLogic.channelLabel(Channel.CHANGE.id))
        assertEquals("과제·성적", NotifyHistoryLogic.channelLabel("change"))
        assertEquals("마감", NotifyHistoryLogic.channelLabel("deadline"))
        assertEquals("알림", NotifyHistoryLogic.channelLabel("unknown"))
    }
}
