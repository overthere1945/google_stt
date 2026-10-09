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
        // add-hyungchul-20261009-1530 : android-vad(WebRTC VAD) 는 JitPack 으로만 배포된다. 이 그룹만 JitPack 에서 찾게 제한한다.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.gkonovalov.*") }
        }
    }
}

rootProject.name = "google_stt"
include(":app")
