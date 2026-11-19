# 릴리스 빌드(R8) 규칙. 앱은 Codex를 별도 프로세스로 실행하고 JSON은 org.json(플랫폼)으로 다뤄서
# 리플렉션 의존이 거의 없다. WorkManager 워커는 이름으로 만들어지므로 남긴다.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keepclassmembers class * extends androidx.work.ListenableWorker { <init>(...); }
