# Android Platform On-device STT 적용 메모

<!-- change(add)-hyungchul-20260812-1709 -->

## 결론

`SpeechRecognizer.createOnDeviceSpeechRecognizer(context)`는 Android 공식 API가 명시한 **on-device SpeechRecognizer 생성 함수**입니다.

- API level 31부터 제공됩니다.
- `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)`가 `false`이면 사용할 수 없습니다.
- `createOnDeviceSpeechRecognizer()`는 일반 `createSpeechRecognizer()`와 달리 on-device recognition service를 요청합니다.
- 반환된 `SpeechRecognizer`는 사용 후 반드시 `destroy()`해야 합니다.

## 이 프로젝트에서 추가한 경로

기존 ML Kit GenAI Speech Recognition의 다음 두 모드는 그대로 유지합니다.

1. `base` : ML Kit `MODE_BASIC`
2. `advanced` : ML Kit `MODE_ADVANCED`

여기에 다음 비교 경로를 추가했습니다.

3. `android_ondevice` : Android Platform `SpeechRecognizer.createOnDeviceSpeechRecognizer()`

앱의 Model 선택 항목에서 `Android Platform On-device`를 선택하면 이 경로를 사용합니다.

출력 예시는 다음과 같습니다.

```text
output/google/android_ondevice/<입력 하위 구조>/<파일명>.txt
```

## 파일 입력에서 중요한 API level 차이

`createOnDeviceSpeechRecognizer()` 자체는 API 31부터 사용할 수 있지만, 이 프로젝트는 마이크가 아니라 기존 음원 파일을 평가해야 하므로 `RecognizerIntent.EXTRA_AUDIO_SOURCE`를 사용합니다.

`EXTRA_AUDIO_SOURCE`와 다음 오디오 형식 지정 extra는 API 33부터 제공됩니다.

- `EXTRA_AUDIO_SOURCE`
- `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT`
- `EXTRA_AUDIO_SOURCE_ENCODING`
- `EXTRA_AUDIO_SOURCE_SAMPLING_RATE`
- `EXTRA_SEGMENTED_SESSION`

따라서 이 프로젝트의 `android_ondevice` **파일 평가 경로는 Android 13 / API 33 이상에서만 실행**하도록 제한했습니다.

입력 PCM 형식은 기존 디코더 결과와 맞춰 다음처럼 지정합니다.

```text
Sample rate : 16000 Hz
Channel     : 1 (mono)
Encoding    : PCM 16-bit
```

## 긴 파일 처리

`EXTRA_SEGMENTED_SESSION`의 값으로 `EXTRA_AUDIO_SOURCE`를 지정했습니다. RecognitionService 구현체가 segmented session을 지원하면 `RecognitionListener.onSegmentResults()`로 여러 segment 결과를 받아 누적하고, `onEndOfSegmentedSession()`에서 종료합니다.

Android 공식 문서에 따르면 `EXTRA_AUDIO_SOURCE` 기반 segmented session은 전달한 audio source가 닫힐 때 세션이 종료됩니다.

## 반드시 알아야 하는 제한

Android 공식 문서는 `EXTRA_AUDIO_SOURCE`에 대해 **recognizer 구현체가 이 기능을 지원하지 않으면 해당 extra가 적용되지 않고 recognizer가 마이크를 열 수 있다**고 명시합니다.

따라서 다음 두 사실을 구분해야 합니다.

- `createOnDeviceSpeechRecognizer()`가 on-device recognition service를 선택한다는 점: Android API가 명시적으로 보장
- 특정 제조사/기기의 RecognitionService가 파일용 `EXTRA_AUDIO_SOURCE`를 실제 지원한다는 점: 구현체 의존

즉, `android_ondevice`는 네트워크 Cloud STT를 호출하기 위한 경로가 아니지만, **파일 주입 지원 여부까지 Android Framework가 모든 기기에서 보장하는 것은 아닙니다.** 실제 평가 장치에서 반드시 확인해야 합니다.

## 관련 Android 공식 문서

- SpeechRecognizer API: `https://developer.android.com/reference/android/speech/SpeechRecognizer`
- RecognizerIntent API: `https://developer.android.com/reference/android/speech/RecognizerIntent`
- RecognitionListener API: `https://developer.android.com/reference/android/speech/RecognitionListener`
