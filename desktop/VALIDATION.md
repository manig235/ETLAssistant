# Windows 0.5.3 검증

## 0.5.3 설치와 자동 실행

- TypeScript/Vite 빌드, Node 25개, 기존 UI 33개와 새 자동 실행 UI 1개, 배포 별칭 Python 검사 5개를 통과했습니다.
- 새 자동 실행 검사는 설치 경로에 공백이 있는 경우, Windows에서 비활성화한 상태, 등록 실패 표시, 개발/다른 OS에서 등록하지 않기, 설치 파일 우선 업데이트 안내를 포함합니다.
- `startup-native.cjs`는 실제 패키지의 `--startup` 실행에서 메인 창이 숨겨지고 JVM 백엔드가 준비되는지, 창을 열어 설정을 켜고 끄는 IPC가 동작하는지 확인했습니다. 이 검사의 시작 프로그램 API는 모의 처리합니다.
- `package_installer.py`는 전체 ZIP의 체크섬·732개 파일과 런타임·오프라인 UI를 검증한 후 NSIS 오프라인 설치 파일을 만듭니다. 공개 업로드는 하지 않습니다.
- `test_installer.ps1`로 실제 Windows 시작 프로그램 명령, 시작 메뉴·바탕화면 바로가기, 앱 제거 등록을 확인했습니다. 자동 실행 해제 후 재설치해도 꺼짐이 유지되고, 제거하면 시작 프로그램·바로가기·앱 제거 등록이 정리되는 것까지 통과했습니다. 기존 설치가 있으면 중단하며 작업 폴더에서 검사합니다. 실제 재부팅은 수행하지 않았습니다.

## Windows 고정 최신 다운로드

`python -m unittest discover -s desktop/scripts -p test_publish_windows_download.py`로 숫자 버전 정렬, Android/고정 별칭 제외, 미공개/사전 배포/잘못된 주소/미검증 자산 거부, 업로드 실패 시 기존 파일 보존, 검증 후 교체 순서를 검사합니다. 고정 링크는 `/releases/download/windows-latest/youngmin-windows-x64.zip`이며 버전별 배포는 그대로 둡니다. 다음 `windows-v*` 배포부터 자동 갱신합니다.

## 0.5.2 자동 업데이트 알림

Node 경계/로그인/업데이트 21개, UI 33개를 확인했습니다. 새 검사는 Windows/Android 채널 분리, 태그·업로드 상태·URL 일치, 페이지네이션, 동시 요청 합치기, 6시간 캐시/재시작, 오프라인 보존/재시도, 하루 미루기, 손상된 캐시/크기 제한을 포함합니다. JVM은 버전별 알림의 중복 방지와 재시작 보존, 잘못된 주소 거부를 검사합니다.

`updates-native.cjs`로 실제 Windows Electron/JVM에서 합성 Windows 릴리스를 제공해 자동 확인→지속 알림 기록→네이티브 알림 dispatch/click→변경 사항/버전 지정 다운로드, 6시간 캐시·재시작·하루 미루기·알림 끄기와 수동 확인을 검사했습니다. Windows 알림 API는 fixture로 대체하므로 실제 시스템 배너가 눈에 보였다고 주장하지 않습니다. 다운로드도 캡처하여 공개되지 않은 테스트 ZIP을 받지 않습니다. 캡처는 native-windows-update.png입니다.

Android 최신 직접 다운로드는 `/releases/latest/download/youngmin.apk`가 HTTP 200, attachment filename=youngmin.apk를 반환하는 것을 확인했습니다. Windows 게시 시 latest=false를 사용해 이 경로를 유지합니다.

## 0.5.0 학습·파일과 재시작

- Windows JDK 17, 실제 Codex 0.170.0과 설치된 Office에서 검사했습니다. 모델은 로컬 SSE fixture이며 계정/API 키/OpenAI 요청을 사용하지 않습니다.
- 실제 Codex → code-mode host → 인증된 MCP로 자료 읽기·PDF/Word 생성·스킬 목록을 호출했습니다. PDF 쪽 이미지를 image(part)로 전달해 모델 요청의 실제 이미지 입력까지 도달하는지 확인합니다. Base64 텍스트만 출력하는 것과 구분합니다.
- DOCX/XLSX/PPTX를 만들고 설치된 Word/Excel/PowerPoint로 PDF를 내보내 한글·표·수식 계산·출력 이미지를 확인했습니다. 원본 보존 편집, 수식 캐시/오류/외부 수식 거부, XLSX 그림, PDF 양식 값/appearance/원본 보존, 쪽 추출/회전, 차트/이미지/CSV/통계, 스킬 리소스/경로 제한 검사를 포함합니다.
- 반복 회차 완료/제외·ICS 한국어/시간/반복/제외 회차, 수업/휴강/통계/설정 복원, 공지/점수/변경/제출/파일 알림의 조용한 기준·중복·끄기, 대화 참조/외부 사본 보호 정리를 확인했습니다. Android 순수 과제/성적/공개 학습자료 검사도 실행합니다.
- 로컬 쓰기는 다른 대화의 승인 거부, 취소, 시간 초과, 자료 변경 시 거부, 정확히 한 번 적용을 검사합니다. UI는 미리보기/명시적 확인/이탈 취소를 검사합니다.
- UI 기존 30개와 사용자 이름 재시작 1개, Node 경계·자동 발급·문서 입력·Windows 업데이트 자산·재시작 순서 15개를 확인했습니다. Windows 테스트 HTTP 서버는 runner 안에서 소유/종료해 셸 자식이 남는 실행기 문제를 수정했습니다.
- 실제 package-check-v0.5.0의 exe로 기존 홈/프로젝트/초안/일정/한글·수식 PDF/사진/펫을 유지하고 수업·통계·반복 일정·DOCX 첨부와 원본 Word 미리보기까지 확인했습니다. Office 미리보기 검사는 YOUNGMIN_TEST_OFFICE=1일 때 실행합니다.
- restart-native.cjs는 실제 렌더러를 무한 루프로 멈춘 후 트레이 콜백으로 재시작합니다. 이전 프로세스 종료, 새 백엔드의 데이터 잠금 획득, 이름/세션 ID/초안을 확인했습니다. Windows 제한 실행 환경의 Electron 시작·임시 폴더 접근 제약 때문에 실제 GUI 검사는 샌드박스 밖에서 별도 테스트 데이터로 실행했습니다. Electron 자체의 샌드박스를 끄지 않았으며 실제 사용자 앱을 종료하지 않았습니다.
- 캡처: native-word-preview.png, native-school-timetable.png, native-school-scores.png, native-restarted.png, office-skills/{docx,xlsx,pptx}-preview.png, office-skills/chart.png.
- 실제 SNU/ChatGPT 로그인·자동 발급·모델 응답 품질, 실학생 알림 배너 클릭, 실제 한글 IME·다중 모니터는 미검증입니다. fixture 검사를 실계정 검사로 안내하지 않습니다.

실행: gradlew -p desktop test stageWindows → UI build/test → electron-smoke.cjs → restart-native.cjs. 실제 Codex 검사는 YOUNGMIN_TEST_CODEX, Office 검사는 YOUNGMIN_TEST_OFFICE=1을 지정합니다. 실행 폴더는 YOUNGMIN_DESKTOP_HOME/YOUNGMIN_SMOKE_APP/YOUNGMIN_SMOKE_EXE로 선택합니다.

## 0.4.4 세션과 생성 PDF

세션 이름 변경/삭제 확인/작업 중 비활성화 UI를 확인했습니다. JVM은 사용자 제목의 첫 질문 후 유지, 삭제와 초안 저장 경합, 다른 세션 작업 유지, 마지막 세션의 빈 대화 교체를 검사합니다. 생성 폴더의 PDF 텍스트와 1·8쪽 이미지 재검수 및 경로 이탈 거부 검사를 추가했습니다.

## 0.4.3 네 항목 높이와 소개 문구

4개 이하 목록은 자연 높이로 표시하고 5개 이상은 데스크톱에서 360px 이후 스크롤합니다. 일반 소개 문구만 제거하고 eTL 확인 정보는 유지합니다. UI 빌드, 관련 홈 검사 2개와 기존 Node 검사 12개를 통과했습니다.

## 0.4.2 구분선 대시보드

상단 요약 카드 3개를 제거하고 마감/시험/오늘 할 일/공지를 2×2 구분선 격자로 변경했습니다. 넓은 화면은 두 열, 1000px 이하 창은 한 열입니다. 관련 기존 UI 검사 3개(데이터/초안 보존, 반응형/일정 추가/알림 기록, 백그라운드 세션 이동)와 Node 검사 12개가 통과했습니다. UI 빌드와 백엔드 버전 갱신 빌드도 통과했습니다.

## 0.4.1 홈 글씨 확대

홈의 제목/날짜/보조 설명을 키웠습니다. 기존 홈 데이터·대화 보존 검사와 1560/1000/720px 반응형 검사 2개가 통과했습니다. UI 빌드와 기존 Node 경계 검사 12개도 통과했습니다. 백엔드는 버전만 갱신했습니다.

## 0.4.0 홈·환영·자동 연결 (2026-11-28)

- JVM 총 108개: 107개 통과, 선택적 실제 HWP 샘플 1개 스킵. 첫 조회 공지 캐시/읽음 보존/실패/재시작 검사를 추가했습니다.
- UI 25개와 Node 경계·로그인 로직 12개 통과. 홈/대화 왕복 초안 보존, 실행 중 세션, 마감/시험 분리, 할 일 완료, 공지 읽음, 좁은 화면, 같은 세션을 펫에서 다시 열기, 간단한 인사말과 수동 토큰 입력을 확인했습니다.
- 실제 Windows Electron + JVM/Codex에서 새 환영 → 계정/알림 → 채워진 홈 → 기존 프로젝트/초안 → 개인 일정 → 실제 PDF 생성/미리보기 → 사진 → 비서 이름/펫 설정까지 확인했습니다. 데이터는 별도 테스트 폴더입니다.
- `etl-login-native.cjs`는 실제 Electron의 임시 세션/창과 로컬 HTTPS fixture로 로그인 쿠키 → API 404 → 프로필 발급 → 쿠키 없는 Bearer 검증 → 저장 콜백 → 쿠키 삭제/창 닫힘을 검사합니다. Node 없는 샌드박스와 preload 없음도 확인합니다. 실제 학교 계정에는 접속하지 않습니다.
- 실제 SNU 로그인/2차 인증/토큰 자동 발급·학교 정책, 실제 ChatGPT 응답 품질·한국어 IME 수동 입력 검증은 남아 있습니다. 로컬 fixture 통과가 실제 계정 인증 성공을 뜻하지 않습니다.
- 토큰 생성 필드는 공식 Canvas의 `token[purpose]`, `token[expires_at]`와 레거시 프로필 필드를 지원하며 90일 만료를 요청합니다. 근거: [공식 TokensController](https://github.com/instructure/canvas-lms/blob/master/app/controllers/tokens_controller.rb). 실패한 POST를 무조건 재시도하지 않습니다.

화면 기록: `build/reports/native-onboarding.png`, `native-home-dashboard.png`, `dashboard-1000.png`, `dashboard-720.png`.

## 0.3.4 실제 원인과 검증 보완

일반 실행은 Windows의 `prefers-reduced-motion: reduce`가 참이어서 CSS가 모든 펫 애니메이션을 끄고 있었습니다. 0.3.2/0.3.3의 Playwright 실행은 도구가 기본 미디어 조건을 덮어써 이 상태를 놓쳤습니다. 이전 기록의 ‘Windows 설정을 강제 변경하지 않고 검사’는 프레임워크의 기본 에뮬레이션까지 제거한 검사가 아니었습니다.

펫 모션 정책 system/on/off를 로컬에 저장합니다. 기본 system은 OS를 따르고, 사용자가 on을 명시하면 펫만 OS의 움직임 줄이기 조건을 우회합니다. off는 항상 정지합니다. OS 설정은 바꾸지 않습니다. 드래그/숨김 시 일시 정지와 말풍선 중앙 정렬은 유지합니다.

`node desktop/ui/tests/native-motion.cjs`는 브라우저 자동화 없이 Electron을 직접 실행합니다. GPU 끄기/미디어 에뮬레이션 없이 실제 OS reducedMotion 값을 기록하고 세 정책 × 대기/작업/완료의 애니메이션과 캡처 픽셀 변화를 검사합니다. UI는 Windows 움직임 줄이기 안내/정책 전환과 우클릭 처리를 더해 21개, Node 경계 검사는 5개입니다. 설정 저장과 패키지 실행 검사도 포함합니다. 사용자 이름을 반영한 설정 제목과 실제 Windows 펫 우클릭 메뉴 열기도 확인합니다.

별도 예시 일정으로 실제 `Agenda.tick` → `Notification.show` → 표시 응답(delivered=true)을 확인했습니다. 사용자 실제 과제/알림 기록은 건드리지 않았습니다. 앱 내 기록 화면은 `build/reports/deadline-notice-inbox.png`입니다.

## 0.3.3 펫 모션 확인

0.3.2에 모션 코드와 프레임 진행이 있음을 강제 미디어 설정 없는 실제 Windows 실행으로 확인했습니다. 작은 대기 동작을 고개/몸/팔에서 더 식별하기 쉽게 조정했습니다. 0.3.3의 실제 창 검사는 Windows 설정을 강제 변경하지 않고, 펫 창의 background throttling 해제, 메인 창을 숨긴 동안의 팔 변환 변화, 펫 숨김 시 모션 정지와 말풍선 중앙 정렬을 검사합니다. 이전 검사의 reducedMotion 강제 no-preference 설정을 제거했습니다. Windows 자체가 움직임 감소를 요청하면 정지하는 동작은 유지합니다.

## 0.3.2 Windows 로컬 검증 (2026-11-28)

- Windows x64, Temurin JDK 17, Node 24, Electron 45.5.0에서 실제 앱을 실행했습니다. 실제 사용자 데이터 폴더 대신 별도 임시 데이터를 사용했습니다.
- JVM 107개 중 106개 통과, 외부 실제 HWP 샘플이 필요한 1개만 스킵했습니다. Windows DPAPI 단위 검사와 실제 Codex/code-mode host/MCP/PDF 통합 검사를 포함합니다. 로컬 모델 SSE 대체 응답을 사용했고 학생 계정·API 키·OpenAI 호출은 사용하지 않았습니다.
- 사진만 보낸 턴이 `localImage`로 실제 Codex에 들어가 로컬 모델 요청의 `input_image`/PNG 데이터로 전달되는 것을 검사했습니다. 사진별 소유 세션·메시지 연결, 첨부 초안 저장과 복원, 경로 이탈·잘못된 형식·개수/크기 제한도 검사했습니다.
- 브라우저 UI 18개, Node 경계/설정/사진 가져오기 5개를 검사했습니다. 대기/작업/완료/실패 상태 전환, PDF 작업, 손인사 중복 방지, 드래그 중 정지, 움직임 감소, 말풍선 중앙 정렬, 이름 변경과 저장, 선 아이콘/글자 크기, 한 번에 하나인 랜덤 질문, 사진 선택/붙여넣기/드래그/취소/출력 썸네일/미리보기를 포함합니다.
- 실제 Windows Electron + Kotlin/Codex에서 첫 안내·설정·프로젝트 전환·초안·개인 일정·한글/수식 A4 PDF 생성/미리보기·여러 파일 탭을 재확인했습니다. 실제 PNG 가져오기→재인코딩→세션별 첨부→썸네일→오른쪽 미리보기와 이름 저장/펫 동기화·움직임·말풍선 중앙 정렬도 확인했습니다.
- 기존 npm 잠금 파일의 존재하지 않는 간접 버전과 다른 체크섬을 공식 npm 메타데이터로 재생성했습니다. 최상위 고정 라이브러리 버전은 유지합니다. 기존 공용 파일 경로 검사의 Windows 구분자·symlink 해석·절대 경로 오류도 수정한 뒤 전체 JVM 검사를 통과했습니다.
- 실제 ChatGPT/eTL 계정 로그인·토큰 갱신·사진 내용 해석, Windows 알림 배너/클릭, 실제 한국어 IME 입력, 배율·다중 모니터 실측은 여전히 별도입니다. 파일 생성/이미지 전달 경로 검사가 실제 계정의 응답 품질 검증을 뜻하지 않습니다. 사진을 새로 생성하는 도구는 추가하지 않았습니다.

화면 기록: `build/reports/native-photo-preview.png`, `native-pet-motion.png`, `custom-name-outline.png`, `pet-motion-done.png`, `photo-input-output.png`.

## 이전 0.3.1 Linux 검증 기록

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
