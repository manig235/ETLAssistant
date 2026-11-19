package kr.snu.etlassistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.wanted.android.wanted.design.theme.DesignSystemTheme
import kotlinx.coroutines.launch
import kr.snu.etlassistant.codex.CodexSession
import kr.snu.etlassistant.codex.ModelCatalog
import kr.snu.etlassistant.codex.ModelChoice
import kr.snu.etlassistant.codex.ModelInfo

/**
 * 채팅 화면이 그리는 상태. 회전해도 입력·시트 상태가 남도록 ViewModel에 둔다.
 * [ChatModel]은 관찰 가능한 상태가 아니라서 [version]을 올려 목록을 다시 그리게 한다(메시지 본문은 제자리에서 바뀐다).
 */
class ChatViewModel : ViewModel() {
    var input by mutableStateOf(TextFieldValue(""))
    var version by mutableIntStateOf(0)
        private set
    /** 올라갈 때마다 목록을 맨 아래로 내린다(새 메시지, 대화 초기화, 화면 복귀). */
    var scrollTick by mutableIntStateOf(0)
        private set
    var banner by mutableStateOf<BannerSpec?>(null)
    var activity by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var models by mutableStateOf<List<ModelInfo>>(emptyList())
    var modelsLoading by mutableStateOf(false)
    var choice by mutableStateOf(ModelChoice())
    var sheetOpen by mutableStateOf(false)

    fun bump() { version++ }

    fun scrollToBottom() { scrollTick++ }

    fun setInput(text: String) { input = TextFieldValue(text, TextRange(text.length)) }

    /** 세션 싱글턴의 현재 값을 한꺼번에 가져온다(화면이 다시 보일 때). */
    fun syncFromSession() {
        busy = CodexSession.busy
        activity = CodexSession.activity
        models = CodexSession.models
        modelsLoading = CodexSession.modelsLoading
        choice = CodexSession.modelChoice
    }
}

/** 화면이 바깥(액티비티, 세션, 시스템)에 부탁하는 일들. */
class ChatActions(
    val onSend: () -> Unit,
    val onSuggestion: (String) -> Unit,
    val onNewChat: () -> Unit,
    val onSettings: () -> Unit,
    val onBanner: (BannerAction) -> Unit,
    val onCopy: (String) -> Unit,
    val onLink: (String) -> Unit,
    val onRefreshModels: () -> Unit,
    val onModelChoice: (ModelChoice) -> Unit,
)

@Composable
fun ChatRoot(vm: ChatViewModel, a: ChatActions) {
    DesignSystemTheme {
        ChatScreen(vm, a)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: ChatViewModel, a: ChatActions) {
    val c = DesignSystemTheme.colors

    // 목록 갱신: version을 읽어 두면 올라갈 때마다 다시 그린다.
    val messages = remember(vm.version) { CodexSession.model.messages.toList() }
    val listState = rememberLazyListState()
    // 목록은 reverseLayout이라 맨 아래가 index 0이다. 맨 아래를 보고 있으면 글이 늘어도 아래쪽에 붙어 있고,
    // 위로 올려 보는 중이면 위치가 그대로다. 새 메시지가 생기면(scrollTick) 맨 아래로 내려간다.
    LaunchedEffect(vm.scrollTick) { listState.scrollToItem(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.backgroundNeutralPrimary)
            .systemBarsPadding()
            .imePadding()
    ) {
        ChatTopBar(onNewChat = a.onNewChat, onSettings = a.onSettings)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            ModelChip(
                label = ModelCatalog.choiceLabel(vm.choice, vm.models),
                onClick = { vm.sheetOpen = true },
            )
        }
        vm.banner?.let { spec ->
            StatusBanner(spec, onClick = { a.onBanner(spec.action) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }

        Column(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyState(onSuggestion = a.onSuggestion)
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val maxBubble = maxWidth * 0.86f
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(messages.asReversed(), key = { it.id }) { m ->
                            MessageRow(
                                role = m.role,
                                text = m.text,
                                maxBubbleWidth = maxBubble,
                                onCopy = a.onCopy,
                                onLink = a.onLink,
                            )
                        }
                    }
                }
            }
        }

        vm.activity?.let { ActivityLine(it) }
        InputBar(
            value = vm.input,
            onValueChange = { vm.input = it },
            mode = ChatLogic.sendMode(vm.busy, vm.input.text),
            onSend = a.onSend,
        )
    }

    if (vm.sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        // 목록이 비어 있고 불러오는 중도 아니면 다시 불러온다.
        LaunchedEffect(Unit) {
            if (vm.models.isEmpty() && !vm.modelsLoading) a.onRefreshModels()
        }
        ModalBottomSheet(
            onDismissRequest = { vm.sheetOpen = false },
            sheetState = sheetState,
            containerColor = c.backgroundNeutralPrimary,
        ) {
            ModelSheetContent(
                models = vm.models,
                choice = vm.choice,
                loading = vm.modelsLoading,
                onChoice = a.onModelChoice,
                onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { vm.sheetOpen = false } },
            )
        }
    }
}
