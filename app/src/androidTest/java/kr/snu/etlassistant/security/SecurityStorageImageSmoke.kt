package kr.snu.etlassistant.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import kr.snu.etlassistant.codex.ChatGptAuthStorage
import kr.snu.etlassistant.etl.SecretStore
import kr.snu.etlassistant.etl.WorkspaceImage
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

/** Exercises Android Keystore and the real image decoder with synthetic data only. */
object SecurityStorageImageSmoke {
    fun run(context: Context) {
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "security-smoke-$id").apply { check(mkdirs()) }
        val alias = "security_smoke_key_$id"
        val blobKey = "security_smoke_blob_$id"
        val store = SecretStore(context, alias, blobKey)
        try {
            val claims = JSONObject().put("exp", 4_000_000_000L)
                .put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "synthetic-account"))
            val jwt = "e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.toString().toByteArray()) + ".synthetic-signature"
            val legacy = File(root, "codex-home").apply { check(mkdir()) }
            File(legacy, "auth.json").writeText(JSONObject().put("tokens", JSONObject()
                .put("access_token", jwt).put("refresh_token", "synthetic-refresh-token")
                .put("account_id", "synthetic-account")).toString())
            ChatGptAuthStorage.migrate(legacy, store::load, store::save)
            check(!File(legacy, "auth.json").exists()) { "legacy auth fixture was not removed" }
            val saved = SecretStore(context, alias, blobKey).load()
            check(saved != null && JSONObject(saved).getString("access_token") == jwt) { "Keystore round trip failed" }
            val ciphertext = context.getSharedPreferences("etl_secure", Context.MODE_PRIVATE).getString(blobKey, null)
            check(ciphertext != null && !ciphertext.contains(jwt) && !ciphertext.contains("synthetic-refresh-token")) {
                "credential fixture was not encrypted"
            }
            store.clear()
            check(!store.has() && store.load() == null) { "logout did not remove ciphertext" }
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)) {
                "logout did not remove the encryption key"
            }

            val workspace = File(root, "workspace")
            val images = File(workspace, "etl-files").apply { check(mkdirs()) }
            val original = File(images, "synthetic.jpg")
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.RED)
                original.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            ExifInterface(original.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_USER_COMMENT, "SYNTHETIC_PRIVATE_METADATA")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                saveAttributes()
            }
            val result = WorkspaceImage.view(workspace, "etl-files/synthetic.jpg")
            check(!result.isError && result.images.size == 1) { "image fixture was rejected" }
            val image = result.images.single()
            check(image.mimeType == "image/jpeg") { "image was not re-encoded" }
            val bytes = Base64.getDecoder().decode(image.data)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outWidth == 40 && bounds.outHeight == 80) { "EXIF rotation was lost" }
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            check(exif.getAttribute(ExifInterface.TAG_USER_COMMENT) == null &&
                exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) == null) { "private EXIF metadata survived" }
            val outside = File(root, "outside.jpg").also { original.copyTo(it) }
            check(WorkspaceImage.view(workspace, outside.absolutePath).isError) { "outside image was allowed" }
            Files.createSymbolicLink(File(images, "escape.jpg").toPath(), outside.toPath())
            check(WorkspaceImage.view(workspace, "etl-files/escape.jpg").isError) { "symlink escape was allowed" }
        } finally {
            store.clear()
            // Remove our symlink itself; never follow it while cleaning up fixtures.
            File(root, "workspace/etl-files/escape.jpg").delete()
            root.deleteRecursively()
        }
    }
}
