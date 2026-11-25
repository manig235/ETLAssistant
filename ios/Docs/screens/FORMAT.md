# 화면 명세 형식

각 화면 그룹 파일 `<group>.md`: 화면마다 `## <id> — <제목>` 절. 안에 Android 진입점(path:line), 목적,
위→아래 레이아웃(구성요소 종류·정확한 한국어 문구·조건부 표시·기본값·dp/sp·색 토큰), 상태(빈/로딩/오류/권한 없음…),
동작 목록(무엇을 누르면 → 무슨 일 → 어느 화면 id), SwiftUI 구현 메모.

`wireframe-<group>.json`:
{
  "screens": [{"id": "...", "title": "...", "kind": "screen|dialog|sheet|drawer|banner|widget|notification|system",
               "group": "<group>", "androidEntry": "path:line",
               "wire": [{"type": "topbar|heading|text|input|button|button-row|chips|list-item|card|grid|toggle|banner|image|divider|bottom-input|section-header|icon-row|timetable-grid|bubble-user|bubble-assistant|spacer", "label": "실제 한국어 문구(22자 이하)"}]}],
  "edges": [{"from": "<id>", "to": "<id>", "trigger": "누르는 것(짧게)"}]
}

표준 id: splash, onboarding-welcome, onboarding-token, onboarding-chatgpt, onboarding-notify, onboarding-done, privacy-policy,
chat, chat-drawer, chat-model-picker, chat-image-preview, chat-update-prompt, chat-cleanup-prompt, chat-pending-notices, chat-write-approval,
my-schedule, my-schedule-edit, my-schedule-submission-check, schedule-draft, timetable, timetable-custom-course, timetable-change-edit,
notify-history, settings, course-alerts, calendar-settings, share-receiver, notification,
widget-deadlines, widget-deadlines-compact, widget-timetable, widget-timetable-tall, widget-next-class.
외부 대상: ext-etl, ext-browser, ext-calendar-app, ext-system-settings, ext-share-sheet, ext-document-picker, ext-file-viewer, ext-widget-picker.
목록에 없는 대화상자·시트는 부모 id를 앞에 붙인 kebab-case(예: settings-token-dialog).
