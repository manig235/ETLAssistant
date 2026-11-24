package kr.snu.etlassistant.codex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatGptAuthTest {
    companion object {
        fun token(account: String = "workspace", expiry: Long = 2_000_000_000, suffix: String = "a"): String {
            val claims = JSONObject().put("exp", expiry).put("email", "student@example.test")
                .put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", account).put("chatgpt_plan_type", "plus"))
            return "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray()) + ".$suffix"
        }
        fun credentials(access: String = token(), refresh: String = "fake-refresh") = ChatGptCredentials.fromTokens(
            JSONObject().put("access_token", access).put("refresh_token", refresh).put("id_token", access), null,
        )
    }
    private class MemoryVault(initial: String? = null) : ChatGptVault {
        var value = initial
        var failSave = false
        override fun load() = value
        override fun save(value: String) { if (failSave) throw IOException("disk unavailable"); this.value = value }
        override fun clear() { value = null }
    }

    @Test fun parseLegacyAndRefreshPreserveGrantButNeverPrintTokens() {
        val old = credentials()
        val legacy = JSONObject().put("tokens", JSONObject(old.json())).put("auth_mode", "chatgpt")
        val parsed = ChatGptCredentials.parse(legacy.toString())
        assertEquals(old.accessToken, parsed.accessToken)
        assertEquals(old.refreshToken, parsed.refreshToken)
        val refreshed = ChatGptCredentials.fromTokens(JSONObject().put("access_token", token(suffix = "new")), old)
        assertEquals(old.refreshToken, refreshed.refreshToken)
        assertEquals(old.accountId, refreshed.accountId)
        assertFalse(old.toString().contains(old.refreshToken))
        assertFalse(old.externalParams().toString().contains("refresh_token"))
        val exception = assertThrows(IOException::class.java) { ChatGptCredentials.parse("bad-secret-document") }
        assertFalse(exception.toString().contains("bad-secret-document"))
        assertThrows(IOException::class.java) { ChatGptCredentials.fromTokens(JSONObject().put("access_token", token(account = "other")), old) }
    }

    @Test fun browserUsesPkceAndRejectsWrongDuplicateMissingStateAndForeignTargets() {
        val verifier = "test-verifier"
        val state = "unguessable-state"
        val url = ChatGptOAuthProtocol.authorize("http://127.0.0.1:1455/auth/callback", verifier, state)
        assertTrue(url.startsWith("https://auth.openai.com/oauth/authorize?"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=" + ChatGptOAuthProtocol.challenge(verifier)))
        assertFalse(url.contains(verifier))
        assertEquals("code", ChatGptOAuthProtocol.callbackCode("/auth/callback?code=code&state=$state", state))
        listOf("/auth/callback?code=code", "/auth/callback?code=code&state=wrong", "/auth/callback?code=code&state=$state&state=$state",
            "/auth/callback?code=a&code=b&state=$state", "https://evil.example/auth/callback?code=code&state=$state", "/other?code=code&state=$state", "//foreign.example/auth/callback?code=code&state=$state").forEach {
            assertNull(ChatGptOAuthProtocol.callbackCode(it, state))
        }
        val attempt = ChatGptOAuth.Attempt()
        attempt.cancel()
        assertThrows(IOException::class.java) { attempt.check() }
    }

    @Test fun deviceCodeFlowUsesPinnedEndpointsAndExchangesWithoutWritingAnyAuthFile() {
        val paths = mutableListOf<String>()
        val transport = ChatGptOAuth.Transport { path, body, json, _ ->
            paths += path
            when (path) {
                "/api/accounts/deviceauth/usercode" -> {
                    assertTrue(json)
                    assertEquals(ChatGptOAuthProtocol.CLIENT_ID, JSONObject(body).getString("client_id"))
                    JSONObject().put("device_auth_id", "device-id").put("user_code", "ABCD-EFGH").put("interval", "1")
                }
                "/api/accounts/deviceauth/token" -> {
                    assertEquals("device-id", JSONObject(body).getString("device_auth_id"))
                    JSONObject().put("authorization_code", "fake-auth-code").put("code_verifier", "fake-verifier")
                }
                "/oauth/token" -> {
                    assertFalse(json)
                    assertTrue(body.contains("code=fake-auth-code"))
                    assertTrue(body.contains("code_verifier=fake-verifier"))
                    assertTrue(body.contains("redirect_uri=https%3A%2F%2Fauth.openai.com%2Fdeviceauth%2Fcallback"))
                    JSONObject(credentials().json())
                }
                else -> throw AssertionError("Unexpected endpoint")
            }
        }
        var prompted = false
        val result = ChatGptOAuth.device(ChatGptOAuth.Attempt(), { url, code ->
            assertEquals("https://auth.openai.com/codex/device", url)
            assertEquals("ABCD-EFGH", code)
            prompted = true
        }, transport)
        assertTrue(prompted)
        assertEquals(credentials().accessToken, result.accessToken)
        assertEquals(listOf("/api/accounts/deviceauth/usercode", "/api/accounts/deviceauth/token", "/oauth/token"), paths)
    }

    @Test fun startupMigrationCommitsAndVerifiesBeforeRemovingAllPlaintextCopies() {
        val home = Files.createTempDirectory("auth-migrate").toFile()
        try {
            val document = credentials().json()
            File(home, "auth.json").writeText(document)
            File(home, ".auth-crash").writeText(document)
            var saved: String? = null
            ChatGptAuthStorage.migrate(home, { saved }, { value ->
                assertTrue(File(home, "auth.json").exists())
                saved = value
            })
            assertEquals(document, saved)
            assertFalse(File(home, "auth.json").exists())
            assertFalse(File(home, ".auth-crash").exists())
            assertTrue(home.listFiles()!!.isEmpty())
        } finally { home.deleteRecursively() }
    }

    @Test fun failedMigrationKeepsOriginalAndNeverStartsWithUncommittedCredentials() {
        val home = Files.createTempDirectory("auth-migrate-fail").toFile()
        try {
            val original = File(home, "auth.json").apply { writeText(credentials().json()) }
            assertThrows(IOException::class.java) { ChatGptAuthStorage.migrate(home, { null }, { throw IOException("disk full") }) }
            assertTrue(original.exists())
            assertThrows(IOException::class.java) { ChatGptAuthStorage.migrate(home, { null }, { }) }
            assertTrue(original.exists())
            assertThrows(IOException::class.java) { ChatGptAuthStorage.migrate(home, { "malformed-existing-ciphertext" }, { }) }
            assertTrue(original.exists())
            original.writeText("invalid document")
            assertThrows(IOException::class.java) { ChatGptAuthStorage.migrate(home, { null }, { }) }
            assertEquals("invalid document", original.readText())
        } finally { home.deleteRecursively() }
    }

    @Test fun deletionFailureBlocksMigrationAndRetryUsesAlreadyCommittedGrant() {
        val home = Files.createTempDirectory("auth-delete-fail").toFile()
        try {
            val original = File(home, "auth.json").apply { writeText(credentials().json()) }
            var committed: String? = null
            var saves = 0
            assertThrows(IOException::class.java) {
                ChatGptAuthStorage.migrate(home, { committed }, { committed = it; saves++ }, deleteLegacy = { false })
            }
            assertTrue(original.exists())
            assertNotNull(committed)
            ChatGptAuthStorage.migrate(home, { committed }, { throw AssertionError("Already committed") })
            assertFalse(original.exists())
            assertEquals(1, saves)
        } finally { home.deleteRecursively() }
    }

    @Test fun canceledLoginAndLogoutCannotResurrectCredentials() {
        val vault = MemoryVault()
        val manager = ChatGptAuthManager(vault)
        val canceled = manager.newLogin()
        manager.cancelLogin()
        assertFalse(manager.finishLogin(canceled, credentials()))
        assertNull(vault.value)
        val active = manager.newLogin()
        assertTrue(manager.finishLogin(active, credentials()))
        assertNotNull(manager.credentials())
        manager.clear()
        assertNull(manager.credentials())
        assertNull(vault.value)
    }

    @Test fun cancelAfterVaultCommitRollsBackBeforeAnotherAccountCanBeInstalled() {
        val original = credentials().json()
        val vault = MemoryVault(original)
        val manager = ChatGptAuthManager(vault)
        val attempt = manager.newLogin()
        assertTrue(manager.finishLogin(attempt, credentials(token(suffix = "new-login"), "new-login-refresh")))
        manager.cancelLogin()
        assertFalse(manager.confirmLogin(attempt))
        manager.rollbackLogin(attempt)
        assertEquals(original, vault.value)
        val nextAttempt = manager.newLogin()
        assertTrue(manager.finishLogin(nextAttempt, credentials()))
        assertTrue(manager.confirmLogin(nextAttempt))
        assertNotNull(manager.credentials())
    }

    @Test fun refreshRotationMustCommitAndRetryKeepsTheRotatedGrant() {
        val initial = credentials(token(expiry = 100))
        val next = credentials(token(suffix = "rotated"), "rotated-refresh")
        val vault = MemoryVault(initial.json())
        val refreshes = AtomicInteger()
        val manager = ChatGptAuthManager(vault, { refreshes.incrementAndGet(); next }, { 1000 })
        vault.failSave = true
        assertThrows(IOException::class.java) { manager.credentials() }
        assertEquals(initial.json(), vault.value)
        vault.failSave = false
        assertEquals(next.accessToken, manager.credentials()!!.accessToken)
        assertEquals(next.json(), vault.value)
        assertEquals(1, refreshes.get()) // Retrying persistence must not reuse the old refresh token.
        assertThrows(IOException::class.java) { manager.credentials(previousAccountId = "wrong-workspace") }
    }

    @Test fun logoutDuringRefreshDiscardsNetworkResult() {
        val vault = MemoryVault(credentials(token(expiry = 100)).json())
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val manager = ChatGptAuthManager(vault, {
            entered.countDown()
            assertTrue(finish.await(2, TimeUnit.SECONDS))
            credentials(token(suffix = "new"), "new-refresh")
        }, { 1000 })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val refresh = worker.submit<Boolean> { try { manager.credentials(); false } catch (_: IOException) { true } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            manager.clear()
            finish.countDown()
            assertTrue(refresh.get(2, TimeUnit.SECONDS))
            assertNull(vault.value)
        } finally { finish.countDown(); worker.shutdownNow() }
    }
}
