package kr.snu.etlassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityTrackerTest {

    @Test fun idleShowsNothing() {
        val t = ActivityTracker()
        assertNull(t.current())
        assertNull(t.toolStarted("a", "etl_digest")) // 턴이 아닐 때 들어온 도구 이벤트는 무시
        assertNull(t.textDelta())
    }

    @Test fun turnStartShowsThinking() {
        val t = ActivityTracker()
        assertEquals(ActivityTracker.THINKING, t.turnStarted())
    }

    @Test fun toolsAreGroupedIntoPlainLabels() {
        assertEquals("조회하는 중…", ActivityTracker.forTool("etl_digest"))
        assertEquals("조회하는 중…", ActivityTracker.forTool("etl_courses"))
        assertEquals("조회하는 중…", ActivityTracker.forTool("etl_assignment"))
        assertEquals("조회하는 중…", ActivityTracker.forTool("etl_search_files"))
        assertEquals("조회하는 중…", ActivityTracker.forTool("etl_verify_submission"))
        assertEquals("조회하는 중…", ActivityTracker.forTool("etl_get"))
        assertEquals("다운로드하는 중…", ActivityTracker.forTool("etl_download"))
        assertEquals("파일 읽는 중…", ActivityTracker.forTool("etl_read_file"))
        assertEquals("파일 읽는 중…", ActivityTracker.forTool("etl_render_pages"))
        assertEquals("처리하는 중…", ActivityTracker.forTool("something_new"))
    }

    @Test fun toolLifecycleReturnsToThinkingThenHidesWhileTextStreams() {
        val t = ActivityTracker()
        t.turnStarted()
        assertEquals("조회하는 중…", t.toolStarted("1", "etl_digest"))
        assertEquals(ActivityTracker.THINKING, t.toolFinished("1"))   // 도구가 끝나고 다음 글이 나오기 전
        assertNull(t.textDelta())                                      // 글이 흐르면 숨김
        assertNull(t.textDelta())
        assertEquals(ActivityTracker.THINKING, t.messageDone())        // 글 덩어리가 끝났고 턴은 계속
        assertNull(t.turnEnded())
        assertNull(t.current())
    }

    @Test fun parallelToolsShowTheLatestAndKeepShowingUntilAllDone() {
        val t = ActivityTracker()
        t.turnStarted()
        assertEquals("조회하는 중…", t.toolStarted("a", "etl_assignment"))
        assertEquals("조회하는 중…", t.toolStarted("b", "etl_verify_submission"))
        assertEquals("조회하는 중…", t.toolFinished("a"))               // b가 아직 진행 중
        assertEquals(ActivityTracker.THINKING, t.toolFinished("b"))
    }

    @Test fun labelFollowsTheMostRecentlyStartedTool() {
        val t = ActivityTracker()
        t.turnStarted()
        t.toolStarted("a", "etl_search_files")
        assertEquals("다운로드하는 중…", t.toolStarted("b", "etl_download"))
        assertEquals("조회하는 중…", t.toolFinished("b"))               // 남은 a의 문구로 돌아간다
    }

    @Test fun toolAfterTextShowsAgain() {
        val t = ActivityTracker()
        t.turnStarted()
        t.textDelta()
        assertEquals("다운로드하는 중…", t.toolStarted("1", "etl_download"))
    }

    @Test fun newTurnClearsLeftovers() {
        val t = ActivityTracker()
        t.turnStarted()
        t.toolStarted("1", "etl_download")
        t.turnEnded()
        assertEquals(ActivityTracker.THINKING, t.turnStarted()) // 이전 턴의 도구가 남지 않는다
    }

    @Test fun finishingUnknownToolIsHarmless() {
        val t = ActivityTracker()
        t.turnStarted()
        assertEquals(ActivityTracker.THINKING, t.toolFinished("nope"))
    }
}
