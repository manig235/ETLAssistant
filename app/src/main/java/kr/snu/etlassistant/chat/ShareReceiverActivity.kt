package kr.snu.etlassistant.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.onboarding.OnboardingActivity
import kr.snu.etlassistant.onboarding.OnboardingLogic
import kr.snu.etlassistant.onboarding.OnboardingPrefs
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 다른 앱에서 "공유 → 영민이"로 받은 내용의 순수 해석(JVM 단위 테스트 대상). */
object ShareIntentLogic {
    const val MAX_TEXT_CHARS = 4000
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /** [uris]: 받을 파일(최대 [AttachmentLogic.MAX_PENDING]개), [dropped]: 상한을 넘어 버린 개수, [text]: 입력창에 채울 글. */
    data class Content(val uris: List<String>, val dropped: Int, val text: String?)

    /**
     * ACTION_SEND(EXTRA_STREAM 하나 또는 글), ACTION_SEND_MULTIPLE(EXTRA_STREAM 여럿)을 해석한다. 다른 동작이면 null.
     * 파일은 content:// 주소만 받고 중복을 뺀다. file:// 주소와 이 앱 자신의 제공자 주소([ownAuthorityPrefix]로 시작)는 버린다
     * (다른 앱이 이 앱의 내부 파일을 대화에 첨부하게 만들지 못하게). 글은 제목(EXTRA_SUBJECT)이 본문에 없으면 앞줄에 붙이고 길이를 자른다.
     */
    fun parse(action: String?, streams: List<String?>, text: String?, subject: String?, ownAuthorityPrefix: String = "kr.snu.etlassistant"): Content? {
        if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return null
        val files = streams.filterNotNull().map { it.trim() }
            .filter { it.startsWith("content://", ignoreCase = true) }
            .filter { !it.substring("content://".length).substringBefore('/').startsWith(ownAuthorityPrefix, ignoreCase = true) }
            .distinct()
        val take = files.take(AttachmentLogic.MAX_PENDING)
        val body = text?.trim().orEmpty()
        val subj = subject?.trim().orEmpty()
        val combined = when {
            subj.isNotEmpty() && body.isNotEmpty() && !body.contains(subj) -> "$subj\n$body"
            body.isNotEmpty() -> body
            // 파일과 함께 온 제목만 있는 경우(메일 앱 등)는 입력창에 넣지 않는다.
            subj.isNotEmpty() && take.isEmpty() -> subj
            else -> ""
        }.take(MAX_TEXT_CHARS)
        if (take.isEmpty() && combined.isEmpty()) return Content(emptyList(), files.size, null)
        return Content(take, files.size - take.size, combined.ifEmpty { null })
    }
}

/**
 * 받은 공유를 채팅 화면에 넘기는 메모리 상자. 외부 앱이 채팅 화면에 첨부 경로를 직접 넣지 못하도록 인텐트에는 무작위 키만 싣는다
 * (채팅 화면이 exported이므로, 엑스트라로 받은 경로는 믿지 않는다).
 */
object ShareInbox {
    data class Shared(val attachments: List<Attachment>, val text: String?, val failures: List<String>, val dropped: Int)

    private val box = ConcurrentHashMap<String, Shared>()

    fun put(s: Shared): String = UUID.randomUUID().toString().also { box[it] = s }
    fun take(key: String?): Shared? = key?.let { box.remove(it) }
}

/**
 * "공유 → 영민이"(ACTION_SEND / ACTION_SEND_MULTIPLE). 화면 없이 받은 즉시 파일을 작업 폴더로 복사하고(받은 읽기 권한은
 * 이 화면에만 짧게 주어진다) 채팅 화면의 새 대화에 첨부·글로 채운다. 자동으로 보내지 않는다.
 * 받은 내용은 입력창·첨부로만 쓰고 그 밖의 동작(설정 변경 등)은 하지 않는다. 첫 설정 전이면 버리고 안내로 보낸다.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        if (!OnboardingLogic.shouldSkip(OnboardingPrefs(this).completed, EtlService.tokens(this).hasToken())) {
            Toast.makeText(app, "먼저 첫 설정을 마쳐 주세요. 공유한 내용은 가져오지 않았어요", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        val content = runCatching { parse(intent) }.getOrNull()
        if (content == null || (content.uris.isEmpty() && content.text == null)) {
            Toast.makeText(app, "가져올 수 있는 내용이 없어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (content.uris.isNotEmpty()) Toast.makeText(app, "파일을 가져오는 중이에요…", Toast.LENGTH_SHORT).show()
        Thread({
            val atts = mutableListOf<Attachment>()
            val failures = mutableListOf<String>()
            for (u in content.uris) {
                runCatching { AttachmentImporter.import(this, Uri.parse(u)) }
                    .onSuccess { atts += it }
                    .onFailure { e -> failures += ((e as? AttachmentImporter.ImportException)?.message ?: "첨부하지 못했어요") }
            }
            val key = ShareInbox.put(ShareInbox.Shared(atts, content.text, failures, content.dropped))
            runOnUiThread {
                app.startActivity(Intent(app, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_SHARE_KEY, key)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }, "share-import").start()
    }

    private fun parse(i: Intent): ShareIntentLogic.Content? {
        val streams: List<String?> = when (i.action) {
            Intent.ACTION_SEND_MULTIPLE -> streamList(i)
            Intent.ACTION_SEND -> listOf(stream(i))
            else -> emptyList()
        } + (i.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri?.toString() } } ?: emptyList())
        return ShareIntentLogic.parse(
            i.action, streams,
            i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            i.getStringExtra(Intent.EXTRA_SUBJECT),
            ownAuthorityPrefix = packageName,
        )
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): String? =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.toString()

    @Suppress("DEPRECATION")
    private fun streamList(i: Intent): List<String?> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.mapNotNull { it as? Uri })
            .orEmpty().map { it.toString() }
}
