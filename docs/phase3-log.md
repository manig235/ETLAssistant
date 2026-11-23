# Phase 3 기록: 알림

AI 없이 앱 코드(WorkManager)만으로 마감·공지를 알린다. 코드는 `notify/` 패키지.

- 구성: `NotifyModels`(설정/스냅샷), `NotifySettingsStore`, `SnapshotFetcher`(eTL 조회), `NotificationPlanner`(무엇을 알릴지, 순수 함수),
  `BriefBuilder`(브리핑 문구), `NotificationPoster`(채널 deadline/announcement/brief), `NotifyScheduler`/`NotifyRunner`와 워커.
- 설정: 마감 알림, 새 공지 알림, 정기 브리핑(최대 2개 시각), 확인 주기(처음엔 1/3/6/12시간 선택, 지금은 1시간 고정), 방해 금지(브리핑은 예외),
  잠금화면 내용 숨김. 모든 변경은 즉시 저장하고 `NotifyScheduler.apply`로 다시 예약한다.
- 중복 방지: `NotifyState`에 이미 알린 항목을 기록한다. 새 공지는 첫 확인에서 기준선만 잡고 이후 새 글만 알린다.
- 테스트: 단위 테스트 226개(순수 로직 위주)가 통과한다.
- **아직 못 한 것**: 앱을 오래 닫아 둔 상태에서 주기 작업이 실제로 도는지(삼성 절전), 알림 권한 거부 흐름의 기기 확인,
  (2026-11-21: `SnapshotFetcher`와 `EtlTools.digest`의 강의별 과제 조회를 `CourseAssignments.fetch`로 합쳤다.)

## eTL 알림을 원문으로 바로 연결 (2026-11-23, 0.2.8)

- 이전에는 알림 본문을 누르면 채팅을 열고 펼친 뒤의 "eTL에서 열기"만 원문으로 연결했다. 이제 유효한 eTL 링크가 있는 알림은 본문과 버튼에 같은 이동 동작을 사용한다. 접힌 알림에서도 본문을 눌러 이동할 수 있다.
- 성적·새 과제·마감 변경·마감 임박 알림은 URL이 없거나 부적절하면 과목·과제 ID로 과제 주소를 복원한다. 공지는 과목·공지 ID로 원문 주소를 복원한다. 외부 호스트·HTTP·제어문자가 섞인 주소를 그대로 열지 않는다.
- 한 강의의 묶음은 강의 화면, 여러 강의의 묶음은 eTL 홈, eTL 수업은 강의 화면, 토큰 오류는 eTL 설정으로 연결한다. 브리핑은 영민이 앱으로 연결하며, eTL과 관계없는 직접 등록 일정에는 eTL 링크를 만들지 않는다.
- 알림 기록에도 복원된 주소가 저장된다. 이미 발송한 과거 알림을 다시 울리거나 중복 방지 기록을 초기화하지 않으며, 업데이트 뒤 새로 만드는 알림부터 적용한다.
- 검증: 전체 단위 테스트 426개 중 425개 통과·기존 제외 1개. 누락/외부 과제 링크 복원, 정상 상세 링크 유지, 공지 링크 복원, 강의별/여러 강의 묶음, 내 일정 제외, 브리핑의 앱 이동 유지와 교수명 캐시 정리를 검사했다. 로컬 release 빌드 성공, Lint 오류 0건.

## 마감 알림 시점 선택과 제시간 알람 (2026-11-20)

- 기본 시점을 3일, 24시간, 3시간, 1시간 전으로 바꿨다. 설정에서 1·3·6·12시간, 1·2·3·7일 중 여러 개를 고른다(최소 1개).
  예전 버전은 시점을 고르는 화면이 없었으므로 저장값(72, 24)을 새 기본값으로 올린다(`deadlineWindowsVersion` 2).
- 주기 확인만으로는 "1시간 전" 알림이 확인 간격만큼 늦을 수 있다. 확인할 때마다 다음 알림 시점
  (`NotificationPlanner.nextDeadlineCheckAt`)에 `AlarmManager.setAndAllowWhileIdle` 알람을 하나 건다(`DeadlineAlarm`).
  울리면 eTL을 다시 조회하므로 그사이 제출한 과제는 알리지 않는다.
  정확한 알람 권한은 쓰지 않는다. 권한 없는 알람은 남은 시간의 75%(최대 1시간)까지 늦게 울린다(SM-S908N에서 `window=+1h` 확인).
  그래서 목표가 멀면 중간 알람(네트워크 없이 다시 걸기만 함)으로 다가가고 마지막 8분 안에서만 목표에 건다(`NotifySchedule.nextAlarm`).
  계산상 최대 지연은 약 6분이고 목표 하나에 몇 번(보통 3~6번) 깨어난다.
  재부팅하면 알람이 사라지지만 다음 주기 확인이 다시 건다.
- 첫 실행 안내의 브리핑 시각에 "직접 선택"을 넣었다(설정 화면은 원래 시각 선택기로 고를 수 있었다).
- 기기(SM-S908N, Android 14)에서 "지금 확인" 뒤 중간 알람이 목표 1시간 5분 전에 걸리는 것을 확인했다.
- **아직 못 한 것**: 알람이 실제로 울려 제시간에 알림이 오는지(절전 상태 포함).

## 마감 알림 시점 직접 추가(분 단위)와 "눌러서 바꿀 수 있음" 표시 (2026-11-20)

- 시점 저장 단위를 분으로 바꿨다(`deadlineWindowsMinutes`, 저장 버전 3). 버전 2(시간 단위)는 ×60, 버전 1은 기본값으로 올린다.
- 설정의 시점 칩 끝 "+ 직접 추가"에서 숫자와 단위(분/시간/일)로 넣는다. 범위 10분~14일, 최대 12개. 직접 넣은 값도 칩으로 보이고 눌러서 끈다(마지막 하나는 못 끈다).
  이름은 `30분`, `1시간 30분`, `24시간`, `2일`처럼 보인다.
- 중복 방지 키를 `deadline-m:{강의}:{과제}:{분}`으로 바꿨다. 예전 키(`deadline:…:{시간}`)로 기록된 알림도 알린 것으로 인정해 업데이트 직후 같은 알림이 다시 오지 않는다.
- 브리핑 시각과 방해 금지 시작·종료를 연필 아이콘이 붙은 테두리 버튼으로 바꾸고, "시각을 눌러 바꿀 수 있어요", "눌러서 켜고 끌 수 있어요" 안내를 달았다.
- SM-S908N에서 확인: 버전 2 설정(3일·24·3·1시간)이 그대로 올라옴, "직접 추가"로 30분을 넣자 칩과 요약 문구에 나타남(시험 뒤 원래대로 끔), 연필 아이콘 표시.

## 알림 설정 정리: 시점은 ×로 지우고 + 추가로 더하기, 확인 주기 1시간 고정 (2026-11-21)

- 마감 알림 시점 칩은 켜진 시점만 보이고, 모든 칩(기본값 포함)에 ×가 있다. 마지막 하나는 지우지 않고 "알림 시점이 하나는 있어야 해요…"를 보인다.
  "+ 추가"는 자주 쓰는 시점 빠른 버튼(이미 있는 값은 ✓로 비활성)과 숫자·단위 직접 입력. "기본값으로 되돌리기"는 기본값이 아닐 때만 보인다.
  저장은 원래 "켜진 분 목록" 하나라(버전 3) 마이그레이션은 필요 없었다.
- 확인 주기 선택을 없애고 1시간으로 고정했다(`NotifySettings.CHECK_INTERVAL_HOURS`). 예전 저장값은 저장할 때 지운다. 앱이 시작할 때(`Phase0App`) `NotifyScheduler.apply`가 불려 기존 사용자도 1시간 주기로 다시 예약된다(UPDATE 정책).
- 설정 알림 섹션의 설명 캡션(맨 위 안내, 브리핑 시각 안내, 시점 안내)을 뺐다. 연필 아이콘 버튼은 그대로.
- SM-S908N 확인: 기본 칩(3시간) 삭제 → 빠른 버튼으로 다시 추가 → 30분 직접 추가 → 삭제 → 기본값으로 되돌리기, WorkManager DB에서 notify-check 주기 60분(flex 15분).

## 제시간 알람 60초 접근, 공지·변경 채널 팝업, 재부팅 재예약, 기본 10분 전 (2026-11-22)

- 사용자 보고: "직접 등록한 알림이 안 뜬다", "카톡처럼 위에 팝업으로 뜨는지". SM-S908N에서 확인했다.
  - 사용자 폰에는 내 마감이 하나도 저장돼 있지 않았다(`my_deadlines.xml` 없음). 울릴 알림이 없었다.
  - 테스트 내 마감(23:50 알림)은 울렸지만 23:53:05에 떴다. 3분 39초 앞에 건 마지막 알람이 허용 창(2분 44초) 끝에서 울렸다.
    삼성은 권한 없는 알람을 거의 항상 창 끝에 울린다. 위 "최대 약 6분" 계산이 그대로 나온 것이다.
  - 마감(deadline) 채널은 HIGH라 홈 화면·앱 안에서 팝업으로 뜨는 것을 캡처로 확인했다. 브리핑(DEFAULT)은 알림창에만 들어간다.
  - 방해 금지 꺼짐, 대기 버킷 ACTIVE, 백그라운드 제한 없음, 알림 권한 허용, 헤드업 전역 설정 켜짐. 한 시간 주기 확인과 13:00 브리핑은 로그상 정상.
- 마지막 알람 거리를 8분에서 60초로 줄였다(`NotifySchedule.ALARM_FINAL_MILLIS`). 그 전에는 남은 시간의 절반 뒤에 중간 알람을 건다.
  창 끝에서 울려도 남은 시간이 1/8로 줄어 보통 3~4번 만에 1분 안에 들어오고, 최악 지연은 45초(단위 테스트로 확인).
  재측정: 23:57 알림이 23:57:29에 팝업으로 떴다(20초 여유 포함).
- 공지·새 과제/마감 변경/성적 채널을 HIGH로 올렸다. 채널 중요도는 만든 뒤 바꿀 수 없어 ID를 `announcement_v2`, `change_v2`로 바꾸고 옛 채널을 지운다.
  옛 채널을 사용자가 꺼 두었으면 새 채널도 끈 채로 만든다. 알림 기록의 옛 ID도 같은 이름으로 보인다. 기기에서 새 채널 importance 4, 옛 채널 `mDeleted=true` 확인.
- `SystemEventReceiver`: BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED에서 `NotifyScheduler.apply` + `DeadlineAlarm.rearm`.
  저장된 목표가 지났으면 바로 확인 작업을 돌려 늦게라도 보낸다(`NotifySchedule.rearmAction`). 기기에서 업데이트 설치 때 "예약 되살림: MY_PACKAGE_REPLACED" 로그와
  알람 유지 확인. 셸은 BOOT_COMPLETED를 보낼 수 없어(SecurityException) 실제 재부팅 확인은 남았다.
- 마감 알림 기본 시점을 3일·24시간·3시간·10분 전으로 바꿨다("1시간 전" → "10분 전"). 추가 대화상자 빠른 버튼에 10분·30분을 넣었다.
  저장된 설정이 있으면 기본값을 쓰지 않으므로 기존 사용자(이 폰: 24시간·3시간·1시간)는 설정에서 직접 바꿔야 한다.

## 앱을 닫아 두면 알림이 안 온다: 받아 둔 과제 폴백, 배터리 최적화 예외 (2026-11-22)

- 사용자 보고: "앱을 켠 상태에서만 알림이 오고, 앱을 끄면(백그라운드) 안 온다." 앱 안에서는 과제가 보이는데 브리핑에는 과제 없이
  "※ 일부 항목을 조회하지 못했어요"만 떴다. 이 문구는 `Snapshot.errors`가 있을 때만 붙는다(강의 목록은 받았는데 과제 조회가 실패했거나,
  당시 오류를 앱 로그에 남기지 않아 어떤 예외였는지는 알 수 없었다).
- SM-S908N(Android 14)에서 재현은 되지 않았다. 앱을 홈으로 보내 cached/frozen 상태(procState 16, Freecess FZ)로 둔 채 화면을 끄고
  JobScheduler로 브리핑·확인 작업을 강제로 돌려도 eTL 조회가 정상이었다. 밤사이 한 시간 주기 확인도 "조회 오류 0"으로 돌았다.
  logcat의 `MARs:ActiveTrafficFilter: filter : kr.snu.etlassistant.phase0`는 Gmail 등과 같이 찍히는 삼성 로그이고 실제 차단은 보지 못했다.
  즉 원인(삼성 절전·백그라운드 DNS 차단, 동시 요청 일부 끊김 등)은 이 기기에서 확정하지 못했다. 사용자가 본 폰에서 다음에 실패하면 아래 로그로 확인한다.
- 대책
  - **진단 로그**: 확인·브리핑 때 `Snapshot.errors`를 앱 로그(`[notify] 조회 오류 N건: …`, 강의명·경로·예외 클래스만)에 남긴다. 전체 실패도 `eTL 조회 실패: …`.
  - **실패한 과목 한 번 더**: 과제 조회가 연결 끊김·시간 초과(`EtlException.kind` network/timeout)로 실패한 과목만 차례로 한 번 더 받는다(`SnapshotFetcher.retryFailed`).
  - **받아 둔 과제 폴백**(`SnapshotFallback`): 실패한 과목은 마지막으로 성공한 과제 목록(`SnapshotCache`, 3일 이내)으로 메운다. 강의 목록부터 실패하면 전체를 저장본으로.
    저장 뒤 마감이 지난 미제출 온라인 과제는 누락으로 본다. 브리핑은 마지막 재시도(4번째)에서만 저장본으로 보내고 끝에
    "※ eTL에서 지금 받지 못한 과제는 N시간 전 받아 둔 정보예요"를 붙인다(그 전 시도는 연결이 돌아오길 기다린다). 주기 확인은 저장본으로 마감 알림을 보내고
    (알림 본문에 같은 줄) 결과를 RETRY로 남겨 다시 시도한다. 새 공지 기준선·새 과제/마감 변경 비교는 실제로 조회됐을 때만 한다.
  - **배터리 최적화 예외 요청**(`BatteryOpt`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, APK 직접 배포): 첫 실행 안내 알림 단계와 설정 알림 카드에
    "배터리 제한 풀기" 버튼, 예외가 아니면 채팅 화면 위 경고 배너(알림 꺼짐 다음 순위, 닫으면 7일 숨김). 삼성 "절전 앱·딥 슬립 앱" 안내 문구를 같이 보인다.
- 기기 확인(SM-S908N): `cmd connectivity set-chain3-enabled true` + `set-package-networking-enabled false`로 앱 네트워크를 막고(UnknownHostException, 사용자가 본 백그라운드 DNS 실패와 같은 예외)
  백그라운드에서 브리핑 작업을 돌렸다. 시도 1~3은 RETRY, 4번째에 "7분 전 받아 둔 정보" 줄이 붙은 브리핑이 과제 5건·누락 1건으로 나갔다. 막은 것을 풀고
  `deviceidle whitelist +pkg`(예외와 같은 상태)로 백그라운드 브리핑을 돌리면 정상 조회(뒤에 원래대로 되돌림).
- 확인하는 몇 초 동안만 띄우는 포그라운드 서비스(`setForeground`)는 넣지 않았다. 이 기기에서는 예외 없이도 백그라운드 조회가 됐고, 예외 상태에서도
  실패한다는 증거가 없었다(사용자 결정: 예외 상태에서도 실패가 확인될 때만 넣는다). 설정 화면·안내 화면의 새 버튼은 기기 화면으로는 확인하지 못했다.

### 실측: 증상이 나는 폰(SM-S938N, Android 17, release 0.1.7)에서 원인 확인 (2026-11-22)

- **절전 모드가 켜져 있었다**: `settings global low_power=1`, `low_power_sticky=1`(사용자가 직접 켬), `low_power_back_data_off=1`(삼성 "백그라운드 데이터 제한").
  netpolicy: `UID=10517 … blocked_state={blocked=BATTERY_SAVER|APP_BACKGROUND,allowed=NONE,effective=BATTERY_SAVER|APP_BACKGROUND}`.
  connectivity: `BLOCKED 10517(…) on netId 121: c1`(= BATTERY_SAVER 0x1 | APP_BACKGROUND 0x40 | OEM_DENY 0x80).
- **작업이 아예 시작되지 않는다**: WorkManager 작업(네트워크 제약 CONNECTED)이 `Unsatisfied constraints: CONNECTIVITY`로 남는다. 15분 주기 진단 빌드로
  정해진 시각(12:44)을 7분 넘게 지나도(충전 중이라 절전 차단은 빠지고 `blocked=APP_BACKGROUND`만 남은 상태) 실행되지 않았다.
  JobScheduler 기록에도 이 앱 작업은 `active-top`(앱이 화면에 있을 때)으로만 돌았다. 11:59 브리핑은 사용자가 앱을 연 순간 밀려 있던 작업이 돈 것.
- **강제로 돌리면 네트워크가 막혀 실패**: `cmd jobscheduler run -f`로 브리핑을 돌리면 작업 중에는 APP_BACKGROUND만 풀리고(`41 → 1`) BATTERY_SAVER는 남아
  즉시 RETRY. `deviceidle whitelist +pkg`(배터리 최적화 예외와 같은 상태)를 주면 `allowed=POWER_SAVE_ALLOWLIST…,effective=NONE`, `UNBLOCKED … 0`이 되고
  강제 실행한 브리핑이 1.4초 만에 새 정보(과제 5건, "받아 둔 정보" 줄 없음)로 나갔다.
- **삼성 Freecess·OEM 차단**: 앱이 백그라운드에서 얼려지면(`mFreezedPackages … Pkg kr.snu.etlassistant.phase0`) `MARs:ActiveTrafficFilter: filter : kr.snu.etlassistant.phase0`와 함께
  OEM_DENY(0x80)가 걸린다. 이 상태에서는 화이트리스트를 줘도 `BLOCKED … 80`이 남아 네트워크 제약 작업이 시작되지 않았다(위 성공은 설치 직후 얼려지기 전이었다).
  S908N(Android 14)에는 APP_BACKGROUND 차단이 없고 작업이 먼저 시작돼 Freecess가 풀어 줬기 때문에 재현되지 않은 것으로 본다.
- 업데이트 설치(install -r) 뒤 앱을 열지 않아도 MY_PACKAGE_REPLACED로 작업·알람이 다시 예약되고 주기 확인이 한 번 돌았다(설치 직후 임시 허용 창에서 조회 성공).
- 남은 일: 네트워크 제약(CONNECTED)을 빼서 작업이 시간만 되면 시작되게(시작되면 Freecess가 풀고 작업 중 APP_BACKGROUND가 풀린다) 하는 수정이 유력하나,
  자연 실행으로 검증하지 못했다. 배터리 예외만으로 OEM_DENY가 풀리는지도 확인하지 못했다.

### 실측 2: 절전 모드를 끈 S938N, 절전 모드를 켠 S908N — 네트워크 제약 제거, 브리핑 재시도, 절전 모드 안내 (2026-11-22)

시험 방법: 앱 화면을 열면 결과가 오염되므로, 진단 빌드가 `ACTION_MY_PACKAGE_REPLACED`(`adb install -r` 직후, 앱을 열지 않아도 옴)에서
몇 분 뒤 한 번짜리 작업(확인 작업 제약 없음/CONNECTED, 브리핑)과 `setAndAllowWhileIdle` 알람을 예약하게 했다(커밋하지 않음).
관찰은 `logcat -b all`, 5초 간격 `dumpsys netpolicy | grep 'UID=… state'`, `dumpsys connectivity`의 BLOCKED 이력, `dumpsys jobscheduler`의 START/STOP 이유, `am_freeze`/`am_unfreeze`.
로그 사본: `~/etl-diag-logs-2026-11-22/`(`s938/`, `s908b/`, 저장소 밖).

- **S938N, 절전 모드 끔(APP_BACKGROUND·앱 얼림만 남음)**: 설치 4분 뒤 예약한 확인 작업(제약 없음/CONNECTED 둘 다)이 13:07:08 제시간에 JobScheduler로 시작,
  `sync unfroze … kr.snu.etlassistant.phase0`로 얼림이 풀리고 실행 중 `UNBLOCKED 10517 … 0`(`allowed=NOT_IN_BACKGROUND, effective=NONE`), 새 조회 성공("조회 오류 0").
  작업이 끝나고 5~10초 뒤 `BLOCKED … 40`(APP_BACKGROUND)이 돌아오고 `Destroyed live tcp sockets for uids={10517}`.
  알람 경로: 13:09:08 예약(창 4분 30초) → 13:13:38 수신 → 13:13:43 확인 작업 SUCCESS.
- **"일부 항목을 조회하지 못했어요"의 메커니즘(S938N)**: 13:09:37 브리핑 작업이 시작 100ms 만에 `STOP … olaf`(삼성 OLAF). WorkManager가 앱 안에서 attempt 1로
  이어 돌렸는데 작업 밖이라 약 5초 뒤 APP_BACKGROUND가 돌아와 소켓이 끊기고 3건이 `UnknownHostException` → 일부만 받은 브리핑.
- **S908N(Android 14), 절전 모드 켬**(`low_power=1`, 실행 중에도 `blocked=BATTERY_SAVER, effective=BATTERY_SAVER`):
  - CONNECTED 확인 작업은 `Unsatisfied constraints: CONNECTIVITY`로 **시작되지 않았다**(다음 install -r의 임시 허용 창에서야 돌았다).
  - 제약 없는 확인 작업은 13:30:22 제시간에 시작됐지만 조회는 `SocketTimeoutException`/`UnknownHostException` → 받아 둔 과제로 메움, 백오프로 최대 3번 재시도 뒤 다음 주기.
  - 브리핑(제약 없음)은 시도 0~2 RETRY, 4번째(13:35:02)에 "브리핑 전송 (받아 둔 정보: 8분 전)".
  - 알람 경로도 같다(13:36:51 수신 → 조회 실패 → 받아 둔 과제). `setAndAllowWhileIdle`의 임시 허용은 절전 모드 차단을 풀지 않았다
    (설치 직후 MY_PACKAGE_REPLACED 때만 잠깐 `allowed=POWER_SAVE_ALLOWLIST`가 보였다).
  - **짧은 포그라운드 서비스 측정**: 배터리 최적화 예외가 없으면 백그라운드 작업에서 `startForegroundService()`가
    `ForegroundServiceStartNotAllowedException(mAllowStartForeground false)`로 막힌다. 예외(`deviceidle whitelist +pkg`)면 서비스 없이도 이미
    `allowed=POWER_SAVE_ALLOWLIST|POWER_SAVE_EXCEPT_IDLE_ALLOWLIST, effective=NONE`이고 새 조회가 성공했다(서비스 시작도 허용됨). 즉 **포그라운드 서비스는
    예외 없이는 띄울 수 없고, 예외가 있으면 필요 없다** → 넣지 않는다.
- **반영한 것**
  - 주기 확인·정기 브리핑의 네트워크 제약(CONNECTED)을 뺐다(`NotifyScheduler.backgroundConstraints = Constraints.NONE`). 막혀 있어도 제시간에 시작해 받아 둔 과제로
    마감 알림·브리핑을 보내고, 절전 모드가 아니면 작업 중 차단이 풀려 새로 조회된다. 재시도는 기존대로 지수 백오프 30초부터 최대 3번. 화면의 "지금 확인"·"지금 브리핑"은 CONNECTED 유지.
  - 브리핑: 일부만 연결 실패(`SnapshotFallback.hasConnectionErrors`, "eTL 연결 실패" 문구)이고 마지막 시도 전이면 보내지 않고 RETRY(다음 시도는 대개 네트워크가 열린 작업 안에서 돈다).
  - 절전 모드 안내(`BatteryOpt.POWER_SAVE_HINT`, `isPowerSaveMode`): 절전 모드이면서 배터리 최적화 예외가 아닐 때만 채팅 배너(`NotifyProblem.PowerSave`, 누르면 예외 요청, 7일 숨김),
    설정 알림 카드, 첫 실행 안내 알림 단계에 "절전 모드에서는 새 과제·공지 알림이 늦을 수 있어요. 이미 받아 둔 마감 알림은 그대로 와요."
- 확인하지 못한 것: 수정본으로 몇 시간·밤새 두는 장기 동작, S938N에서 앱이 얼어 있을 때 제약 없는 작업이 OEM_DENY(0x80)를 넘는지(절전 모드를 끈 시험에서는 OEM_DENY가 보이지 않았다),
  배너·설정 문구의 실제 화면.

## 브리핑이 다음 날로 밀리던 문제 (2026-11-22)

- 원인: `Phase0App.onCreate`가 프로세스가 뜰 때마다 `NotifyScheduler.apply`를 부르고, 브리핑 슬롯을 `ExistingWorkPolicy.REPLACE`·최소 1분 앞으로
  다시 계산했다. 브리핑 작업이 프로세스를 콜드 스타트시키면(삼성이 캐시 프로세스를 자주 죽임) 막 돌려던 오늘 브리핑이 취소되고 내일로 밀렸다.
- 고침: 슬롯마다 예약 시각을 저장(`notify_schedule` prefs)하고, 같은 시각(분) 설정의 예약이 아직 유효하면(4시간 넘게 지나지 않았고 이틀보다 먼 미래도 아님,
  `NotifySchedule.keepBriefTarget`) 다시 넣지 않는다. 브리핑이 끝나면(보냄·건너뜀·포기) `briefFinished`가 그 슬롯 예약을 지우고 다음 것을 잡는다.
  시각·시간대 변경 때만 강제로 다시 계산(`apply(forceBriefs = true)`), 재부팅·업데이트 때는 유지. 단위 테스트 `NotifyScheduleTest`.
- 주기 확인은 `ExistingPeriodicWorkPolicy.UPDATE`라 다시 불러도 주기가 밀리지 않는다(문제 없음). 업데이트 확인은 KEEP.

## 내 마감 반복 (2026-11-23)

- 사용자 요청: "과제 마감 등록할때 반복 주기나 요일 설정 가능하게 해줘". 반복은 없음/매일/매주(요일 여러 개)/격주(요일 여러 개)/매월, 끝 날짜(그날 포함)는 선택.
- 모델: `MyDeadline.repeat`(`MyRepeat`: freq, weekdays, until)에 규칙만 저장하고 회차는 저장하지 않는다. `due`는 첫 회차 기준(시작 날짜와 마감 시각).
  회차는 그때그때 펼친다(`MyDeadlines.occurrences`/`nextOccurrence`/`lastMissed`). 날짜·요일은 KST 기준.
  - 매주·격주에서 요일을 비우면 시작일의 요일. 시작일 전 회차는 없다(화요일 시작·월수 반복이면 첫 회차는 수요일).
  - 격주는 시작일이 든 주(월요일 시작)부터 두 주마다.
  - 매월은 시작일과 같은 날짜, 그 날짜가 없는 달은 **그달 말일로 맞춘다**(31일 시작이면 2/28(윤년 2/29), 4/30). 건너뛰지 않는다.
- 완료: 반복 마감은 회차마다(`doneDates`), "이번만 지우기"는 `skipDates`. 반복 마감의 `done`은 쓰지 않는다. 60일보다 오래된 날짜는 저장할 때 지운다.
- 어디에 어떻게 보이는가
  - 알림·브리핑·위젯(`MyDeadlines.active`): 최근 놓친 회차(7일 이내, 가장 최근 하나만)와 다음 미완료 회차만. 매일 반복이 며칠 치 마감 알림을 한꺼번에 만들지 않게 했다.
    `AssignmentItem.assignmentId`는 회차마다 다르다(`MyDeadlines.itemId` = id × 100000 + epochDay)라 마감 알림 기록(`deadline-m:-1:…`)이 회차별로 남아 매주 다시 울린다.
    반복 없는 마감은 예전처럼 id 그대로(이미 쌓인 알림 기록과 호환).
  - 직접 정한 알림 시각(`reminders`)은 마감과의 간격 그대로 회차마다 따라간다. 앞으로 31일 안의 회차를 본다. 키 `mine-remind:{id}:{분}`은 회차마다 시각이 달라 그대로 회차별.
  - 내 일정 화면: 앞으로 7일의 회차를 모두(완료 포함, 숨김 칩 따름), 그 안에 남은 회차가 없으면 다음 회차 하나를 "그 이후"에, 최근 놓친 회차를 "마감 지남"에.
    행을 누르면 반복 전체를 고친다("고치면 반복 전체에 적용돼요"). 완료 버튼은 그 회차만. 지우기는 "이번만/전체".
  - 비서 `my_deadlines`: 회차마다 한 줄에 `occurrence`·`repeat`. `my_deadline_add|update`에 `repeat`·`weekdays`·`until`, update의 `done`은 `occurrence` 회차에만
    (없으면 최근 놓친 회차, 없으면 다음 회차), `my_deadline_delete`에 `occurrence`를 주면 그 회차만 뺀다.
- 저장 형식: 반복 항목만 `repeat`·`doneDates`·`skipDates` 키가 붙는다. 예전 데이터(키 없음)와 모르는 freq는 반복 없음으로 읽는다.
- 검증: 단위 테스트 `MyDeadlinesRepeatTest`(요일 여러 개, 격주, 31일 매월, 끝 날짜, KST 자정 근처, 회차별 완료·알림 키·직접 알림, 이번만 지우기, JSON 호환, 내 일정·위젯·비서 결과).
  기기에서는 아직 확인하지 못했다(편집 화면 모양, 위젯 완료 버튼, 반복 알림이 실제로 매주 울리는지).

## 제출 직후 자동 점검 (2026-11-23)

- 파일을 받지 않고 제출 정보(메타데이터)만 본다. 과제 목록(`include[]=submission`)의 제출을 `SubmissionCheck.evaluate`(`etl/SubmissionCheck.kt`)로 판정해
  `AssignmentItem.submittedAt`·`check`(늦음, 문제 목록, 첨부 목록 확인 여부)에 넣는다. 판정은 `etl_verify_submission`과 같은
  `EtlLogic.evaluateSubmission`·`checkAttachment`: 누락 표시, 허용되지 않은 제출 방식, 0바이트 첨부, 허용되지 않은 확장자, 파일 업로드인데 첨부 없음.
- 알림(`AssignmentWatch.diff`의 `submits`): 제출 시각이 지난 확인과 다르면(새 제출·다시 제출) 문제가 있을 때만 `submit_check` 채널(HIGH, "제출 확인")로 알린다.
  키 `submit:{과목}:{과제}:{제출 시각}`. **늦음만 있으면 알리지 않는다**(본인이 안다) — 내 일정에 "제출함(늦음)"으로 보인다. 과목별 알림 끄기를 따른다.
  설정 "제출 직후 확인"(`submitAlerts`, 기본 켬). 새 과제·변경 알림을 꺼도 따로 돈다.
- 기준선: `WatchState.submitInitialized`(version 2). 예전 기록에는 제출 시각이 없어서 업데이트 뒤 첫 확인은 기준선만 잡는다.
- 첨부 목록: 파일 업로드 제출인데 목록 JSON에 `attachments` 키가 **아예 없으면** 그 새 제출만 `GET …/submissions/self`로 한 번 더 받는다(확인당 최대 5개).
  받지 못하면 제출 시각을 기록하지 않고 다음 확인에서 다시 본다. **서울대 eTL 목록 응답에 제출 첨부가 들어 있는지는 토큰 없이 확인하지 못했다**
  (Canvas 기본 동작으로는 들어 있다).
- 내 일정: 제출한 eTL 과제 행에 "제출 확인" 버튼(과제 + `submissions/self`, GET 두 번)이 있고 결과를 대화상자로 보여 준다(늦음도 문제로 보인다).
  점검에서 문제가 나온 제출은 "제출·완료 숨김"이어도 보인다("제출함 · 확인 필요"). 마감이 지난 제출 과제는 원래 목록에 없어서 버튼도 없다.
- 검증: 단위 테스트 `SubmitWatchTest`. 기기에서는 확인하지 못했다.

## 새 강의자료 알림 (2026-11-23)

- 주기 확인의 마지막 단계로 **4시간에 한 번**(`NewFiles.INTERVAL_MS`, 마지막 실행 시각 저장, 10분 여유) 현재 학기 강의를 **하나씩 차례로**
  `GET /api/v1/courses/{id}/files?sort=updated_at&order=desc&per_page=30`(첫 쪽만) 본다. 파일은 받지 않는다. 코드 `notify/NewFiles.kt`, 저장 `notify_files`.
- 숨김(`hidden_for_user`)·잠김(`locked_for_user`) 파일은 뺀다. 과목별로 본 파일 ID(최대 500개)를 남기고, 처음 보는 과목은 기준선만 잡는다.
- 파일 탭이 숨겨진 강의(401 권한 없음, 403, 404)는 7일 건너뛴다. 401인데 권한 문구가 없으면(토큰 거부로 판정되는 경우) 다른 과목이 성공했으면 같은 처리,
  모든 과목이 그랬으면 토큰 문제로 보고 아무것도 바꾸지 않는다. 연결 실패는 기준선을 바꾸지 않고, 연속 2번이면 남은 과목은 이번에 건너뛴다.
  성공한 과목이 하나도 없으면 마지막 실행 시각을 바꾸지 않아 다음 확인에서 다시 본다. 모듈(`type=File`)로 대신 보는 것은 넣지 않았다.
- eTL 조회가 실패했거나(`etlOk` 아님) 방해 금지 시간이면 돌지 않는다.
- 알림: 과목별 묶음 "새 강의자료 N개 · 과목명"(파일 이름), 채널 `new_files`는 **DEFAULT**(팝업 없음): 마감처럼 시간이 급하지 않고, 학기 초나 시험 전에는
  여러 과목이 한꺼번에 올려 팝업이 몰릴 수 있어서. 중요도는 채널을 만든 뒤 못 바꾸므로 올리려면 `_v2`로 새로 만들어야 한다.
  과목별 알림 끄기·숨기기를 따르되 기준선은 갱신한다. 설정 "새 강의자료 알림"(`newFileAlerts`, 기본 켬).
- 누르면 채팅이 열리고 "{과목} 새 강의자료 {파일명} 받아줘"(4개 이상이면 "{첫 파일} 외 N개")가 입력창에 채워진다(보내지 않음). "eTL에서 열기"는 과목 파일 화면.
  `Notice.prefill`/`prefillAction`(버튼 이름이 있으면 그 버튼이 채팅을 연다)을 `NotificationPoster`가 처리한다.
- 검증: 단위 테스트 `NewFilesTest`. 서울대 eTL의 `sort=updated_at`·`order=desc` 지원과 파일 탭 숨김 강의의 실제 응답 코드는 기기에서 확인하지 못했다.

## 시험 공지 배지와 "일정 정리 부탁" (2026-11-23)

- `Queries.examMention`: 시험·중간고사·기말고사·퀴즈·quiz·exam·midterm·finals(단독 "final"은 final project 등이 많아 뺐다). 키워드만 보고 날짜는 뽑지 않는다.
  - 제목에 있으면 시험 공지. 단 제목이 성적·점수·채점·해설·이의신청·결과 공지이고 일정 말(일정·일시·장소·범위·시간·실시 등)이 없으면 제외("중간고사 성적 공개").
  - 본문에만 있으면 본문에 일정 말도 있을 때만("시험에 반영됩니다" 같은 과제 공지를 거른다).
- 배지: 제목의 시험 > 보강 > 휴강 > 본문의 시험. 묶음 알림 줄에도 "(시험 공지)".
- 시험 공지 알림에 "일정 정리 부탁" 버튼: 채팅을 열고 "{과목} ‘{공지 제목}’ 공지 보고 시험 일정(날짜·시간·장소) 내 일정에 넣어줘"를 입력창에 채운다
  (자동으로 보내지 않는다. 사용자가 보내면 비서가 공지를 읽고 `my_deadline_add`를 쓴다 — "사용자가 직접 요청했을 때만" 지침과 맞는다). 본문 누르기는 그대로 eTL 원문.
  묶음 알림은 시험 공지 두 개까지 이름을 적고 나머지는 "외 N개".
- 검증: 단위 테스트(`QueriesTest.examAnnouncementDetection`, `NotificationPlannerTest`의 시험 공지). 기기에서는 확인하지 못했다(알림 버튼 모양, 잠금화면에서 누를 때).
