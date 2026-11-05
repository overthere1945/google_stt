# 릴리스 노트 — google_stt v3.0.0

- 배포일: 2026-11-04
- 이전 판: 2.6.0 (versionCode 10) → **3.0.0** (versionCode 11)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
  - Major: 호환되지 않는 변경(기능 삭제, 결과 폴더·설정 구조 변경)
  - Minor: 호환되는 기능 추가
  - Patch: 호환되는 버그 수정
- 이번에 Major 를 올린 이유
  - 기능을 지웠다: Android 플랫폼 on-device STT, WebRTC VAD, Moonshine tiny KO.
  - Lang 기본값 '폴더 이름으로 자동' 을 쓰면 결과 위치가 `<출력>/<언어 폴더>/…` 로 바뀐다.
  - 예전 목록 견본·설정(WEBRTC_*, moonshine-tiny-ko)은 이제 검증에서 걸린다.
- 요청(2026-11-04): 서버 `stt_all_gui_server` 에서 Python 으로 돌리는 STT 엔진 중 **갤럭시에서 도는 것, 무료, 상업 이용 가능한 것만** 앱에서 직접 돌린다.
  - Google 은 ML Kit GenAI Basic 만 쓴다.
  - VAD 는 Silero · pyannote · FSMN-VAD · FireRedVAD · TEN 이고, 값은 서버를 따른다.
  - 입력 폴더 kr / en 으로 언어를 자동으로 고른다.
  - 결과 폴더는 서버와 같은 구조로 만들고, TXT 에는 전사문만 남긴다.

## 1. 호환되지 않는 변경 (Breaking / Removed)

| 항목 | 2.6.0 | 3.0.0 | 이유 |
|---|---|---|---|
| Google STT | ML Kit Basic + Android 플랫폼 on-device | **ML Kit Basic 만** | 사용자 요청 |
| VAD 엔진 | Silero / WebRTC / pyannote / FSMN / FireRed / TEN | **Silero / pyannote / FSMN / FireRed / TEN** | 사용자 요청 |
| 의존성 | `com.github.gkonovalov.android-vad:webrtc:2.0.10` + JitPack 저장소 | 둘 다 없음 | WebRTC VAD 삭제 |
| STT 모델 | moonshine-tiny-ko 있음 | **없음** | Moonshine Community License — 연매출 100만 달러 초과 시 상업 사용 불가 |
| Lang 기본값 | English | **폴더 이름으로 자동 (kr / en)** — 예전 'auto (기기 언어)' 자리 | 사용자 요청 |
| 목록 견본 | `plan_kr_en_app_REALTIME_VAD21.csv` (525 작업) | `plan_kr_en_app_REALTIME_VAD22.csv` (616 작업) | 위 변경 반영 |

- 지운 파일: `stt/PlatformOnDeviceTranscriber.kt`, `vad/WebRtcVadDetector.kt`.
- 예전 결과 폴더(`google-platform_ondevice_*`, `*_WEBRTC_*`, `moonshine-tiny-ko_*`)는 그대로 남는다. 앱은 지우지 않는다.

## 2. 추가 (Added)

1. **폴더 이름으로 언어 자동(kr / en)** — 서버 v8.4.0 '폴더 이름으로 자동' 규칙을 옮겼다.
   - 어느 폴더를 볼지(서버 `_lang_folder_entries` 와 같은 순서)
     1. 입력 폴더 바로 아래에 이름으로 언어가 정해지는 하위 폴더가 있으면, 그 하위 폴더마다 차례로 돈다. 이름순이라 `en` 다음 `kr` 이다.
     2. 그런 하위 폴더가 없고 입력 폴더 자신의 경로로 언어가 정해지면(예: `…/input_sound/kr`), 입력 폴더 하나를 그 언어로 돌린다. 이때 결과 폴더를 나누지 않는다.
     3. 이름으로 언어를 모르는 폴더는 건너뛰고 로그 `[lang]` 에 남긴다. 서버는 이때 묻는 창을 띄우지만 앱은 묻지 않는다.
   - 이름 규칙(서버 `INPUT_LANG_WORDS` 와 같음, 대소문자 무시, 폴더 이름이 같거나 `_<낱말>` 로 끝나면 해당)
     - 한국어: `kr`·`ko`·`kor`·`korea`·`korean`·`한국어`·`*_KR`
     - 영어: `en`·`eng`·`english`·`영어`·`*_EN`
   - 결과는 `<출력>/<언어 폴더>/<엔진>_<모델>_<조건>/<입력 하위 폴더>/<음원>.txt` 와 `<출력>/<언어 폴더>/<엔진>_<조건>_result.csv` 다. 서버의 '결과 = `<결과>/<언어 폴더>/`' 와 같다.
   - 한 언어 폴더가 끝까지 돌지 못하면(모델 없음·엔진 먹통 등) 남은 언어 폴더는 돌리지 않는다.
   - 음원이 없는 언어 폴더, 그리고 언어 전용 모델이 맞지 않는 언어 폴더는 건너뛴다. 예: zipformer-ko 를 고르면 `en` 은 건너뛴다.
   - 모두 건너뛰어 돌린 것이 없으면 토스트로 알린다.
   - [목록에 추가] 는 언어를 English / 한국어 로 직접 골라야 한다. 목록은 줄마다 언어를 적기 때문이다.
   - Lang 에서 English / 한국어 를 직접 고르면 예전처럼 고른 폴더 전체를 그 언어로 돌리고, 결과도 예전처럼 출력 폴더 바로 아래에 쓴다.
2. **영어 전용 sherpa-onnx 오프라인 모델 4종** — 서버와 같은 가중치이며 모두 상업 사용 가능하다.

   | Model(화면) | 결과 엔진 이름 | 공식 패키지 | 라이선스 | 한 조각 상한 |
   |---|---|---|---|---|
   | Zipformer EN | `zipformer-en` | `sherpa-onnx-zipformer-en-2023-06-26`(encoder·joiner int8 + decoder fp32 — 서버와 같은 조합) | Apache-2.0(icefall) / LibriSpeech CC-BY-4.0 | 15 s |
   | Parakeet-TDT 0.6B v2 EN | `parakeet-tdt-0.6b-v2` | `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`(modelType `nemo_transducer`) | CC-BY-4.0 | 30 s |
   | Moonshine tiny EN | `moonshine-tiny-en` | `sherpa-onnx-moonshine-tiny-en-int8`(v1: preprocess / encode / uncached·cached decode) | MIT | 25 s |
   | Moonshine base EN | `moonshine-base-en` | `sherpa-onnx-moonshine-base-en-int8` | MIT | 25 s |

   - 받기: `bash tools/download_stt_models.sh zipformer-en parakeet-tdt-0.6b-v2 moonshine-tiny-en moonshine-base-en`
   - 조각 상한은 실측으로 정했다(2026-11-04). sherpa-onnx 1.13.8 리눅스 CLI 에 패키지 test_wavs 0·1 을 0.5 초 쉼으로 이어 붙인 음원을 넣었다.
     - Zipformer EN: 15 s 는 빠짐없음, 24.3 s 는 66→57 단어, 97 s 는 264→193 단어
     - Parakeet: 48.7 s 까지 빠짐없음, 73 s 는 198→155 단어, 97 s 는 264→156 단어
     - Moonshine tiny: 24.3 s 는 66/66 단어, 97 s 는 410 단어(반복)
     - 서버는 Zipformer EN·Parakeet 에 파일 전체를 한 번에 넣으므로, 긴 음원에서는 서버 결과에도 같은 빠짐이 생길 수 있다(4장).
3. **VAD 프리셋 `SILERO_FILE`**
   - 서버의 'VAD 단계 Silero(파일)' 조건이며, 값은 서버처럼 SILERO 와 같다(0.5 / 250 / 400 / 150).
   - 앱은 모든 VAD 를 엔진 앞에서 따로 돌리므로 계산도 SILERO 와 같다. 서버 결과 폴더 이름과 1:1 로 맞추려는 조건이다.
4. **목록 실행에서 TEN VAD 사용 가능**
   - 예전(Q40)에는 TEN 을 목록에 넣지 못했지만, 이번 요청의 VAD 5종에 TEN 이 들어 있어 허용했다.
   - 새 견본: kr 13모델 + en 15모델 × REALTIME × VAD 22개 = **616 작업**.
   - VAD 22개 = NOVAD 1 + SILERO 9 + TEN 3 + PYANNOTE 3 + FSMN 3 + FIRERED 3.

## 3. 바뀌지 않은 것
- result.csv 99열·run_meta 키·TXT 형식은 그대로다. TXT 에는 전사문만 들어가며, 화자 분리·시간은 쓰지 않는다. 서버 `--plain-text` 와 같은 형식이다.
- 목록 실행의 결과 위치(`<출력>/<kr|en>/<날짜>_<FEED>/…`)는 2.5.0 결정(Q41) 그대로다.
- sherpa-onnx 1.13.8 정적 AAR, onnxruntime-android 1.25.1, vosk-android 0.3.75, JNA 5.18.1 은 그대로다.

## 4. 알려진 제한·확인할 것
- Gradle 빌드와 실기기(S25 Ultra) 실행은 아직 하지 않았다. 작업 환경에서 Google Maven 에 접속할 수 없기 때문이다.
- 새 영어 4종의 실기기 속도·메모리는 아직 모른다. 특히 Parakeet encoder 는 int8 652 MB 다.
- '폴더 이름으로 자동' 에서 모르는 폴더는 묻지 않고 건너뛴다(서버 v8.6.0 의 '기억한 값' 기능은 없다).
- 서버(`sherpa_onnx_stt.py`)는 zipformer-en·parakeet 에 파일 전체를 한 번에 넣는다. 위 실측대로라면 70 초가 넘는 영어 음원에서 서버 결과가 짧아질 수 있다.
  - 서버 코드는 바꾸지 않았다(요청 범위 밖). 서버 결과와 비교할 때 참고한다.

## 5. 검증
| 단계 | 결과 |
|---|---|
| 컴파일(차등) | kotlinc 2.0.21 + android-35 android.jar + sherpa-onnx 1.13.8 AAR classes.jar + 일부 AndroidX 스텁. v2.6.0 과 3.0.0 을 같은 조건으로 컴파일해 오류 목록을 비교했다. **새 오류 0건**(남은 오류는 두 판에 같은 스텁·의존성 부족분). |
| JVM 시험 | 언어 폴더 규칙 15건(서버 규칙·tree_text 구조·`*_KR`·SAF 문서 ID), 견본 CSV 616 작업 검증 오류 0, 잘못된 줄(언어 전용 모델·삭제한 모델·WEBRTC) 4건 모두 걸림 — 23 / 23 통과 |
| sherpa 실제 실행 | sherpa-onnx 1.13.8 리눅스 CLI 로 새 4종 설정(파일 조합·modelType)이 불러와지고 정답과 같은 글자가 나오는 것을 확인. 조각 상한 실측(2장) |
| 패키지 내용 | GitHub Releases `asr-models` 의 tar.bz2 4개 목록을 받아 다운로드 스크립트의 파일 이름과 대조(일치) |
| 독립 검토 | 컴파일·API·남은 참조·START 처리에서 결함 없음. 지적 3건(빈 언어 폴더가 뒤 폴더를 멈춤, 경로 없는 문서 ID, 모두 건너뛸 때 알림 없음)을 고친 뒤 다시 차등 컴파일했다(새 오류 0). |
