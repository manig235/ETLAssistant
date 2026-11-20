# Phase 3 기록: 알림

AI 없이 앱 코드(WorkManager)만으로 마감·공지를 알린다. 코드는 `notify/` 패키지.

- 구성: `NotifyModels`(설정/스냅샷), `NotifySettingsStore`, `SnapshotFetcher`(eTL 조회), `NotificationPlanner`(무엇을 알릴지, 순수 함수),
  `BriefBuilder`(브리핑 문구), `NotificationPoster`(채널 deadline/announcement/brief), `NotifyScheduler`/`NotifyRunner`와 워커.
- 설정: 마감 알림, 새 공지 알림, 정기 브리핑(최대 2개 시각), 확인 주기(1/3/6/12시간), 방해 금지(브리핑은 예외),
  잠금화면 내용 숨김. 모든 변경은 즉시 저장하고 `NotifyScheduler.apply`로 다시 예약한다.
- 중복 방지: `NotifyState`에 이미 알린 항목을 기록한다. 새 공지는 첫 확인에서 기준선만 잡고 이후 새 글만 알린다.
- 테스트: 단위 테스트 226개(순수 로직 위주)가 통과한다.
- **아직 못 한 것**: 앱을 오래 닫아 둔 상태에서 주기 작업이 실제로 도는지(삼성 절전), 알림 권한 거부 흐름의 기기 확인,
  `SnapshotFetcher`와 `EtlTools.digest`의 중복 조회 코드 정리.

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
