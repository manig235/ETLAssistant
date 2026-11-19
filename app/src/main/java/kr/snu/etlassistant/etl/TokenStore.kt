package kr.snu.etlassistant.etl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * eTL 토큰과 API 키 같은 비밀값을 Android Keystore의 AES-GCM 키로 암호화해 앱 내부 저장소에 둔다.
 * 원문은 로그, 예외 메시지, 크래시 리포트에 남기지 않는다.
 *
 * 값마다 Keystore 키 별칭과 저장 키가 다르다. **기존 값의 별칭과 저장 키는 바꾸지 말 것**(이미 저장된 값을 읽지 못하게 된다).
 */
open class SecretStore(context: Context, private val alias: String, private val blobKey: String) {

    private val prefs = context.applicationContext.getSharedPreferences("etl_secure", Context.MODE_PRIVATE)

    fun has(): Boolean = prefs.contains(blobKey)

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct
        prefs.edit().putString(blobKey, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    /** 저장된 토큰. 없거나 복호화에 실패하면 null(키가 바뀐 경우 등). */
    fun load(): String? {
        val b64 = prefs.getString(blobKey, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LEN)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(blobKey).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}

/** eTL 액세스 토큰. */
class TokenStore(context: Context) : SecretStore(context, "etl_token_key", "token_blob") {
    fun hasToken(): Boolean = has()
}

/** 사용자가 직접 넣는 OpenAI API 키(고급 옵션). Codex에는 환경변수로만 넘기고 auth.json에는 남기지 않는다. */
class ApiKeyStore(context: Context) : SecretStore(context, "openai_api_key_key", "apikey_blob")
