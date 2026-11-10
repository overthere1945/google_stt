# google_stt — 온디바이스 STT 평가 앱 (Android)

휴대폰 안에서 **엔진·모델 × VAD 조건 × 언어(kr/en)** 조합을 자동으로 모두 돌리고, 결과를 서버 실행기(`stt_all_gui_server` v8.8.0)와
같은 폴더 구조·같은 74열 CSV 로 남기는 평가 앱입니다. 결과 폴더는 서버의 WER/CER 평가기(`run_eval.py`)로 그대로 채점할 수 있습니다.

- 현재 판: **4.0.0** (versionCode 12, 2026-11-09) — 변경 내용은 [`docs/RELEASE_NOTES_v4.0.0.md`](docs/RELEASE_NOTES_v4.0.0.md), 인계는 [`docs/AI_analyze/HANDOFF_20261109_v4.0.0.md`](docs/AI_analyze/HANDOFF_20261109_v4.0.0.md) (이전 판: [`v3.0.0`](docs/RELEASE_NOTES_v3.0.0.md))
- 대상 기기: Galaxy S24 Ultra(SM-S928N, arm64-v8a). minSdk 31 / targetSdk 35 / compileSdk 37. APK 는 **arm64-v8a 전용**입니다.
- v4.0.0 은 v3.0.0 을 다시 쓴 판입니다(화면 Compose, 포그라운드 서비스, 앱 안에서 모델 받기, 서버 74열 CSV). 전사 결과는 v3.0.0 과 같습니다(오프라인 엔진 TXT 바이트 동일 — 릴리스 노트 5장).
- 네트워크: 추론은 모두 휴대폰 안에서 합니다. `INTERNET` 권한은 **모델을 받을 때만** 씁니다. 추론 중 앱의 송수신이 잡히면 그 행의 `note` 에 `WARN_NET_DELTA` 가 남습니다.

## 1. 처리 순서

```
입력 폴더(SAF) → 디코딩(16 kHz mono PCM16) → [노이즈 저감] → [VAD] → [구간 처리] → [Speech EPD] → STT → TXT·CSV
```

| 단계 | 선택지 |
|---|---|
| STT | sherpa-onnx 오프라인 13종, sherpa-onnx 스트리밍 3종, Vosk 2종, ML Kit GenAI Speech Recognition Basic 1종(합 19종) — 2-1장 |
| 언어 | 입력 폴더의 하위 폴더 이름으로 자동(kr / en). 이름으로 모르는 폴더는 처음 한 번 묻고 기억합니다 |
| VAD | 사용 안 함(NOVAD) / Silero / pyannote / FSMN / FireRed / TEN — 2장 |
| 구간 처리 | **엔진별 최대 길이**(기본) / 모든 엔진 10초 통일(`SEG10`) |
| 공급 속도(스트리밍·Vosk) | **사용 안 함 = 최대 속도**(기본) / REALTIME / D50 / D30 / D20 / D10. ML Kit 은 REALTIME 고정, 오프라인 sherpa 는 해당 없음 |
| 노이즈 저감 | **사용 안 함**(기본) / GTCRN / DPDFNet / NSNet2 / MMSE-STSA / Wiener / Spectral Subtraction |
| Speech EPD | **사용 안 함**(기본) / 세션 나누기 / 긴 무음 넣기 — ML Kit 61초 앞부분 유실 결함 회피용 |
| 실행 목적 | **시간 측정**(동시 1) / 정확도만(동시 1~3 — 오프라인 sherpa 와 ML Kit 은 각각 동시에 1개만 돕니다) |

## 2. VAD 엔진과 프리셋


| 엔진 | 라이선스(상업) | 실행 방식 | 프리셋(조건 이름) |
|---|---|---|---|
| Silero v6.2 | MIT (allowed) | ONNX Runtime | SILERO, SILERO_SAFE, SILERO_FW, SILERO_DEFAULT, SILERO_SHERPA, SILERO_QWEN3, SILERO_WS, SILERO_USER, SILERO_FILE(v3.0.0), SILERO_CUSTOM |
| pyannote seg-3.0 | MIT, HF 동의 필요 (allowed) | ONNX Runtime(직접 이식) | PYANNOTE_DEFAULT, PYANNOTE_S500, PYANNOTE_S2000, PYANNOTE_CUSTOM |
| FunASR FSMN-VAD | Apache-2.0 + 출처 표기 (allowed) | ONNX Runtime(직접 이식) | FSMN_DEFAULT(동적 무음), FSMN_CONFIG(800/0.6), FSMN_S500, FSMN_CUSTOM |
| FireRedVAD | Apache-2.0 (allowed) | ONNX Runtime(직접 이식) | FIRERED_DEFAULT, FIRERED_S500, FIRERED_S2000, FIRERED_CUSTOM |
| TEN VAD | Apache-2.0 + 추가 조건 (**conditional**) | libten_vad.so + JNA | TEN_DEFAULT, TEN_SHERPA, TEN_ASR, TEN_CUSTOM |

Silero 8종 값(threshold / minSpeech ms / minSilence ms / pad ms, 최대 길이 무제한, 사이 무음 200 ms — SILERO_FW 만 0 ms):

| 조건 | 값 | 출처 |
|---|---|---|
| SILERO | 0.5 / 250 / 400 / 150 | 프로젝트 기준선(Balanced) |
| SILERO_SAFE | 0.3 / 150 / 600 / 300 | 프로젝트 ASR Safe |
| SILERO_FW | 0.5 / 0 / 2000 / 400 | faster-whisper VadOptions |
| SILERO_DEFAULT | 0.5 / 250 / 100 / 30 | silero-vad get_speech_timestamps() |
| SILERO_SHERPA | 0.5 / 250 / 500 / 0 | sherpa-onnx SileroVadModelConfig |
| SILERO_QWEN3 | 0.5 / 1500 / 500 / 30 | Qwen3-ASR-Toolkit |
| SILERO_WS | 0.5 / 0 / 500 / 100 | whisper_streaming VADIterator |
| SILERO_USER | 0.4 / 250 / 300 / 100 | 사용자 정의 |
| SILERO_FILE (v3.0.0) | 0.5 / 250 / 400 / 150 | 서버 'VAD 단계 Silero(파일)' 조건 — 서버처럼 SILERO 와 같은 값(이름을 서버 결과 폴더와 맞추기 위함) |

- 값을 직접 고치면 자동으로 `<엔진>_CUSTOM` 으로 바뀌고, 결과 조건 이름에 값이 들어갑니다(예: `SILERO_CUSTOM_t40_sp250_si300_pad100`).
- Picovoice Cobra 는 AccessKey·인터넷이 필요하고 무료 플랜이 없어 넣지 않았습니다.

- v4.0.0 화면은 프리셋만 고릅니다(값을 직접 고치는 `<엔진>_CUSTOM` 은 화면에 아직 없습니다).

## 2-1. STT 엔진·모델 (19종)


| Model(화면) | 결과 엔진 이름 | 가중치 라이선스(상업) | 한 조각 상한 | 언어 설정 | 휴대폰에 넣는 크기(int8) |
|---|---|---|---|---|---|
| Whisper tiny / base / small / medium / turbo | `whisper-tiny` … `whisper-turbo` | MIT (allowed) | 29 s (sherpa 가 2950 프레임에서 자름) | `ko` / `en` | 약 0.1 / 0.16 / 0.37 / 0.95 / 1.04 GB |
| Qwen3-ASR 0.6B | `qwen3asr-0.6b` | Apache-2.0 (allowed) | 30 s | `Korean` / `English`(스트림 옵션) | 약 0.94 GB |
| Omnilingual ASR 300M CTC | `omnilingual-300m` | Apache-2.0 (allowed) | 39 s (공식: 40초 미만) | 없음(CTC) | 약 0.35 GB |
| **Nemotron 3.5 스트리밍 0.6B** (v2.2.0) — 지연 320 / 560 ms | `nemotron3.5-320ms` / `nemotron3.5-560ms` | OpenMDW-1.1 (allowed) | 없음(스트리밍, 파일 전체를 한 스트림) | `ko` / `en`(스트림 옵션) | 각 약 0.68 GB |
| Zipformer KO (v2.3.0) | `zipformer-ko` | Apache-2.0(icefall) / KsponSpeech 학습 (**conditional**) | 15 s (실측) | 없음(한국어 전용) | 약 0.08 GB |
| Zipformer KO 스트리밍 (v2.3.0) | `zipformer-ko-stream` | 위와 같음 (**conditional**) | 없음(스트리밍) | 없음(한국어 전용) | 약 0.13 GB |
| SenseVoice Small (v2.3.0) | `sensevoice-small` | FunASR MODEL_LICENSE (**conditional**) | 30 s (PC FSMN-VAD 최대 구간과 같게) | `ko` / `en`, ITN 켬 | 약 0.24 GB |
| Vosk small KO 0.22 / EN-US 0.15 (v2.4.0, vosk-android 0.3.75) | `vosk-small-ko` / `vosk-small-en` (모델 이름 `vosk`) | Apache-2.0 (allowed) | 없음(스트리밍, Feed 설정) | 없음(언어별 모델 — Language 와 맞는 쪽을 고른다) | 82 MB / 40 MB |
| **Zipformer EN** (v3.0.0) | `zipformer-en` | Apache-2.0(icefall) / LibriSpeech CC-BY-4.0 (allowed) | 15 s (실측) | 없음(영어 전용) | 약 0.07 GB |
| **Parakeet-TDT 0.6B v2 EN** (v3.0.0) | `parakeet-tdt-0.6b-v2` | CC-BY-4.0 (allowed) | 30 s (실측: 48.7 s 까지 정상, 73 s 부터 뒷부분 빠짐) | 없음(영어 전용, 구두점·대소문자 포함) | 약 0.66 GB |
| **Moonshine tiny / base EN** (v3.0.0) | `moonshine-tiny-en` / `moonshine-base-en` | MIT (allowed) | 25 s (서버 moonshine_stt.py 기본과 같게) | 없음(영어 전용) | 약 0.12 / 0.29 GB |

- v3.0.0 에서 **Moonshine tiny KO 를 뺐습니다** — Moonshine Community License 는 연매출 100만 달러를 넘으면 상업 사용을 할 수 없습니다(사용자 기준 '상업 이용 가능한 것만').
- 영어 전용 4종은 서버(`stt_all_gui_server`)의 sherpa `zipformer-en`·`parakeet-tdt-0.6b-v2`, moonshine 영어 Tiny/Base 와 **같은 가중치**입니다. 조각 상한 근거는 `docs/RELEASE_NOTES_v3.0.0.md` 2장.
| ML Kit GenAI Basic | `google-mlkit` (모델 이름 `basic`) | Google ML Kit 약관 (allowed) | 없음(세션 — EPD 로 나눔) | `ko-KR` / `en-US` | 기기에 설치(Google 이 관리) |

- 런타임: sherpa-onnx **1.13.8 정적 링크 AAR**(onnxruntime 1.28.2 내장), vosk-android 0.3.75, ML Kit genai-speech-recognition 1.0.0-alpha1, VAD·노이즈 저감은 onnxruntime-android 1.25.1.
- 언어 전용 모델은 맞지 않는 언어 폴더에서 자동으로 빠집니다(설정 탭 요약에 "제외 n: 언어 전용 모델" 로 보입니다).
- **Vosk 한국어 모델은 실행마다 결과가 달라집니다**(Kaldi 특징 추출의 난수 잡음 dither). 약 30회에 1회는 오류 없이 글자가 거의 나오지 않으며, 그 행에는 `note=DECODE_COLLAPSE_SUSPECT` 가 붙습니다. 8장 참고.

## 3. 빌드와 설치

1. 빌드 전에 받아 둘 것(인터넷 되는 PC, 저장소에는 넣지 않습니다)
   ```bash
   bash tools/download_stt_models.sh aar    # app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar
   bash tools/download_models.sh            # 노이즈 저감 모델(app/src/main/assets)과 TEN VAD 라이브러리(jniLibs)
   ```
2. 빌드·설치
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. 빌드 구성: AGP 9.3.1(built-in Kotlin), Gradle 9.6.1, Kotlin 2.4.21, Compose BOM 2026.09.00. 컴파일 대상은 JVM 17 입니다.

## 4. 사용법

화면은 탭 3개입니다: **설정 · 실행 · 모델**.

1. **모델 준비**(모델 탭) — 둘 중 하나
   - 앱에서 받기: 모델을 골라 [받기]. 받은 뒤 압축을 풀고 SHA256 을 검증합니다. "Wi-Fi 에서만" 설정을 지킵니다. 끊기면 이어받습니다.
   - PC 에서 가져오기(휴대폰이 외부망에 못 나갈 때)
     ```bash
     python3 tools/fetch_assets.py --only sherpa-onnx-whisper-base silero-vad   # 인자 없이 돌리면 전부(약 6.8 GB)
     adb push stt_models/stt_assets /sdcard/Documents/
     ```
     그 뒤 모델 탭 [PC 에서 가져오기] 로 `Documents/stt_assets` 를 고릅니다. 같은 해시로 검증합니다.
   - 모델은 앱 전용 저장소(`Android/data/com.example.google_stt/files/models/`)에 둡니다. 앱을 지우면 함께 지워집니다.
2. **설정 탭** — 입력 폴더(안에 `kr`·`en` 같은 언어 폴더), 출력 폴더, 모델, VAD 를 고릅니다. "고급" 에 구간 처리·공급 속도·노이즈 저감·EPD·실행 목적이 있습니다.
   - 요약 카드가 작업 수, 제외 내역, 받아야 할 모델, 예상 시간을 보여 줍니다. [조합 CSV 저장] 은 조합 목록을 출력 폴더에 `plan_<시각>.csv` 로 남깁니다.
3. **[실행]** — 언어 선택(필요할 때) → 이어 하기(같은 출력 폴더에 지난 기록이 있을 때) → 시작 전 점검(권한·배터리·VAD 적재·저장공간) → 모델 받기 확인(필요할 때) 순서로 진행합니다.
4. **실행 탭** — 진행(끝난 작업 / 전체), 현재 작업, 기기 상태(메모리·발열·배터리), 로그. [중지] 는 "지금" 과 "이 작업 끝나고" 두 가지입니다.
   - 실행은 포그라운드 서비스로 돌아 화면을 꺼도 계속됩니다. 발열이 심하면 식을 때까지 기다렸다가 이어 갑니다.
   - 앱이 꺼져도 같은 출력 폴더로 [실행] → [이어 하기] 를 누르면 끝난 작업은 건너뛰고 남은 것만 돕니다.
   - **긴 실행은 벽 충전기에 연결하고 돌립니다.** PC USB 포트로는 실행 중 소모를 따라가지 못해 방전됩니다.
5. 문제가 생기면 `adb logcat -s GoogleStt` 또는 출력 폴더의 `_app_log.txt` 를 봅니다.

## 5. 결과 폴더

```
<출력 폴더>/
  kr/                                             ← 언어 폴더(입력의 하위 폴더 이름)
    whisper-base_sherpa-int8_SILERO/Customer_Service_KR/01.txt    ← <엔진>_<모델>_<조건>/<시나리오>/<NN>.txt (전사문만)
    whisper-base_SILERO_result.csv                ← 파일별 결과, 서버 통일 CSV v2.0 74열(utf-8-sig, CRLF)
    whisper-base_SILERO_device.csv                ← 파일별 기기 계측(배터리·발열·메모리·단계별 시간)
    whisper-base_SILERO_run_meta.csv              ← 작업 1개의 환경·설정·자산 해시(key,value)
  en/ …
  _plan.csv            이번 실행의 조합 목록          _plan_progress.csv   작업별 상태(이어 하기의 근거)
  _plan_exits.csv      앱이 꺼진 이유 기록            _plan_memlog.csv     30초마다 메모리·발열
  _summary.csv         작업별 요약(소요·음원 길이·RTF)  _app_log.txt         앱 로그
```

- `<조건>` = `[NS_<노이즈>_]<VAD 프리셋 또는 NOVAD>[_SEG10][_EPD_…][_FEED_<속도>]`. `_FEED_` 는 스트리밍·Vosk 에서 공급 속도를 고른 경우에만 붙습니다.
  예) `NOVAD`, `SILERO`, `PYANNOTE_S500_SEG10`, `SILERO_FEED_REALTIME`
- 돌고 있는 작업의 결과 폴더는 `_<이름>` 이고 끝나면 `_` 가 떨어집니다. 실패한 파일은 `.txt` 대신 `<NN>.ERROR.txt`(첫 줄 `[ERROR] …`)가 생깁니다.
- 상태 값은 `OK` / `NO_MATCH` / `ERROR` 셋입니다. 그 밖의 사정은 `note` 열에 남습니다. 열 설명은 [`STT_계측_CSV_설명서.md`](STT_계측_CSV_설명서.md).

## 6. 서버 평가기로 채점

```bash
adb pull /sdcard/Documents/output/<출력 폴더> ./result
python3 <서버>/stt_all_gui_server/run_eval.py --ref <정답지>/kr --hyp ./result/<출력 폴더>/kr --out ./eval_kr
```

- 서버 `stt_common.split_algo_option` 의 엔진 이름 목록에 앱 엔진 이름 19개가 들어 있어야 CSV 행과 TXT 가 같은 이름으로 등록됩니다(2026-11-07 반영, `docs/AI_analyze/patches/`).
  **앱에 엔진을 더하면 서버 목록에도 같은 이름을 더해야 합니다.** 빠지면 같은 결과가 두 번 채점됩니다.

## 7. 소스 구성

`app/src/main/java/com/example/google_stt/` — 의존 방향은 `ui → run/data → engine → domain` 입니다.

| 위치 | 역할 |
|---|---|
| `domain/` | 순수 Kotlin(android import 없음): 모델·자산 카탈로그, 언어 판정, 조건 이름, 조합 생성, 74열 CSV 정의, VAD 구간 규칙·조각 묶기, 예상 시간 |
| `engine/` | sherpa-onnx·Vosk·ML Kit 어댑터, VAD 5종, 오디오 디코딩, 노이즈 저감 |
| `data/` | SAF 출력 트리·결과 쓰기, 자산 받기·가져오기·검증, 설정(DataStore), 기기 계측 |
| `run/` | 실행 서비스(포그라운드), 스케줄러, 작업 파이프라인, 이어 하기, 발열 대기, 메모리 기록 |
| `ui/` | Compose 화면: 설정·실행·모델 탭, 대화창 5종 |
| `AppContainer.kt`, `SttApp.kt` | 객체 수동 주입 |

시험: `./gradlew testDebugUnitTest`(단위 132개), 기기 연결 후 `./gradlew installDebug installDebugAndroidTest` → `adb shell am instrument -w com.example.google_stt.test/androidx.test.runner.AndroidJUnitRunner`(계측 37개).

## 8. 알려진 제한

- **Vosk 한국어**: 결과가 실행마다 다릅니다(dither). 앱 실행 경로 61회 중 2회는 오류 없이 43~109자(평소 600~650자)로 끝났습니다. 그런 행은 `note=DECODE_COLLAPSE_SUSPECT` 로 표시되지만, 500자 수준의 부분 저하는 표시되지 않습니다. 정확도 비교에는 여러 번 돌린 값을 쓰기를 권합니다.
- ML Kit: 공식 문서상 실시간 속도 공급만 지원합니다. `com.google.android.tts`(SODA)는 약 35세션에서 응답을 멈춥니다. 시간 초과 뒤 재시도한 파일은 CSV 에 재시도 표시가 없고 시간 값에 대기 시간이 들어갑니다. 영어 모델은 기기에 받아져 있어야 합니다.
- "지금 중지" 는 디코딩·노이즈 저감·VAD 한 파일 처리 중, 그리고 큰 오프라인 모델의 한 조각 처리 중에는 그 단계가 끝난 뒤에 멈춥니다.
- 동시 2~3 으로 돌다 앱이 죽으면 어느 작업이 원인인지 알 수 없어, 이어 할 때 같은 작업이 다시 돕니다.
- 화면을 돌리면 떠 있던 대화창이 닫힙니다. 조합 CSV 는 저장만 되고 불러오기는 없습니다. VAD·노이즈 저감·EPD 의 세부 값은 화면에서 바꿀 수 없습니다.
- 예상 시간은 실측이 쌓인 엔진만 계산합니다(없는 작업은 "실측이 없는 작업 n개는 빠짐").
- pyannote / FSMN / FireRed 는 공식 구현을 Kotlin 으로 옮긴 것입니다. 서버와 프레임 단위로 다를 수 있습니다(Silero 는 서버와 VAD 뒤 길이가 일치함을 확인했습니다).
- 24시간 연속 실행 시험(LT-01, 165작업)은 28.4시간에 완주했으나, 26시간째에 Vosk 라이브러리(Kaldi) 네이티브 충돌로 앱이 한 번 꺼졌습니다. [이어 하기]로 복구되어 끝까지 돌았습니다. 앱이 죽을 때 `_app_log.txt` 의 마지막 몇 분이 사라질 수 있습니다.
- whisper.cpp 엔진, sherpa-onnx 2.x, ML Kit alpha2 는 이번 판에 없습니다.

## 9. 라이선스 메모


앱 코드에서 쓰는 외부 모델·라이브러리의 라이선스는 각 원본을 따릅니다(Silero MIT,
pyannote segmentation-3.0 MIT(HF 동의), FunASR 모델 Apache-2.0 + 출처·모델명 표기, FireRedVAD Apache-2.0,
TEN VAD Apache-2.0 + 추가 조건("Powered by ten-vad" 표기 권장, Agora 경쟁 배포 금지), ONNX Runtime MIT, JNA Apache-2.0/LGPL,
sherpa-onnx Apache-2.0, vosk-android·Vosk 모델 Apache-2.0, Whisper 가중치 MIT, Qwen3-ASR 가중치 Apache-2.0, Omnilingual ASR 가중치 Apache-2.0,
Nemotron 3.5 OpenMDW-1.1, Parakeet-TDT 0.6B v2 CC-BY-4.0(출처 표기), Zipformer EN Apache-2.0(icefall)/LibriSpeech CC-BY-4.0, Moonshine 영어 Tiny·Base MIT,
상업 조건부: Zipformer KO(icefall Apache-2.0 / KsponSpeech 약관 미확인), SenseVoice(FunASR MODEL_LICENSE — 출처·모델명 유지, "reference and learning purposes only" 문구)).
v3.0.0 에서 Moonshine 한국어(Moonshine AI Community License — 연매출 100만 달러 이상이면 상업 사용 불가)는 뺐습니다.
