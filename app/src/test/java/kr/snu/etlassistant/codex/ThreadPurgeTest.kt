package kr.snu.etlassistant.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThreadPurgeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val a = "019a1b2c-3d4e-7f00-8a11-223344556677"
    private val b = "019a1b2c-3d4e-7f00-8a11-aabbccddeeff"

    private fun touch(f: File): File { f.parentFile.mkdirs(); f.writeText("x"); return f }

    @Test fun threadIdFromFileName() {
        assertEquals(a, ThreadPurge.threadIdOf("rollout-2026-11-23T10-00-00-$a.jsonl"))
        assertEquals(a, ThreadPurge.threadIdOf("rollout-2026-11-23T10-00-00-${a.uppercase()}.jsonl.zst"))
        assertNull(ThreadPurge.threadIdOf("rollout-2026-11-23T10-00-00-$a.json"))
        assertNull(ThreadPurge.threadIdOf("notes-$a.jsonl"))
        assertFalse(ThreadPurge.isThreadId("../etc"))
    }

    @Test fun deletesOnlyThatThreadInBothFolders() {
        val home = tmp.newFolder("codex-home")
        val live = touch(File(home, "sessions/2026/11/23/rollout-2026-11-23T10-00-00-$a.jsonl"))
        val arch = touch(File(home, "archived_sessions/rollout-2026-11-20T10-00-00-$a.jsonl.zst"))
        val other = touch(File(home, "sessions/2026/11/23/rollout-2026-11-23T11-00-00-$b.jsonl"))
        val config = touch(File(home, "config.toml"))
        assertEquals(2, ThreadPurge.delete(home, a))
        assertFalse(live.exists())
        assertFalse(arch.exists())
        assertTrue(other.exists())
        assertTrue(config.exists())
        assertEquals(0, ThreadPurge.delete(home, "not-a-uuid"))
    }

    @Test fun archivedOrphansSkipKeptThreads() {
        val home = tmp.newFolder("codex-home")
        touch(File(home, "archived_sessions/rollout-2026-11-20T10-00-00-$a.jsonl"))
        touch(File(home, "archived_sessions/rollout-2026-11-20T10-00-00-$b.jsonl"))
        touch(File(home, "sessions/2026/11/23/rollout-2026-11-23T10-00-00-${a.replace('6', '0')}.jsonl"))
        assertEquals(setOf(a), ThreadPurge.archivedOrphans(home, setOf(b.uppercase())))
    }

    @Test fun doesNotFollowLinksOutOfCodexHome() {
        val outside = tmp.newFolder("outside")
        val victim = touch(File(outside, "rollout-2026-11-23T10-00-00-$a.jsonl"))
        val home = tmp.newFolder("codex-home")
        File(home, "sessions").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(home, "sessions/2026").toPath(), outside.toPath())
        java.nio.file.Files.createSymbolicLink(File(home, "archived_sessions").toPath(), outside.toPath())
        assertEquals(0, ThreadPurge.delete(home, a))
        assertTrue(victim.exists())
    }
}
