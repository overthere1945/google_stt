# google_stt — 온디바이스 STT 평가 앱 (Android)

입력 폴더의 음성 파일을 16 kHz/mono/PCM16 으로 변환한 뒤, **휴대폰 안에서** STT 로 일괄 전사하고
지연·자원을 계측하는 평가 앱입니다. 결과는 PC "STT 통합 실행기"와 같은 폴더·조건 이름 규칙으로 저장되어
PC 쪽 WER/CER 평가기에서 그대로 비교할 수 있습니다.

- 현재 판: **2.4.0** (2026-10-11) — 변경 내용은 [`docs/RELEASE_NOTES_v2.4.0.md`](docs/RELEASE_NOTES_v2.4.0.md) (이전: [`v2.3.0`](docs/RELEASE_NOTES_v2.3.0.md), [`v2.2.0`](docs/RELEASE_NOTES_v2.2.0.md), [`v2.1.1`](docs/RELEASE_NOTES_v2.1.1.md), [`v2.1.0`](docs/RELEASE_NOTES_v2.1.0.md), [`v2.0.0`](docs/RELEASE_NOTES_v2.0.0.md))
- 대상 기기: Galaxy S25 Ultra (arm64-v8a), minSdk 31 / targetSdk 35 — APK 는 **arm64-v8a 전용**(`abiFilters`, 에뮬레이터·32비트 기기 설치 불가)
- 네트워크: **INTERNET 권한 없음**(온디바이스 실행 증명용). VAD·노이즈 저감 모델은 APK 에 넣고, STT 모델(sherpa-onnx)은 휴대폰 공용 폴더 `/sdcard/Documents/stt_models` 에 넣고 앱은 **'모든 파일 접근'** 권한으로 읽습니다(v2.1.1).

## 1. 처리 순서

```
입력 폴더(SAF) → 디코딩(16 kHz mono PCM16) → [노이즈 저감] → [VAD] → [구간 처리] → [Speech EPD] → STT → TXT·CSV
```

| 단계 | 선택지 |
|---|---|
| STT | ML Kit GenAI Speech Recognition **Basic**(API 31+), Android 플랫폼 on-device(API 33+), **sherpa-onnx 오프라인**: Whisper tiny/base/small/medium/turbo, Qwen3-ASR 0.6B, Omnilingual 300M, **sherpa-onnx 스트리밍**: Nemotron 3.5 0.6B(지연 320 / 560 ms), **상업 조건부(v2.3.0)**: Zipformer KO(오프라인·스트리밍), SenseVoice Small, Moonshine tiny KO, **Vosk(v2.4.0)**: small KO 0.22 / EN-US 0.15 |
| 노이즈 저감 | 사용 안 함 / GTCRN / DPDFNet / NSNet2 / MMSE-STSA / Wiener / Spectral Subtraction |
| VAD 엔진 | 사용 안 함 / **Silero** / **WebRTC** / **pyannote** / **FSMN** / **FireRed** / **TEN** |
| 구간 처리 | **엔진별 최대 길이**(Google = 기존: 이어 붙여 1세션 + EPD) / **모든 엔진 10초 통일**(SEG10) |
| Speech EPD | OFF / A안(세션 분할) / B안(긴 무음) — ML Kit 61초 앞부분 유실 결함 회피용, SEG10·sherpa 엔진에서는 쓰지 않음 |

> v2.0.0 에서 **ML Kit Advanced(Pixel 10 전용)** 를 제거했습니다.

## 2. VAD 엔진과 프리셋

| 엔진 | 라이선스(상업) | 실행 방식 | 프리셋(조건 이름) |
|---|---|---|---|
| Silero v6.2 | MIT (allowed) | ONNX Runtime | SILERO, SILERO_SAFE, SILERO_FW, SILERO_DEFAULT, SILERO_SHERPA, SILERO_QWEN3, SILERO_WS, SILERO_USER, SILERO_CUSTOM |
| WebRTC VAD | BSD-3 / android-vad MIT (allowed) | android-vad 2.0.10 | WEBRTC_DEFAULT, WEBRTC_LIB, WEBRTC_ASR, WEBRTC_CUSTOM |
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

- 값을 직접 고치면 자동으로 `<엔진>_CUSTOM` 으로 바뀌고, 결과 조건 이름에 값이 들어갑니다(예: `SILERO_CUSTOM_t40_sp250_si300_pad100`).
- Picovoice Cobra 는 AccessKey·인터넷이 필요하고 무료 플랜이 없어 넣지 않았습니다.

## 2-1. STT 엔진 — sherpa-onnx 오프라인 (v2.1.0)

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
| Moonshine tiny KO (v2.3.0) | `moonshine-tiny-ko` | Moonshine Community License (**conditional** — 연매출 100만 달러 이상 상업 사용 불가) | **9 s** (실측: 9.3 s 이상 빈 결과) | 없음(한국어 전용) | 약 0.07 GB |

- 런타임: sherpa-onnx **1.13.7 정적 링크 AAR**(onnxruntime 내장 — 앱의 onnxruntime-android 1.25.1 과 따로 돈다), CPU 4 스레드.
- 실행: 실시간 공급 없이 **최대 속도로 디코딩**한다. 모델은 배치 시작 때 1번 불러 끝에 해제한다(불러온 시간은 run_meta `stt_model_load_ms`).
- **Nemotron(스트리밍)** 은 Feed 설정대로 100 ms 조각을 넣는다(REALTIME = 실시간). 지연 비교는 `tail_ms`·`first_result_ms` 로 한다. 결과 글자는 토큰을 이어 만든다(sherpa 스트리밍 `text` 가 한국어 띄어쓰기를 지우는 문제 회피). 값 뜻은 `STT_계측_CSV_설명서.md` 7장.
- **상업 조건부 4종**(v2.3.0, 사용자 결정 Q5): 화면 이름 끝에 `[상업 조건부]`, run_meta `stt_commercial=conditional`. Zipformer KO 스트리밍은 Nemotron 과 같은 스트리밍 경로(Feed 설정)이고, Zipformer KO 두 종은 토큰으로 띄어쓰기를 만든다. **Moonshine 은 "10초 통일" 에서도 9 s 조각**으로 줄인다(run_meta `stt_chunk_max_sec=9.0`). 근거는 `docs/RELEASE_NOTES_v2.3.0.md`.
- Cohere Transcribe 2B 는 **보류**(v2.2.0, 사용자 결정 Q25): sherpa int8 판이 실제 한국어 대화 음원에서 반복·영어 섞임이 심하고 메모리 약 3.4 GB — 근거는 `docs/HANDOFF_20261011_v2.2.0.md`.
- 구간 처리: "엔진별 최대 길이" = 위 조각 상한으로 말 구간을 묶고 자른다. "10초 통일" = 10 s. Speech EPD 는 쓰지 않는다.
- 결과 이름: `whisper-base_sherpa-int8_<조건>/…`, `whisper-base_<조건>_result.csv` (모델마다 CSV 가 따로 생긴다).
- 모델 위치(휴대폰): `/sdcard/Documents/stt_models/<패키지>/` — 앱은 **'모든 파일 접근'(MANAGE_EXTERNAL_STORAGE)** 으로 읽는다. 앱을 지워도 모델은 남는다(v2.1.1, 사용자 결정 Q24).
  - v2.1.0 의 앱 전용 폴더(`Android/data/…/files/models`)는 adb 가 만든 폴더를 앱이 읽지 못해(`canRead=false`) 바꿨다. 예전 폴더는 `adb shell rm -rf /sdcard/Android/data/com.example.google_stt/files/models` 로 지운다.

## 3. 처음 설정

1. 모델 받기(프로젝트 루트, 인터넷 되는 PC):
   ```bash
   bash tools/download_models.sh        # Silero + GTCRN + DPDFNet + VAD 추가분(pyannote/FSMN/FireRed/TEN)
   bash tools/download_models.sh all    # 위 + NSNet2 + DPDFNet 2/4/8
   ```
   - assets: `silero_vad_16k_op15.onnx`, `pyannote_seg3.onnx`, `fsmn_vad_quant.onnx`, `fsmn_vad_am.mvn`,
     `fireredvad_vad.onnx`, `fireredvad_cmvn.ark`, 노이즈 저감 모델
   - jniLibs: `arm64-v8a/libten_vad.so`, `armeabi-v7a/libten_vad.so`
   - 해시 확인: `cd app/src/main/assets && sha256sum -c models.sha256`
2. sherpa-onnx AAR·STT 모델 받기(빌드 전에 AAR 이 꼭 있어야 합니다):
   ```bash
   bash tools/download_stt_models.sh aar             # app/libs/sherpa-onnx-static-link-onnxruntime-1.13.7.aar
   ```
3. Android Studio 에서 Gradle Sync → Run(실기기). WebRTC VAD 는 JitPack 저장소에서 받습니다.
4. USB 디버깅을 연결하고 STT 모델을 휴대폰 공용 폴더에 넣기(앱 설치 여부와 무관, MTP·파일 관리자로 `Documents/stt_models` 에 복사해도 된다):
   ```bash
   bash tools/download_stt_models.sh                 # 기본: whisper-base + qwen3asr-0.6b + omnilingual-300m + nemotron3.5-560ms (+aar)
   bash tools/download_stt_models.sh whisper-tiny zipformer-ko vosk-small-ko   # 고른 모델만 / all = 15종 전부(약 5.6 GB)
   ```
5. 앱에서 입력 폴더·출력 폴더 선택 → 언어·Model·Feed·노이즈 저감·VAD 엔진·프리셋·구간 처리·EPD 선택 → START.
   - sherpa 모델로 처음 START 하면 '모든 파일 접근 허용' 설정 화면이 열린다 → 이 앱을 켜고 돌아와 다시 START.
6. 문제가 생기면 Android Studio **Logcat** 에서 `tag:GoogleStt` 로 봅니다. 화면 로그와 같은 줄(Log.i)에 더해 `[D]` 로 시작하는 디버그 줄(Log.d)이 함께 나옵니다(디버그 줄은 화면 로그창에는 나오지 않습니다).
   - 디버그 줄만: `tag:GoogleStt message:[D]` / sherpa 만: `tag:GoogleStt message:[D][sherpa]`
   - `[D][batch]` 배치 시작·끝·중단·warm-up, `[D][file]` 파일 시작·끝·디코딩 상세, `[D][stt]` STT 에 넣는 조각 길이·결과 요약, `[D][sherpa]` 모델 폴더 점검(있는지·읽을 수 있는지·크기)·설정 경로·불러오기 시간·조각별 디코딩 시간

## 4. 결과 폴더 (PC GUI v7.7 평탄화 규칙)

```
<출력 폴더>/
  google-mlkit_basic_<조건>/<입력 하위폴더>/<파일명>.txt
  google-platform_ondevice_<조건>/…
  whisper-base_sherpa-int8_<조건>/…    ← sherpa 엔진(CSV 는 whisper-base_<조건>_result.csv)
  google-mlkit_<조건>_result.csv        ← 파일별 계측(utf-8-sig)
  google-mlkit_<조건>_run_meta.csv      ← 실행 1회 환경 요약
```

- `<조건>` = `[NS_<노이즈>_]<VAD 프리셋 또는 NOVAD>[_SEG10][_EPD_…]`
  예) `NOVAD`, `SILERO_SAFE`, `NS_GTCRN_LIM12_SILERO`, `PYANNOTE_S500_SEG10`, `SILERO_EPD_SPLIT_T45_M55`
- result.csv 는 기존 91개 열 뒤에 `vad_engine, vad_preset, vad_params, vad_commercial, seg_mode, seg_chunks, seg_splits, condition` 을 덧붙였습니다(앞 열 순서 그대로). 열 설명은 `STT_계측_CSV_설명서.md`.

## 5. 소스 구성

| 위치 | 역할 |
|---|---|
| `MainActivity.kt` | 화면 초기화, 폴더 선택, 권한, START 입력 검증, 조건 이름 조합, 로그·진행률 |
| `batch/BatchRunner.kt` | 배치 실행 본문(디코딩 → 노이즈 저감 → VAD → 구간 처리 → EPD → STT → 저장), preflight, CSV 공통 열 |
| `stt/` | `SttModels`(경로·공급 속도·결과형), `SttConstants`, `SttUtil`, `PcmFeeder`, `MlKitTranscriber`, `PlatformOnDeviceTranscriber`, `GoogleSttSession`(세션 수·할당량 재시도·조각 합치기·warm-up), `SherpaModels`(sherpa 모델 목록), `SherpaOfflineTranscriber`(sherpa 불러오기·디코딩) |
| `vad/` | `VadModels`(엔진·항목·프리셋), `VadPipeline`(공통 계약·이어 붙이기·팩토리), `FrameSegmenter`(Silero 방식 구간 규칙), `KaldiFbank`, `SegmentPacker`(10초 조각), 엔진별 `*VadDetector` |
| `ui/` | `VadUi`, `EpdUi`, `NoiseUi`, `UiUtils`, `Selections` |
| `io/` | `AudioInput`(입력 폴더 탐색), `ResultStore`(결과 저장) |
| `SileroVadProcessor.kt` | Silero ONNX 실행기(VadDetector 구현), `VadConfig`, `VadSegment`, `VadResult` |
| `SpeechEpd.kt`, `SttTelemetry.kt`, `AudioDecoder.kt`, `denoise/` | 기존 그대로(계측 열 추가만) |

## 6. 알려진 제한

- ML Kit 은 공식 문서상 **실시간 속도 공급만 지원**합니다. WER/CER 확정 실행은 REALTIME 으로 하세요.
- `com.google.android.tts`(SODA)는 약 **35세션**에서 응답을 멈춥니다. 10초 통일은 파일당 여러 세션을 써서 한도에 빨리 닿습니다.
- pyannote / FSMN / FireRed 는 공식 PyTorch/C++ 구현을 Kotlin 으로 옮긴 것입니다. PC 결과와 프레임 단위로 다를 수 있으니 같은 음원으로 비교해 확인하세요.
- 2단계 엔진은 Vosk(v2.4.0)까지 넣었습니다(보류: Cohere 2B). 결정 사항은 `docs/HANDOFF_20261011_v2.4.0.md`.
- Vosk 는 이 작업 환경에서 모델을 받을 수 없어(alphacephei.com 접속 차단) 실음성 실행을 확인하지 못했습니다. 컴파일은 vosk-api 0.3.75 공식 소스로 확인했습니다.
- sherpa 엔진의 실기기 속도·메모리·두 onnxruntime(앱 1.25.1 + sherpa 정적) 공존은 아직 실기기에서 확인하지 않았습니다.
- APK 는 arm64-v8a 만 넣습니다(`app/build.gradle.kts` 의 `ndk.abiFilters`). 에뮬레이터용으로 x86_64 를 더할 수는 있지만(정적이라 충돌 없음), TEN VAD `.so` 는 x86_64 판이 없습니다.
- sherpa 정적 AAR 은 x86 에서만 `libonnxruntime.so`(ORT 1.27.1)를 따로 넣어 앱의 onnxruntime-android 1.25.1 과 경로가 겹칩니다. 그래서 `packaging.jniLibs.excludes` 에 `lib/x86/libonnxruntime.so` 를 넣었습니다. **`abiFilters` 만으로는 풀리지 않습니다** — AGP 는 모든 ABI 를 먼저 합치면서 중복을 검사하고, `abiFilters` 는 APK 를 묶을 때만 적용하기 때문입니다.
- USB(MTP)로 PC 에서 결과 폴더를 보면 앱이 막 쓴 파일이 0바이트로 보일 수 있습니다(휴대폰 미디어 색인 미갱신). `adb pull` 이나 USB 재연결로 확인하세요.

## 7. 라이선스 메모

앱 코드에서 쓰는 외부 모델·라이브러리의 라이선스는 각 원본을 따릅니다(Silero MIT, android-vad MIT/WebRTC BSD-3,
pyannote segmentation-3.0 MIT(HF 동의), FunASR 모델 Apache-2.0 + 출처·모델명 표기, FireRedVAD Apache-2.0,
TEN VAD Apache-2.0 + 추가 조건("Powered by ten-vad" 표기 권장, Agora 경쟁 배포 금지), ONNX Runtime MIT, JNA Apache-2.0/LGPL,
sherpa-onnx Apache-2.0, vosk-android·Vosk 모델 Apache-2.0, Whisper 가중치 MIT, Qwen3-ASR 가중치 Apache-2.0, Omnilingual ASR 가중치 Apache-2.0,
Nemotron 3.5 OpenMDW-1.1, 상업 조건부: Zipformer KO(icefall Apache-2.0 / KsponSpeech 약관 미확인), SenseVoice(FunASR MODEL_LICENSE — 출처·모델명 유지, "reference and learning purposes only" 문구),
Moonshine 한국어(Moonshine AI Community License — 상업 사용 시 등록, 연매출 100만 달러 이상이면 종료, "Powered by Moonshine AI" 표시)).
