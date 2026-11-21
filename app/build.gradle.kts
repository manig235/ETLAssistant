plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kr.snu.etlassistant.phase0"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.snu.etlassistant.phase0"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.3"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // 코드와 리소스를 줄여 APK 크기를 낮춘다. 규칙은 app/proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 지인에게 직접 파일로 전달하는 용도라 debug 키로 서명한다(같은 키라야 기존 설치 위에 업데이트된다).
            // 스토어 배포 전에는 전용 서명 키로 바꿔야 한다.
            signingConfig = signingConfigs.getByName("debug")
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
            // 공식 바이너리를 그대로 쓴다(strip하지 않음).
            keepDebugSymbols += "**/libcodex.so"
        }
    }
}

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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
