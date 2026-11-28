# Windows 0.3.1 검증

2026-11-28, Linux x64 / JDK 17 / Node 22 / 실제 Electron 45.5.0과 가상 디스플레이에서 확인했습니다. 아래 예시 자료는 모두 테스트용이며 실제 학생 계정 데이터는 사용하지 않았습니다.

## 0.3.1 빈 화면 수정

- 백엔드의 첫 출력 전에 Electron의 초기 IPC 상태를 UI가 받는 상황을 실제 앱에서 재현했습니다. 0.3.0은 프로젝트/일정 필드가 없는 시작 상태를 넘겨 `Cannot read properties of undefined (reading 'find')`로 메인 화면이 사라졌습니다. 펫은 남을 수 있어 사용자 화면과 같은 증상이 발생했습니다.
- Electron과 React가 같은 완전한 시작 상태 JSON을 사용하도록 통일했습니다. 먼저 도착한 스냅샷에서 빠진 필드는 기본값으로 채우고, 예상하지 못한 렌더링 오류는 복구 버튼이 있는 화면으로 표시합니다.
- 실제 Electron에서 테스트용 Java 래퍼로 첫 백엔드 출력을 보류했습니다. 보류 중 메인 화면과 설정창을 열고 닫은 뒤 실제 JVM/Codex를 시작했고, 이전 형식의 대화와 초안이 보존되어 표시되는지 확인했습니다. 배포 앱에는 지연용 옵션이나 테스트 우회 경로를 추가하지 않았습니다.
- 브라우저 UI 13개와 Node 초기 상태/IPC 경계 3개가 통과했습니다. 시작 순서 검사, 이전 IPC 필드 기본값, 예상하지 못한 오류의 복구 화면, 기존 프로젝트/파일/일정/키보드 동작을 포함합니다.
- 실제 Electron의 설정창·프로젝트 전환·개인 일정·한글/수식 PDF 생성과 미리보기도 재실행해 통과했습니다. ZIP에서 추출한 UI/셸/라이브러리로 시작 순서 검사를 별도로 수행합니다.
- 0.3.1 검증 당시에는 펫 모션이 없는 구현을 확인했습니다. 이후 사용자가 모션을 추가할 계획으로 변경했으므로 정지 동작은 앞으로의 요구사항이 아닙니다. 정지/작업 상태와 마우스를 올린 상태에서도 실행 중인 애니메이션이 없는지 확인하고, 단일/다중 말풍선이 펫 중심에 맞는지 검사합니다. 실제 창 화면은 `build/reports/native-pet-static.png`입니다.
- Linux 테스트 실행기는 가상 디스플레이 환경 제약으로 `--no-sandbox`를 사용합니다. 배포 앱은 이 실행 플래그를 추가하지 않으며 창은 `sandbox:true`로 설정합니다. Windows 실제 로그인·DPAPI·알림 실측 범위는 아래와 같습니다.

시작 순서 증거는 `build/reports/startup-before-backend.png`, `startup-after-backend.png`입니다. `startup-failure.png`는 수정 전 재현 화면입니다.

## 0.3.0 기존 기능 검증 기록

## 통과한 검사

- JVM 104개 중 102개 통과, 2개 건너뜀. Chromium UI 10개와 Node IPC/링크 경계 2개 통과.
- 8개 세션의 동시 실행과 응답·완료 라우팅, 화면에는 대화 하나만 표시, 세션별 effort·초안·이력, 프로젝트 범위·지침과 독립 대화 보관을 확인했습니다. 세션 목록을 읽을 때 100개로 자르던 제한도 제거했습니다.
- 실제 Codex rust-v0.170.0 → code-mode host → 인증된 MCP가 세션의 내부 context로 자료를 읽고 PDF 도구를 호출해 소유 대화로 결과를 전달하는지 확인했습니다. 모델만 로컬 SSE 응답으로 대체하고 실제 도구 전송과 reasoning effort를 검사했습니다. 계정·API 키·OpenAI 호출은 사용하지 않았습니다.
- eTL 첫 조회의 조용한 기준 저장, 0점·점수 변경·새 공지, 일부 조회 실패 시 기존 기준 유지, 알림 전달 전 대기열 저장과 재시작 후 복원, 개인 일정 저장·편집·완료·삭제, 알림 임계 구간 중복 방지와 끄기 동작을 검사했습니다.
- 참고 다운로드는 카드가 생기지 않고, 요청한 다운로드만 원래 백그라운드 세션에 전달되는지 확인했습니다. 유효하지 않거나 비활성화한 context와 26-2 범위 밖 조회를 거부하는지도 확인했습니다.
- 기존 OAuth PKCE·기기 코드·토큰 갱신 계약, eTL 출처 검증·숨김 첨부·잘린 다운로드 거부·경로 제한, HWP/XLSX·PDF 텍스트와 쪽 이미지 검사를 유지했습니다.
- 브라우저에서 설정창을 1560×980 / 720×720 / 1000×500으로 직접 열었습니다. 제목 여백, 스크롤하는 본문, 화면 안에 고정된 닫기·완료 버튼을 검사했습니다. 실제 Electron에서도 1560×980 및 1000×560 창으로 확인했습니다.
- 프로젝트/세션 이동과 작성 중 초안 유지, 커스텀 프로젝트의 복수 과목·공통 지침, effort 선택, Enter 전송·Ctrl+Enter 개행·한글 조합 보호, Markdown·수식·표, 외부 HTML/이미지 실행 방지, 개인 일정 CRUD·성적 알림 목록, 첫 실행 안내, 프로젝트 이름이 있는 세션별 펫 말풍선을 검사했습니다.
- **실제 Electron + sandboxed preload + Kotlin + Codex**로 첫 실행 안내 → 설정 → 통합/과목/커스텀 프로젝트 → 초안 전환 → 개인 시험 일정 추가 → 한글·LaTeX·표가 들어 있는 A4 PDF 생성 → PDF.js 캔버스/텍스트 미리보기 → 두 파일 탭 전환과 닫기를 수행했습니다. PDF 텍스트와 실제 화면도 확인했습니다. 숨겨진 인쇄 창이 애니메이션 프레임을 기다리던 지연과 PDF 미리보기 가로 넘침을 발견해 수정한 뒤 재실행해 통과했습니다.
- 실제 창 닫기가 프로세스를 종료하지 않고 트레이로 숨기는지, 다시 표시할 수 있는지, 펫을 표시·숨길 수 있는지 확인했습니다. 실제 stdio 백엔드의 시작·세션 초안·전환·입력 종료 후 정상 종료도 별도로 검사했습니다.
- Windows ZIP 검증기는 모든 파일 체크섬, x64 GUI 실행 파일, Chromium DLL, UI/로컬 폰트/KaTeX, PDF.js worker, JRE·Codex·code-mode host와 프로젝트·일정·PDF 필수 JVM 클래스를 확인합니다.

확인한 실행 화면과 PDF:

- `build/reports/native-settings-desktop.png`, `native-settings-short.png`
- `build/reports/native-onboarding.png`, `native-custom-schedule.png`
- `build/reports/native-pdf-preview.png`, `native-generated-study.pdf`
- `build/reports/electron-native.png`, `montage-pet.png`

이전 실패 시점의 `native-failure.png`는 검사 실패 진단용이며 최종 성공 화면이 아닙니다.

## 이 환경에서 실측하지 못한 항목

Windows 실행 환경과 실제 계정을 사용하지 않아 다음은 별도 Windows 확인이 필요합니다. Windows용 수동 CI를 제공하지만 이번 작업에서 원격 실행하지 않았습니다.

1. Windows DPAPI 암호화·암호문 복원, Windows 시스템 알림의 표시와 클릭 복귀, 실제 화면 배율·다중 모니터.
2. 실제 ChatGPT 브라우저/기기 코드 로그인과 만료 토큰 갱신, 실제 학생 계정의 eTL 26-2 조회·게시판·점수 공개.
3. Windows 한글 IME의 조합 확정/전송 Enter 및 여러 실제 모델의 동시 답변. 입력 핸들러와 추론 강도 전달은 자동 테스트로 확인했습니다.

Windows에서는 첫 안내를 마친 뒤 **설정 → 테스트 알림 보내기**로 Windows 알림을 확인할 수 있습니다. OS의 알림 설정·집중 지원에 따라 배너 표시가 달라질 수 있으며, 앱 내 받은 알림 기록은 유지합니다.

실행 파일은 코드 서명하지 않았습니다. PC와 휴대폰의 대화·파일·일정은 자동 동기화되지 않습니다.

Windows 알림 등록은 동봉한 Electron 45.5.0의 [Start Menu shortcut/COM activator 구현](https://github.com/electron/electron/blob/v45.5.0/shell/browser/notifications/win/windows_toast_activator.cc)을 확인했으며, 앱은 고정 AppUserModelID를 설정합니다. 초기 기준 저장·변경 감지와 트레이 사용 흐름은 [ttokttok-pet](https://github.com/hyukjunlim/ttokttok-pet)의 동작을 참고해 앱의 로컬 저장소와 세션 구조에 맞게 구현했습니다.
