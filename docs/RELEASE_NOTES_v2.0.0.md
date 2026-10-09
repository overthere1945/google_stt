# 릴리스 노트 — google_stt v2.0.0

- 배포일: 2026-10-09
- 이전 판: 1.0 (versionCode 1) → **2.0.0** (versionCode 2)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
  - Major: 호환되지 않는 변경(기능 삭제, 결과 폴더·설정 구조 변경)
  - Minor: 호환되는 기능 추가
  - Patch: 호환되는 버그 수정
- 이번에 Major 를 올린 이유: ML Kit Advanced 경로 삭제, VAD Profile(Default/Safe/Custom) 삭제, 결과 폴더 규칙 변경으로 예전 결과·설정과 호환되지 않는다.

## 1. 호환되지 않는 변경 (Breaking)

| 항목 | 예전 (1.0) | 지금 (2.0.0) |
|---|---|---|
| Model | Basic / **Advanced(Pixel 10)** / Android 플랫폼 | Basic / Android 플랫폼 (Advanced 삭제) |
| VAD 설정 | 스위치 ON/OFF + Profile Default / Safe / Custom | VAD 엔진 선택 → 프리셋 선택 → 엔진별 값(Custom) |
| 결과 폴더 | `output/google/<모드>/[ns_*/][vad_*/][epd_*/]…` | `output/<엔진>_<모델>_<조건>/…` (PC GUI v7.7 평탄화) |
| CSV 위치 | `…/<모드>/…/result.csv`, `run_meta.csv` | `output/<엔진>_<조건>_result.csv`, `…_run_meta.csv` |
| `vad_profile` 값 | "Default (GUI 기본값)" | 조건 이름(`SILERO`, `SILERO_SAFE`, …) |

- 예전 Default = 새 `SILERO`(0.5/250/400/150), 예전 Safe = 새 `SILERO_SAFE`(0.3/150/600/300) — 값은 같다.

## 2. 새 기능 (Added)

1. **VAD 엔진 6종**: Silero, Google WebRTC VAD, pyannote segmentation-3.0, FunASR FSMN-VAD, FireRedVAD, TEN VAD
2. **엔진별 프리셋**(공식 기본값 + 추천값 + Custom)
   - Silero 8종: SILERO / SILERO_SAFE / SILERO_FW / SILERO_DEFAULT / SILERO_SHERPA / SILERO_QWEN3 / SILERO_WS / SILERO_USER (+ SILERO_CUSTOM)
   - PYANNOTE_DEFAULT / S500 / S2000, FSMN_DEFAULT(동적) / FSMN_CONFIG / S500, FIRERED_DEFAULT / S500 / S2000 — PC GUI v8.1 과 같은 이름·값
   - WEBRTC_DEFAULT / LIB / ASR, TEN_DEFAULT / SHERPA / ASR
3. **구간 처리** 선택: 엔진별 최대 길이(기존 동작) / 모든 엔진 10초 통일(SEG10)
4. result.csv 열 8개 추가: `vad_engine, vad_preset, vad_params, vad_commercial, seg_mode, seg_chunks, seg_splits, condition`
5. run_meta 키 추가: `vad_engine, vad_preset, vad_params, vad_license, vad_commercial, seg_mode, condition, result_dir, vad_runtime, vad_model_sha256`
6. `tools/download_models.sh vads` — pyannote / FSMN / FireRed 모델과 TEN 라이브러리 내려받기
7. README.md 신설

## 3. 바뀐 점 (Changed)

- `MainActivity.kt`(2,876줄)를 기능별 파일로 분리: `stt/`, `vad/`, `ui/`, `io/`, `batch/` (분리 단계는 동작 변경 없음)
- `SileroVadProcessor`: 공통 계약 `VadDetector` 구현. 구간 규칙은 `vad/FrameSegmenter`, 이어 붙이기는 `vad/VadPcmJoiner` 로 이동(같은 코드)
- 할당량(SODA) 재시도: 조각이 여러 개면 **조각 단위**로 재시도(이미 끝난 조각의 세션을 다시 쓰지 않음)
- 10초 통일에서는 Speech EPD 를 쓰지 않고 CSV `epd_mode` 도 OFF 로 기록

## 4. 의존성

| 추가 | 판 | 용도 |
|---|---|---|
| `com.github.gkonovalov.android-vad:webrtc` (JitPack) | 2.0.10 | WebRTC VAD |
| `net.java.dev.jna:jna@aar` | 5.17.0 | TEN VAD(libten_vad.so) 호출 |

- `settings.gradle.kts` 에 JitPack 저장소 추가(그룹 `com.github.gkonovalov` 만)
- ONNX Runtime 은 1.25.1 그대로(pyannote / FSMN / FireRed 도 이 런타임으로 실행)

## 5. 검증 상태

| 단계 | 상태 |
|---|---|
| 1차 정적 검증(공식 소스 대조, 독립 검토) | 완료 — 컴파일·런타임 오류 미발견, 리팩터 이동만 확인, FireRed·FSMN·SegmentPacker 는 참조 구현과 수치 대조 일치 |
| 2차 Gradle 빌드 | **미실행** — 작업 환경에서 Maven 접속 불가. PC Android Studio 에서 빌드 필요 |
| 3차 실기기(S25 Ultra) | **미실행** |

## 6. 알려진 문제·확인 필요

- FSMN_DEFAULT 동적 무음 일정: 앱은 FunASR 1.4.16 `DEFAULT_SILENCE_SCHEDULE`(10 s/2000 … 60 s/200, 그 뒤 100 ms)을 쓴다. 프로젝트 문서 v8.0 의 일정과 표기가 다르다 — PC site-packages `fsmn_vad_streaming/model.py` 로 확인 필요(차이는 한 구간이 60초를 넘을 때만 생긴다).
- pyannote 원본 모델은 Hugging Face 사용 동의가 필요하다(앱은 sherpa-onnx 변환본 사용).
- TEN VAD 는 라이선스 추가 조건이 있어 `commercial=conditional` 로 표시한다.
- 화면 회전 시 Custom 입력값이 초기화된다(폴더 선택 등 다른 상태도 예전부터 같은 동작).
