package kr.snu.youngmini.desktop

import kr.snu.etlassistant.codex.ChatGptCredentials
import kr.snu.etlassistant.codex.ChatGptOAuth
import kr.snu.etlassistant.codex.ChatGptOAuthProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

class AuthAndStorageTest {
    private fun credentials(): ChatGptCredentials {
        val payload = JSONObject().put("exp", 2_000_000_000).put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "test-account").put("chatgpt_plan_type", "plus"))
        val jwt = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray()) + ".signature"
        return ChatGptCredentials.fromTokens(JSONObject().put("access_token", jwt).put("refresh_token", "not-a-real-refresh-token").put("id_token", jwt), null)
    }

    @Test fun deviceLoginExchangesTokensThroughPinnedProtocolAndKeepsRefreshOutOfCodex() {
        val calls = mutableListOf<String>()
        val expected = credentials()
        val transport = ChatGptOAuth.Transport { path, body, json, _ ->
            calls += path
            when (path) {
                "/api/accounts/deviceauth/usercode" -> JSONObject().put("device_auth_id", "fake").put("user_code", "ABCD-EFGH")
                "/api/accounts/deviceauth/token" -> JSONObject().put("authorization_code", "fake-code").put("code_verifier", "fake-verifier")
                "/oauth/token" -> { assertFalse(json); assertTrue(body.contains("code=fake-code")); JSONObject(expected.json()) }
                else -> throw AssertionError("Unexpected request")
            }
        }
        val actual = ChatGptOAuth.device(ChatGptOAuth.Attempt(), { url, code ->
            assertEquals("https://auth.openai.com/codex/device", url); assertEquals("ABCD-EFGH", code)
        }, transport)
        assertEquals(expected.accessToken, actual.accessToken)
        assertFalse(actual.externalParams().toString().contains(actual.refreshToken))
        assertEquals(listOf("/api/accounts/deviceauth/usercode", "/api/accounts/deviceauth/token", "/oauth/token"), calls)
    }

    @Test fun pkceCallbackRejectsForgedStateAndCancelIsEffective() {
        assertEquals("code", ChatGptOAuthProtocol.callbackCode("/auth/callback?state=expected&code=code", "expected"))
        assertNull(ChatGptOAuthProtocol.callbackCode("/auth/callback?state=forged&code=code", "expected"))
        assertNull(ChatGptOAuthProtocol.callbackCode("/auth/callback?state=expected&state=expected&code=code", "expected"))
        assertNull(ChatGptOAuthProtocol.callbackCode("https://evil.example/auth/callback?state=expected&code=code", "expected"))
        val attempt = ChatGptOAuth.Attempt().also { it.cancel() }
        assertThrows(IOException::class.java) { attempt.check() }
    }

    @Test fun windowsVaultPersistsCiphertextAndLogoutRemovesIt() {
        assumeTrue("Windows DPAPI requires a Windows test runner", Platform.windows)
        val root = Files.createTempDirectory("youngmini-dpapi").toFile()
        try {
            val vault = SecretVault(root)
            val secret = credentials().json()
            vault.write("chatgpt", secret)
            val encrypted = File(root, "chatgpt.dpapi").readBytes()
            assertFalse(encrypted.toString(Charsets.UTF_8).contains("not-a-real-refresh-token"))
            assertEquals(secret, SecretVault(root).read("chatgpt"))
            vault.clear("chatgpt")
            assertNull(vault.read("chatgpt"))
        } finally { root.deleteRecursively() }
    }

    @Test fun chatHistoryCanResumeAndAppDataHasASingleWriter() {
        val root = Files.createTempDirectory("youngmini-history").toFile()
        try {
            val paths = AppPaths(root)
            paths.lock().use { assertThrows(IOException::class.java) { paths.lock() } }
            paths.lock().close()
            val saved = SavedChat(UUID.randomUUID().toString(), "A lecture", "fake-thread", listOf(ChatMessage("1", "user", "Explain eigenvalues"), ChatMessage("2", "assistant", "A scalar…")))
            ChatHistory(paths.chats).save(saved)
            assertEquals(saved, ChatHistory(paths.chats).list().single())
        } finally { root.deleteRecursively() }
    }
}
