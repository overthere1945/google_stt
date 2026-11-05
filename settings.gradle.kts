pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // change-hyungchul-20261104-1750 : JitPack 저장소(android-vad WebRTC VAD 전용) 제거 — WebRTC VAD 삭제로 쓰는 의존성이 없다
    }
}

rootProject.name = "google_stt"
include(":app")
