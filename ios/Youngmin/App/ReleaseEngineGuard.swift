// Release는 항상 앱 내장 Codex 엔진으로 빌드한다(ios/Config/NativeCodex.xcconfig).
// 설정이 빠지거나 명령줄에서 지워지면 예전 Swift 인증·채팅 경로로 조용히 돌아가지 않고 빌드를 멈춘다.
#if !DEBUG && !YOUNGMIN_NATIVE_CODEX
#error("Release builds must embed the native Codex engine. See ios/Config/NativeCodex.xcconfig and ios/CODEX-RUNTIME.md.")
#endif
