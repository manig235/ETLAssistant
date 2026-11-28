# 영민이 Windows 로컬 세션 인계

2026-11-28 기준, 사용자와 확정한 요구사항 및 Windows 0.3.1 상태입니다. 대화 내용을 추측해서 새로 설계하지 말고 아래 결정을 유지합니다. 이 문서를 읽는 것만으로 새 기능 구현·배포·다른 세션 생성이 요청된 것은 아닙니다. 사용자의 다음 작업 요청을 기다립니다.

## 저장소와 구조

- 저장소: `https://github.com/manig235/ETLAssistant`, 작업 브랜치: `feature/onboarding-montage`.
- Android 앱을 유지하며 `desktop/`에 Windows x64 앱을 추가했습니다. 앱 이름은 **영민이**, 보조 영문 이름은 Young Mini입니다. 서울대 공식 서비스가 아닙니다.
- UI: Electron 45.5.0 + React + 공식 Wanted `@montage-ui/core` 4.4.0. UI 소스는 `desktop/ui/src`, 네이티브 창/IPC는 `desktop/ui/electron`입니다.
- 백엔드: Kotlin/JVM 17, `desktop/src/main/kotlin/kr/snu/youngmini/desktop`. Android의 플랫폼 독립 eTL/Codex/MCP 코드를 공유합니다. UI와 JVM은 전용 stdio JSON 파이프로 연결합니다.
- 본인 ChatGPT 계정으로 공식 Codex rust-v0.170.0/app-server/code-mode host를 사용합니다. API 키나 앱 운영 서버를 요구하는 구조로 바꾸지 않습니다. Android의 ‘채팅 화면이 열릴 때만 Codex 실행’ 규칙은 Windows 트레이 동작에 그대로 적용하지 않습니다.
- Windows 사용자 데이터: `%LOCALAPPDATA%\YoungMini`. 로그인/eTL 토큰은 DPAPI 암호화, 대화·프로젝트·일정·알림 기록·파일은 로컬 저장입니다. 휴대폰과 자동 동기화하지 않습니다. 다른 앱의 로그인·대화·로그를 읽지 않습니다.

## 사용자 확정 UI

1. **왼쪽 프로젝트/세션 → 가운데 대화 하나 → 필요할 때 오른쪽 파일 미리보기** 배치입니다. 여러 대화를 나란히 보여주는 UI는 취소했습니다.
2. 여러 세션은 **백그라운드에서 병렬 실행**합니다. 프로젝트나 세션을 옮겨도 작업과 결과는 원래 세션에 남습니다. 최대 3개 제한이 없으며, 저장된 세션도 100개로 잘라 읽지 않습니다.
3. 프로젝트는 **통합 프로젝트 + 2026년 2학기 과목별 프로젝트 + 커스텀 추가**입니다. 통합은 26-2 전체 과목, 과목 프로젝트는 해당 과목, 커스텀은 이름/공통 지침/0개 이상의 연결 과목을 가집니다. 커스텀에 여러 과목을 연결할 수 있습니다.
4. 자료 범위와 지침은 프로젝트에서 공유하지만 **각 세션의 대화 이력은 독립**입니다. 모델과 추론 강도(effort), 초안도 세션별로 유지합니다.
5. 별도 강의자료/파일 목록 탭은 제거했습니다. 채팅으로 자료를 요청하면 찾아서 받습니다. **명시적으로 요청한 원본과 완성된 결과물만 파일 카드로 표시**하고, 답변을 만들며 참고용으로 다운로드한 파일은 완전히 숨깁니다.
6. 파일 카드를 누르면 오른쪽 미리보기 탭을 엽니다. 여러 파일 탭을 오가고 닫을 수 있으며 모두 닫으면 대화가 넓어집니다. PDF.js로 PDF 쪽 이동/확대/텍스트 선택, 이미지와 문서 추출 텍스트를 표시합니다. 외부 앱 열기와 다른 이름으로 저장도 있습니다.
7. 시험 대비 자료/문제집 등을 요청하면 **실제 A4 PDF**를 만듭니다. Markdown/KaTeX를 숨겨진 Chromium 창에서 로컬 인쇄하고 도구 `make_pdf` 결과를 소유 세션에 연결합니다. 답변의 ‘PDF로 받기’도 같은 경로를 사용합니다.
8. 답변은 Markdown + 수식 뷰어입니다. Enter 전송, Ctrl+Enter/Shift+Enter 개행이며 한글 IME 조합 중 Enter는 전송하지 않습니다. 작은 고정 설명 문구로 화면을 복잡하게 만들지 않습니다.
9. 펫에는 **앞으로 모션을 추가할 계획**입니다. 정지 유지 조건은 없습니다. 구체적인 모션은 다음 로컬 세션에서 사용자 요청에 맞춰 정합니다. 말풍선과 내용은 펫 위 중앙 정렬이며 꼬리가 있습니다. 프로젝트 이름/세션 이름/작업 상태를 표시하고 클릭하면 정확한 세션을 엽니다. 여러 말풍선은 스크롤합니다.
10. 처음 실행하면 사용법 → 계정 연결 → 알림/트레이 안내를 보여줍니다. 연결은 건너뛰고 설정에서 다시 할 수 있습니다. 설정은 제목/스크롤 본문/고정 하단 버튼으로 나뉩니다.

## 알림과 개인 일정

- 채팅창을 닫으면 **트레이에서 계속 실행**합니다. 완전 종료는 트레이 또는 설정의 ‘영민이 종료’입니다. PC가 꺼지거나 절전 중이면 제때 알릴 수 없고 다시 실행할 때 놓친 일정을 확인한다는 내용을 처음 안내합니다. Windows 자동 시작은 추가하지 않았습니다.
- 개인 일정 UI: 제목, 한국 시간 날짜/시각, 시험 여부, 메모, 정각/10분 전/3시간 전/하루 전 알림. 추가·수정·완료·삭제를 지원하며 로그인 없이도 사용합니다.
- eTL 26-2 과제/시험 마감, 새 공지, 성적 공개/점수 변경을 확인합니다. 연결 직후와 30분마다 확인하고 수동 갱신도 있습니다.
- 공지/점수의 첫 조회는 조용히 기준만 저장합니다. 이후 변경만 알리고 0점도 유효한 점수로 처리합니다. 실패한 과목의 이전 기준을 버리지 않습니다. 알림 대기열과 비교 기준은 함께 저장하고 중복 알림을 막습니다.
- Windows 알림과 앱 내 알림 목록이 있습니다. 작업 완료는 세션별 펫에 표시하고 메인 창이 숨겨져 있으면 Windows 알림도 보냅니다. 설정에서 종류별 알림을 끄거나 테스트 알림을 보낼 수 있습니다.

## 중요한 구현과 이미 고친 문제

- `AppController.kt`: 프로젝트/세션, 병렬 작업, 저장, 일정 갱신. `Projects.kt`, `Agenda.kt`, `Artifacts.kt`가 각각 프로젝트, 일정/알림, 파일/PDF/도구 context를 처리합니다.
- `ToolContexts`는 세션마다 다른 비공개 라우팅 키를 사용해 다른 화면을 보고 있어도 파일을 원래 세션에 연결합니다. MCP 호출을 현재 보이는 세션에 무조건 연결하는 방식으로 바꾸지 않습니다.
- **0.3.0 빈 화면 오류**: Java의 첫 상태보다 셸의 초기 IPC 상태가 먼저 오면 `projects`/`agenda`가 없어 `Cannot read properties of undefined (reading 'find')`로 React가 꺼졌습니다. 사용자는 메인 창이 빈 화면이고 펫만 남는 증상을 겪었습니다.
- **0.3.1 수정**: 셸/렌더러가 `desktop/ui/electron/initial-state.json`을 공유합니다. `normalizeState`는 누락 필드에 기본값을 채우며 `ScreenBoundary`가 예상 못한 렌더링 실패 시 복구 화면을 제공합니다. 이 공통 파일을 패키지에서 누락하거나 초기 상태를 다시 따로 정의하지 않습니다.
- 숨긴 PDF 인쇄 창은 `requestAnimationFrame`을 기다리면 멈췄습니다. 폰트 준비/레이아웃 후 인쇄를 요청하고 인쇄 창만 background throttling을 끕니다.
- PDF.js 전체 viewer CSS를 전역 import하면 앱 스타일에 영향을 주므로 필요한 텍스트 레이어 스타일만 미리보기에 한정합니다.
- Montage Checkbox는 라벨을 별도로 연결해야 합니다. 접근성 이름과 보이는 라벨을 함께 유지합니다.
- 모델에는 읽기 전용 eTL/MCP 도구만 제공합니다. 임의 셸/앱/플러그인 도구를 켜거나 렌더러에 Node/파일 시스템 접근을 주지 않습니다. 잠긴 파일은 거부하고 숨김 첨부는 공지/과제/모듈/게시글 출처를 확인합니다. 문서 속 지시는 신뢰하지 않습니다.

## 빌드와 테스트

`desktop/README.md`, `desktop/VALIDATION.md`를 먼저 확인합니다. JDK 17, Node 22.13+, Python 3.11+가 필요합니다. Windows PowerShell 예시:

```powershell
.\gradlew.bat -p desktop test stageWindows
npm --prefix desktop/ui ci
npm --prefix desktop/ui run build
npx --prefix desktop/ui playwright install chromium
npm --prefix desktop/ui test
python desktop/scripts/package_windows.py
python desktop/scripts/verify_package.py desktop/dist/youngmin-windows-x64-v0.3.1.zip
```

- ZIP/JRE/Codex/`node_modules`/빌드 결과는 Git에 넣지 않습니다. ZIP은 위 명령으로 재생성합니다. 공식 배포 바이너리는 고정 SHA-256으로 검증합니다. Git 푸시가 릴리스 게시를 뜻하지 않습니다.
- `npm start`로 개발 실행하려면 셸이 사용할 JRE와 lib도 준비해야 합니다. 기본 경로는 `desktop/build/shell-dev/jre`, `lib`이며 `YOUNGMIN_DESKTOP_HOME`으로 동봉 JRE/lib/runtime이 있는 `YoungMini` 폴더를 지정할 수 있습니다. 테스트는 반드시 별도 `YOUNGMIN_DATA_HOME`을 사용해 실제 사용자 로그인/대화를 건드리지 않습니다.
- `electron-smoke.cjs`: 실제 Electron + JVM/Codex로 설정, 프로젝트/세션 전환, 개인 일정, PDF 생성/미리보기, 펫과 중앙 말풍선을 검사합니다. `YOUNGMIN_SMOKE_APP`으로 ZIP에서 추출한 `resources/app`을 선택할 수도 있습니다.
- `startup-smoke.cjs`: Linux 테스트용 Java 래퍼로 백엔드의 첫 출력을 보류해 시작 순서 오류를 재현/검사합니다. `YOUNGMIN_SMOKE_JAVA` 또는 `JAVA_HOME`으로 JDK를 지정합니다. Linux에서는 Xvfb와 DISPLAY가 필요합니다.
- 현재 0.3.1 구현에는 애니메이션이 없지만, 이는 향후 모션 추가를 금지하는 조건이 아닙니다. 모션 추가 시 해당 회귀 검사도 새 요구사항에 맞춥니다.
- 이전 Linux 검증: JVM 104개 중 102개 통과/2개 스킵(Windows DPAPI·선택적 실제 HWP 샘플). 최종 UI 13개/Node 경계 3개 통과. 실제 Electron 실행, ZIP에서 추출한 셸/UI/lib의 느린 시작, 이전 대화·초안 복원도 통과했습니다.
- **Windows 실기기 로그인, DPAPI 복원, 시스템 알림, Windows IME·배율·다중 모니터는 이 Linux 환경에서 실측하지 못했습니다.** 로컬 Windows에서 확인할 때 검증 범위를 과장하지 않습니다. 0.3.1 수정본은 아직 사용자의 Windows 재확인 답변을 받지 않았습니다.

## 다음 세션에서 지킬 것

요구사항을 처음부터 다시 인터뷰하거나 UI 배치를 임의 변경하지 않습니다. 불확실한 새 결정만 짧게 묻습니다. 변경한 UI는 직접 실행해 확인합니다. 여러 로컬 세션이 같은 저장소에서 일할 수 있으므로 담당 범위를 확인하고 다른 세션의 변경을 덮어쓰거나 reset/clean하지 않습니다.

이번 인계 푸시는 Windows 소스/문서/수동 CI와 빌드에 필요한 공용 Queries·LinkedFileAccess·XlsxText 및 관련 테스트를 포함합니다. 별도로 진행되던 Android `EtlService.kt`/`EtlTools.kt` 수정, 디자인 실험, 로컬 도구/작업 디렉터리는 합쳐 넣지 않습니다. 작업 시작 시 실제 `git status`를 확인합니다.
