package kr.snu.etlassistant.codex

import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Token material is never included in toString, errors, URLs, or application logs. */
class ChatGptCredentials(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val accountId: String,
    val plan: String?,
    val expiresAt: Long,
) {
    override fun toString() = "ChatGptCredentials(<redacted>)"
    fun json(): String = JSONObject().put("access_token", accessToken).put("refresh_token", refreshToken)
        .put("id_token", idToken).put("account_id", accountId).put("plan", plan ?: JSONObject.NULL).put("expires_at", expiresAt).toString()
    fun externalParams(): JSONObject = JSONObject().put("accessToken", accessToken).put("chatgptAccountId", accountId)
        .put("chatgptPlanType", plan ?: JSONObject.NULL)

    companion object {
        private fun text(o: JSONObject, key: String) = o.optString(key).takeIf { it.isNotBlank() && it != "null" }
        fun parse(text: String): ChatGptCredentials = try {
            val root = JSONObject(text)
            val tokens = root.optJSONObject("tokens") ?: root
            fromTokens(tokens, null)
        } catch (_: Exception) { throw IOException("저장된 ChatGPT 로그인 정보를 읽지 못했어요. 다시 로그인해 주세요.") }

        fun fromTokens(tokens: JSONObject, previous: ChatGptCredentials?): ChatGptCredentials {
            try {
                val access = text(tokens, "access_token") ?: throw IOException()
                val refresh = text(tokens, "refresh_token") ?: previous?.refreshToken ?: throw IOException()
                val id = text(tokens, "id_token") ?: previous?.idToken.orEmpty()
                val claims = claims(access)
                val identity = claims.optJSONObject("https://api.openai.com/auth")
                val idIdentity = runCatching { claims(id).optJSONObject("https://api.openai.com/auth") }.getOrNull()
                val account = text(tokens, "account_id") ?: identity?.let { text(it, "chatgpt_account_id") }
                    ?: idIdentity?.let { text(it, "chatgpt_account_id") } ?: previous?.accountId ?: throw IOException()
                if (previous != null && account != previous.accountId) throw IOException()
                val plan = text(tokens, "plan") ?: identity?.let { text(it, "chatgpt_plan_type") } ?: previous?.plan
                val expiry = claims.optLong("exp", 0).takeIf { it > 0 } ?: throw IOException()
                return ChatGptCredentials(access, refresh, id, account, plan, expiry)
            } catch (_: Exception) { throw IOException("ChatGPT 로그인 응답을 확인하지 못했어요. 다시 로그인해 주세요.") }
        }

        private fun claims(jwt: String): JSONObject {
            val pieces = jwt.split('.')
            if (pieces.size != 3 || pieces[1].length > 64 * 1024) throw IOException()
            return JSONObject(String(Base64.getUrlDecoder().decode(pieces[1]), Charsets.UTF_8))
        }
    }
}

/** Protocol values copied from bundled Codex rust-v0.170.0 login/{server,device_code_auth}.rs. */
internal object ChatGptOAuthProtocol {
    const val ORIGIN = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val LOGIN_TIMEOUT_MS = 15 * 60 * 1000L
    fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun form(values: Map<String, String>) = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    fun authorize(redirect: String, verifier: String, state: String): String = ORIGIN + "/oauth/authorize?" + form(linkedMapOf(
        "response_type" to "code", "client_id" to CLIENT_ID, "redirect_uri" to redirect,
        "scope" to "openid profile email offline_access api.connectors.read api.connectors.invoke",
        "code_challenge" to challenge(verifier), "code_challenge_method" to "S256", "state" to state,
        "id_token_add_organizations" to "true", "codex_cli_simplified_flow" to "true", "originator" to "codex_cli_rs",
    ))
    fun callbackCode(target: String, expectedState: String): String? = try {
        val uri = URI(target)
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null || uri.rawPath != "/auth/callback") null else {
            val pairs = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
            val values = pairs.groupBy({ URLDecoder.decode(it[0], "UTF-8") }, { URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") })
            val state = values["state"]?.singleOrNull()
            if (state == null || !MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) null
            else values["code"]?.singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 16 * 1024 }
        }
    } catch (_: Exception) { null }
}
