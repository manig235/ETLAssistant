package kr.snu.etlassistant.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyFormatTest {

    @Test fun acceptsTypicalKeysAndRejectsJunk() {
        assertTrue(SettingsActivity.isPlausibleApiKey("sk-proj-abcdefghijklmnopqrstuvwxyz0123456789"))
        assertTrue(SettingsActivity.isPlausibleApiKey("sk-" + "a".repeat(40)))
        assertFalse(SettingsActivity.isPlausibleApiKey(""))
        assertFalse(SettingsActivity.isPlausibleApiKey("sk-short"))
        assertFalse(SettingsActivity.isPlausibleApiKey("pk-" + "a".repeat(40)))
        assertFalse(SettingsActivity.isPlausibleApiKey("sk-" + "a".repeat(20) + " " + "b".repeat(10)))
        assertFalse(SettingsActivity.isPlausibleApiKey("sk-" + "가".repeat(30)))
        assertFalse(SettingsActivity.isPlausibleApiKey("sk-" + "a".repeat(20) + "\n"))
    }

    @Test fun maskShowsOnlyLastFourAndNeverTheMiddle() {
        val key = "sk-proj-SECRETSECRETSECRET-abcd"
        val masked = SettingsActivity.maskKey(key)
        assertEquals("sk-…abcd", masked)
        assertFalse(masked.contains("SECRET"))
        assertEquals("••••", SettingsActivity.maskKey("sk-abc"))
    }
}
