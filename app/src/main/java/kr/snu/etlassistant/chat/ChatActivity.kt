package kr.snu.etlassistant.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import kr.snu.etlassistant.codex.CodexSession
import kr.snu.etlassistant.codex.SessionEvent
import kr.snu.etlassistant.etl.EtlService
import kr.snu.etlassistant.onboarding.OnboardingActivity
import kr.snu.etlassistant.onboarding.OnboardingLogic
import kr.snu.etlassistant.onboarding.OnboardingPrefs
import kr.snu.etlassistant.phase0.R
import java.io.File
import java.util.concurrent.Executors
import kr.snu.etlassistant.update.UpdateChecker
import kr.snu.etlassistant.update.UpdateScheduler
import android.app.NotificationManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kr.snu.etlassistant.notify.BatteryOpt
import kr.snu.etlassistant.notify.Channel
import kr.snu.etlassistant.notify.NotifySettingsStore

/**
 * 채팅 화면(Montage). Codex 세션은 이 화면이 열려 있을 때만 돌린다([CodexSession]이 화면이 모두 사라지면 일정 시간 뒤 멈춘다).
 * 그리기는 [ChatRoot]가 하고, 이 클래스는 세션 이벤트와 대화 변경을 [ChatViewModel]의 상태로 옮겨 준다.
 */
class ChatActivity : AppCompatActivity(), CodexSession.Listener, ChatModel.Listener {

    private val vm: ChatViewModel by viewModels()
    private val main = Handler(Looper.getMainLooper())
    private var flushPosted = false
    private var ready = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateBanner() }

    // ---- 첨부 고르기(권한 없이 쓰는 시스템 선택기) ----
    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentLogic.MAX_PENDING)) { uris ->
        importAll(uris)
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importAll(uris)
    }
    /** 카메라가 찍은 사진을 받을 임시 파일. 가져온 뒤 지운다. */
    private var cameraFile: File? = null
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        cameraFile = null
        if (ok && f != null && f.length() > 0) importAll(listOf(providerUri(f)), cleanup = f) else f?.delete()
    }
    private val importer = Executors.newSingleThreadExecutor()

    // ---- 파일 카드 "드라이브에 저장"(시스템 문서 선택기, files/SaveCopy) ----
    /** 선택기에서 돌아올 때 저장할 첨부. 프로세스가 다시 만들어져도 잃지 않게 상태에 남긴다. */
    private var pendingSave: Attachment? = null
    private val createDocument = registerForActivityResult(kr.snu.etlassistant.files.CreateDocumentContract()) { uri ->
        val a = pendingSave
        pendingSave = null
        if (uri != null && a != null) saveCopy(a, uri)
    }
    private val saver = Executors.newSingleThreadExecutor()

    /** 스트리밍 중에는 변경이 아주 잦으므로 묶어서(약 70ms) 한 번에 다시 그린다. */
    private val flush = Runnable {
        flushPosted = false
        vm.bump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Phase0)
        super.onCreate(savedInstanceState)
        pendingSave = savedInstanceState?.getString(STATE_PENDING_SAVE)?.let { runCatching { Attachment.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        // 처음 쓰는 사람(안내를 끝내지 않았고 eTL 토큰도 없음)은 첫 실행 안내로 보낸다.
        if (!OnboardingLogic.shouldSkip(OnboardingPrefs(this).completed, EtlService.tokens(this).hasToken())) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        ready = true
        // 수식 글꼴을 처음 읽는 비용을 화면 밖에서 미리 치른다(첫 수식 답에서 화면이 멈칫하지 않게)
        Thread({ runCatching { MathRender.warmUp() } }, "math-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        // 받은 파일 자동 정리(하루 한 번, 기간을 고른 경우만)
        Thread({ kr.snu.etlassistant.files.DownloadCleanup.runIfDue(applicationContext) }, "download-cleanup").apply { isDaemon = true }.start()
        enableEdgeToEdge()
        val actions = ChatActions(
            onSend = { onSendClicked() },
            onSuggestion = { text -> send(text, clearInput = false) },
            onNewChat = {
                if (!CodexSession.newConversation()) toast("답변이 끝난 뒤에 새 대화를 시작할 수 있어요")
                refreshChats()
            },
            onSettings = { openSettings() },
            onMyDeadlines = { openMyDeadlines() },
            onTimetable = { startActivity(Intent(this, TimetableActivity::class.java)) },
            onHistory = { startActivity(Intent(this, NotifyHistoryActivity::class.java)) },
            onBanner = { action ->
                when (action) {
                    BannerAction.SETTINGS -> openSettings()
                    BannerAction.RETRY -> CodexSession.restart()
                    BannerAction.NOTIFICATIONS -> fixNotifications()
                    BannerAction.NONE -> {}
                }
            },
            onDismissBanner = {
                uiPrefs().edit().putLong(KEY_NOTIFY_BANNER_DISMISSED, System.currentTimeMillis()).apply()
                updateBanner()
            },
            onCopy = { text -> copy(text) },
            onLink = { url -> openLink(url) },
            onRefreshModels = { CodexSession.refreshModels() },
            onModelChoice = { choice ->
                vm.choice = choice
                CodexSession.setModelChoice(choice)
            },
            onAttach = { source -> attach(source) },
            onDrawerOpened = { refreshChats() },
            onOpenChat = { id ->
                if (!CodexSession.openConversation(id)) toast("답변이 끝난 뒤에 다른 대화로 옮길 수 있어요")
                refreshChats()
                vm.scrollToBottom()
            },
            onRenameChat = { id, title ->
                CodexSession.renameConversation(id, title)
                refreshChats()
            },
            onDrawerBusy = { toast("답변이 끝난 뒤에 바꾸거나 지울 수 있어요") },
            onDeleteChat = { id ->
                if (!CodexSession.deleteConversation(id)) toast("답변이 끝난 뒤에 지울 수 있어요")
                refreshChats()
            },
            attachments = AttachmentActions(onOpen = { openAttachment(it) }, onShare = { shareAttachment(it) },
                onRedownload = { vm.setInput(AttachmentLogic.redownloadPrompt(it.name, kr.snu.etlassistant.files.GeneratedFilesLogic.isGeneratedPath(it.shownPath))) },
                onSaveCopy = { saveAttachmentCopy(it) }, saveCopyLabel = kr.snu.etlassistant.files.SaveCopy.label(this)),
        )
        setContent { ChatRoot(vm, actions) }
        applyPrefill(intent)
        shareKey = intent?.getStringExtra(EXTRA_SHARE_KEY)?.also { intent.removeExtra(EXTRA_SHARE_KEY) }
        // 새 버전 확인: 하루 한 번 워커 예약 + 마지막 확인 후 6시간이 지났으면 지금 뒤에서 한 번.
        UpdateScheduler.apply(this)
        UpdateChecker.maybeCheckOnStart(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("askedNotif", false).not()
        ) {
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ready) applyPrefill(intent)
        intent.getStringExtra(EXTRA_SHARE_KEY)?.let { key ->
            intent.removeExtra(EXTRA_SHARE_KEY)
            shareKey = key
            if (ready && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) applyShare()
        }
    }

    /** 공유로 받은 내용(이미 작업 폴더로 복사된 첨부와 글)을 새 대화의 입력창에 채운다. 보내지는 않는다. */
    private var shareKey: String? = null

    private fun applyShare() {
        val s = ShareInbox.take(shareKey.also { shareKey = null }) ?: return
        if (CodexSession.newConversation()) refreshChats() else toast("답변 중이라 지금 대화의 입력창에 넣었어요")
        s.failures.distinct().forEach { toast(it) }
        val room = (AttachmentLogic.MAX_PENDING - vm.pending.size - vm.importing).coerceAtLeast(0)
        vm.pending += s.attachments.take(room)
        if (s.dropped > 0 || s.attachments.size > room) toast("한 번에 ${AttachmentLogic.MAX_PENDING}개까지 첨부할 수 있어요")
        s.text?.let { vm.setInput(it) }
        vm.scrollToBottom()
    }

    /** 첫 실행 안내에서 고른 추천 질문을 입력창에 채워 둔다(자동으로 보내지는 않는다). */
    private fun applyPrefill(i: Intent?) {
        val text = i?.getStringExtra(EXTRA_PREFILL) ?: return
        i.removeExtra(EXTRA_PREFILL)
        vm.setInput(text)
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        CodexSession.model.listener = this
        vm.syncFromSession()
        vm.bump()
        vm.scrollToBottom()
        CodexSession.attach(this)
        CodexSession.ensureStarted()
        updateBanner()
        if (shareKey != null) applyShare()
        loadPendingNotices()
    }

    /**
     * 등록 안 한 공지(받아 둔 것만, 네트워크 없음). 새 공지가 있을 때만 시트를 자동으로 연다.
     * 채팅을 열 때 뜨는 창은 한 실행에 하나: 업데이트 팝업 > 받은 파일 정리 선택 > 이 시트(나머지는 다음 기회로).
     */
    private fun loadPendingNotices() {
        Thread {
            val store = kr.snu.etlassistant.notify.PendingNoticeStore(this)
            val list = runCatching { store.targets(this) }.getOrDefault(emptyList())
            val otherPrompt = kr.snu.etlassistant.update.updatePromptPending(this) || kr.snu.etlassistant.files.cleanupPromptPending(this)
            val auto = kr.snu.etlassistant.notify.PendingNoticesLogic.shouldAutoShow(list, store.seen(),
                kr.snu.etlassistant.notify.NotifySettingsStore(this).load().pendingSheet, otherPrompt)
            if (auto) store.markSeen(list.map { it.id })
            runOnUiThread {
                vm.pendingNotices = list
                if (auto) vm.pendingSheet = true
                if (list.isEmpty()) vm.pendingSheet = false
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // 알림 설정이나 권한 창에서 돌아오면 바로 다시 판정한다(켜면 배너가 사라진다).
        if (ready) updateBanner()
    }

    override fun onStop() {
        if (ready) {
            main.removeCallbacks(flush)
            flushPosted = false
            CodexSession.detach(this, isChangingConfigurations)
            CodexSession.model.listener = null
        }
        super.onStop()
    }

    // ---- 보내기 --------------------------------------------------------------

    private fun onSendClicked() {
        if (CodexSession.busy) {
            CodexSession.interrupt()
            return
        }
        val text = vm.input.text.trim()
        if (vm.importing > 0) return
        if (text.isEmpty() && vm.pending.isEmpty()) return
        send(text, clearInput = true, attachments = vm.pending.toList())
    }

    /** 문제가 있으면(예: 아직 준비 중) 이유를 토스트로 알리고 입력과 첨부는 그대로 둔다. */
    private fun send(text: String, clearInput: Boolean, attachments: List<Attachment> = emptyList()) {
        val problem = CodexSession.send(text, attachments)
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_SHORT).show()
            return
        }
        if (clearInput) vm.setInput("")
        if (attachments.isNotEmpty()) vm.pending.removeAll(attachments)
    }

    // ---- 첨부 ----------------------------------------------------------------

    private fun attach(source: AttachSource) {
        if (vm.pending.size + vm.importing >= AttachmentLogic.MAX_PENDING) {
            toast("한 번에 ${AttachmentLogic.MAX_PENDING}개까지 첨부할 수 있어요")
            return
        }
        try {
            when (source) {
                AttachSource.PHOTO -> pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                AttachSource.FILE -> pickFiles.launch(arrayOf("*/*"))
                AttachSource.CAMERA -> {
                    val dir = File(cacheDir, "camera").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // 지난번에 남은 임시 사진
                    val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                    cameraFile = f
                    takePicture.launch(providerUri(f))
                }
            }
        } catch (_: ActivityNotFoundException) {
            toast(if (source == AttachSource.CAMERA) "카메라 앱을 찾지 못했어요" else "파일을 고를 수 있는 앱이 없어요")
        }
    }

    /** 고른 항목을 작업 폴더로 복사해 보내기 대기 목록에 넣는다. 남은 자리보다 많이 고르면 앞에서부터. */
    private fun importAll(uris: List<Uri>, cleanup: File? = null) {
        val room = AttachmentLogic.MAX_PENDING - vm.pending.size - vm.importing
        val take = uris.take(room.coerceAtLeast(0))
        if (uris.size > take.size) toast("한 번에 ${AttachmentLogic.MAX_PENDING}개까지 첨부할 수 있어요")
        if (take.isEmpty()) {
            cleanup?.delete()
            return
        }
        vm.importing += take.size
        importer.execute {
            for (uri in take) {
                val result = runCatching { AttachmentImporter.import(this, uri) }
                main.post {
                    vm.importing -= 1
                    result.onSuccess { vm.pending += it }
                    result.onFailure { e ->
                        toast((e as? AttachmentImporter.ImportException)?.message ?: "첨부하지 못했어요")
                    }
                }
            }
            cleanup?.delete()
        }
    }

    private fun providerUri(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    /**
     * 다른 앱에 넘길 주소. 폰 Download/etl/ 사본이 남아 있으면 그것(뷰어에 원래 파일 이름이 보인다),
     * 사용자가 지웠거나 없으면 앱 작업 폴더의 파일(`{파일ID}-이름`)을 쓴다.
     */
    private fun shareableUri(a: Attachment): Uri? {
        a.uri?.let { Uri.parse(it) }?.takeIf { u ->
            runCatching { contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false }.getOrDefault(false)
        }?.let { return it }
        a.path?.let { File(it) }?.takeIf { it.isFile }?.let { f ->
            return runCatching { providerUri(f) }.getOrNull()
        }
        return null
    }

    /** 폰에 깔린 기본 앱(PDF 뷰어, 갤러리, 오피스 등)으로 연다. */
    private fun openAttachment(a: Attachment) {
        val uri = shareableUri(a) ?: return toast("파일이 정리되어 더는 없어요")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, a.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("이 파일을 열 수 있는 앱이 없어요. 길게 눌러 다른 앱으로 보낼 수 있어요")
        } catch (_: SecurityException) {
            toast("파일을 열 수 없어요")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSave?.let { outState.putString(STATE_PENDING_SAVE, it.toJson().toString()) }
    }

    /** 시스템 문서 선택기를 연다(Drive가 있으면 그 안의 위치도 고를 수 있다). 지난번 저장한 위치에서 시작한다. */
    private fun saveAttachmentCopy(a: Attachment) {
        pendingSave = a
        try {
            createDocument.launch(kr.snu.etlassistant.files.CreateDocumentContract.Input(
                kr.snu.etlassistant.files.SaveCopyLogic.fileName(a.name),
                kr.snu.etlassistant.files.SaveCopyLogic.mimeFor(a.name, a.mime),
                kr.snu.etlassistant.files.SaveCopy.initialUri(this),
            ))
        } catch (_: ActivityNotFoundException) {
            pendingSave = null
            toast("파일을 저장할 수 있는 앱이 없어요")
        }
    }

    /** 고른 곳에 원본 바이트를 그대로 쓴다(뒤에서). 원본이 정리돼 없으면 만들어진 빈 문서를 지우고 다시 받기를 안내한다. */
    private fun saveCopy(a: Attachment, dest: Uri) {
        if (a.size >= kr.snu.etlassistant.files.SaveCopyLogic.BIG_BYTES) toast("저장하는 중이에요…")
        val app = applicationContext
        saver.execute {
            val result = runCatching { kr.snu.etlassistant.files.SaveCopy.copy(app, a.path, a.uri, dest) }
            val msg = result.fold(
                onSuccess = {
                    kr.snu.etlassistant.files.SaveCopy.rememberLocation(app, dest)
                    "저장했어요"
                },
                onFailure = { e ->
                    runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, dest) }
                    if (e is java.io.FileNotFoundException) "원본 파일이 정리돼 저장하지 못했어요. 비서에게 다시 받아 달라고 해 주세요"
                    else "저장하지 못했어요(${e.javaClass.simpleName})"
                },
            )
            main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun shareAttachment(a: Attachment) {
        val uri = shareableUri(a) ?: return toast("파일이 정리되어 더는 없어요")
        val send = Intent(Intent.ACTION_SEND)
            .setType(a.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(a.name, uri)
        try {
            startActivity(Intent.createChooser(send, a.name))
        } catch (_: ActivityNotFoundException) {
            toast("공유할 수 있는 앱이 없어요")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun openMyDeadlines() = startActivity(Intent(this, MyDeadlinesActivity::class.java))

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show()
    }

    /** 답변 속 링크(http/https만 남겨 둔 것)를 브라우저로 연다. */
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "링크를 열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- 세션 이벤트 -----------------------------------------------------------

    override fun onEvent(e: SessionEvent) {
        when (e) {
            is SessionEvent.Busy -> vm.busy = e.busy
            is SessionEvent.Activity -> vm.activity = e.label
            is SessionEvent.Models -> {
                vm.models = e.models
                vm.modelsLoading = e.loading
                vm.choice = CodexSession.modelChoice
            }
            is SessionEvent.LoginDone -> if (e.success) Toast.makeText(this, "로그인했어요", Toast.LENGTH_SHORT).show()
            else -> {}
        }
        updateBanner()
    }

    /** 지금 상태에 맞는 안내를 위쪽 배너로 보여 준다. 누르면 해당 화면으로 가거나 다시 시도한다. */
    private fun updateBanner() {
        vm.banner = ChatLogic.banner(
            hasToken = EtlService.tokens(this).hasToken(),
            state = CodexSession.state,
            detail = CodexSession.stateDetail,
            loggedIn = CodexSession.account?.loggedIn == true,
            notifyProblem = notifyProblem(),
            notifyDismissedAt = uiPrefs().getLong(KEY_NOTIFY_BANNER_DISMISSED, 0L),
            nowMs = System.currentTimeMillis(),
        )
    }

    private fun uiPrefs() = getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** 대화 목록과 지금 대화를 화면 상태로 옮긴다. */
    private fun refreshChats() {
        vm.chatList = CodexSession.chats.list()
        vm.currentChatId = CodexSession.chatId
    }

    /** 켜 둔 마감·공지·브리핑 알림이 실제로 올 수 있는지(권한, 앱 전체 알림, 그 알림의 채널). */
    private fun notifyProblem(): NotifyProblem? {
        val s = NotifySettingsStore(this).load()
        val used = buildList {
            if (s.deadlineAlerts) add(Channel.DEADLINE.id)
            if (s.announcementAlerts) add(Channel.ANNOUNCEMENT.id)
            if (s.briefEnabled) add(Channel.BRIEF.id)
        }
        val nm = NotificationManagerCompat.from(this)
        val permitted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val blocked = used.filter { id -> nm.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE }.toSet()
        return ChatLogic.notifyProblem(permitted && nm.areNotificationsEnabled(), used, blocked, batteryOptimized = !BatteryOpt.isIgnoring(this), powerSave = BatteryOpt.isPowerSaveMode(this))
    }

    /** 배너를 눌렀을 때: 권한을 물을 수 있으면 묻고, 아니면 앱 알림 설정(채널만 꺼졌으면 그 채널 설정)으로 보낸다. */
    private fun fixNotifications() {
        val problem = notifyProblem() ?: return updateBanner()
        if (problem == NotifyProblem.BatteryOptimized || problem == NotifyProblem.PowerSave) {
            BatteryOpt.request(this)
            Toast.makeText(this, BatteryOpt.SAMSUNG_HINT, Toast.LENGTH_LONG).show()
            return
        }
        val needPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needPermission && (!uiPrefs().getBoolean("askedNotif", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            uiPrefs().edit().putBoolean("askedNotif", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val intent = if (problem is NotifyProblem.ChannelOff && !needPermission) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, problem.channelId)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ---- 대화 변경 -------------------------------------------------------------

    override fun onAdded(index: Int) {
        vm.bump()
        vm.scrollToBottom()
    }

    override fun onChanged(index: Int) {
        if (!flushPosted) {
            flushPosted = true
            main.postDelayed(flush, 70)
        }
    }

    override fun onReset() {
        vm.bump()
        vm.scrollToBottom()
    }

    companion object {
        const val EXTRA_PREFILL = "prefill"
        /** "공유 → 영민이"로 받은 내용의 [ShareInbox] 키(내용 자체는 메모리에만 있다). */
        const val EXTRA_SHARE_KEY = "shareKey"
        private const val STATE_PENDING_SAVE = "pendingSave"
        private const val KEY_NOTIFY_BANNER_DISMISSED = "notifyBannerDismissedAt"
    }
}
