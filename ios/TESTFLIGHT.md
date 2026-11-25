# TestFlight 준비와 가상 eTL 테스트

이 변경은 학교 계정 없이 동작하는 가상 eTL과 Apple 비공개 JavaScriptCore 함수 제거를 준비한다. TestFlight 업로드가 완료된 상태는 아니다.

## 테스터가 시작하는 방법

첫 실행의 **가상 eTL로 테스트하기**를 선택한다. 기존 사용자는 **설정 → 학습 데이터 → 가상 eTL로 전환 예약**을 누르고 앱 전환 화면에서 영민이를 완전히 종료한 뒤 다시 연다. 화면 위의 **가상 eTL** 표시로 확인한다. Release에서도 사용할 수 있다.

학교 eTL 토큰은 필요 없다. 채팅에는 본인의 ChatGPT 로그인 또는 OpenAI API 키가 필요하다. 기존 네이티브 ChatGPT 로그인은 공유하지만 실제·가상 대화는 구분한다. 계정별로 서버가 허용하는 모델과 사용량이 적용된다.

다음 질문으로 확인한다.

- “수강 과목과 앞으로 7일 마감, 누락 과제를 알려줘.”
- “회로이론 RC 회로 과제 설명을 읽어줘.”
- “기말시험 공지를 읽고 날짜·장소를 알려줘.”
- “회로이론 강의자료를 찾아 RC 회로 내용을 요약해줘.” (가상 PDF의 영어 본문을 읽는다.)
- “글쓰기 에세이 초안이 제대로 제출됐는지 확인해줘.”
- “내일 오후 6시까지 가상 복습 일정을 추가해줘.” → 변경 승인 후 내 일정 확인. 취소하면 추가되지 않아야 한다.

과목 3개, 과제 5개(미제출·누락·제출·채점 상태), 공지와 PDF/TXT 자료가 있다. 날짜는 실행 시점을 기준으로 생성한다. 고정 답변을 재생하지 않고 실제 AI가 기존 eTL 도구를 호출한다. 지원하지 않는 가상 API는 오류로 끝내며 실제 학교 서버로 대체 요청하지 않는다. 외부 LearningX 게시판은 이 가상 데이터셋에서 제공하지 않는다.

가상 모드의 대화·첨부·내 마감·시간표·캐시는 별도 UserDefaults suite와 `Application Support/Youngmin/FakeETL`, 사용자 다운로드는 `Documents/etl-fake`에 보관한다. 실제 eTL 토큰은 읽거나 덮어쓰지 않는다. 캘린더 접근·내보내기와 위젯 공유 데이터 접근은 가상 모드에서 막는다. 프로필 전환 때 이전 알림 예약을 지우고 현재 프로필 기준으로 다시 계획한다.

**설정 → 학습 데이터 → 가상 데이터 초기화 예약** 후 완전히 종료·재실행하면 가상 데이터만 초기화한다. 실제 데이터와 ChatGPT 로그인은 유지된다. **실제 eTL로 전환 예약** 후 재실행하면 원래 데이터로 돌아간다. 전환을 즉시 적용하지 않는 이유는 진행 중인 도구 호출과 저장소가 다른 프로필에 섞이지 않게 하기 위해서다.

## 비공개 API 제거 확인

JavaScriptCore의 비공개 실행 시간 제한 함수 대신 앱에 소스로 포함한 QuickJS의 공개 interrupt API를 쓴다. QuickJS OS 모듈·JIT·외부 실행 파일을 포함하지 않는다. 비동기 네이티브 도구·쓰기 승인·중단을 유지하고 메모리·실행 시간도 제한한다. CI에서 macOS/iOS 시뮬레이터의 실행 테스트, 실제 Codex delegate 계약, 최종 arm64 앱 링크를 검증한다. 최종 앱의 import에 제거한 두 SPI가 있으면 빌드를 실패시킨다.

## Apple 배포에 별도로 필요한 것

- Apple Developer Program 가입 및 App Store Connect 앱 등록.
- 배포용 Bundle ID·개발 팀·배포 인증서·프로비저닝 프로파일·필요한 App Group 권한 설정.
- 배포 서명한 Release archive와 App Store Connect 업로드 인증. 현재 Actions의 서명 없는 IPA는 Windows Sideloadly 설치용이며 TestFlight에 그대로 업로드할 수 없다.
- 앱과 확장에 설정 저장·앱 파일/사용자 선택 파일 메타데이터·타이머의 required reason API 선언을 포함했다. 앱은 내장 Codex 정적 라이브러리(SQLite·Rust `nix`)가 `statfs`/`fstatfs`/`fstatvfs`를 import하므로 디스크 공간(E174.1)도 선언한다(실제 쓰임은 파일 시스템 종류 확인이라 딱 맞는 이유가 없어 가장 가까운 것을 골랐다). CI의 `.github/scripts/check-privacy-manifests.py`가 각 .app/.appex의 매니페스트 유무와 `plutil -lint`, Mach-O import·selector 기준 미선언 범주를 검사한다. 최종 서명 archive에서 실제 API 사용과 개인정보 공개 내용을 다시 확인한다. [Apple 문서](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api)
- 앱 정보·아이콘·스크린샷·개인정보 공개 내용·지원/개인정보 URL·암호화 관련 질문과 베타 심사 정보 작성.
- 심사 메모에 가상 eTL 진입 절차와 채팅에 OpenAI 로그인이 필요하다는 점을 설명한다. 검증에 전용 계정이 필요하면 승인된 전용 테스트 계정을 App Store Connect 심사 정보에 제공한다. 실제 학교 계정이나 자격 증명을 소스·아티팩트에 넣지 않는다.

이 구현과 자동 테스트는 Apple의 심사 통과나 OpenAI 로그인 경로의 제3자 iOS 배포 승인을 보장하지 않는다. 실제 설치본에서 로그인·AI 도구 호출·승인/취소·모드 전환·초기화를 한 번 더 확인해야 한다.
