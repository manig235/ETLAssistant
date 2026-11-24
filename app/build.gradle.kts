import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep checksum-verified upstream downloads intact. Package a reproducible .rodata-only
// helper-basename change so Codex can own its helper over private stdio pipes on Android.
val privateCodexRuntime = layout.buildDirectory.dir("generated/privateCodexRuntime")
val preparePrivateCodexRuntime by tasks.registering(Exec::class) {
    val upstream = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    val script = rootProject.layout.projectDirectory.file("scripts/prepare-codex-runtime.py")
    inputs.file(script)
    inputs.file(upstream.file("libcodex.so"))
    inputs.file(upstream.file("libcodex-code-mode-host.so"))
    outputs.dir(privateCodexRuntime)
    commandLine("python3", "-I", script.asFile.absolutePath,
        "--codex", upstream.file("libcodex.so").asFile.absolutePath,
        "--host", upstream.file("libcodex-code-mode-host.so").asFile.absolutePath,
        "--destination", privateCodexRuntime.get().dir("jni/arm64-v8a").asFile.absolutePath,
        "--metadata", privateCodexRuntime.get().file("assets/codex-runtime.json").asFile.absolutePath,
        "--android-only")
}

// Bundle the maintained document verbatim; policy edits automatically invalidate this task.
abstract class BundlePrivacyPolicy : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val destination = outputDirectory.file("privacy-policy.md").get().asFile
        destination.parentFile.mkdirs()
        sourceFile.get().asFile.copyTo(destination, overwrite = true)
    }
}

val bundlePrivacyPolicy = tasks.register<BundlePrivacyPolicy>("bundlePrivacyPolicy") {
    sourceFile.set(rootProject.layout.projectDirectory.file("docs/privacy-policy.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/privacyPolicyAssets"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundlePrivacyPolicy, BundlePrivacyPolicy::outputDirectory)
}

// release 서명 키. 저장소 밖 ~/.android/youngmin-release.properties(storeFile, storePassword, keyAlias, keyPassword)에서 읽는다.
// 경로는 gradle 속성 youngmin.signing.props 또는 환경변수 YOUNGMIN_SIGNING_PROPS로 바꿀 수 있다.
// 파일이 없어도 debug 빌드와 단위 테스트는 된다. release APK·AAB를 만들 때만 오류로 멈춘다(debug 키로 넘어가지 않는다).
val releaseSigningFile: File = file(
    (findProperty("youngmin.signing.props") as String?)
        ?: System.getenv("YOUNGMIN_SIGNING_PROPS")
        ?: "${System.getProperty("user.home")}/.android/youngmin-release.properties"
)
val releaseSigning: Properties? = releaseSigningFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "kr.snu.etlassistant.phase0"
    sourceSets.getByName("main") {
        jniLibs.setSrcDirs(listOf(privateCodexRuntime.map { it.dir("jni") }))
        assets.srcDir(privateCodexRuntime.map { it.dir("assets") })
    }
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.snu.etlassistant.phase0"
        testInstrumentationRunner = "kr.snu.etlassistant.security.SecuritySmokeInstrumentation"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.3.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                // minSdk 26이라 v1은 필요 없다. v2와 v3를 모두 넣는다(v3는 나중에 키 순환을 쓸 수 있게).
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 코드와 리소스를 줄여 APK 크기를 낮춘다. 규칙은 app/proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 0.1.6부터 전용 release 키(CN=Youngmin)로 서명한다. 그 전 설치본(debug 키)은 지우고 다시 설치해야 한다.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // extractNativeLibs=true: 설치 시 libcodex.so를 nativeLibraryDir에 실제 파일로 풀어 둔다.
            useLegacyPackaging = true
            // 검증한 .rodata 경로 변환 외에는 바꾸지 않는다(기록한 SHA와 일치하도록 strip하지 않음).
            keepDebugSymbols += "**/libcodex.so"
            keepDebugSymbols += "**/libcodex-modehost.so"
        }
    }
}

tasks.named("preBuild") { dependsOn(preparePrivateCodexRuntime) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Montage(Wanted 디자인 시스템, MIT, Jetpack Compose). 첫 실행 안내 화면에 쓴다.
    implementation("com.github.wanteddev:montage-android:v4.0.3")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    // Montage는 Compose 라이브러리를 runtime 범위로만 선언하므로, 화면에서 직접 쓰는 것은 여기서 선언한다(버전은 BOM이 맞춘다).
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // 채팅 수식(LaTeX)을 기기 안에서 그린다. GPLv2 + classpath exception(링크하는 앱은 자기 라이선스를 유지), 글꼴은 OFL 등.
    // 외부 서버·CDN 없이 동작해야 해서 WebView+KaTeX 대신 이것을 쓴다.
    implementation("ru.noties:jlatexmath-android:0.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// release 산출물을 만들 때 서명 키가 없으면 서명 안 된 APK를 만들지 않고 바로 멈춘다.
gradle.taskGraph.whenReady {
    val needsRelease = allTasks.any { it.project == project && it.name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle") }
    if (needsRelease && releaseSigning == null) {
        throw GradleException(
            // 콘솔 인코딩에 따라 한글이 깨져 보여서 영어로 쓴다.
            "Release signing config not found: ${releaseSigningFile.path}\n" +
                "Create it with storeFile, storePassword, keyAlias, keyPassword, " +
                "or point to it with -Pyoungmin.signing.props=<path> or YOUNGMIN_SIGNING_PROPS."
        )
    }
}
