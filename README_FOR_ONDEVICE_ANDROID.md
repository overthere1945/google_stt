# Google On-Device STT 안드로이드 앱 (ML Kit GenAI Speech Recognition)

폴더 안의 음성 파일을 **기기 내부(On-Device)** 에서 **ML Kit GenAI Speech
Recognition** 으로 전사하는 안드로이드 앱입니다. 화면에는 **Input / Output /
Lang / Start** 가 있고, Start 를 누르면 Input 폴더(하위 폴더 포함)의 모든 음성
파일이 전사되어 Output 폴더에 **입력과 동일한 폴더 구조**로 `.txt` 저장됩니다.

---

## 1. 어떤 파일을 교체/추가하나요?

받으신 프로젝트(`com.example.google_stt`)에 아래처럼 넣으세요.

| 파일 | 위치 | 비고 |
|---|---|---|
| `MainActivity.kt` | `app/src/main/java/com/example/google_stt/` | 교체 |
| `AudioDecoder.kt` | `app/src/main/java/com/example/google_stt/` | **새로 추가** |
| `activity_main.xml` | `app/src/main/res/layout/` | 교체 |
| `strings.xml` | `app/src/main/res/values/` | 교체 |
| `AndroidManifest.xml` | `app/src/main/` | 교체(INTERNET 권한 추가) |
| `build.gradle.kts` | `app/` | 교체(의존성/최소SDK 수정) |

`colors.xml`, `themes.xml` 은 그대로 두면 됩니다.

---

## 2. 빌드 설정에서 꼭 확인할 점

### (1) Kotlin 플러그인
`build.gradle.kts` 상단 `plugins` 블록에 **Kotlin Android 플러그인**이 있어야 합니다.
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)   // ← 이 줄
}
```
만약 빌드 시 `Unresolved reference: kotlin` 또는 `libs.plugins.kotlin.android`
오류가 나면, 프로젝트의 `gradle/libs.versions.toml` 에 아래를 추가하세요.
```toml
[versions]
kotlin = "2.0.21"

[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```
(또는 위 alias 줄 대신 `id("org.jetbrains.kotlin.android") version "2.0.21"` 사용)

### (2) 최소 SDK = 31
ML Kit Speech Recognition 의 **Basic 모드는 API 31 이상**에서 동작하므로
`minSdk = 31` 로 올렸습니다.

### (3) 추가된 의존성 (build.gradle.kts 에 이미 포함)
```kotlin
implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
implementation("androidx.documentfile:documentfile:1.0.1")
```

---

## 3. 사용 방법 (앱 화면)

1. **Input** 버튼 → 음성 파일이 들어 있는 **폴더**를 선택합니다.
   (그 폴더의 하위 폴더까지 모두 탐색합니다.)
2. **Output** 버튼 → 결과를 저장할 **폴더**를 선택합니다.
3. **Lang** → 입력 음성의 언어를 고릅니다. **영어(en-US) / 한국어(ko-KR) / auto**.
4. **Start** → 전사를 시작합니다.
   - 시작할 때 **"STT 를 시작합니다."** Toast,
   - 끝나면 **"STT 완료 (성공 N, 실패 M)"** Toast 가 뜹니다.
   - 화면 아래 로그로 파일별 진행 상황을 볼 수 있습니다.

### 결과 저장 구조 (요청하신 예시와 동일)
입력이 `input_folder/vLog/01.wav` 이면 →
```
output_folder/google/base/vLog/01.txt        ← Basic 모드 결과
output_folder/google/advanced/vLog/01.txt    ← Advanced(GenAI) 모드 결과
```
즉 `output_folder/google/<모델>/<입력 하위 구조>/<파일명>.txt` 형식으로,
**base(Basic)** 와 **advanced(Advanced)** 두 모델 결과를 각각 만듭니다.

---

## 4. 중요한 제약 사항 (공식 문서 기준, 꼭 읽어주세요)

이 API 는 현재 **alpha** 단계이며 아래 제약이 있습니다. 코드는 이를 모두 고려해
작성했지만, 기기에 따라 동작 여부가 달라집니다.

### (A) Basic vs Advanced
- **Basic**: 기존 온디바이스 음성모델. **API 31 이상 대부분의 안드로이드 기기**에서 동작.
  한국어(ko-KR)·영어(en-US) 지원.
- **Advanced**: **Gemini Nano(GenAI) 모델**. 정확도·언어 폭이 넓지만
  **현재 Pixel 10 계열에서만** 사용 가능(점차 확대 예정).
  → **Pixel 10 이 아니면** Advanced 는 사용할 수 없고, `advanced/*.txt` 에
  `"[ERROR] 모델 사용 불가..."` 가 기록됩니다(앱은 멈추지 않고 계속 진행).

### (B) 파일 입력은 "실시간 속도"로 공급됩니다
ML Kit STT 는 파일을 한 번에 읽지 못하고 **마이크처럼 실시간 스트림**으로만
받습니다(공식 제약: *"파일을 전체 속도로 읽는 방식은 지원하지 않음"*).
그래서 이 앱은 오디오를 **16kHz·모노·16bit PCM 으로 변환**한 뒤
**초당 약 32KB(실시간 속도)** 로 인식기에 흘려보냅니다.
→ **전사에는 오디오 길이만큼 시간이 걸립니다**(예: 1분 파일 ≈ 1분 소요).
   이는 온디바이스 스트리밍 STT 의 구조적 특성입니다.

### (C) 언어(Lang)
- ML Kit STT 는 **하나의 언어(locale)** 를 지정해야 합니다.
  영어→`en-US`, 한국어→`ko-KR`.
- **"auto" 는 진짜 언어 자동감지가 아닙니다.** 이 API 에는 언어 자동감지가 없어,
  auto 를 고르면 **기기 시스템 언어**를 따릅니다(한국어 기기면 ko-KR, 그 외 en-US).
  정확한 결과를 원하면 **영어/한국어를 직접 선택**하는 것을 권장합니다.

### (D) 지원 기기/환경
- 부트로더가 **언락(unlocked)** 된 기기에서는 동작하지 않습니다.
- 기기 최초 설정 직후에는 AICore 초기화가 끝나지 않아 잠시 실패할 수 있습니다.
  네트워크 연결 후 몇 분~몇 시간 기다리거나 재부팅하면 해결됩니다.
- 첫 실행 시 모델 다운로드가 필요할 수 있어 **인터넷 연결**을 권장합니다.

---

## 5. 지원 오디오 형식
`mp3, wav, m4a, flac, ogg, aac, mp4, wma, opus` 를 처리합니다.
내부적으로 안드로이드 `MediaCodec/MediaExtractor` 로 디코딩 후
16kHz·모노·16bit PCM 으로 변환합니다.
(WAV 는 16-bit PCM 형식을 권장합니다.)

---

## 6. 자주 나는 문제

| 증상 | 해결 |
|---|---|
| `advanced` 결과가 전부 ERROR | 기기가 Pixel 10 이 아니면 정상입니다. Basic(`base`) 결과를 사용하세요. |
| `Unresolved reference: kotlin.plugins...` | 2-(1) 의 Kotlin 플러그인/카탈로그 설정을 확인하세요. |
| 첫 실행에서 모델 관련 오류 | 인터넷 연결 후 몇 분 기다렸다가 재시도(또는 재부팅). |
| 전사가 오래 걸림 | 정상입니다. 실시간 스트리밍이라 오디오 길이만큼 시간이 걸립니다. |
| 결과가 비어 있음 | 무음이거나 해당 언어/모델이 그 오디오를 인식하지 못한 경우입니다. Lang 을 바꿔보세요. |
| 폴더 선택이 안 됨 | Input/Output 모두 "폴더"를 선택해야 합니다(파일 아님). |

---

## 7. 동작 원리 요약 (개발자용)
1. **SAF(Storage Access Framework)** 로 Input/Output **폴더 트리**를 선택하고
   `DocumentFile` 로 하위 폴더를 재귀 탐색합니다(별도 저장소 권한 불필요).
2. 각 오디오를 `AudioDecoder` 가 **16kHz/모노/16bit PCM** 으로 변환합니다.
3. `ParcelFileDescriptor.createPipe()` 의 write 쪽에 PCM 을 **실시간 속도**로 쓰고,
   read 쪽을 `AudioSource.fromPfd()` 로 인식기에 전달합니다.
4. `SpeechRecognizer.startRecognition().collect { }` 로 스트리밍 결과를 모으고,
   오디오가 끝나면 `stopRecognition()` 으로 마무리합니다.
5. `FinalTextResponse.text` 들을 이어 붙여 `.txt` 로 저장합니다.
6. **Basic → base/**, **Advanced → advanced/** 두 모드를 파일마다 실행합니다.
