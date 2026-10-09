# google_stt — 온디바이스 STT 평가 앱 (Android)

입력 폴더의 음성 파일을 16 kHz/mono/PCM16 으로 변환한 뒤, **휴대폰 안에서** STT 로 일괄 전사하고
지연·자원을 계측하는 평가 앱입니다. 결과는 PC "STT 통합 실행기"와 같은 폴더·조건 이름 규칙으로 저장되어
PC 쪽 WER/CER 평가기에서 그대로 비교할 수 있습니다.

- 현재 판: **2.0.0** (2026-10-09) — 변경 내용은 [`docs/RELEASE_NOTES_v2.0.0.md`](docs/RELEASE_NOTES_v2.0.0.md)
- 대상 기기: Galaxy S25 Ultra (arm64-v8a), minSdk 31 / targetSdk 35
- 네트워크: **INTERNET 권한 없음**(온디바이스 실행 증명용). 모델은 미리 받아 APK 에 넣습니다.

## 1. 처리 순서

```
입력 폴더(SAF) → 디코딩(16 kHz mono PCM16) → [노이즈 저감] → [VAD] → [구간 처리] → [Speech EPD] → STT → TXT·CSV
```

| 단계 | 선택지 |
|---|---|
| STT | ML Kit GenAI Speech Recognition **Basic**(API 31+), Android 플랫폼 on-device(API 33+) |
| 노이즈 저감 | 사용 안 함 / GTCRN / DPDFNet / NSNet2 / MMSE-STSA / Wiener / Spectral Subtraction |
| VAD 엔진 | 사용 안 함 / **Silero** / **WebRTC** / **pyannote** / **FSMN** / **FireRed** / **TEN** |
| 구간 처리 | **엔진별 최대 길이**(Google = 기존: 이어 붙여 1세션 + EPD) / **모든 엔진 10초 통일**(SEG10) |
| Speech EPD | OFF / A안(세션 분할) / B안(긴 무음) — ML Kit 61초 앞부분 유실 결함 회피용, SEG10 에서는 쓰지 않음 |

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
2. Android Studio 에서 Gradle Sync → Run(실기기). WebRTC VAD 는 JitPack 저장소에서 받습니다.
3. 앱에서 입력 폴더·출력 폴더 선택 → 언어·Model·Feed·노이즈 저감·VAD 엔진·프리셋·구간 처리·EPD 선택 → START.

## 4. 결과 폴더 (PC GUI v7.7 평탄화 규칙)

```
<출력 폴더>/
  google-mlkit_basic_<조건>/<입력 하위폴더>/<파일명>.txt
  google-platform_ondevice_<조건>/…
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
| `stt/` | `SttModels`(경로·공급 속도·결과형), `SttConstants`, `SttUtil`, `PcmFeeder`, `MlKitTranscriber`, `PlatformOnDeviceTranscriber`, `GoogleSttSession`(세션 수·할당량 재시도·조각 합치기·warm-up) |
| `vad/` | `VadModels`(엔진·항목·프리셋), `VadPipeline`(공통 계약·이어 붙이기·팩토리), `FrameSegmenter`(Silero 방식 구간 규칙), `KaldiFbank`, `SegmentPacker`(10초 조각), 엔진별 `*VadDetector` |
| `ui/` | `VadUi`, `EpdUi`, `NoiseUi`, `UiUtils`, `Selections` |
| `io/` | `AudioInput`(입력 폴더 탐색), `ResultStore`(결과 저장) |
| `SileroVadProcessor.kt` | Silero ONNX 실행기(VadDetector 구현), `VadConfig`, `VadSegment`, `VadResult` |
| `SpeechEpd.kt`, `SttTelemetry.kt`, `AudioDecoder.kt`, `denoise/` | 기존 그대로(계측 열 추가만) |

## 6. 알려진 제한

- ML Kit 은 공식 문서상 **실시간 속도 공급만 지원**합니다. WER/CER 확정 실행은 REALTIME 으로 하세요.
- `com.google.android.tts`(SODA)는 약 **35세션**에서 응답을 멈춥니다. 10초 통일은 파일당 여러 세션을 써서 한도에 빨리 닿습니다.
- pyannote / FSMN / FireRed 는 공식 PyTorch/C++ 구현을 Kotlin 으로 옮긴 것입니다. PC 결과와 프레임 단위로 다를 수 있으니 같은 음원으로 비교해 확인하세요.
- STT 엔진 추가(sherpa-onnx: Whisper / Qwen3-ASR / Omnilingual / Nemotron 3.5 / Cohere / SenseVoice / Zipformer, Vosk)는 다음 단계입니다. 결정 사항은 `docs/HANDOFF_20261009_v2.0.0.md`.

## 7. 라이선스 메모

앱 코드에서 쓰는 외부 모델·라이브러리의 라이선스는 각 원본을 따릅니다(Silero MIT, android-vad MIT/WebRTC BSD-3,
pyannote segmentation-3.0 MIT(HF 동의), FunASR 모델 Apache-2.0 + 출처·모델명 표기, FireRedVAD Apache-2.0,
TEN VAD Apache-2.0 + 추가 조건("Powered by ten-vad" 표기 권장, Agora 경쟁 배포 금지), ONNX Runtime MIT, JNA Apache-2.0/LGPL).
