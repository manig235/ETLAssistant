# 배포(GitHub Release)와 업데이트 알림

2026-11-22 사용자 결정: 업데이트가 잦아서 APK를 GitHub Release로 올리고, 영민이가 직접 새 버전을 확인해 알린다(Obtainium은 쓰지 않는다).

## 앱 쪽 동작 (`update/`)

- `GET https://api.github.com/repos/manig235/ETLAssistant/releases/latest` 하나만 보낸다. 토큰·계정·기기 정보 없음,
  User-Agent `youngmin-android`, 연결 8초/읽기 10초, 리다이렉트 안 따라감, 응답 512KB 상한.
- 언제: 채팅 화면을 열 때(마지막 확인 후 6시간이 지났으면) + `UpdateCheckWorker` 하루 한 번(네트워크 제약 없이 예약: 앱 UID 네트워크가 막히면 CONNECTED 작업은 시작되지 않는다. 알림 확인 워커와 별개).
  재부팅·앱 업데이트 때 `SystemEventReceiver`가 다시 예약한다.
- 초안·사전 배포(prerelease)는 무시한다. 태그는 `v<숫자.숫자…>`만 읽고 지금 versionName과 숫자 단위로 비교한다.
- 알림 채널 `update_v2`("앱 업데이트", HIGH), 버전마다 한 번. 0.2.3부터 알림·채팅 팝업의 "받기"·설정의 새 버전 링크를 누르면
  확인한 태그의 APK 직접 다운로드 주소(`https://github.com/manig235/ETLAssistant/releases/download/<태그>/<파일>.apk`)를 연다.
  Release 페이지를 거치지 않고 브라우저가 APK를 다운로드하며, 설치는 사용자가 받은 파일을 열어 진행한다.
- APK 파일 이름을 확인 결과에 함께 저장한다. 앱은 `youngmin-v<버전>.apk`를 선택하며, 버전 없는 별칭이 먼저 나와도 고르지 않는다.
  이전 저장값의 파일 이름이 없거나 `youngmin.apk`이면 배포 규칙(`youngmin-v<버전>.apk`)으로 복원한다.
  APK가 없는 릴리스는 업데이트로 안내하지 않고, 다른 저장소·APK가 아닌 파일·경로 이동이 들어간 링크는 열지 않는다.
- 설정: "기타 → 업데이트 알림"(기본 켬, 끄면 워커 취소·앱 시작 확인 안 함), "앱 정보 → 업데이트 확인"(직접 누르면 꺼져 있어도 확인)과
  새 버전이 있으면 "새 버전 0.1.8이 있어요" 링크.
- 0.2.5부터 수동 확인 결과에 설치된 버전과 최신 공개 버전을 표시한다. 새 버전 링크가 있어도 다시 확인할 수 있고,
  요청에 `Cache-Control: no-cache`를 보낸다. 응답을 해석하지 못했거나 APK가 없거나 404이면 확인 실패로 표시하며 "최신"으로 처리하지 않는다.
- 0.2.3의 변경은 APK 직접 다운로드에 관한 것으로 버전 감지와는 별개다. 같은 버전은 업데이트로 알리지 않으며,
  이미 배포한 APK를 고칠 때는 versionName·versionCode를 모두 올려 새 태그로 배포한다.

## 릴리스 규칙

- 태그: `v<versionName>` (예: `v0.1.8`). 버전 올림 커밋을 main에 push한 뒤 그 커밋에 태그를 단다.
- 제목: `영민이 <버전>`.
- asset: `youngmin-v<버전>.apk`와 `youngmin-v<버전>.apk.sha256`(`sha256sum` 출력 형식). `~/shared/`에 만든 것과 같은 파일.
- **사용자 요청(2026-11-23): 앞으로 다운로드 파일명은 반드시 버전을 포함한다.** 릴리스 본문·채팅 안내·앱 내 업데이트의 다운로드 링크는
  `…/releases/download/v<버전>/youngmin-v<버전>.apk`를 쓴다. `scripts/github-release.sh`도 이 링크를 본문에 자동으로 넣는다.
- 버전 없는 `youngmin.apk`는 이미 공유된 고정 링크를 위한 호환용으로만 올린다(`~/shared/youngmin.apk`, sha256 같음).
  새 다운로드 안내에는 버전 없는 고정 링크를 쓰지 않는다.
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
- [youngmin-v0.1.8.apk 다운로드](https://github.com/manig235/ETLAssistant/releases/download/v0.1.8/youngmin-v0.1.8.apk)

받아서 설치하세요. 브라우저나 파일 앱에서 "출처를 알 수 없는 앱 설치"를 한 번 허용해야 할 수 있어요.

- 0.1.6 이상을 쓰고 있다면 그대로 업데이트돼요.
- **0.1.5 이하를 쓰고 있다면** 서명 키가 바뀌어서 업데이트되지 않아요. 영민이를 지우고 다시 설치해 주세요.
  이때 폰 안의 eTL 토큰·ChatGPT 로그인·내 마감·대화가 지워져요.

서울대학교의 공식 앱이 아니에요. sha256은 `youngmin-v0.1.8.apk.sha256`에 있어요.
```
