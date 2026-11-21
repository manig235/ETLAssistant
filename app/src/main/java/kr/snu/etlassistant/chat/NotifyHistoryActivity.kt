package kr.snu.etlassistant.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wanted.android.wanted.design.actions.button.textbutton.WantedTextButton
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import kr.snu.etlassistant.notify.HistoryEntry
import kr.snu.etlassistant.notify.NotifyHistory
import kr.snu.etlassistant.notify.NotifyHistoryLogic
import java.time.Instant

/** 지난 알림(최근 90일). 실수로 지웠거나 사라진 알림을 다시 본다. ttokttok-pet의 etl-history.log에 해당한다. */
class NotifyHistoryActivity : ComponentActivity() {
    private val store by lazy { NotifyHistory(this) }
    private var items by mutableStateOf<List<HistoryEntry>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HistoryScreen(
                items = items,
                onBack = { finish() },
                onOpen = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                onClear = { store.clear(); reload() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    private fun reload() {
        items = store.load().sortedByDescending { it.at }
    }
}

@Composable
private fun HistoryScreen(items: List<HistoryEntry>, onBack: () -> Unit, onOpen: (String) -> Unit, onClear: () -> Unit) {
    DesignSystemTheme {
        val c = DesignSystemTheme.colors
        val t = DesignSystemTheme.typography
        var confirm by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf<Long?>(null) }
        Column(Modifier.fillMaxSize().background(c.backgroundNeutralPrimary).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = c.foregroundNeutralPrimary) }
                Text("알림 기록", style = t.title2Bold, color = c.foregroundNeutralPrimary, modifier = Modifier.padding(start = 4.dp).weight(1f))
                if (items.isNotEmpty()) {
                    IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.DeleteSweep, contentDescription = "기록 지우기", tint = c.foregroundNeutralSecondary) }
                }
            }
            Text("최근 ${NotifyHistoryLogic.KEEP_DAYS}일 동안 띄운 알림이에요. 이 폰에만 남아요.", style = t.caption1Regular, color = c.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("아직 알림 기록이 없어요", style = t.body1Medium, color = c.foregroundNeutralSecondary)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.at.toString() + it.title }) { e ->
                        val open = expanded == e.at
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceNeutralSecondary)
                                .clickable(role = Role.Button) { expanded = if (open) null else e.at }
                                .padding(16.dp)
                        ) {
                            Text(myDeadlineLabel(Instant.ofEpochMilli(e.at)) + " · " + NotifyHistoryLogic.channelLabel(e.channel), style = t.caption1Regular, color = c.foregroundNeutralSecondary)
                            Spacer(Modifier.height(2.dp))
                            Text(e.title, style = t.body1Medium, color = c.foregroundNeutralPrimary)
                            Text(if (open && !e.bigText.isNullOrBlank()) e.bigText else e.text, style = t.body2Regular, color = c.foregroundNeutralSecondary)
                            if (open && e.url != null && e.url.startsWith("https://myetl.snu.ac.kr/")) {
                                WantedTextButton(text = "eTL에서 열기", onClick = { onOpen(e.url) })
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                containerColor = c.backgroundNeutralPrimary,
                title = { Text("알림 기록을 지울까요?", style = t.headline2Bold, color = c.foregroundNeutralPrimary) },
                text = { Text("지운 기록은 되돌릴 수 없어요. 알림 설정은 그대로예요.", style = t.body2Regular, color = c.foregroundNeutralSecondary) },
                confirmButton = { WantedTextButton(text = "지우기", onClick = { onClear(); confirm = false }) },
                dismissButton = { WantedTextButton(text = "취소", onClick = { confirm = false }) },
            )
        }
    }
}
