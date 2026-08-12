plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.google_stt"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.google_stt"
        // ML Kit GenAI Speech Recognition 의 Basic 모드는 API 31 이상에서 동작합니다.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // ── 이 앱을 위해 추가한 라이브러리 ──
    // 1) Google On-Device STT (ML Kit GenAI Speech Recognition, alpha)
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")
    // 2) 코루틴 (스트리밍 Flow 수집 + 실시간 오디오 공급)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 3) lifecycleScope 사용
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // 4) SAF 로 선택한 폴더의 하위 파일 탐색/생성 (DocumentFile)
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
