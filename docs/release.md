# 배포(GitHub Release)와 업데이트 알림

2026-11-22 사용자 결정: 업데이트가 잦아서 APK를 GitHub Release로 올리고, 영민이가 직접 새 버전을 확인해 알린다(Obtainium은 쓰지 않는다).

## 앱 쪽 동작 (`update/`)

- `GET https://api.github.com/repos/manig235/ETLAssistant/releases/latest` 하나만 보낸다. 토큰·계정·기기 정보 없음,
  User-Agent `youngmin-android`, 연결 8초/읽기 10초, 리다이렉트 안 따라감, 응답 512KB 상한.
- 언제: 채팅 화면을 열 때(마지막 확인 후 6시간이 지났으면) + `UpdateCheckWorker` 하루 한 번(네트워크 제약 없이 예약: 앱 UID 네트워크가 막히면 CONNECTED 작업은 시작되지 않는다. 알림 확인 워커와 별개).
  재부팅·앱 업데이트 때 `SystemEventReceiver`가 다시 예약한다.
- 초안·사전 배포(prerelease)는 무시한다. 태그는 `v<숫자.숫자…>`만 읽고 지금 versionName과 숫자 단위로 비교한다.
- 알림 채널 `update`("앱 업데이트", 기본 중요도), 버전마다 한 번. 누르면 Release 페이지(`https://github.com/manig235/ETLAssistant/…`만)를 브라우저로 연다.
  **앱이 APK를 받거나 설치하지 않는다**(1단계).
- 설정: "기타 → 업데이트 알림"(기본 켬, 끄면 워커 취소·앱 시작 확인 안 함), "앱 정보 → 업데이트 확인"(직접 누르면 꺼져 있어도 확인)과
  새 버전이 있으면 "새 버전 0.1.8이 있어요" 링크.

## 릴리스 규칙

- 태그: `v<versionName>` (예: `v0.1.8`). 버전 올림 커밋을 main에 push한 뒤 그 커밋에 태그를 단다.
- 제목: `영민이 <버전>`.
- asset: `youngmin-v<버전>.apk`와 `youngmin-v<버전>.apk.sha256`(`sha256sum` 출력 형식). `~/shared/`에 만든 것과 같은 파일.
- 같은 APK를 버전 없는 이름 `youngmin.apk`로도 올린다(`~/shared/youngmin.apk`, sha256 같음). 그러면
  `https://github.com/manig235/ETLAssistant/releases/latest/download/youngmin.apk`가 항상 최신 APK를 가리키는 고정 링크가 된다.
  릴리스 본문에는 이 고정 링크와 그 버전 링크(`…/releases/download/v<버전>/youngmin-v<버전>.apk`)를 둘 다 넣는다.
- 초안(draft)이나 사전 배포(prerelease)로 올리면 앱이 알리지 않는다. 시험할 때만 그렇게 쓴다.
- 반드시 전용 키(`~/.android/youngmin-release.jks`)로 서명한 release APK만 올린다. 다른 키면 기존 설치본 위에 업데이트되지 않는다.

## 절차

```
./gradlew testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk ~/shared/youngmin-v0.1.8.apk
(cd ~/shared && sha256sum youngmin-v0.1.8.apk > youngmin-v0.1.8.apk.sha256)
cp ~/shared/youngmin-v0.1.8.apk ~/shared/youngmin.apk
git push origin main
gh release create v0.1.8 ~/shared/youngmin-v0.1.8.apk ~/shared/youngmin-v0.1.8.apk.sha256 ~/shared/youngmin.apk \
  --target main --title "영민이 0.1.8" --notes-file notes.md
# gh가 없으면: GITHUB_TOKEN=… scripts/github-release.sh 0.1.8 notes.md (youngmin.apk는 웹에서 추가) 또는 GitHub 웹에서 같은 규칙으로
```

`GITHUB_TOKEN`은 이 저장소에 contents 쓰기 권한이 있는 fine-grained 토큰. 저장소·셸 기록에 남기지 않는다(예: `read -s GITHUB_TOKEN; export GITHUB_TOKEN`).

## 릴리스 본문 템플릿

```
## 바뀐 점
- …

## 설치
- 항상 최신: https://github.com/manig235/ETLAssistant/releases/latest/download/youngmin.apk
- 이 버전: https://github.com/manig235/ETLAssistant/releases/download/v0.1.8/youngmin-v0.1.8.apk

받아서 설치하세요. 브라우저나 파일 앱에서 "출처를 알 수 없는 앱 설치"를 한 번 허용해야 할 수 있어요.

- 0.1.6 이상을 쓰고 있다면 그대로 업데이트돼요.
- **0.1.5 이하를 쓰고 있다면** 서명 키가 바뀌어서 업데이트되지 않아요. 영민이를 지우고 다시 설치해 주세요.
  이때 폰 안의 eTL 토큰·ChatGPT 로그인·내 마감·대화가 지워져요.

서울대학교의 공식 앱이 아니에요. sha256은 `youngmin-v0.1.8.apk.sha256`에 있어요.
```
