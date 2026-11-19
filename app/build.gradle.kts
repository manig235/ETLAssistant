plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.snu.etlassistant.phase0"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.snu.etlassistant.phase0"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-phase0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
