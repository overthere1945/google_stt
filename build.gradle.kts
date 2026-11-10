// Top-level build file where you can add configuration options common to all sub-projects/modules.
// add-hyungchul-20261105-1625 : [T0-2] Kotlin(KGP) 판 고정 — AGP 9 built-in Kotlin 의 기본 판보다 높은 판은
//   공식 방법대로 최상위 buildscript classpath 로 지정한다(D20). 아래 compose 플러그인 판과 반드시 같은 값이어야 한다.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")   // add-hyungchul-20261105-1625 : KGP 2.4.21 고정
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false   // add-hyungchul-20261105-1625 : Compose 컴파일러 플러그인(판 = Kotlin 판)
}