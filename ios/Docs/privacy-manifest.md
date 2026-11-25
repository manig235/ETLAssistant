# 개인정보 매니페스트 (PrivacyInfo.xcprivacy)

App Store Connect는 required-reason API를 쓰는데 이유를 밝히지 않은 번들을 받지 않는다(ITMS-91053, 2024-05-01부터).
기준 문서: <https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api>

## 파일과 타깃

| 번들 | 파일 | 들어가는 방법 |
| --- | --- | --- |
| `Youngmin.app` | `Youngmin/PrivacyInfo.xcprivacy` | 폴더 동기화 그룹(`Youngmin`) → Copy Bundle Resources |
| `YoungminWidgets.appex` | `YoungminWidgets/PrivacyInfo.xcprivacy` | 폴더 동기화 그룹(`YoungminWidgets`) |
| `YoungminShare.appex` | `YoungminShare/PrivacyInfo.xcprivacy` | 폴더 동기화 그룹(`YoungminShare`) |

`project.pbxproj`는 Xcode 16 폴더 동기화 그룹(`PBXFileSystemSynchronizedRootGroup`)이라 폴더에 넣은 파일이 그 타깃의 리소스가 된다. 그래서 프로젝트 파일은 고치지 않았다.
`YoungminShared/`는 세 타깃에 모두 들어가므로 매니페스트를 두면 안 된다(같은 이름의 리소스가 겹친다).

## required-reason API → 이유

| 번들 | 범주 | 이유 | 근거(코드) |
| --- | --- | --- | --- |
| 앱 | UserDefaults | CA92.1 | `UserDefaultsStore`(`.standard`), 테마·첫 실행 표시. App Group `UserDefaults(suiteName:)`은 쓰지 않으므로 1C8F.1은 넣지 않는다(공유는 App Group 폴더의 파일로 한다). |
| 앱 | File timestamp | C617.1 | `modificationDate`(ChatStore, EtlDownloads), `contentModificationDateKey`(FilesCleanup, EtlDownloads), `creationDateKey`(PendingShare: App Group 폴더), 내장 Codex의 `stat`/`fstat`/`fstatat`/`lstat`(CODEX_HOME·작업 폴더, 앱 컨테이너 안). 사용자가 고른 파일은 크기(`fileSizeKey`, 목록에 없음)만 보고 앱 폴더로 복사하므로 3B52.1은 넣지 않았다. |
| 앱 | System boot time | 35F9.1 | 쓰기 승인 90초 제한(`DispatchTime.now().uptimeNanoseconds`), Codex(Rust) 타이머·시간 초과. 모두 앱 안의 경과 시간 계산이고 밖으로 보내지 않는다. 지금 빌드에는 `mach_absolute_time` import가 없고 `systemUptime`은 Rust objc2 바인딩 문자열로만 보인다. 정의가 실제 쓰임과 맞아 미리 선언했다. |
| 앱 | Disk space | E174.1 | 내장 Codex 정적 라이브러리(SQLite, Rust `nix`)가 `statfs`/`fstatfs`/`fstatvfs`를 import한다. 용량을 사용자에게 보여 주거나 보내지 않는다. **판단 사항:** 실제 쓰임은 파일 시스템 종류·플래그 확인이라 Apple 이유 목록에 딱 맞는 것이 없다. 가장 가까운 E174.1(쓰기 가능 여부)을 골랐다. SQLite를 넣은 SDK들이 흔히 고르는 값이다. |
| 위젯 | UserDefaults | CA92.1 | YoungminKit(`UserDefaultsStore`)이 정적으로 링크된다. |
| 위젯 | File timestamp | C617.1 | `PendingShare`(`creationDateKey`, App Group 폴더), YoungminKit 파일 정리 코드. |
| 공유 확장 | UserDefaults | CA92.1 | 위젯과 같다. |
| 공유 확장 | File timestamp | C617.1 | 위젯과 같다. |

Active keyboards(`activeInputModes`)는 선언하지 않았다. Debug 빌드의 내장 Codex에 Rust `objc2-ui-kit` 바인딩 문자열로만 들어 있고, 앱은 키보드에 따라 동작을 바꾸지 않으므로 맞는 이유(54BD.1)가 없다.
Release(최적화·dead strip) 빌드에서도 남는지 확인해야 한다. CI 검사가 이것을 경고로 보여 준다.

## 수집 데이터(NSPrivacyCollectedDataTypes) — 판단 사항

앱에는 개발자 서버가 없다. 그래도 다음 이유로 앱 매니페스트에 두 가지를 선언했다.

- Apple의 "수집"은 기기 밖으로 보내져 실시간 처리보다 오래 접근 가능한 경우다. 개발자뿐 아니라 앱에 넣은 제3자 코드의 업체도 포함한다.
- 앱은 OpenAI의 공식 Codex를 넣어 사용자의 ChatGPT 계정으로 대화를 보낸다. OpenAI는 대화 기록을 그 계정에 남긴다. 사용자 계정이지만 제3자 코드가 보내는 것이므로 보수적으로 수집으로 본다.

| 항목 | Linked | Tracking | 목적 | 내용 |
| --- | --- | --- | --- | --- |
| `OtherUserContent` | 예 | 아니오 | App Functionality | 채팅 메시지, 비서가 읽은 eTL 자료(공지·과제·성적·파일 글), 켰을 때 캘린더 일정 |
| `PhotosorVideos` | 예 | 아니오 | App Functionality | 채팅에 첨부한 사진(사진 보관함·카메라) |

넣지 않은 것과 이유:

- eTL 토큰과 eTL 요청: 사용자가 자기 학교 계정으로 학교 서버에서 자기 자료를 받는 것이다. 개발자나 제3자가 새로 갖게 되는 데이터가 아니다.
- ChatGPT 계정 ID·이메일: OpenAI가 이미 가진 계정 정보를 인증에 쓰는 것이다.
- 업데이트 확인·분석 SDK: iOS 판에는 없다.
- Codex 사용 통계: 안드로이드 판은 `[analytics] enabled = false`, `[feedback] enabled = false`, `[otel] metrics_exporter = "none"`을 명시한다. iOS 내장 브리지(`NativeCodex/bridge/src/lib.rs`)의 설정 덮어쓰기에는 아직 이 세 줄이 없다. analytics는 app-server 기본값이 꺼짐이라 선언하지 않았지만, OTel 지표 기본값(Statsig)이 내장 실행에서 켜지는지는 확인하지 못했다. 배포 전에 브리지에 같은 설정을 넣어야 이 판단이 확실해진다. 넣지 않으면 `ProductInteraction`(Analytics)을 선언해야 할 수 있다.

App Store Connect의 "앱 개인정보 보호" 답변도 위 표와 같게 맞춘다.

## CI 검사

`.github/scripts/check-privacy-manifests.py`:

- `--source ios`: 모든 매니페스트를 `plutil -lint`(macOS)와 `plistlib`로 읽고, 범주별 이유 코드가 Apple 목록에 있는지, 추적이 꺼져 있는지 확인한다.
- `--app <Youngmin.app>`: 앱과 `PlugIns/*.appex`마다 `PrivacyInfo.xcprivacy`가 있는지 확인하고, 번들 안 Mach-O(Debug의 `*.debug.dylib` 포함)의 import 심볼과 Objective-C selector를 읽어 required-reason API를 찾는다. 쓰는데 선언하지 않은 범주가 있으면 실패한다. C 문자열로만 보이는 selector는 경고다.
- 실행 위치: `ios.yml` logic-tests(소스 검사·단위 테스트 `scripts/test-ios-privacy-manifests.py`), `ios-simulator.sh`(시뮬레이터 빌드 결과), `ios-device-ipa.sh`(기기 빌드, Native Codex IPA 워크플로도 이 스크립트를 쓴다). 결과는 `privacy-manifests.json`·`.log`로 남는다.

## 아직 확인하지 못한 것

- App Store Connect 실제 업로드(ITMS-91053 경고 유무)
- Xcode Organizer의 Privacy Report(Archive → Generate Privacy Report)
- Release·최적화 빌드의 내장 Codex 심볼(`activeInputModes`, `systemUptime`이 dead strip으로 사라지는지)
