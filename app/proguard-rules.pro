# 릴리스 빌드(R8) 규칙. 앱은 Codex를 별도 프로세스로 실행하고 JSON은 org.json(플랫폼)으로 다뤄서
# 리플렉션 의존이 거의 없다. WorkManager 워커는 이름으로 만들어지므로 남긴다.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keepclassmembers class * extends androidx.work.ListenableWorker { <init>(...); }

# 채팅 수식(JLatexMath). 글꼴·기호 표(XML, assets)를 패키지 경로와 이름으로 읽고 내부에서 클래스 이름을 쓰는 곳이 있어
# 줄이거나 이름을 바꾸지 않는다(라이브러리가 소비자 규칙을 주지 않는다).
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**
