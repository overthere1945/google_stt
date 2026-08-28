// app/build.gradle.kts  —  AGP 9.x (built-in Kotlin) 기준
//
// add-hyungchul-20260826-1100
//   노이즈 저감(GTCRN / DPDFNet / NSNet2)과 Silero VAD 가 모두 ONNX Runtime 위에서 돈다.
//   모델 파일은 app/src/main/assets/ 에 넣어야 한다 → tools/download_models.sh 참고.
//   ※ git 저장소의 app/build.gradle.kts 에는 onnxruntime 이 커밋되어 있지 않았다(로컬에만 있었음).
//     이 파일이 그 누락을 포함한 최종본이다.
//
// ★ 이번 에러 원인
//   AAPT: resource style/Theme.Material3.DayNight.NoActionBar not found
//   → themes.xml 이 Material3 테마를 상속하는데, 의존성에
//     com.google.android.material 이 빠져 있었다(제가 준 dependencies 에서 누락).
//   → 같은 이유로 activity_main.xml 의 ConstraintLayout 도 의존성이 필요하다.
//
// ★ 이전 에러 원인(참고)
//   Cannot add extension with name 'kotlin' → AGP 9 built-in Kotlin 과
//   org.jetbrains.kotlin.android 플러그인 중복. plugins 에서 그 줄을 제거해 해결.

plugins {
    id("com.android.application")
    // id("org.jetbrains.kotlin.android")   ← AGP 9 built-in Kotlin 과 충돌하므로 넣지 않는다
}

android {
    namespace = "com.example.google_stt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.google_stt"
        // ML Kit GenAI Speech Recognition 라이브러리 자체의 최소 요건은 API 26이지만
        // MODE_BASIC 은 API 31+, Android Platform on-device 파일 입력은 API 33+ 이다.
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// android{} 바깥, 최상위. AGP 9 built-in Kotlin 에서도 이 확장은 그대로 제공된다.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ★ 추가 1: themes.xml 의 Theme.Material3.DayNight.NoActionBar 를 제공한다.
    //   1.12.0 은 compileSdk 34+ 에서 안전하게 동작하는 검증된 조합이다.
    //   (compileSdk 를 36 으로 올릴 계획이면 1.13.0 / 1.14.0 도 사용 가능)
    implementation("com.google.android.material:material:1.12.0")

    // ★ 추가 2: activity_main.xml 의 androidx.constraintlayout.widget.ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // ML Kit GenAI Speech Recognition — 2026-08 기준 유일한 공개 버전(알파)
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")

    // ★ add-hyungchul-20260825-1430 / 20260826-1100 : ONNX Runtime
    //   쓰는 곳: Silero VAD(silero_vad_16k_op15.onnx)
    //           GTCRN(gtcrn_simple.onnx), DPDFNet(dpdfnet_*.onnx), NSNet2(nsnet2-20ms-baseline.onnx)
    //   · 버전은 반드시 숫자로 고정한다. latest.release 를 쓰면 빌드할 때마다 런타임이 바뀌어
    //     ns_infer_ms / vad_process_ms 비교가 무의미해진다.
    //   · 1.25.1 은 2026-04-28 배포된 patch 릴리스다(Maven Central 최신은 1.29.0 / 2026-08-12).
    //   · 실제로 어떤 버전이 돌았는지는 run_meta.csv 의 onnxruntime_version 컬럼에 남는다.
    //   · settings.gradle.kts 에 mavenCentral() 이 이미 있어 저장소 추가는 필요 없다.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.25.1")

    // ※ Google Cloud STT 관련 의존성(google-cloud-speech, grpc, 서비스계정 JSON 등)은
    //    이 앱에 필요 없다. 세 경로 모두 온디바이스이며 네트워크 자격증명을 쓰지 않는다.
}
