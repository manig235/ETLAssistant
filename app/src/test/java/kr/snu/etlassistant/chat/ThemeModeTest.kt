package kr.snu.etlassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test fun choicesRestoreFromSavedKeys() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.key)) }
    }
    @Test fun existingUsersAndUnknownValuesFollowSystem() {
        listOf(null,"","unknown").forEach { assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(it)) }
    }
}
