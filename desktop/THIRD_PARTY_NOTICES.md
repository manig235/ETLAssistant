# 주요 구성요소

배포본은 다음 오픈소스 구성요소를 사용합니다. 각 JAR 및 JRE의 LICENSE/NOTICE 파일도 함께 보관합니다.

| 구성요소 | 출처 | 라이선스 |
|---|---|---|
| Codex 및 code-mode host rust-v0.170.0 | https://github.com/openai/codex | Apache-2.0 |
| Kotlin | https://github.com/JetBrains/kotlin | Apache-2.0 |
| Electron / Chromium | https://github.com/electron/electron | MIT 및 동봉된 LICENSES.chromium.html |
| React | https://github.com/facebook/react | MIT |
| Montage Web 4.4.0 | https://github.com/wanteddev/montage-web | MIT |
| react-markdown / remark / rehype | https://github.com/remarkjs/react-markdown | MIT |
| PDF.js 6.5.438 | https://github.com/mozilla/pdf.js | Apache-2.0 |
| KaTeX | https://github.com/KaTeX/KaTeX | MIT |
| kotlinx.coroutines | https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |
| JSON-java | https://github.com/stleary/JSON-java | Public domain |
| JNA | https://github.com/java-native-access/jna | LGPL-2.1-or-later 또는 Apache-2.0 (Apache-2.0 경로 사용) |
| Apache PDFBox / FontBox | https://pdfbox.apache.org | Apache-2.0 |
| Noto Sans KR | https://github.com/notofonts/noto-cjk | SIL Open Font License 1.1 (`fonts/OFL.txt`를 앱 JAR에 포함) |
| Pretendard | https://github.com/orioncactus/pretendard | SIL Open Font License 1.1 (`resources/app/dist/fonts/OFL.txt`) |
| Eclipse Temurin OpenJDK JRE 17 | https://adoptium.net | GPL-2.0 with Classpath Exception 및 JRE legal 폴더의 고지 |

펫의 기본 인터랙션은 MIT 라이선스인 https://github.com/hyukjunlim/ttokttok-pet 를 참고했습니다. 해당 저장소의 캐릭터·이미지·소스코드를 배포본에 복사하지 않았습니다. 로봇은 이 저장소 Android 앱의 기존 벡터 로고입니다.

렌더러 라이브러리는 앱과 함께 번들로 배포하며 외부 CDN에서 불러오지 않습니다. 추가 고지는 `licenses` 폴더를 확인하세요.
