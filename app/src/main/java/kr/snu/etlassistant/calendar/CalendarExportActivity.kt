package kr.snu.etlassistant.calendar

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 설정 → 캘린더로 내보내기. 대상(이 폰의 앱 캘린더 / 구글 계정 캘린더)과 보낼 항목을 고르고, .ics 파일로도 내보낸다.
 * 무거운 일(ContentResolver)은 백그라운드 스레드에서 한다.
 */
class CalendarExportActivity : AppCompatActivity() {
    private var settings by mutableStateOf(CalendarExportSettings())
    private var status by mutableStateOf<String?>(null)
    private var note by mutableStateOf<String?>(null)
    private var permitted by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var calendars by mutableStateOf<List<CalendarExport.CalendarInfo>?>(null)

    /** 권한을 받은 뒤 이어서 할 일. */
    private var afterPermission: (() -> Unit)? = null

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        reload()
        val next = afterPermission
        afterPermission = null
        if (result.values.all { it } && CalendarExport.hasPermission(this)) next?.invoke()
        else if (CalendarExport.PERMISSIONS.none { shouldShowRequestPermissionRationale(it) }) {
            // 다시 묻지 않음 상태: 앱 정보 화면으로 보낸다.
            toast("설정 → 권한에서 캘린더를 허용해 주세요")
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        } else toast("캘린더 권한이 없어 내보낼 수 없어요")
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
            CalendarExportScreen(
                s = settings,
                status = status,
                note = note,
                permitted = permitted,
                busy = busy,
                calendars = calendars,
                onBack = { finish() },
                onEnable = ::setEnabled,
                onTarget = ::chooseTarget,
                onPickGoogle = { withPermission { loadCalendars() } },
                onChoose = ::chooseGoogle,
                onDismissPicker = { calendars = null; pendingEnable = false },
                onOptions = { next ->
                    CalendarExport.saveSettings(this, next)
                    reload()
                    CalendarExport.requestSync(this)
                },
                onSyncNow = { withPermission { background({ CalendarExport.syncIfEnabled(this) ?: "꺼져 있어요" }) { toast(it) } } },
                onShareIcs = ::shareIcs,
                onSaveIcs = { saveIcs.launch(CalendarLogic.icsFileName(Instant.now())) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    private fun reload() {
        settings = CalendarExport.settings(this)
        status = CalendarExport.lastResult(this)
        note = CalendarExport.note(this)
        permitted = CalendarExport.hasPermission(this)
    }

    private fun withPermission(block: () -> Unit) {
        if (CalendarExport.hasPermission(this)) block()
        else {
            afterPermission = block
            askPermission.launch(CalendarExport.PERMISSIONS)
        }
    }

    private fun setEnabled(on: Boolean) {
        if (!on) {
            background({ CalendarExport.disable(this) ?: "껐어요. 넣은 일정을 지웠어요" }) { toast(it) }
            return
        }
        withPermission {
            val s = CalendarExport.settings(this)
            if (s.target == CalendarTarget.GOOGLE && s.googleCalendarId == null) loadCalendars(enableAfter = true)
            else background({ CalendarExport.enable(this, s) ?: "켰어요" }) { toast(it) }
        }
    }

    private fun chooseTarget(t: CalendarTarget) {
        val s = CalendarExport.settings(this)
        if (s.target == t) return
        val next = s.copy(target = t)
        if (t == CalendarTarget.GOOGLE && next.googleCalendarId == null) {
            // 켜져 있으면 캘린더를 고른 뒤에 옮긴다. 꺼져 있으면 고르는 것만.
            withPermission { loadCalendars() }
            return
        }
        if (!s.enabled) { CalendarExport.saveSettings(this, next); reload(); return }
        withPermission { background({ CalendarExport.switchTarget(this, next.copy(enabled = true)) ?: "옮겼어요" }) { toast(it) } }
    }

    private fun chooseGoogle(c: CalendarExport.CalendarInfo) {
        calendars = null
        val s = CalendarExport.settings(this)
        val turnOn = s.enabled || pendingEnable
        pendingEnable = false
        if (s.enabled && s.target == CalendarTarget.GOOGLE && s.googleCalendarId == c.id) return
        val next = s.copy(target = CalendarTarget.GOOGLE, googleCalendarId = c.id, googleCalendarName = c.name)
        if (!turnOn) { CalendarExport.saveSettings(this, next); reload(); return }
        background({ CalendarExport.switchTarget(this, next.copy(enabled = true)) ?: "\"${c.name}\" 캘린더로 내보내요" }) { toast(it) }
    }

    /** 스위치를 켜려다 캘린더 고르기로 온 경우: 고르면 바로 켠다. */
    private var pendingEnable = false

    private fun loadCalendars(enableAfter: Boolean = false) {
        pendingEnable = enableAfter
        background({
            val list = CalendarExport.googleCalendars(contentResolver)
            runOnUiThread { calendars = list }
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
            runOnUiThread { runCatching { startActivity(Intent.createChooser(send, ".ics 파일 보내기")) }.onFailure { toast("보낼 앱이 없어요") } }
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
}

/**
 * 캘린더 앱의 "앱에서 열기"(CalendarContract.ACTION_HANDLE_CUSTOM_EVENT)를 받는 곳. 내 일정 화면을 연다.
 * 다른 앱이 보낸 값은 내 마감 ID(숫자) 하나만 읽고, 그 밖의 것은 쓰지 않는다.
 */
class CalendarOpenActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = runCatching { intent.getStringExtra(android.provider.CalendarContract.EXTRA_CUSTOM_APP_URI) ?: intent.dataString }.getOrNull()
        val open = Intent(this, MyDeadlinesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        CalendarLogic.mineIdFromUri(uri)?.let { open.putExtra(kr.snu.etlassistant.widget.HomeWidgets.EXTRA_DEADLINE_ID, it) }
        runCatching { startActivity(open) }
        finish()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarExportScreen(
    s: CalendarExportSettings,
    status: String?,
    note: String?,
    permitted: Boolean,
    busy: Boolean,
    calendars: List<CalendarExport.CalendarInfo>?,
    onBack: () -> Unit,
    onEnable: (Boolean) -> Unit,
    onTarget: (CalendarTarget) -> Unit,
    onPickGoogle: () -> Unit,
    onChoose: (CalendarExport.CalendarInfo) -> Unit,
    onDismissPicker: () -> Unit,
    onOptions: (CalendarExportSettings) -> Unit,
    onSyncNow: () -> Unit,
    onShareIcs: () -> Unit,
    onSaveIcs: () -> Unit,
) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        Column(Modifier.fillMaxSize().background(c.backgroundNeutralPrimary).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = c.foregroundNeutralPrimary) }
                Text("캘린더로 내보내기", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                if (busy) WantedCircularProgressIndicator()
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                Card {
                    SwitchLine("캘린더로 내보내기", "eTL 과제 마감과 내 마감을 폰 캘린더에 일정으로 넣고, 바뀌면 고치거나 지워요", s.enabled, enabled = !busy, onChange = onEnable)
                    note?.let { Spacer(Modifier.height(6.dp)); Text(it, style = t.caption1Regular, color = c.foregroundCautionaryPrimary) }
                    if (s.enabled && !permitted) {
                        Spacer(Modifier.height(6.dp))
                        Text("캘린더 권한이 꺼져 있어요", style = t.caption1Regular, color = c.foregroundNegativePrimary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("넣을 곳", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WantedChip(text = "이 폰에만 (삼성 캘린더 등)", isActive = s.target == CalendarTarget.LOCAL, onClick = { if (!busy) onTarget(CalendarTarget.LOCAL) })
                        WantedChip(text = "구글 계정 캘린더", isActive = s.target == CalendarTarget.GOOGLE, onClick = { if (!busy) onTarget(CalendarTarget.GOOGLE) })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (s.target == CalendarTarget.LOCAL) {
                        Hint("앱이 이 폰에 \"영민이\" 캘린더를 만들어 넣어요. 서버로 올라가지 않아요. 삼성 캘린더에서는 보이지만, 구글 캘린더 앱에서는 안 보일 수 있어요.")
                    } else {
                        Hint("고른 구글 캘린더에 넣으면 구글 계정과 동기화돼서 다른 기기·웹에서도 보여요(일정 제목과 과목 이름이 구글 서버로 가요).")
                        Spacer(Modifier.height(4.dp))
                        Hint("앱은 구글 캘린더를 새로 만들 수 없어요. 먼저 구글 캘린더(웹)에서 \"영민이\" 같은 새 캘린더를 만들고, 폰에서 동기화된 뒤 여기서 고르면 다른 일정과 섞이지 않아요.")
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (s.googleCalendarId == null) "아직 고르지 않았어요" else "\"${s.googleCalendarName}\"",
                                style = t.body2Medium, color = c.foregroundNeutralPrimary, modifier = Modifier.weight(1f),
                            )
                            SoftButton("캘린더 고르기", enabled = !busy, onClick = onPickGoogle)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card {
                    Text("보낼 항목", style = t.body2Medium, color = c.foregroundNeutralPrimary)
                    SwitchLine("eTL 과제 마감", "알림 확인 때 받아 둔 과제(숨긴 과목 제외)", s.includeEtl) { onOptions(s.copy(includeEtl = it)) }
                    SwitchLine("내 마감", "반복 마감은 앞으로 60일의 회차를 하나씩 넣어요", s.includeMine) { onOptions(s.copy(includeMine = it)) }
                    SwitchLine("제출·완료한 항목도 보내기", "끄면 제출하거나 완료한 항목은 캘린더에서 지워요", s.includeDone) { onOptions(s.copy(includeDone = it)) }
                    Spacer(Modifier.height(8.dp))
                    Hint("일정은 마감 시각에 0분짜리로 들어가고 알림은 넣지 않아요. 캘린더에서 지운 일정은 다시 넣지 않아요.")
                    Spacer(Modifier.height(12.dp))
                    status?.let { Text(it, style = t.caption1Regular, color = c.foregroundNeutralSecondary); Spacer(Modifier.height(8.dp)) }
                    SoftButton("지금 내보내기", enabled = s.enabled && !busy, onClick = onSyncNow)
                    Spacer(Modifier.height(12.dp))
                    Hint("끄면 \"영민이\" 캘린더를 지우거나(이 폰에만) 앱이 넣은 일정만 지워요(구글). 앱을 그냥 지우면 넣은 일정을 정리할 수 없으니, 지우기 전에 먼저 꺼 주세요.")
                }
                Spacer(Modifier.height(12.dp))
                Card {
                    Text(".ics 파일로 내보내기", style = t.body1Medium, color = c.foregroundNeutralPrimary)
                    Spacer(Modifier.height(4.dp))
                    Hint("위에서 고른 항목을 일정 파일 하나로 만들어요. 네이버 캘린더(웹의 일정 가져오기)나 삼성 캘린더 가져오기에 쓸 수 있어요. 파일은 만든 때의 내용이라 나중에 바뀐 것은 반영되지 않아요.")
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("공유하기", enabled = !busy, onClick = onShareIcs)
                        SoftButton("파일로 저장", enabled = !busy, onClick = onSaveIcs)
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }

        if (calendars != null) {
            AlertDialog(
                onDismissRequest = onDismissPicker,
                containerColor = c.backgroundNeutralPrimary,
                title = { Text("구글 캘린더 고르기", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (calendars.isEmpty()) {
                            Text("쓸 수 있는 구글 캘린더가 없어요. 폰에 구글 계정을 추가하고 캘린더 동기화를 켜 주세요.", style = t.body2Regular, color = c.foregroundNeutralSecondary)
                        }
                        for (cal in calendars) {
                            Column(Modifier.fillMaxWidth().clickable { onChoose(cal) }.padding(vertical = 10.dp)) {
                                Text(cal.name, style = t.body1Medium, color = c.foregroundNeutralPrimary)
                                Text(cal.account, style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                            }
                        }
                    }
                },
                confirmButton = { WantedTextButton(text = "닫기", onClick = onDismissPicker) },
            )
        }
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
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
    WantedButton(text = text, type = ButtonType.ASSISTIVE, size = ButtonSize.MEDIUM, variant = ButtonVariant.OUTLINED, enabled = enabled, onClick = onClick)
}
