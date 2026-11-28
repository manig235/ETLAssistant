# 영민이 Windows 로컬 세션 인계

2026-11-28 기준, 사용자와 확정한 요구사항 및 Windows 0.5.2 상태입니다. 대화 내용을 추측해서 새로 설계하지 말고 아래 결정을 유지합니다. 이 문서를 읽는 것만으로 새 기능 구현·배포·다른 세션 생성이 요청된 것은 아닙니다. 사용자의 다음 작업 요청을 기다립니다.

## 저장소와 구조

- 저장소: `https://github.com/manig235/ETLAssistant`, 작업 브랜치: `feature/onboarding-montage`.
- Android 앱을 유지하며 `desktop/`에 Windows x64 앱을 추가했습니다. 앱 이름은 **영민이**, 보조 영문 이름은 Young Mini입니다. 서울대 공식 서비스가 아닙니다.
- UI: Electron 45.5.0 + React + 공식 Wanted `@montage-ui/core` 4.4.0. UI 소스는 `desktop/ui/src`, 네이티브 창/IPC는 `desktop/ui/electron`입니다.
- 백엔드: Kotlin/JVM 17, `desktop/src/main/kotlin/kr/snu/youngmini/desktop`. Android의 플랫폼 독립 eTL/Codex/MCP 코드를 공유합니다. UI와 JVM은 전용 stdio JSON 파이프로 연결합니다.
- 본인 ChatGPT 계정으로 공식 Codex rust-v0.170.0/app-server/code-mode host를 사용합니다. API 키나 앱 운영 서버를 요구하는 구조로 바꾸지 않습니다. Android의 ‘채팅 화면이 열릴 때만 Codex 실행’ 규칙은 Windows 트레이 동작에 그대로 적용하지 않습니다.
- Windows 사용자 데이터: `%LOCALAPPDATA%\YoungMini`. 로그인/eTL 토큰은 DPAPI 암호화, 대화·프로젝트·일정·알림 기록·파일은 로컬 저장입니다. 휴대폰과 자동 동기화하지 않습니다. 다른 앱의 로그인·대화·로그를 읽지 않습니다.

## 0.5.2 GitHub 배포와 새 버전 알림

- `electron/updates.cjs`가 GitHub 공개 releases 목록에서 windows-vX.Y.Z 태그와 정확히 일치하는 업로드 완료 ZIP만 선택합니다. Android 최신 endpoint를 Windows 조회에 사용하지 않습니다. 실행 시와 6시간마다 확인하며 캐시·요청 합치기·오프라인 보존·재시도·24시간 미루기를 지원합니다. 전용 비영구 공개 조회 세션을 사용하고 eTL/ChatGPT 토큰을 보내지 않습니다.
- `SchoolFeatures.settings.updates`가 기본 true이며 설정에서 끕니다. 수동 확인은 꺼진 상태에서도 가능합니다. `UpdateNotice.tsx`는 배너/변경 사항/정확한 버전의 다운로드 버튼을 표시합니다. Windows 알림 클릭도 같은 화면을 엽니다. 알림 끄기는 자동 조회·배너·새 Windows 알림에 적용합니다.
- 알림 기록과 버전 중복 방지는 `Agenda.windowsUpdate`가 원자적으로 저장합니다. 이 private 명령은 렌더러 명령 허용 목록에 넣지 않습니다. 다운로드는 확인한 Windows ZIP URL로만 이동하며 설치된 버전의 오래된 대기 알림은 띄우지 않습니다.
- `.github/workflows/windows-preview.yml`은 수동 검증과 windows-v* 태그의 테스트→ZIP/체크섬 검증→draft 자산 업로드→정식 게시를 처리합니다. `--latest=false`로 Android 최신 릴리스와 youngmin.apk 별칭을 유지합니다. 배포 태그는 UI 버전과 같아야 하며 이미 공개한 배포를 덮어쓰지 않습니다.
- 실제 앱의 `updates-native.cjs`는 합성 릴리스로 자동 확인→지속 알림 기록→네이티브 알림 dispatch/click→다운로드 선택, 6시간 캐시/재시작/하루 미루기/끄기를 확인합니다. 이 테스트는 Windows 알림 API를 fixture로 대체하므로 실사용 배너를 눈으로 확인한 것으로 보고하지 않습니다.
- 고정 직접 링크: Windows `/releases/download/windows-latest/youngmin-windows-x64.zip`; Android `/releases/latest/download/youngmin.apk`. Windows의 버전별 원본 ZIP도 유지합니다. `publish_windows_download.py`가 정식 버전 중 가장 높은 Windows ZIP의 크기·SHA-256을 검증한 뒤 별칭 자산을 업로드/검증하고 교체합니다. 업로드 실패 시 기존 고정 ZIP은 보존하며 태그를 강제 이동하지 않습니다. 배포 작업은 직렬화하고 고정 릴리스도 latest=false로 Android Latest를 보존합니다. 로컬 exe는 package-check-v0.5.2/YoungMini/YoungMini.exe입니다.

## 0.5.0 후속 구현

- SchoolFeatures/SchoolTools와 src/portable이 시간표·통계·공개 자료와 설정을 연결합니다. Agenda는 반복/회차별 완료·제외/시험 정보와 추가 알림을 처리합니다. 별도 school.json에 수업·통계·캐시·설정을 저장합니다.
- LocalApprovals는 변경 미리보기→현재 대화의 확인→재검증→반영입니다. 취소/90초/이탈/중단/자료 변경은 미승인입니다. eTL 쓰기나 임의 셸을 열지 않았습니다.
- FileTools/OfficeFiles/OfficeRenderer/BundledSkills는 스킬 7종과 실제 고정 도구입니다. Apache POI 6.0.0으로 DOCX/XLSX/PPTX 생성·검사·원본 보존 편집을 합니다. 원본 출력은 설치된 Office의 읽기 전용 사본으로 처리합니다. 매크로·ActiveX·임베딩·외부 Word 필드를 거부하고 외부 관계를 제거합니다. 사용 중인 PowerPoint는 건드리지 않고 Word/Excel도 사용자 새 문서가 있으면 응용 프로그램 전체를 종료하지 않습니다.
- 문서 첨부는 경로로 읽고 정규화된 사진만 localImage로 전달합니다. 생성 이미지 완료 이벤트도 관리 파일 카드로 저장합니다. CalendarFiles의 ICS는 오프라인 파일 교환이며 자동 동기화가 아닙니다.
- electron/lifecycle.cjs가 설정·트레이·펫 우클릭의 재시작을 처리합니다. JVM EOF로 전체 세션 저장과 Codex 종료를 기다린 뒤 app.relaunch/app.exit로 창을 교체합니다. 제한 시간 이후에는 해당 백엔드만 종료합니다. 진행 중 답변은 중단됩니다. 렌더러가 멈춰도 트레이 콜백을 사용합니다.
- 실제 exe의 수업·통계·반복 일정, DOCX 입력→Word 출력→오른쪽 PDF 미리보기와 기존 사진/PDF/펫을 확인했습니다. restart-native.cjs는 실제 대화창을 무한 루프로 멈춘 뒤 네이티브 재시작으로 이전 프로세스 종료·새 백엔드 잠금·이름/세션/초안을 확인합니다. 모두 별도 테스트 데이터이며 실계정을 사용하지 않았습니다.
- 로컬 실행 링크는 desktop/build/package-check-v0.5.0/YoungMini/YoungMini.exe입니다. 기존 0.4.4 이하 폴더를 덮어쓰지 않았으며 사용자에게 절대 경로 exe 하이퍼링크를 제공합니다. 실제 학생 로그인/자동 발급·모델 응답 품질·실제 IME/다중 모니터는 별도입니다.

## 사용자 확정 UI

1. **왼쪽 프로젝트/세션 → 가운데 대화 하나 → 필요할 때 오른쪽 파일 미리보기** 배치입니다. 여러 대화를 나란히 보여주는 UI는 취소했습니다.
2. 여러 세션은 **백그라운드에서 병렬 실행**합니다. 프로젝트나 세션을 옮겨도 작업과 결과는 원래 세션에 남습니다. 최대 3개 제한이 없으며, 저장된 세션도 100개로 잘라 읽지 않습니다.
3. 프로젝트는 **통합 프로젝트 + 2026년 2학기 과목별 프로젝트 + 커스텀 추가**입니다. 통합은 26-2 전체 과목, 과목 프로젝트는 해당 과목, 커스텀은 이름/공통 지침/0개 이상의 연결 과목을 가집니다. 커스텀에 여러 과목을 연결할 수 있습니다.
4. 자료 범위와 지침은 프로젝트에서 공유하지만 **각 세션의 대화 이력은 독립**입니다. 모델과 추론 강도(effort), 초안도 세션별로 유지합니다.
5. 별도 강의자료/파일 목록 탭은 제거했습니다. 채팅으로 자료를 요청하면 찾아서 받습니다. **명시적으로 요청한 원본과 완성된 결과물만 파일 카드로 표시**하고, 답변을 만들며 참고용으로 다운로드한 파일은 완전히 숨깁니다.
6. 파일 카드를 누르면 오른쪽 미리보기 탭을 엽니다. 여러 파일 탭을 오가고 닫을 수 있으며 모두 닫으면 대화가 넓어집니다. PDF.js로 PDF 쪽 이동/확대/텍스트 선택, 이미지와 문서 추출 텍스트를 표시합니다. 외부 앱 열기와 다른 이름으로 저장도 있습니다.
7. 시험 대비 자료/문제집 등을 요청하면 **실제 A4 PDF**를 만듭니다. Markdown/KaTeX를 숨겨진 Chromium 창에서 로컬 인쇄하고 도구 `make_pdf` 결과를 소유 세션에 연결합니다. 답변의 ‘PDF로 받기’도 같은 경로를 사용합니다.
8. 답변은 Markdown + 수식 뷰어입니다. Enter 전송, Ctrl+Enter/Shift+Enter 개행이며 한글 IME 조합 중 Enter는 전송하지 않습니다. 작은 고정 설명 문구로 화면을 복잡하게 만들지 않습니다.
9. 펫은 **대기·작업·완료 모션**을 지원합니다. 기존 SVG를 분리해 초당 약 8포즈로 움직입니다. 대기 숨쉬기/눈 깜빡임, 작업 고개/팔, 성공 시 1.5초 손인사입니다. 실패·중단은 축하하지 않으며, 다른 작업 중에는 작업 모션을 우선합니다. 드래그와 움직임 감소 설정을 존중합니다. 말풍선과 내용은 펫 위 중앙 정렬이며 꼬리가 있습니다. 프로젝트 이름/세션 이름/작업 상태를 표시하고 클릭하면 정확한 세션을 엽니다. 여러 말풍선은 스크롤합니다.
10. 처음 실행하면 **안녕하세요 영민이예요 → 계정 연결 → 알림 설정**을 보여줍니다. 장황한 프로젝트/보관/첫 조회 설명은 UI에서 제거했습니다. 연결은 건너뛰고 설정에서 다시 할 수 있습니다. 설정은 제목/스크롤 본문/고정 하단 버튼으로 나뉩니다.

## 0.4.4 세션과 PDF 수정

세션 옆 연필·휴지통이 항상 보이며 이름(40자) 변경과 삭제 확인을 지원합니다. 해당 세션의 답변/PDF 중에는 비활성화합니다. 삭제는 저장 mutex 이후 이력을 지우고 늦은 초안/종료 저장으로 복원되지 않게 합니다. 다른 실행 세션은 계속되고 같은 프로젝트의 마지막 세션을 지우면 빈 세션을 엽니다. 삭제된 세션의 미리보기·작업 알림도 정리합니다. 내려받은 파일 사본 자체를 삭제하는 동작은 별도입니다.

`DocumentReader`가 etl-files만 허용해 generated의 완성 PDF 재검수가 실패했습니다. Windows 전용 경로 검사가 etl-files/generated/uploads의 내부 실경로만 허용하도록 수정했습니다. make_pdf는 쪽 수와 재검수 방법을 돌려줍니다. 설정의 버그 신고는 https://open.kakao.com/o/sR3ebBYi 입니다.

사용자가 Android 대조/이식과 학습·파일 스킬 전체 범위를 선택했습니다. 0.5.0의 대응 및 Windows/호스트 전용 기능 차이는 `FEATURE_PARITY.md`에 있습니다. Android 기준은 원본 main 0.3.8(025ac84)이며 순수 로직은 desktop/src/portable에 별도 패키지로 두었습니다.

## 0.4.3 홈 미세 조정

목록 4개 이하는 자연 높이로 표시해 내부 스크롤바가 뜨지 않습니다. 5개 이상은 360px 범위에서 스크롤하고 좁은 창에서는 전체 높이를 표시합니다. 소개 문구 “나의 일정과 학습 현황을 한곳에서”는 삭제하고 eTL 확인 정보만 유지합니다.

## 0.4.2 구분선 홈

상단 요약 카드 3개를 제거했습니다. 마감/시험/오늘 할 일/새 공지를 동일한 너비의 2×2 격자로 놓고 중앙 구분선만 표시합니다. 박스 배경/테두리/둥근 모서리/그림자는 제거했습니다. 작은 창에서는 한 열로 전환하고 실행 중 대화는 격자 아래에 유지합니다. 0.4.1에서 키운 글씨를 유지합니다.

## 0.4.1 홈 글씨

홈의 항목 제목은 16px, 날짜·보조 설명은 14px, 카드 제목은 18px입니다. 상단 날짜와 요약 숫자도 4px 키웠습니다. 홈 전용 CSS만 조정해 프로젝트/대화/펫 배치는 유지합니다.

## 0.4.0 홈과 연결

- 기본 화면은 `Dashboard.tsx`의 홈입니다. 다가오는 7일 마감, 최근 7일 읽지 않은 공지, 한국 시간의 오늘 할 일을 표시하고 시험은 오른쪽에 따로 정리합니다. 기존 프로젝트/대화/미리보기 구조는 유지합니다. 홈 왕복 시 초안·첨부·작업을 보존합니다.
- `Agenda.kt`는 첫 조용한 조회에서도 공지 목록을 캐시하며 읽음 상태와 실패한 과목의 캐시를 보존합니다. OS 알림은 이후 새 공지/점수 변경부터 보냅니다.
- eTL 설명은 **과목을 가져와요.**입니다. **서울대 로그인으로 연결**은 `etl-login.cjs`에서 비영구 격리 세션/Node 없는 샌드박스 창으로 SNU HTTPS만 허용합니다. 사용자가 직접 로그인하며 앱이 비밀번호를 수집하지 않습니다. 고정 토큰 생성 POST만 허용하고 API 404에서만 프로필 경로로 한 번 대체합니다. 발급 토큰은 쿠키 없는 Bearer 확인 후 전용 stdin으로 JVM에 전달하고 DPAPI 저장 응답을 기다립니다. UI 렌더러에 토큰/쿠키를 주지 않습니다. 취소/종료/실패 시 임시 저장소를 정리하고 수동 붙여넣기를 유지합니다.
- 실제 서울대 계정 자동 발급은 아직 검증하지 않았습니다. 자동화는 로컬 HTTPS 응답 fixture로만 검사했습니다.
- 0.4.0 ZIP의 714개 파일 체크섬 검사를 통과했습니다. `build/package-check-v0.4.0/YoungMini/YoungMini.exe`를 실제 실행해 홈/환영/설정/프로젝트/초안/일정/사진/PDF/펫 검사를 통과했습니다. UI 25개, Node 12개, JVM 108개 중 107개 통과/선택적 HWP 1개 스킵입니다. 사용자에게는 위 exe의 절대 경로 하이퍼링크를 제공합니다.

## 알림과 개인 일정

- 채팅창을 닫으면 **트레이에서 계속 실행**합니다. 완전 종료는 트레이 또는 설정의 ‘영민이 종료’입니다. PC가 꺼지거나 절전 중이면 제때 알릴 수 없고 다시 실행할 때 놓친 일정을 확인합니다. Windows 자동 시작은 추가하지 않았습니다.
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
python desktop/scripts/verify_package.py desktop/dist/youngmin-windows-x64-v0.5.0.zip
```

- ZIP/JRE/Codex/`node_modules`/빌드 결과는 Git에 넣지 않습니다. ZIP은 위 명령으로 재생성합니다. 공식 배포 바이너리는 고정 SHA-256으로 검증합니다. Git 푸시가 릴리스 게시를 뜻하지 않습니다.
- `npm start`로 개발 실행하려면 셸이 사용할 JRE와 lib도 준비해야 합니다. 기본 경로는 `desktop/build/shell-dev/jre`, `lib`이며 `YOUNGMIN_DESKTOP_HOME`으로 동봉 JRE/lib/runtime이 있는 `YoungMini` 폴더를 지정할 수 있습니다. 테스트는 반드시 별도 `YOUNGMIN_DATA_HOME`을 사용해 실제 사용자 로그인/대화를 건드리지 않습니다.
- `electron-smoke.cjs`: 실제 Electron + JVM/Codex로 설정, 프로젝트/세션 전환, 개인 일정, PDF 생성/미리보기, 펫과 중앙 말풍선을 검사합니다. `YOUNGMIN_SMOKE_APP`으로 ZIP에서 추출한 `resources/app`을 선택할 수도 있습니다.
- `startup-smoke.cjs`: Linux 테스트용 Java 래퍼로 백엔드의 첫 출력을 보류해 시작 순서 오류를 재현/검사합니다. `YOUNGMIN_SMOKE_JAVA` 또는 `JAVA_HOME`으로 JDK를 지정합니다. Linux에서는 Xvfb와 DISPLAY가 필요합니다.
- 0.3.2는 펫 애니메이션을 검사합니다. 이전의 정지 강제 검사를 되살리지 않습니다. `PetMotion.ts`, `Robot.tsx`, `pet-motion.css`가 상태 전환과 동작을 담당합니다.
- 이전 Linux 검증: JVM 104개 중 102개 통과/2개 스킵(Windows DPAPI·선택적 실제 HWP 샘플). 최종 UI 13개/Node 경계 3개 통과. 실제 Electron 실행, ZIP에서 추출한 셸/UI/lib의 느린 시작, 이전 대화·초안 복원도 통과했습니다.
- **Windows 실기기 로그인, DPAPI 복원, 시스템 알림, Windows IME·배율·다중 모니터는 이 Linux 환경에서 실측하지 못했습니다.** 로컬 Windows에서 확인할 때 검증 범위를 과장하지 않습니다. 0.3.1 수정본은 아직 사용자의 Windows 재확인 답변을 받지 않았습니다.

## 0.3.4 모션 중단의 실제 원인

일반 실행에서 Windows의 prefers-reduced-motion:reduce가 참이었습니다. Playwright 기본 에뮬레이션이 이를 가려 이전 두 번의 검증에서 놓쳤습니다. 사용자 옵션 실수가 아닙니다. 펫 모션 설정에 system(기본)/on/off를 추가하고 appearance.json에 저장합니다. OS를 바꾸지 않고 ‘항상 켜기’로 펫만 움직일 수 있습니다. 움직임 줄이기 감지 시 설정에 이유를 표시합니다. CSS의 두 곳 reduced-motion 전역 규칙이 모두 이 선택을 따르도록 수정했습니다. 추가 요청으로 펫 우클릭에 채팅 열기/사용자 이름 종료 메뉴를 붙였고 오른쪽 버튼은 드래그/채팅 열기를 유발하지 않습니다. 설정 창 제목도 이름 변경에 따라 ‘계종이 설정’처럼 바뀝니다.

`native-motion.cjs`는 자동화 없이 Electron 직접 실행으로 실제 OS 조건에서 정책별 픽셀 변화를 검사합니다. GPU 비활성화나 미디어 강제 설정을 하지 않습니다. Windows 전체 설정은 바꾸지 않았습니다. 0.3.4 당시 제안한 홈 대시보드는 이후 사용자 요청으로 0.4.0에 구현했습니다. 별도 테스트 데이터로 실제 마감 알림을 한 번 보여 주었고 앱 내 알림 캡처를 남겼습니다.

## 0.3.3 펫 모션 가시성 수정

사용자가 모션이 보이지 않는다고 보고했습니다. 실제 0.3.2 실행 파일과 강제 미디어 설정 없는 테스트에서 CSS 프레임은 진행됐지만, 대기 동작은 1~2px라 잘 보이지 않았습니다. 대기 고개 4도/몸 3px/팔 10도로 조정했습니다. 펫 창에 backgroundThrottling=false를 지정해 포커스 없는 창에서도 프레임을 유지하며, 펫 표시를 끄면 CSS 모션을 일시 정지합니다. 실제 창 검사는 reducedMotion을 강제로 덮어쓰지 않고 메인 창을 숨긴 상태에서 팔의 변화를 측정합니다. Windows 움직임 감소 설정은 따릅니다. 기존 0.3.2가 트레이에 살아 있으면 새 exe는 기존 앱만 다시 표시하므로 완전히 종료한 뒤 새 버전을 실행해야 합니다.

## 0.3.2 로컬 후속 작업

- 원본 작업 폴더는 `main`(`025ac84`)이며 `docs/contest/`, `tmp/`를 보존했습니다. 구현은 기준 `6dc914c`에서 만든 `codex/windows-pet-motion`, `tmp/windows-pet-motion` 작업 폴더에 있습니다. main으로 강제 전환하거나 복사하지 않았습니다.
- 하단 일정/설정은 20px SVG 선 아이콘(같은 1.6 두께)과 15px 글씨로 변경했습니다. 전체 배치는 유지합니다.
- 설정에서 데스크톱 비서 이름을 바꿉니다. `appearance.cjs`가 기본값·검증·저장을 담당합니다. `NameContext`로 UI와 펫에 반영하며 백엔드의 다음 턴 지침에도 이름을 전달합니다. 앱 ID와 사용자 데이터 폴더는 바꾸지 않습니다.
- 사진 선택/붙여넣기/드래그, 세션별 첨부 초안 저장, 취소, 사진만 전송, 입력·결과 이미지 썸네일과 기존 미리보기를 연결했습니다. Electron은 PNG로 재인코딩하고, 경로를 UI에서 받지 않습니다. JVM은 등록된 uploads UUID 사본만 localImage로 전달합니다.
- 예시 질문은 여러 후보 중 한 번에 하나만 랜덤으로 표시하고, 세션 전환 후에도 유지합니다. 누르면 초안에만 채웁니다. 연결 과목 범위를 따릅니다.
- 기존 npm 잠금 파일에 잘못된 간접 버전/체크섬이 있어, 최상위 고정 버전은 유지한 채 공식 npm 메타데이터로 재생성했습니다. Windows에서 기존 공용 EtlFiles의 symlink 경계·구분자·절대 경로 검사도 보완했습니다.
- Windows JDK 17 백엔드 107개 중 106개 통과/실제 HWP 샘플 1개 스킵. DPAPI 단위 검사와 실제 Codex → 로컬 모델로 이미지 전달·MCP·PDF 검사를 포함합니다. 실제 학생 계정이나 OpenAI 호출은 사용하지 않았습니다.
- 실제 Windows Electron에서 설정·프로젝트/초안·개인 일정·실제 한글/수식 PDF·미리보기·사진 가져오기/썸네일/미리보기·이름 저장·펫 움직임/말풍선 중앙 정렬을 확인했습니다. Windows 실제 로그인/갱신·시스템 알림·IME 실측·배율/다중 모니터는 여전히 별도입니다.

## 다음 세션에서 지킬 것

요구사항을 처음부터 다시 인터뷰하거나 UI 배치를 임의 변경하지 않습니다. 불확실한 새 결정만 짧게 묻습니다. 변경한 UI는 직접 실행해 확인합니다. 여러 로컬 세션이 같은 저장소에서 일할 수 있으므로 담당 범위를 확인하고 다른 세션의 변경을 덮어쓰거나 reset/clean하지 않습니다.

이번 인계 푸시는 Windows 소스/문서/수동 CI와 빌드에 필요한 공용 Queries·LinkedFileAccess·XlsxText 및 관련 테스트를 포함합니다. 별도로 진행되던 Android `EtlService.kt`/`EtlTools.kt` 수정, 디자인 실험, 로컬 도구/작업 디렉터리는 합쳐 넣지 않습니다. 작업 시작 시 실제 `git status`를 확인합니다.
