!include nsDialogs.nsh
!include LogicLib.nsh
!define YOUNGMIN_RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"
!define YOUNGMIN_STARTUP_NAME "kr.snu.youngmini.desktop"
!ifndef BUILD_UNINSTALLER
  Var StartupChoice
  Var StartupCheckbox
!endif

!macro customInit
  ; Silent reinstalls preserve the existing choice; fresh installs default to on.
  StrCpy $StartupChoice "preserve"
  ${If} $hasPerUserInstallation == "0"
    StrCpy $StartupChoice "1"
  ${EndIf}
  ${GetParameters} $R0
  ClearErrors
  ${GetOptions} $R0 "/AUTOSTART=" $R1
  ${IfNot} ${Errors}
    ${If} $R1 == "0"
    ${OrIf} $R1 == "1"
      StrCpy $StartupChoice $R1
    ${EndIf}
  ${EndIf}
!macroend

!macro customPageAfterChangeDir
  Page custom YoungminStartupPage YoungminStartupLeave
  Function YoungminStartupPage
    ${If} ${isUpdated}
      Abort
    ${EndIf}
    !insertmacro MUI_HEADER_TEXT "자동 실행" "로그인할 때 영민이를 시작할지 선택해 주세요."
    nsDialogs::Create 1018
    Pop $0
    ${If} $0 == error
      Abort
    ${EndIf}
    ${NSD_CreateLabel} 0 0 100% 42u "자동 실행을 켜면 Windows 로그인 후 대화창을 열지 않고 트레이에서 시작해 마감 알림을 보내요. 앱 설정에서 언제든 바꿀 수 있어요."
    Pop $0
    ${NSD_CreateCheckbox} 0 52u 100% 16u "Windows 로그인 시 자동 실행"
    Pop $StartupCheckbox
    ${If} $StartupChoice == "preserve"
      ReadRegStr $0 HKCU "${YOUNGMIN_RUN_KEY}" "${YOUNGMIN_STARTUP_NAME}"
      ${If} $0 != ""
        ${NSD_Check} $StartupCheckbox
      ${EndIf}
    ${ElseIf} $StartupChoice == "1"
      ${NSD_Check} $StartupCheckbox
    ${EndIf}
    nsDialogs::Show
  FunctionEnd
  Function YoungminStartupLeave
    ${NSD_GetState} $StartupCheckbox $StartupChoice
  FunctionEnd
!macroend

!macro customInstall
  ${If} $StartupChoice == "1"
    WriteRegStr HKCU "${YOUNGMIN_RUN_KEY}" "${YOUNGMIN_STARTUP_NAME}" '$\"$INSTDIR\${APP_EXECUTABLE_FILENAME}$\" --startup'
  ${ElseIf} $StartupChoice == "0"
    DeleteRegValue HKCU "${YOUNGMIN_RUN_KEY}" "${YOUNGMIN_STARTUP_NAME}"
  ${EndIf}
!macroend

!macro customUnInstall
  ${IfNot} ${isUpdated}
    DeleteRegValue HKCU "${YOUNGMIN_RUN_KEY}" "${YOUNGMIN_STARTUP_NAME}"
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run" "${YOUNGMIN_STARTUP_NAME}"
  ${EndIf}
!macroend
