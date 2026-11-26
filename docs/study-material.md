# 시험 자료 만들기와 공개 기출 자료 (2026-11-27)

"시험 자료 만들어줘"라고 하면 비서가 (1) 그 과목의 eTL 자료와 강의계획서, (2) 허용 목록에 있는 공개 사이트의 공개 라이선스 기출·연습 문제
(지금은 MIT OpenCourseWare), (3) 사용자가 직접 첨부한 족보·기출 파일을 모아 `make_pdf`로 PDF를 만든다
(시험 범위 요약, 핵심 개념, 연습 문제와 풀이, `## 출처`).

## 하지 않는 것 (사용자 결정)

- 학생이 운영하는 족보 사이트(예: "CSE archive") 크롤링·로그인. 앱은 시험 파일을 모으거나 다시 나누지 않는다.
- 사용자의 족보는 기존 첨부와 "공유 → 영민이"로만 받는다.
- 공개 자료를 이 과목(서울대 강의)의 기출이라고 말하지 않는다.

## 도구

| 도구 | 하는 일 |
|---|---|
| `study_search(query, course_id?, materials?, site="mit_ocw", limit?)` | `course_id` 없음: 주제(영어)로 과목 검색. 과목 번호·학기·수준·강사·있는 자료 종류·`has_exam_solutions`. `course_id` 있음: 그 과목의 시험·풀이·문제 세트 목록(`format`, `fetchable`, `url`). `materials`: `exams_and_problem_sets`(기본), `exams`, `problem_sets`, `lecture_notes`, `all` |
| `study_fetch(url, max_chars?)` | 자료 페이지·PDF 주소면 PDF를 `etl-files/study/`에 받는다(`workspace_path`, `pages`, `source`). 일반 페이지면 본문 글과 같은 사이트 링크. 비서는 `etl_read_file`·`etl_render_pages`·`etl_view_image`로 읽는다 |

- 받은 PDF는 강의계획서 첨부처럼 비서만 읽는다(`EtlFiles.isPrivateRead`: 폰 저장·파일 카드·쪽 미리보기 없음). 14일 지난 작업 파일 정리 대상이다.
- 결과의 `source`: 사이트, 자료 페이지·파일 주소, 자료 제목, 과목명, 과목 번호, 학기, 수준, 강사, 라이선스(JSON-LD `license`가 BY-NC-SA 4.0이 아니면 "확인 필요"), 출처 문구 형식.
- 설정 → 대화 → "공개 기출 자료 찾기"(기본 켬, Android prefs `study_settings`/`publicExams`, iOS UserDefaults 같은 이름). 끄면 두 도구 모두
  "공개 기출 자료 찾기가 꺼져 있어요. 사용자에게 앱의 설정 → 대화 → '공개 기출 자료 찾기'를 켜 달라고 안내하세요."를 돌려준다.
- 지침(AGENTS.md "시험 자료 만들기"): 사용자가 시험 자료를 요청했을 때만 외부 조회, 순서(범위 → 주제 검색 → 시험·풀이 받기 → 첨부 족보 → `make_pdf`),
  출처 표기(MIT OCW 문구, 같은 라이선스로만 나눔), 문제마다 변형/그대로/직접 만듦/사용자 첨부 표시, "기출"이라는 말을 지어내지 않기, 과목 수준에 맞추기,
  외부 내용은 신뢰하지 않는 데이터.

## MIT OCW 엔드포인트 (이 리눅스 서버에서 curl로 확인)

OCW 검색 화면(`ocw.mit.edu/search/`)은 자바스크립트로 그리는데, 번들(`static_shared/js/www.*.js`)을 보면 MIT Learn API(`https://api.learn.mit.edu`)를 쓴다.
이 API는 로그인·쿠키 없이 GET으로 JSON을 준다.

- 과목 검색: `GET https://api.learn.mit.edu/api/v1/learning_resources_search/?q=…&offered_by=ocw&resource_type=course&course_feature=Exams&course_feature=Problem%20Sets&limit=N`
  - 같은 이름의 `course_feature`는 OR(예: "linear algebra" 77과목 → Exams 27, Problem Sets 58, 둘 중 하나 63).
  - 결과: `id`(= 아래 `resource_id`), `title`, `url`, `course.course_numbers[].value`, `runs[0].semester/year/level/instructors`, `course_feature`, `license_cc`, `description`.
  - 한국어 검색어는 0건이라 영어로 찾게 안내한다(결과가 없고 검색어에 한글이 있으면 `hint`).
- 과목 안 자료: `GET https://api.learn.mit.edu/api/v1/content_file_search/?resource_id=5611&content_feature_type=Exams&content_feature_type=Exam%20Solutions&limit=50`
  - 결과: `title`, `url`(자료 페이지 `…/resources/<이름>/`), `content_feature_type`, `content_type`(page/pdf/file), `file_type`, `file_extension`, `run_title`, `semester`, `year`, `course_number`.
  - 같은 자료 종류에 zip 같은 것도 섞여 온다(`fetchable=false`로 표시).
- 자료 페이지 HTML: 머리의 `og:title`("Final Exam, Fall 2009 | Signals and Systems | … | MIT OpenCourseWare"), JSON-LD `license`,
  `course-number-term-detail`("6.003 | Fall 2011 | Undergraduate"), `course-info-instructor`, 본문 `#main-course-section`, 내려받기 `<a class="download-file" href="/courses/…/…pdf">`.
- PDF: `https://ocw.mit.edu/courses/<과목>/<해시>_<이름>.pdf`, `content-type: application/pdf`, 다른 호스트로 리다이렉트하지 않는다(Fastly 캐시, S3 원본 헤더).
- 리다이렉트: 끝 `/` 없는 과목 주소 → 301 같은 주소+`/`, `http://` → 301 `https://`, 짧은 주소(`/6-003F11`) → 307 과목 주소. 모두 `ocw.mit.edu` 안.
- 범위(2026-11-27 집계): OCW 과목 중 Exams 457개, Exam Solutions 240개, Problem Sets 839개, Problem Set Solutions 300개. 시험 풀이가 있는 과목은 절반 정도다.

## 보안·개인정보

- 허용 목록 한 표(`StudySites`): 검색 호스트 `api.learn.mit.edu`(JSON만, 앱이 만든 주소만), 받기 호스트 `ocw.mit.edu`. 사이트를 늘리면 여기에 한 줄과 응답 해석을 더한다.
- HTTPS만(허용 호스트의 `http://`는 `https://`로 올림), 사용자 정보·다른 포트 거부, 리다이렉트는 직접 따라가며 매번 같은 검사(최대 3번).
- 이름이 사설망·루프백·링크 로컬 주소로 풀리면 거부(Android `LocalConnectProxy.isPublicDestination`, iOS `StudyLogic.isPublicAddress` + getaddrinfo).
- 쿠키·토큰·인증 헤더 없음, User-Agent `Mozilla/5.0 (compatible; StudyMaterialFetcher/1.0)`(앱·기기·계정 정보 없음).
- 상한: JSON·HTML 2MB, PDF 30MB(`%PDF`로 시작해야 함), 연결 8초·읽기 15초(받기 30초), 앱 전체 동시 2개. PDF·HTML 말고는(zip, 동영상) 받지 않는다.
- 보내는 것: 비서가 정한 검색어(과목 주제)와 공개 자료 주소. eTL 토큰·eTL 정보·파일·이름·학번은 보내지 않는다. 로그에는 검색어 앞 80자와 주소 경로(쿼리 없음)만.
- 개인정보처리방침: Android 1.3, iOS 1.4(2026-11-27 개정)에 받는 곳·보관·끄는 법을 더했다.

## 코드

- Android: `study/StudySources.kt`(`StudySites`, `StudyLogic` 순수), `study/StudyClient.kt`(HttpURLConnection), `study/StudyTools.kt`(도구, `StudySettings`).
  `EtlTools`가 도구 목록·호출을 넘긴다. 설정은 `SettingsScreen`의 "대화" 절.
- iOS: `AssistantKit/StudySources.swift`, `StudyClient.swift`(URLSession ephemeral, 쿠키·캐시 없음), `ToolboxStudy.swift`(도구, `StudySettings`).
  `Toolbox(studyEnabled:study:)`로 시험에서 바꿔 끼운다. 설정은 `SettingsView`의 "대화" 절.

## 확인

- Android 단위 시험 `StudyLogicTest`(실제 응답 고정 파일 `app/src/test/resources/study/`), `EtlFilesTest`(study 폴더 비공개 읽기).
- 실제 접속 시험 `StudyLiveTest`(`STUDY_LIVE=1`일 때만): 리눅스 서버에서 "signals and systems" 검색 → 6.003 Fall 2011 → 시험 목록 →
  자료 페이지 → "Final Exam, Fall 2009" PDF 291,158바이트 받기, 끝 `/` 없는 주소의 리다이렉트까지 통과.
- iOS `swift test`의 `StudyTests`(같은 고정 파일, 가짜 응답으로 도구 전체 흐름·리다이렉트 거부·사설망 거부·형식 거부).
- 기기 미확인: 실제 폰에서 비서가 지침대로 범위 → 검색 → 받기 → PDF 순서를 따르는지, 출처 표기 품질.
