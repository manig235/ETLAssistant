package kr.snu.etlassistant.calendar

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.wanted.android.wanted.design.actions.button.WantedButton
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.actions.chip.WantedChip
import com.wanted.android.wanted.design.input.input.control.CheckBoxState
import com.wanted.android.wanted.design.input.input.control.CheckBoxStyle
import com.wanted.android.wanted.design.input.input.control.WantedCheckBox
import com.wanted.android.wanted.design.loading.loading.WantedCircularProgressIndicator
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import com.wanted.android.wanted.design.util.ButtonSize
import com.wanted.android.wanted.design.util.ButtonType
import com.wanted.android.wanted.design.util.ButtonVariant
import kr.snu.etlassistant.chat.MyDeadlinesActivity
import java.io.File
import java.time.Instant

/**
 * 설정 → 캘린더 연동(2026-11-24, 처음 이름은 "캘린더로 내보내기").
 * - 영민이 → 캘린더: 과제·내 마감(시험 포함)을 폰 캘린더에 자동으로 넣고, 캘린더에서 고친 내 마감은 되가져온다.
 * - 캘린더 → 영민이: 고른 캘린더의 일정을 내 일정·브리핑에 보이고(B), 비서가 그 캘린더를 읽고 일정을 넣게 한다(C).
 * - 고급(접힘): 일정 파일 공유·저장(네이버 캘린더용 .ics)과 자세한 동작 안내. "ics" 같은 말은 여기에만 쓴다.
 * 무거운 일(ContentResolver)은 백그라운드 스레드에서 한다.
 */
class CalendarExportActivity : AppCompatActivity() {
    private var settings by mutableStateOf(CalendarExportSettings())
    private var read by mutableStateOf(CalendarReadSettings())
    private var status by mutableStateOf<String?>(null)
    private var note by mutableStateOf<String?>(null)
    private var permitted by mutableStateOf(false)
    private var canRead by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    /** 구글 캘린더 고르기 창(null이면 닫힘). */
    private var googlePicker by mutableStateOf<List<CalendarExport.CalendarInfo>?>(null)
    /** 읽을 캘린더 고르기 창. */
    private var readPicker by mutableStateOf<List<CalendarReader.Info>?>(null)
    private var allCalendars by mutableStateOf<List<CalendarReader.Info>>(emptyList())
    private var changes by mutableStateOf<List<CalendarToolLogic.Change>>(emptyList())

    /** 권한을 받은 뒤 이어서 할 일. */
    private var afterPermission: (() -> Unit)? = null
    private var asked: Array<String> = CalendarExport.PERMISSIONS

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        reload()
        val next = afterPermission
        afterPermission = null
        if (result.values.all { it } && asked.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) next?.invoke()
        else if (asked.none { shouldShowRequestPermissionRationale(it) }) {
            // 다시 묻지 않음 상태: 앱 정보 화면으로 보낸다.
            toast("설정 → 권한에서 캘린더를 허용해 주세요")
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        } else toast("캘린더 권한이 없어 켤 수 없어요")
    }

    private val saveIcs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri == null) return@registerForActivityResult
        background({
            val text = CalendarExport.icsContent(this)
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("열 수 없음")
            "저장했어요"
        }) { toast(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CalendarLinkScreen(
                s = settings, read = read, status = status, note = note, permitted = permitted, canRead = canRead, busy = busy,
                calendars = allCalendars, changes = changes,
                onBack = { finish() },
                onEnable = ::setEnabled,
                onTarget = ::chooseTarget,
                onPickGoogle = { withPermission(CalendarExport.PERMISSIONS) { loadGoogleCalendars() } },
                onOptions = { next ->
                    CalendarExport.saveSettings(this, next)
                    reload()
                    CalendarExport.requestSync(this)
                },
                onSyncNow = { withPermission(CalendarExport.PERMISSIONS) { background({ CalendarExport.syncIfEnabled(this) ?: "꺼져 있어요" }) { toast(it) } } },
                onShareIcs = ::shareIcs,
                onSaveIcs = { saveIcs.launch(CalendarLogic.icsFileName(Instant.now())) },
                onShow = ::setShow,
                onAssistant = ::setAssistant,
                onPickRead = { withPermission(arrayOf(Manifest.permission.READ_CALENDAR)) { loadReadCalendars() } },
                onWriteCalendar = { cal -> saveRead(read.copy(writeCalendarId = cal.id, writeCalendarName = cal.name)) },
                onUndo = { c -> withPermission(CalendarExport.PERMISSIONS) { background({ CalendarUndo.undo(this, c.id) }) { toast(it) } } },
                onAccountSettings = ::openAccountSettings,
            )
            googlePicker?.let { list ->
                GooglePicker(list, onChoose = ::chooseGoogle, onDismiss = { googlePicker = null; pendingEnable = false },
                    onAccountSettings = ::openAccountSettings, onRetry = { loadGoogleCalendars(pendingEnable) })
            }
            readPicker?.let { list ->
                ReadPicker(list, read.calendarIds, onSave = { ids ->
                    readPicker = null
                    // 스위치를 켜려다 고르기로 왔으면 이어서 켠다. 하나도 고르지 않으면 둘 다 끈다.
                    var next = read.copy(calendarIds = ids).fixWrite(list)
                    next = if (ids.isEmpty()) next.copy(showEnabled = false, assistantEnabled = false)
                    else next.copy(showEnabled = next.showEnabled || pendingShow, assistantEnabled = next.assistantEnabled || pendingAssistant)
                    pendingShow = false
                    pendingAssistant = false
                    saveRead(next)
                },
                    onDismiss = { readPicker = null }, onAccountSettings = ::openAccountSettings, onRetry = ::loadReadCalendars)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    private fun reload() {
        settings = CalendarExport.settings(this)
        read = CalendarReader.settings(this)
        status = CalendarExport.lastResult(this)
        note = CalendarExport.note(this)
        permitted = CalendarExport.hasPermission(this)
        canRead = CalendarReader.canRead(this)
        changes = CalendarUndo.list(this)
        if (canRead) Thread {
            val list = CalendarReader.calendars(this)
            runOnUiThread { allCalendars = list }
        }.start()
    }

    private fun withPermission(perms: Array<String>, block: () -> Unit) {
        if (perms.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) block()
        else {
            afterPermission = block
            asked = perms
            askPermission.launch(perms)
        }
    }

    // ---- 영민이 → 캘린더 ------------------------------------------------------------

    private fun setEnabled(on: Boolean) {
        if (!on) {
            background({ CalendarExport.disable(this) ?: "껐어요. 넣은 일정을 지웠어요" }) { toast(it) }
            return
        }
        withPermission(CalendarExport.PERMISSIONS) {
            val s = CalendarExport.settings(this)
            if (s.target == CalendarTarget.GOOGLE && s.googleCalendarId == null) loadGoogleCalendars(enableAfter = true)
            else background({ CalendarExport.enable(this, s) ?: "켰어요" }) { toast(it) }
        }
    }

    private fun chooseTarget(t: CalendarTarget) {
        val s = CalendarExport.settings(this)
        if (s.target == t) return
        val next = s.copy(target = t)
        if (t == CalendarTarget.GOOGLE && next.googleCalendarId == null) {
            // 켜져 있으면 캘린더를 고른 뒤에 옮긴다. 꺼져 있으면 고르는 것만.
            withPermission(CalendarExport.PERMISSIONS) { loadGoogleCalendars() }
            return
        }
        if (!s.enabled) { background({ CalendarExport.switchTarget(this, next) }) { it?.let(::toast) }; return }
        withPermission(CalendarExport.PERMISSIONS) { background({ CalendarExport.switchTarget(this, next.copy(enabled = true)) ?: "옮겼어요" }) { toast(it) } }
    }

    private fun chooseGoogle(c: CalendarExport.CalendarInfo) {
        googlePicker = null
        val s = CalendarExport.settings(this)
        val turnOn = s.enabled || pendingEnable
        pendingEnable = false
        if (s.enabled && s.target == CalendarTarget.GOOGLE && s.googleCalendarId == c.id) return
        val next = s.copy(target = CalendarTarget.GOOGLE, googleCalendarId = c.id, googleCalendarName = c.name)
        if (!turnOn) { background({ CalendarExport.switchTarget(this, next) }) { it?.let(::toast) }; return }
        background({ CalendarExport.switchTarget(this, next.copy(enabled = true)) ?: "\"${c.name}\" 캘린더에 넣어요" }) { toast(it) }
    }

    /** 스위치를 켜려다 캘린더 고르기로 온 경우: 고르면 바로 켠다. */
    private var pendingEnable = false

    private fun loadGoogleCalendars(enableAfter: Boolean = false) {
        pendingEnable = enableAfter
        background({
            val list = CalendarExport.googleCalendars(contentResolver)
            runOnUiThread { googlePicker = list }
            null
        }) { it?.let(::toast) }
    }

    /** 폰의 계정 동기화 설정(구글 캘린더 동기화를 켜는 곳). 안 되면 계정 추가, 그것도 안 되면 설정 첫 화면. */
    private fun openAccountSettings() {
        val tries = listOf(Intent(Settings.ACTION_SYNC_SETTINGS), Intent(Settings.ACTION_ADD_ACCOUNT), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) if (runCatching { startActivity(i) }.isSuccess) return
        toast("설정을 열 수 없어요")
    }

    // ---- 캘린더 → 영민이 ------------------------------------------------------------

    private fun saveRead(next: CalendarReadSettings) {
        CalendarReader.saveSettings(this, next)
        reload()
    }

    /** 쓸 캘린더가 선택에서 빠졌으면 비우고, 쓸 수 있는 것이 하나뿐이면 그것으로. */
    private fun CalendarReadSettings.fixWrite(list: List<CalendarReader.Info>): CalendarReadSettings {
        val writable = list.filter { it.id in calendarIds && it.writable }
        return when {
            writeCalendarId != null && writable.any { it.id == writeCalendarId } -> this
            writable.size == 1 -> copy(writeCalendarId = writable[0].id, writeCalendarName = writable[0].name)
            else -> copy(writeCalendarId = null, writeCalendarName = "")
        }
    }

    private fun setShow(on: Boolean) {
        if (!on) { saveRead(read.copy(showEnabled = false)); return }
        withPermission(arrayOf(Manifest.permission.READ_CALENDAR)) {
            val cur = CalendarReader.settings(this)
            if (cur.calendarIds.isEmpty()) { pendingShow = true; loadReadCalendars() }
            else saveRead(cur.copy(showEnabled = true))
        }
    }

    private fun setAssistant(on: Boolean) {
        if (!on) { saveRead(read.copy(assistantEnabled = false)); return }
        withPermission(CalendarExport.PERMISSIONS) {
            val cur = CalendarReader.settings(this)
            if (cur.calendarIds.isEmpty()) { pendingAssistant = true; loadReadCalendars() }
            else saveRead(cur.copy(assistantEnabled = true))
        }
    }

    private var pendingShow = false
    private var pendingAssistant = false

    private fun loadReadCalendars() {
        background({
            val list = CalendarReader.calendars(this)
            runOnUiThread { readPicker = list }
            null
        }) { it?.let(::toast) }
    }

    private fun shareIcs() {
        background({
            val dir = File(cacheDir, "ics").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, CalendarLogic.icsFileName(Instant.now()))
            f.writeText(CalendarExport.icsContent(this), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/calendar"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread { runCatching { startActivity(Intent.createChooser(send, "일정 파일 보내기")) }.onFailure { toast("보낼 앱이 없어요") } }
            null
        }) { it?.let(::toast) }
    }

    /** [work]를 백그라운드에서 돌리고 결과 문구를 UI 스레드에서 [done]으로 넘긴다(그 뒤 화면을 다시 읽는다). */
    private fun background(work: () -> String?, done: (String?) -> Unit) {
        busy = true
        Thread {
            val msg = try {
                work()
            } catch (_: SecurityException) {
                "캘린더 권한이 필요해요"
            } catch (e: Exception) {
                "하지 못했어요(${e.javaClass.simpleName})"
            }
            runOnUiThread {
                busy = false
                reload()
                done(msg)
            }
        }.start()
    }

    private fun toast(text: String?) {
        if (!text.isNullOrBlank()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun ReadPicker(
        list: List<CalendarReader.Info>,
        selected: Set<Long>,
        onSave: (Set<Long>) -> Unit,
        onDismiss: () -> Unit,
        onAccountSettings: () -> Unit,
        onRetry: () -> Unit,
    ) {
        var picked by remember { mutableStateOf(selected.filter { id -> list.any { it.id == id } }.toSet()) }
        DesignSystemTheme {
            val c = DesignSystemTheme.colors
            val t = DesignSystemTheme.typography
            AlertDialog(
                onDismissRequest = { onDismiss(); pendingShow = false; pendingAssistant = false },
                containerColor = c.backgroundNeutralPrimary,
                title = { Text("볼 캘린더 고르기", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (list.isEmpty()) NoCalendarsHint(onAccountSettings, onRetry)
                        else Hint("영민이가 넣은 \"영민이\" 캘린더는 목록에 없어요(내 일정에 이미 보여요).")
                        for (cal in list) {
                            val on = cal.id in picked
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { picked = if (on) picked - cal.id else picked + cal.id }
                                    .semantics(mergeDescendants = true) { contentDescription = "${cal.name}, ${cal.account}" + (if (on) ", 선택됨" else "") }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(cal.color or 0xFF000000.toInt())))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(cal.name, style = t.body1Medium, color = c.foregroundNeutralPrimary)
                                    Text(cal.account + (if (!cal.writable) " · 읽기 전용" else ""), style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                                }
                                WantedCheckBox(onCheckedChange = { picked = if (it) picked + cal.id else picked - cal.id }, style = CheckBoxStyle.CheckBox,
                                    checkState = if (on) CheckBoxState.Checked else CheckBoxState.Unchecked)
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextBtn("취소") { onDismiss(); pendingShow = false; pendingAssistant = false }
                        if (list.isNotEmpty()) TextBtn("저장") { onSave(picked) }
                    }
                },
            )
        }
    }
}

/**
 * 캘린더 앱의 "앱에서 열기"(CalendarContract.ACTION_HANDLE_CUSTOM_EVENT)를 받는 곳. 내 일정 화면을 연다.
 * 문서대로 data는 일정 URI이고 우리 URI는 EXTRA_CUSTOM_APP_URI에 온다(캘린더 앱에 따라 data에 우리 URI를 넣기도 한다).
 * 다른 앱이 보낸 값은 내 마감 ID(숫자) 하나만 읽고, 그 밖의 것은 쓰지 않는다.
 */
class CalendarOpenActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = runCatching {
            intent.getStringExtra(android.provider.CalendarContract.EXTRA_CUSTOM_APP_URI)
                ?: intent.dataString?.takeIf { it.startsWith(CalendarLogic.APP_URI_PREFIX) }
                ?: intent.data?.takeIf { it.authority == android.provider.CalendarContract.AUTHORITY }?.lastPathSegment?.toLongOrNull()?.let(::customUriOf)
        }.getOrNull()
        val open = Intent(this, MyDeadlinesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        CalendarLogic.mineIdFromUri(uri)?.let { open.putExtra(kr.snu.etlassistant.widget.HomeWidgets.EXTRA_DEADLINE_ID, it) }
        runCatching { startActivity(open) }
        finish()
    }

    /** 일정 URI만 왔을 때 그 일정의 CUSTOM_APP_URI(권한이 있을 때만). */
    private fun customUriOf(eventId: Long): String? = runCatching {
        contentResolver.query(android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(android.provider.CalendarContract.Events.CUSTOM_APP_URI), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarLinkScreen(
    s: CalendarExportSettings,
    read: CalendarReadSettings,
    status: String?,
    note: String?,
    permitted: Boolean,
    canRead: Boolean,
    busy: Boolean,
    calendars: List<CalendarReader.Info>,
    changes: List<CalendarToolLogic.Change>,
    onBack: () -> Unit,
    onEnable: (Boolean) -> Unit,
    onTarget: (CalendarTarget) -> Unit,
    onPickGoogle: () -> Unit,
    onOptions: (CalendarExportSettings) -> Unit,
    onSyncNow: () -> Unit,
    onShareIcs: () -> Unit,
    onSaveIcs: () -> Unit,
    onShow: (Boolean) -> Unit,
    onAssistant: (Boolean) -> Unit,
    onPickRead: () -> Unit,
    onWriteCalendar: (CalendarReader.Info) -> Unit,
    onUndo: (CalendarToolLogic.Change) -> Unit,
    onAccountSettings: () -> Unit,
) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        var advanced by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(c.backgroundNeutralPrimary).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = c.foregroundNeutralPrimary) }
                Text("캘린더 연동", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                if (busy) WantedCircularProgressIndicator()
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                // ---- 영민이 → 캘린더 ----
                SectionTitle("영민이 → 캘린더")
                Card {
                    SwitchLine("영민이 일정을 캘린더에 자동으로 넣기", "과제·내 마감이 바뀌면 캘린더도 따라 바뀌어요", s.enabled, enabled = !busy, onChange = onEnable)
                    note?.let { Spacer(Modifier.height(6.dp)); Text(it, style = t.caption1Regular, color = c.foregroundCautionaryPrimary) }
                    if (s.enabled && !permitted) {
                        Spacer(Modifier.height(6.dp))
                        Text("캘린더 권한이 꺼져 있어요", style = t.caption1Regular, color = c.foregroundNegativePrimary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("넣을 곳", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                    Spacer(Modifier.height(8.dp))
                    TargetOption("이 폰에만", "삼성 캘린더에서 보여요(구글 캘린더 앱·웹에는 안 보일 수 있어요)", s.target == CalendarTarget.LOCAL) { if (!busy) onTarget(CalendarTarget.LOCAL) }
                    TargetOption("구글 계정 캘린더", "삼성 캘린더와 구글 캘린더(앱·웹) 모두에서 보여요", s.target == CalendarTarget.GOOGLE) { if (!busy) onTarget(CalendarTarget.GOOGLE) }
                    Spacer(Modifier.height(4.dp))
                    Hint("두 곳에 함께 넣지는 않아요(삼성 캘린더에 같은 일정이 두 번 보이지 않게). 삼성 캘린더에서 안 보이면 삼성 캘린더 → 캘린더 관리에서 켜 주세요.")
                    if (s.target == CalendarTarget.LOCAL) {
                        Spacer(Modifier.height(4.dp))
                        Hint("이 폰에 \"영민이\" 캘린더를 만들어 넣어요. 서버로 올라가지 않아요.")
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Hint("고른 구글 캘린더는 구글 계정과 동기화돼요(일정 제목과 과목 이름이 구글 서버로 가요). 구글 캘린더(웹)에서 \"영민이\" 같은 새 캘린더를 먼저 만들어 고르면 다른 일정과 섞이지 않아요.")
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (s.googleCalendarId == null) "아직 고르지 않았어요" else "\"${s.googleCalendarName}\"",
                                style = t.body2Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
                            SoftButton("캘린더 고르기", enabled = !busy, onClick = onPickGoogle)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card {
                    Text("넣을 일정", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                    SwitchLine("eTL 과제 마감", "알림 확인 때 받아 둔 과제(숨긴 과목 제외)", s.includeEtl) { onOptions(s.copy(includeEtl = it)) }
                    SwitchLine("내 마감·시험", "시험은 시작~끝 시간과 장소로 들어가요", s.includeMine) { onOptions(s.copy(includeMine = it)) }
                    SwitchLine("제출·완료한 항목도 넣기", "끄면 제출하거나 완료한 항목은 캘린더에서 지워요", s.includeDone) { onOptions(s.copy(includeDone = it)) }
                    SwitchLine("캘린더에서 고친 것 가져오기", "캘린더 앱에서 내 마감 일정을 옮기거나 이름을 바꾸면 앱에도 반영해요. eTL 과제 일정은 원래대로 돌아가요", s.pullBack) { onOptions(s.copy(pullBack = it)) }
                    Spacer(Modifier.height(8.dp))
                    status?.let { Text(it, style = t.caption1Regular, color = c.foregroundNeutralSecondary); Spacer(Modifier.height(8.dp)) }
                    SoftButton("지금 맞추기", enabled = s.enabled && !busy, onClick = onSyncNow)
                    Spacer(Modifier.height(12.dp))
                    Hint("끄면 \"영민이\" 캘린더를 지우거나(이 폰에만) 앱이 넣은 일정만 지워요(구글). 앱을 그냥 지우면 넣은 일정을 정리할 수 없으니, 지우기 전에 먼저 꺼 주세요.")
                }

                // ---- 캘린더 → 영민이 ----
                SectionTitle("캘린더 → 영민이")
                Card {
                    SwitchLine("내 캘린더 일정 보기", "고른 캘린더의 일정을 내 일정(\"캘린더\")과 아침 브리핑(오늘 일정)에 보여 줘요", read.showEnabled, enabled = !busy, onChange = onShow)
                    SwitchLine("비서가 캘린더 보고 등록하기", "대화에서 부탁하면 비서가 고른 캘린더의 일정을 읽고, 일정을 넣거나 고치거나 지워요", read.assistantEnabled, enabled = !busy, onChange = onAssistant)
                    Hint("비서가 일정을 읽으면 그 내용(제목·시간·장소·메모)이 내 ChatGPT 계정으로 OpenAI에 전달돼요. 비서는 내가 부탁할 때만 쓰고, 바꿀 때마다 채팅에 한 줄로 알려요.")
                    if ((read.showEnabled || read.assistantEnabled) && !canRead) {
                        Spacer(Modifier.height(6.dp))
                        Text("캘린더 권한이 꺼져 있어요", style = t.caption1Regular, color = c.foregroundNegativePrimary)
                    }
                    Spacer(Modifier.height(10.dp))
                    val names = calendars.filter { it.id in read.calendarIds }.map { it.name }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(CalendarReadLogic.selectionLabel(names.ifEmpty { if (read.calendarIds.isNotEmpty() && !canRead) listOf("…") else emptyList() }),
                            style = t.body2Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
                        SoftButton("볼 캘린더 고르기", enabled = !busy, onClick = onPickRead)
                    }
                    if (read.assistantEnabled) {
                        val writable = calendars.filter { it.id in read.calendarIds && it.writable }
                        Spacer(Modifier.height(10.dp))
                        Text("비서가 새 일정을 넣을 캘린더", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                        Spacer(Modifier.height(6.dp))
                        if (writable.isEmpty()) Hint("고른 캘린더 중 일정을 넣을 수 있는 캘린더가 없어요(읽기 전용만 골랐어요).")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (cal in writable) WantedChip(text = cal.name, isActive = read.writeCalendarId == cal.id, onClick = { onWriteCalendar(cal) })
                        }
                    }
                }
                if (changes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Card {
                        Text("비서가 바꾼 일정", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                        Hint("최근 14일. 되돌리면 넣은 일정은 지우고, 고친 일정은 전 모양으로, 지운 일정은 다시 넣어요(알림·초대는 되살리지 않아요).")
                        for (ch in changes.take(10)) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${ch.action.label} · ${ch.summary}", style = t.body2Regular, color = if (ch.undone) c.foregroundNeutralTertiary else c.foregroundNeutralPrimary)
                                    Text(ch.calendarName + " · " + kr.snu.etlassistant.notify.NoticeText.dateLabel(Instant.ofEpochMilli(ch.at)) + (if (ch.undone) " · 되돌림" else ""),
                                        style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                                }
                                if (!ch.undone) TextBtn("되돌리기") { onUndo(ch) }
                            }
                        }
                    }
                }

                // ---- 고급 ----
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = if (advanced) "접기" else "펼치기") { advanced = !advanced }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("고급", style = t.headline2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
                    Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = c.foregroundNeutralSecondary)
                }
                if (advanced) {
                    Card {
                        Text("일정 파일(.ics)로 공유·저장", style = t.body1Medium, color = c.foregroundNeutralPrimary)
                        Spacer(Modifier.height(4.dp))
                        Hint("위에서 고른 일정을 .ics 파일 하나로 만들어요. 네이버 캘린더(웹의 일정 가져오기)나 삼성 캘린더 가져오기에 쓸 수 있어요. 만든 때의 내용이라 나중에 바뀐 것은 반영되지 않아요.")
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton("공유하기", enabled = !busy, onClick = onShareIcs)
                            SoftButton("파일로 저장", enabled = !busy, onClick = onSaveIcs)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Card {
                        Text("자세한 동작", style = t.body1Medium, color = c.foregroundNeutralPrimary)
                        Spacer(Modifier.height(4.dp))
                        Hint("• 마감은 마감 시각에 0분짜리 일정, 시험은 시작~끝 일정으로 들어가요. 영민이가 넣는 일정에는 캘린더 알림을 달지 않아요.")
                        Hint("• 반복 마감은 앞으로 60일의 회차를 하나씩 넣어요. 캘린더에서 반복 회차를 옮겨도 반복 전체는 바뀌지 않고 원래대로 돌아가요(반복 마감은 앱에서 고쳐 주세요).")
                        Hint("• 캘린더에서 지운 일정은 다시 넣지 않아요(내 마감은 지워지지 않아요). 마감이 14일 넘게 지난 일정은 그대로 둬요.")
                        Hint("• 캘린더가 바뀌면 몇 초~1분 안에 맞춰요. 같은 일정을 몇 번 되돌려도 캘린더가 다시 바꾸면 그 모양을 그대로 둬요.")
                        Hint("• 비서는 반복 일정과 다른 사람이 초대된 일정은 고치거나 지우지 않아요. 고른 캘린더 밖의 일정은 읽지 않아요.")
                        Hint("• 모두 폰 안에서 처리하고, 앱이 직접 인터넷으로 보내는 것은 없어요(구글 캘린더는 구글 계정 동기화가 올려요).")
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun GooglePicker(
    calendars: List<CalendarExport.CalendarInfo>,
    onChoose: (CalendarExport.CalendarInfo) -> Unit,
    onDismiss: () -> Unit,
    onAccountSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = c.backgroundNeutralPrimary,
            title = { Text("구글 캘린더 고르기", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (calendars.isEmpty()) NoCalendarsHint(onAccountSettings, onRetry)
                    for (cal in calendars) {
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onChoose(cal) }
                            .semantics(mergeDescendants = true) { contentDescription = "${cal.name}, ${cal.account}" }.padding(vertical = 10.dp)) {
                            Text(cal.name, style = t.body1Medium, color = c.foregroundNeutralPrimary)
                            Text(cal.account, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                        }
                    }
                }
            },
            confirmButton = { TextBtn("닫기", onDismiss) },
        )
    }
}

/** 쓸 수 있는 캘린더가 없을 때: 구글 계정 캘린더 동기화를 켜는 방법과 버튼. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoCalendarsHint(onAccountSettings: () -> Unit, onRetry: () -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Text("쓸 수 있는 캘린더가 없어요.", style = t.body2Medium, color = c.foregroundNeutralPrimary)
    Spacer(Modifier.height(4.dp))
    Text("폰 설정 → 계정 및 백업 → 계정 관리 → Google → 계정 동기화에서 '캘린더'를 켜 주세요. 켠 뒤 잠시 기다렸다가 다시 확인해 주세요.",
        style = t.body2Regular, color = c.foregroundNeutralSecondary)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftButton("계정 설정 열기", onClick = onAccountSettings)
        SoftButton("다시 확인", onClick = onRetry)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = DesignSystemTheme.typography.headline2Bold, color = DesignSystemTheme.colors.foregroundNeutralPrimary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

/** 넣을 곳 하나: 칩 + 어디에 보이는지 한 줄. */
@Composable
private fun TargetOption(title: String, desc: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        WantedChip(text = title, isActive = active, onClick = onClick,
            modifier = Modifier.semantics { contentDescription = "$title, $desc" + if (active) ", 선택됨" else "" })
        Spacer(Modifier.height(2.dp))
        Hint(desc)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DesignSystemTheme.colors.surfaceNeutralSecondary).padding(16.dp),
        content = content,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = DesignSystemTheme.typography.caption1Regular, color = DesignSystemTheme.colors.foregroundNeutralSecondary)
}

@Composable
private fun SwitchLine(title: String, desc: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = DesignSystemTheme.colors
    val t = DesignSystemTheme.typography
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body1Medium, color = c.foregroundNeutralPrimary)
            if (desc != null) {
                Spacer(Modifier.height(2.dp))
                Text(desc, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        WantedCheckBox(
            onCheckedChange = onChange,
            style = CheckBoxStyle.Switch,
            checkState = if (checked) CheckBoxState.Checked else CheckBoxState.Unchecked,
            enabled = enabled,
        )
    }
}

@Composable
private fun SoftButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    WantedButton(text = text, type = ButtonType.ASSISTIVE, size = ButtonSize.MEDIUM, variant = ButtonVariant.OUTLINED, enabled = enabled, onClick = onClick,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text; role = Role.Button })
}

/** 대화상자 글자 버튼(접근성 트리에 이름이 빠지지 않게). */
@Composable
private fun TextBtn(text: String, onClick: () -> Unit) {
    WantedTextButton(text = text, onClick = onClick, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text; role = Role.Button })
}
