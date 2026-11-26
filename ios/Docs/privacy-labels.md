# App Store 개인정보 레이블 (App Privacy)

App Store Connect → 앱 → **앱 개인정보 보호(App Privacy)** 설문에 그대로 옮겨 적을 표다. 기준은 `ios/Youngmin/PrivacyInfo.xcprivacy`의 `NSPrivacyCollectedDataTypes`이고, 이 문서의 표와 매니페스트가 다르면 `scripts/test-ios-privacy-manifests.py`가 실패한다.

## 원칙 (사용자 결정, 2026-11-26)

**가장 보수적으로 선언한다.** 개발자는 서버를 운영하지 않고 아무것도 받지 않지만, Apple의 "수집"은 개발자 또는 **제3자 파트너**가 요청 처리에 필요한 시간보다 오래 볼 수 있게 기기 밖으로 보내는 것을 말한다. 앱 안에 공식 Codex 엔진(OpenAI 코드)을 넣었고 OpenAI·서울대 eTL 서버는 요청 기록과 내용을 자체 방침대로 보관할 수 있으므로, 기기 밖으로 나갈 수 있고 보관될 수 있는 것은 모두 선언한다. 애매하면 선언한다.

- **사용자와 연결(Linked)**: 모두 "예". 모든 전송이 이용자 본인의 ChatGPT 계정(또는 API 키)·eTL 토큰으로 인증된 요청이라 계정과 묶인다.
- **추적(Tracking)**: 모두 "아니요". 다른 회사 앱·웹사이트의 데이터와 합치거나 광고·데이터 브로커에 넘기지 않는다. 광고 식별자(IDFA)를 쓰지 않고 App Tracking Transparency 권한도 요청하지 않는다. 매니페스트의 `NSPrivacyTracking`은 `false`, `NSPrivacyTrackingDomains`는 빈 배열이다.
- **목적**: 모두 "앱 기능". OpenAI는 이용자의 ChatGPT 데이터 설정("모두를 위한 모델 개선" 등)에 따라 대화 내용을 모델 개선에 쓸 수 있으므로 내용 계열에는 "기타 목적"을, OpenAI가 서비스 운영·개선을 위해 분석할 수 있는 요청 메타데이터 계열에는 "분석"을 더했다. 광고·개인화 목적은 없다.

## 설문 첫 질문

- "귀하 또는 타사 파트너가 이 앱에서 데이터를 수집합니까?" → **예, 이 앱에서 데이터를 수집합니다.**

## 선언하는 데이터 유형

| Apple 키 | App Store Connect 항목 | 수집 | 사용자와 연결 | 추적 | 목적 | 무엇이 어디로 가나 |
|---|---|---|---|---|---|---|
| `NSPrivacyCollectedDataTypeOtherUserContent` | 사용자 콘텐츠 → 기타 사용자 콘텐츠 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 기타 목적(Other) | 채팅 질문, 첨부한 문서, 비서가 읽은 eTL 내용(공지·과제 설명·강의자료·게시판 글·강의계획서), 켰을 때 비서가 읽은 캘린더 일정(제목·시간·장소·메모), 내 마감·시험 장소·메모 → OpenAI. 비서가 만든 PDF의 원문도 OpenAI가 생성한 것이다 |
| `NSPrivacyCollectedDataTypePhotosorVideos` | 사용자 콘텐츠 → 사진 또는 비디오 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 기타 목적(Other) | 사진 선택기·카메라로 첨부한 사진(긴 변 2048px로 다시 인코딩해 위치 등 메타데이터 제거), eTL 이미지·PDF 쪽 그림 → OpenAI |
| `NSPrivacyCollectedDataTypeEmailsOrTextMessages` | 사용자 콘텐츠 → 이메일 또는 문자 메시지 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 기타 목적(Other) | 이용자가 요청하면 비서가 `etl_get`으로 eTL 받은편지함(쪽지, `/api/v1/conversations`)과 게시판 글을 읽어 OpenAI로 보낼 수 있다 |
| `NSPrivacyCollectedDataTypeSearchHistory` | 검색 기록 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 기타 목적(Other) | 파일·게시판 검색어 → eTL(`search_term`, LearningX `keyword`)과, 검색을 요청한 대화를 통해 OpenAI |
| `NSPrivacyCollectedDataTypeOtherDataTypes` | 기타 데이터 → 기타 데이터 유형 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 기타 목적(Other) | 학업 정보: 수강 과목, 성적·점수, 과제 제출 여부, 시간표·보강/휴강, 내 마감 → 비서가 도구로 읽으면 OpenAI. eTL·수강편람 조회 요청 자체도 서울대 서버 기록에 남을 수 있다 |
| `NSPrivacyCollectedDataTypeName` | 연락처 정보 → 이름 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality) | eTL 프로필·과목 정보의 이용자 이름(비서가 `etl_get /api/v1/users/self` 등을 읽으면 OpenAI로), ChatGPT 계정 이름(OpenAI가 이미 보유) |
| `NSPrivacyCollectedDataTypeEmailAddress` | 연락처 정보 → 이메일 주소 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality) | ChatGPT 계정 이메일(로그인 토큰·로그인 힌트로 OpenAI에), eTL 프로필 이메일(비서가 읽으면 OpenAI로) |
| `NSPrivacyCollectedDataTypeUserID` | 식별자 → 사용자 ID | 예 | 예 | 아니요 | 앱 기능(AppFunctionality) | ChatGPT 계정 ID(`chatgpt-account-id` 헤더) → OpenAI. eTL 사용자 ID·로그인 ID(학번일 수 있음) → eTL, 비서가 읽으면 OpenAI |
| `NSPrivacyCollectedDataTypeDeviceID` | 식별자 → 기기 ID | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 분석(Analytics) | 설치마다 임의로 만든 ID: Codex 설치 ID(`x-codex-installation-id`, `CODEX_HOME`에 저장)와 ChatGPT 로그인용 설치 ID → OpenAI. 기기 정보로 만들지 않으며 IDFA·IDFV가 아니다 |
| `NSPrivacyCollectedDataTypeProductInteraction` | 사용 데이터 → 제품 상호작용 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 분석(Analytics) | 대화·스레드·턴 ID, 고른 모델·추론 강도, 도구 호출 이름과 결과가 담긴 요청 → OpenAI(서버 사용 기록) |
| `NSPrivacyCollectedDataTypeOtherUsageData` | 사용 데이터 → 기타 사용 데이터 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 분석(Analytics) | 계정별 사용량·한도 계산에 쓰이는 요청 수·토큰 사용량 → OpenAI |
| `NSPrivacyCollectedDataTypePerformanceData` | 진단 → 성능 데이터 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 분석(Analytics) | 요청에 붙는 시각 정보(`turn_started_at_unix_ms`, `x-codex-ws-stream-request-start-ms`) → OpenAI |
| `NSPrivacyCollectedDataTypeOtherDiagnosticData` | 진단 → 기타 진단 데이터 | 예 | 예 | 아니요 | 앱 기능(AppFunctionality), 분석(Analytics) | User-Agent의 OS 종류·버전·CPU 구조, Codex 버전, 앱 이름(`youngmin_ios`)·버전, 요청 ID, `analytics_enabled: false` 표시 → OpenAI. eTL·수강편람 요청의 URLSession 기본 User-Agent → 서울대 |

App Store Connect에서는 항목마다 "이 데이터는 사용자의 신원과 연결되어 있습니까?" → 예, "추적 목적으로 사용합니까?" → 아니요, 목적은 표의 목적을 고른다(앱 기능 / 분석 / 기타 목적).

## 끈 것 (Codex 원격 측정)

앱 안 Codex(고정 커밋 `29eb79e9866b3262a777fdf8195c1b9e63684c91`)는 설정을 비우면 다음을 켠 채로 시작한다. `ios/NativeCodex/bridge/src/telemetry.rs`가 Android `config.toml`과 같은 값을 CLI 계층 덮어쓰기로 넣고, 요청의 `config`에서 같은 키를 지우며, `feedback/upload`를 거절한다. 그래서 아래는 선언하지 않는다.

- **사용 분석 이벤트**: `analytics.enabled`가 비어 있으면 `AnalyticsEventsClient`가 켜져 `…/codex/analytics-events/events`로 스레드·턴·토큰 사용량·도구 호출·오류 이벤트를 보낸다(`analytics/src/client.rs`, `app-server/src/analytics_utils.rs`). → `analytics.enabled = false`로 큐 자체를 만들지 않는다(`core/src/session/session.rs`도 `Some(false)`면 비활성 클라이언트).
- **피드백 업로드(Sentry)**: `feedback/upload` RPC가 로그·대화 기록을 Sentry로 올린다(`feedback/src/lib.rs`). → `feedback.enabled = false`, 브리지가 요청을 거절, Swift 앱은 부르지 않는다.
- **OpenTelemetry 지표·추적·로그**: 지표 기본값은 Statsig(`core/src/config/otel.rs`). 앱 내부(in-process) 서버는 `otel_init::build_provider`를 부르지 않아 내보내기 자체가 없고, 값도 `otel.exporter`·`trace_exporter`·`metrics_exporter = "none"`으로 고정한다.

## 선언하지 않는 데이터 유형과 이유

| 항목 | 이유 |
|---|---|
| 충돌 데이터(Crash Data) | 앱과 Codex 모두 충돌 보고 SDK가 없다. Codex의 Sentry 피드백 업로드와 오류 분석 이벤트는 위처럼 껐다. 이용자가 켠 Apple의 "앱 개발자와 공유"·TestFlight 충돌 보고는 Apple이 수집하는 것이라 레이블 대상이 아니다 |
| 정확한 위치·대략적인 위치 | 위치 API를 쓰지 않는다. 사진은 다시 인코딩해 위치 메타데이터를 뺀다. 캘린더 일정·시험 장소처럼 이용자가 적은 장소 글은 "기타 사용자 콘텐츠"로 선언했다 |
| 오디오 데이터 | 마이크·음성 입력이 없다 |
| 연락처 | 주소록에 접근하지 않는다. 캘린더 참석자 정보는 비서에게 넘기지 않는다(초대 일정인지 여부만 판단) |
| 전화번호·물리적 주소·기타 연락처 정보 | eTL(Canvas) 프로필에 없고 앱이 따로 받지 않는다 |
| 건강·피트니스·금융 정보·구매 내역 | 해당 기능이 없다. 앱은 무료이고 결제가 없다 |
| 민감한 정보 | 앱이 그런 정보를 요구하거나 처리하지 않는다. 이용자가 채팅에 직접 쓴 내용은 "기타 사용자 콘텐츠"에 포함된다 |
| 브라우징 기록 | 앱 밖의 방문 기록을 읽지 않는다. 링크는 Safari 등으로 열 뿐이다 |
| 광고 데이터 | 광고가 없다 |
| 고객 지원 | 문의는 앱 밖의 카카오톡 오픈채팅 링크로 연다. 앱이 문의 내용을 보내지 않는다 |
| 게임 플레이 콘텐츠, 주변 환경 스캔, 손·머리 | 해당 없음 |

## 받는 곳별 정리

| 받는 곳 | 보내는 것 | 관련 레이블 |
|---|---|---|
| OpenAI (`api.openai.com`, `chatgpt.com` 백엔드, `auth.openai.com`) — 앱 안 Codex 또는 API 키 모드의 Responses 클라이언트 | 대화·첨부·도구 결과, 계정 토큰·계정 ID, 설치 ID, 요청 메타데이터·User-Agent | 표의 모든 항목 |
| 서울대 eTL (`myetl.snu.ac.kr`, LearningX) | eTL 토큰, 조회 요청, 검색어 | 사용자 ID, 검색 기록, 기타 데이터 유형, 기타 진단 데이터 |
| 서울대 수강편람·파일 서버 (`sugang.snu.ac.kr`, `shine.snu.ac.kr`) | 공개 과목 코드, 강의계획서 첨부 요청(토큰 없음) | 기타 데이터 유형 |
| eTL 파일이 연결하는 서명된 저장소 주소 | 파일 다운로드 요청(토큰 없음) | 기타 사용자 콘텐츠 |
| 이용자가 고른 캘린더 계정(iCloud·Google) | 이용자가 켠 자동 넣기·비서 변경 일정. iOS 캘린더 동기화가 보내며 앱은 직접 통신하지 않음 | 기타 사용자 콘텐츠 |

위젯(`YoungminWidgets`)과 공유 확장(`YoungminShare`)은 네트워크를 쓰지 않으므로 각자의 매니페스트에는 수집 항목이 없다(`NSPrivacyCollectedDataTypes` 빈 배열). App Store Connect 레이블은 앱 전체에 하나이며 위 표를 쓴다.

## 다시 확인할 때

- Codex 버전을 올리면 `telemetry.rs`의 기본값 전제 테스트(`codex_defaults_would_enable_telemetry`)와 새 원격 측정 경로를 다시 확인한다.
- 새 외부 호스트, 음성 입력, 위치, 결제, 충돌 보고 도구를 넣으면 이 표와 매니페스트를 함께 고친다.
