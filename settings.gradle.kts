pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Montage(Wanted 디자인 시스템)는 JitPack으로만 배포된다. 이 그룹으로 범위를 좁혀 다른 의존성이 여기서 해석되지 않게 한다.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.wanteddev") }
        }
    }
}
rootProject.name = "etl-android"
include(":app")
