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
