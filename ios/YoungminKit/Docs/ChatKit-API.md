# ChatKit API

ChatKit = Kotlin `chat`·`codex` 패키지의 순수 로직 + 새 AI 엔진(OpenAI Responses API) + ChatGPT 로그인(SIWC).
다른 모듈에 의존하지 않는다(ETLKit도 가져오지 않음). Kotlin 이름은 그대로 두되 enum case는 Swift식 소문자(`Role.user` ↔ `USER`, 저장값은 Kotlin 이름).
모든 글 위치(마크다운·수식 스팬, 제목 길이)는 Kotlin처럼 **UTF-16 단위**다(`NSRange`와 바로 맞음).

원본 표기: 파일 이름 = `$ANDROID/app/src/main/java/kr/snu/etlassistant/` 아래 Kotlin 파일, **new** = iOS에서 새로 만듦.

## 대화 모델·저장

| 선언 | 원본 | 설명 |
|---|---|---|
| `enum Role` (`user/assistant/notice/files`, rawValue `USER`…) | chat/ChatModel.kt | 대화 한 줄의 주체 |
| `struct Attachment` (`name, mime, size, path, uri, shownPath, pagePreview, referenceOnly`, `isImage`, `toJson()`, `static fromJson(_:)`) | chat/ChatModel.kt | 대화에 붙은 파일 하나, 저장 키 원본과 같음 |
| `final class Message` (`id, role, text, status, error, itemId, attachments`) | chat/ChatModel.kt | 화면의 대화 한 줄 |
| `final class ChatModel` (`init(_ file: URL?)`, `messages`, `listener`, `addUser`, `addFiles`, `addNotice`, `appendAssistant(itemId, delta)`, `finishAssistant(itemId, text)`, `closeStreaming()`, `switchTo(_:)`, `clear()`, `save()`, `load()`, `MAX_SAVED`) | chat/ChatModel.kt | 대화 상태(메인 스레드 전용), JSON 파일 저장 |
| `protocol ChatModel.Listener` (`onAdded/onChanged/onReset`) | chat/ChatModel.kt | 변경 알림 |
| `enum ChatPaths` (`root`, `directory(_:)`, `chats`, `threads`) | new (ETLKit `AppPaths`와 같은 규칙) | Application Support/Youngmin 아래 폴더(백업 제외) |
| `struct ChatMeta` (`id, title, createdAt, updatedAt`(epoch ms), `threadId, renamed`, `toJson`, `fromJson`) | chat/ChatStore.kt | 대화 색인 항목 |
| `enum ChatTitles` (`DEFAULT, MAX_LEN, from(_:_:), cut(_:max:), Group(.label), groupOf(_:now:zone:), grouped(_:now:zone:)`) | chat/ChatStore.kt | 제목 만들기, 날짜 묶음(오늘/어제/지난 7일/이전) |
| `final class ChatStore` (`init(_ dir:, attachmentRoot:)`, `load, list, get, fileFor, newId, noteUserMessage, setThread, rename, delete, migrateLegacy, INDEX, isSafeId`) | chat/ChatStore.kt (+ files/WorkFileCleanup.kt `WorkFiles` 내부 이식) | `index.json` + `{id}.json`, 지울 때 다른 대화가 안 쓰는 첨부만 지움 |
| `enum ThreadPurge` (`isThreadId`) | codex/ThreadPurge.kt | 스레드 ID(UUID) 확인. rollout 파일 정리는 iOS에 없음 |
| `final class ConversationThreadStore` (`init(_ dir:)`, `newThreadId, load, save, delete, orphans(keep:), purgePending`) | new (ThreadPurge 삭제 규칙 반영) | 대화마다 Responses 항목 목록 `threads/{threadId}.json` 저장·삭제 |

## 화면 판정·마크다운·수식·첨부

| 선언 | 원본 | 설명 |
|---|---|---|
| `enum BannerAction`, `struct BannerSpec`, `enum NotifyProblem`, `enum SendMode`, `struct MdStyle` | chat/ChatLogic.kt | 배너·전송 버튼·스타일 값 |
| `enum ChatLogic` (`NOTIFY_BANNER_SNOOZE_MS, banner(…), notifyProblem(…), sendMode(…), styleRanges(_:)`) | chat/ChatLogic.kt | 채팅 화면 순수 판정 |
| `enum MarkdownLite` (`Kind, Span, Result, render(_:)`; internal `inlineMathAt`) | chat/MarkdownLite.kt | 작은 마크다운 + LaTeX 스팬 |
| `enum MathText` (`Piece(.text/.block)`, `split`, `Replacement`, `mapOffset(_:_:isEnd:)`, `toUnicode(_:)`) | chat/MathText.kt | 블록 수식 나누기, 위치 옮기기, LaTeX→유니코드 |
| `struct MathSelectionText` (`original, display, maths, copySelection(_:_:)`, `static prepare(_:)`) | chat/MathSelectionText.kt | 수식 단위 선택·복사 |
| `MarkdownLite.Kind.italic`, `MdStyle.italic`, internal `MarkdownLite.italicClose` | **new** (PdfMarkdown 규칙) | `*기울임*`·`_기울임_`: 표시 옆 공백·단어 안 `_`·`\*`·겹친 표시는 아님 |
| `struct MathParser` (`static parse(_:) throws -> [MathNode]`, `MAX_LENGTH`), `enum MathNode`, `MathAtomClass`, `MathFontStyle`, `MathAccentKind`, `MathArrayKind`, `MathParseError` | **new** (JLatexMath 대신) | TeX 부분 집합 해석: 분수·첨자·근호·그리스 문자·연산자·관계·화살표·큰 연산자(위아래 첨자)·`\left \right`·`\big`·글꼴(`\mathbf \mathrm \mathbb \mathcal \text \operatorname`)·악센트·`matrix/pmatrix/bmatrix/vmatrix/cases/aligned/gathered/array`·`\boxed \overset \xrightarrow \not \pmod`. 모르면 오류 → 유니코드 대체 |
| `enum MathLayout` (`layout(_:fontSize:display:) -> MathBox?`, `canParse`, `draw(_:in:x:baseline:color:yDown:)`, `font`, `line`), `struct MathBox` (`width, ascent, descent, items, scaled(_:)`), `enum MathDrawItem`, `MathPathOp`, `MathFontFace` | **new** | CoreText 배치(Times New Roman 계열, 없는 글자는 대체 글꼴)·TeX 간격 규칙·수식 축 정렬. 그리기는 CGContext(UIKit 그림판 `yDown: true`, PDF `false`) |
| `enum AttachmentLogic` (`MAX_PENDING, MAX_FILE_BYTES, IMAGE_MAX_SIDE, IMAGE_QUALITY, UPLOAD_DIR, shouldReencode, uploadName, promptText, formatSize, Availability, availability, redownloadPrompt, FileKind, kindOf`) | chat/AttachmentLogic.kt (`EtlFiles.DIR/TEXT_EXT` 값 복사) | 첨부 규칙 |
| `final class ActivityTracker` (`turnStarted, toolStarted, toolFinished, textDelta, messageDone, turnEnded, current`, `THINKING`, `static forTool`) | chat/ActivityTracker.kt | "생각하는 중…/조회하는 중…" 한 줄 |

## 세션·모델·설정

| 선언 | 원본 | 설명 |
|---|---|---|
| `enum SessionState` (`stopped/starting/ready/failed`) | codex/CodexSession.kt | 비서 준비 상태 |
| `struct AccountInfo` (`Kind none/chatgpt/apiKey/unknown`, `email, plan, loggedIn`) | codex/CodexSession.kt | 로그인한 계정 |
| `enum SessionEvent` (`state, account, busy, activity, models, loginDone, loginIdle`) | codex/CodexSession.kt (일부) | 앱 계층이 화면에 보낼 이벤트 모양 |
| `struct EffortOption`, `struct ModelInfo`, `struct ModelChoice` (`isAuto`) | codex/ModelCatalog.kt | 모델·추론 강도 선택 |
| `enum ModelCatalog` (`parse`(Codex 형식), `effortLabel`, `choiceLabel(_:_:savedName:)`, `sanitize`) | codex/ModelCatalog.kt | 모델 목록·선택 규칙. `savedName`(iOS)은 목록을 받기 전 칩에 쓸 저장된 표시 이름 |
| `ModelCatalog.resolveModel`, `parseOpenAIModels`, `ASSUMED_EFFORTS` | new | "자동" → 실제 모델, `GET /v1/models` 해석(SIWC `models[]` / API 키 `data[]`) |
| `protocol ChatKeyValueStore`, `protocol ChatSecretStore`, `InMemoryChatKeyValueStore`, `InMemoryChatSecretStore` | new | ETLKit `KeyValueStore`/`SecretStore`와 같은 요구 사항(앱에서 빈 extension으로 연결) |
| `final class ChatSettings` (`AUTH_CHATGPT, AUTH_API_KEY, NAMESPACE`, `authMode, model, effort, legacyThreadId, casualTone, currentChatId, pendingThreadDeletes, modelChoice, modelName, toneInstructions()`, `static toneInstructions(casual:)`) | codex/CodexSession.kt `AppSettings` + `toneInstructions` | 원본 키 이름(`authMode`, `model`, `effort`, `threadId`, `casualTone`, `currentChatId`, `pendingThreadDeletes`) + iOS `modelName`(고른 모델 표시 이름) |

## AI 엔진 (Responses API) — 모두 new

| 선언 | 설명 |
|---|---|
| `enum ChatJSON` (+ 리터럴, `subscript`, `string/int/double/bool/array/object`, `setting`, `init(parsing:)`, `serialized()`, `jsonString`, `Codable`) | JSON 값(요청·항목·도구 스키마) |
| `struct SSEMessage`, `struct SSEParser` (`push(line:)`, `finish()`, `static parse`), `struct SSELineSplitter` | 순수 SSE 해석(빈 줄 보존) |
| `enum ResponsesStreamEvent` (`decode(_:)`) | `response.*`/`error` 이벤트 해석 |
| `struct ResponsesRoundAccumulator` (`apply`, `terminal`, `outputItems`, `usage`, `static functionCalls(in:)`, `static messageText`) | 한 회차 스트림 모으기 |
| `struct FunctionTool` (`json`), `enum ToolPackaging` (`automatic/plain/namespace`, `defaultNamespace`) | 함수 도구 정의와 싣는 방식 |
| `struct ToolCall` (`argumentsJSON`), `struct ToolOutput` (`outputJSON`), `typealias ToolExecutor` | 도구 호출·결과·실행기 |
| `struct InputImage`, `struct InputFile`, `enum ResponsesInput` (`userMessage, developerMessage, functionCallOutput`) | 입력 항목(data URL 이미지·파일) |
| `struct ResponsesUsage`, `struct ResponsesError` (`kind, status, code, message, param, requestId, isRetryable, needsSignIn, userMessage`) | 사용량, 오류(문서의 `subscription_sharing_*` 코드 → 한국어) |
| `struct TurnConfig`, `enum TurnStatus`, `struct TurnResult`, `enum ResponsesEvent` | 턴 설정·결과·화면 이벤트 |
| `enum ResponsesRequest` (`endpoint, body, tools, urlRequest, httpError`) | 요청 본문(`store:false`, `stream:true`, 미지원 필드 제외) |
| `enum ConversationHistory` (`seal, compactAttachments, trim, interruptedOutput`) | 다음 요청용 항목 정리 |
| `struct HTTPLineResponse`, `protocol HTTPTransport`, `final class URLSessionTransport` | HTTP 계층(리다이렉트 안 따라감), 테스트는 흉내 |
| `actor ResponsesEngine` (`init(auth:transport:endpoint:retryDelays:statusLabel:)`, `send(history:input:config:tools:)`, `interrupt()`, `isBusy`) | 스트리밍 + 도구 루프 + 중단 + 재시도 |
| `enum ModelListClient` (`endpoint`, `fetch(auth:transport:)`) | `GET /v1/models` |

## 인증 — 모두 new (참고: codex/ChatGptOAuth.kt, ChatGptCredentials.kt)

| 선언 | 설명 |
|---|---|
| `enum AuthKind`, `enum AuthError` (`userMessage`), `protocol AuthProvider` (`kind`, `bearerToken(forceRefresh:)`) | 토큰 공급 계약 |
| `final class APIKeyAuthProvider` (`SECRET_KEY`, `hasKey`, `save`) | OpenAI API 키(Keychain) |
| `enum SIWC` (주소·`resource`·scope·`dynamicClientId`·`agentNameHint`="영민이"·`redirectURI(port:)`·`grantsDirectUsage`·`splitScope`·`terminalRefreshErrors`) | SIWC 상수 |
| `enum PKCE` (`random`, `challenge`), `enum Base64URL`, `enum FormEncoding` (`encode, form, parse`) | PKCE S256, 인코딩 |
| `struct SIWCAttempt` (`authorizeURL`, `tokenRequestBody`, `isRegistration`) | 시도마다 새 state·nonce·verifier |
| `enum SIWCCallback` (`parse(target:expectedState:)`) | 콜백 해석(state 먼저, 중복 거부) |
| `enum SIWCHostID` (`loadOrCreate`, `isValid`) | `urn:uuid:` 호스트 ID(설치마다 고정) |
| `struct SIWCTokenResponse` (`parse`, `errorCode`), `struct ChatGPTCredentials` (`expiresAt, canUsePlan, needsRefresh, refreshed, json, parse, accountInfo`) | 토큰 응답·저장 기록(문서 필드 이름) |
| `struct JWT` (`decode, validateClaims, verifySignature(jwks:)`, `Failure`), `enum RSAPublicKey` | ID 토큰 iss/aud/exp/nonce/sub + RS256 서명 확인 |
| `actor ChatGPTAuthProvider` (`credentials, accountInfo, issuedClientId, forgetRegistration, makeAttempt(port:), complete(attempt:callbackTarget:), bearerToken, refresh, signOut`), `enum SIWCError`, `struct SIWCSignOutResult` | 로그인·새로 고침(직렬화)·로그아웃(철회) |
| `enum LoopbackHTTP` (`parseRequestHead, headerComplete, response(_:)`, `Page`), `enum LoopbackDecision`, `enum LoopbackError`, `final class LoopbackCallbackServer` (`start, startPreferring, waitForCallback, stop, siwcDecision`) | 127.0.0.1 콜백 서버(NWListener), 한국어 HTML 응답 |

## 화면이 턴 하나를 돌리는 법

```swift
// 준비(앱 시작 시)
let settings = ChatSettings(appSettingsStore)                 // UserDefaultsStore(namespace: ChatSettings.NAMESPACE)
let auth: AuthProvider = settings.authMode == ChatSettings.AUTH_API_KEY
    ? APIKeyAuthProvider(secrets: keychain) : chatGPTAuth      // ChatGPTAuthProvider(secrets:settings:)
let engine = ResponsesEngine(auth: auth)
let chats = ChatStore(ChatPaths.chats); chats.load()
let threads = ConversationThreadStore(ChatPaths.threads)
let models = try await ModelListClient.fetch(auth: auth, transport: URLSessionTransport())

// 보내기
model.addUser(text, attachments)
chats.noteUserMessage(chatId, text, attachments, now: nowMs)
let threadId = chats.get(chatId)?.threadId ?? { let t = threads.newThreadId(); chats.setThread(chatId, t); return t }()
let prompt = AttachmentLogic.promptText(text, files, imageCount: images.count)
let input = [ResponsesInput.userMessage(prompt, images: images)]
let config = TurnConfig(model: ModelCatalog.resolveModel(settings.modelChoice, models)!,
                        effort: ModelCatalog.sanitize(settings.modelChoice, models).effort,
                        instructions: agentsMd + "\n\n" + settings.toneInstructions(),
                        tools: assistantTools)                  // AssistantKit이 FunctionTool로 정의
for await e in await engine.send(history: threads.load(threadId), input: input, config: config, tools: { call in
    await assistant.execute(call.name, call.arguments)          // → ToolOutput
}) {
    switch e {
    case .activity(let label): statusLine = label               // "생각하는 중…", "조회하는 중…"
    case .textDelta(let id, let d): model.appendAssistant(id, d)
    case .textDone(let id, let t): model.finishAssistant(id, t)
    case .turnEnded(let r):
        threads.save(threadId, r.history)
        model.closeStreaming()
        switch r.status {
        case .interrupted: model.addNotice("답변을 멈췄어요")
        case .failed(let err):
            model.addNotice(err.userMessage)
            if !settings.modelChoice.isAuto { model.addNotice("선택한 모델 설정 때문일 수 있어요. 설정에서 '자동'으로 되돌려 보세요.") }
        default: break
        }
    default: break
    }
}
// 멈춤 버튼: await engine.interrupt()
// 대화 삭제: if let t = chats.delete(id)?.threadId, !threads.delete(t) { settings.pendingThreadDeletes.insert(t) }
```

ChatGPT 로그인:

```swift
let (server, port) = try await LoopbackCallbackServer.startPreferring()   // 1455, 안 되면 빈 포트
let attempt = await chatGPTAuth.makeAttempt(port: port)
// attempt.authorizeURL 을 ASWebAuthenticationSession/Safari로 연다(URL은 기록하지 않는다)
let target = try await server.waitForCallback(decide: LoopbackCallbackServer.siwcDecision(expectedState: attempt.state))
// 콜백을 받으면 웹 세션을 닫는다(http 루프백이라 세션이 스스로 끝나지 않는다)
let creds = try await chatGPTAuth.complete(attempt: attempt, callbackTarget: target)
if !creds.canUsePlan { /* 플랜 사용 미허용 안내 → 다시 로그인 또는 API 키 */ }
```
