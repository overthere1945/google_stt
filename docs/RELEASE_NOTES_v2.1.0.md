# 릴리스 노트 — google_stt v2.1.0

- 배포일: 2026-10-10
- 이전 판: 2.0.0 (versionCode 2) → **2.1.0** (versionCode 3)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
- 이번에 Minor 를 올린 이유: STT 엔진을 **추가**했고, 기존 Google 엔진의 결과 폴더·CSV·설정은 그대로라 예전 결과와 호환된다.

## 1. 새 기능 (Added)

1. **sherpa-onnx 오프라인 STT 엔진**(2단계 2-1 — 무료·상업 사용 가능·한국어+영어)

   | Model | 결과 엔진 이름 | 가중치 라이선스 | 한 조각 상한 | 언어 설정 |
   |---|---|---|---|---|
   | Whisper tiny / base / small / medium / turbo | `whisper-<크기>` | MIT | 29 s | `ko` / `en` |
   | Qwen3-ASR 0.6B (int8) | `qwen3asr-0.6b` | Apache-2.0 | 30 s | `Korean` / `English` |
   | Omnilingual ASR 300M CTC (int8) | `omnilingual-300m` | Apache-2.0 | 39 s | 없음 |

2. 런타임: sherpa-onnx **1.13.7 정적 링크 AAR**(onnxruntime 내장). 앱의 onnxruntime-android 1.25.1(VAD·노이즈 저감)과 따로 돈다. CPU 4 스레드.
3. `tools/download_stt_models.sh` — AAR 을 `app/libs/` 에 받고, 모델을 받아 필요한 파일만 풀고, SHA256SUMS.txt 를 만들고, adb 로 휴대폰 앱 전용 폴더에 넣는다.
4. 결과 이름 "엔진 = 계열-크기": `whisper-base_sherpa-int8_<조건>/…`, `whisper-base_<조건>_result.csv`(모델마다 CSV 가 따로 생긴다).
5. run_meta 키 추가: `stt_runtime, stt_model_dir, stt_model_files, stt_model_sha256, stt_num_threads, stt_model_load_ms, stt_chunk_max_sec, stt_language, stt_license, stt_commercial`.

## 2. 바뀐 점 (Changed)

- sherpa 엔진에서는 구간 처리 "엔진별 최대 길이" 가 모델별 조각 상한(위 표)으로 말 구간을 묶고 자른다(SegmentPacker). "10초 통일" 은 10 s.
- sherpa 엔진은 Speech EPD·Google warm-up·SODA 할당량 안내를 쓰지 않는다. 조건 이름에도 EPD 부분이 붙지 않는다.
- sherpa 엔진은 실시간 공급 없이 최대 속도로 디코딩한다: `feed_ms = -1`, `tail_ms = stt_wall_ms`, `stt_timeout_sec = -1`, `stt_session_index = -1`.
- result.csv 컬럼(99열)은 바뀌지 않았다. 값 뜻은 `STT_계측_CSV_설명서.md` 6장.
- Google 엔진(ML Kit Basic / Android 플랫폼)의 동작·결과 이름은 그대로다.
- APK 에 넣는 ABI 를 **arm64-v8a 하나**로 제한했다(`app/build.gradle.kts` 의 `ndk.abiFilters`). sherpa 정적 링크 AAR 은 x86 에서만 `libonnxruntime.so`(ORT 1.27.1)를 따로 넣어, 앱의 onnxruntime-android 1.25.1 과 `lib/x86/libonnxruntime.so` 경로가 겹쳐 빌드(`mergeDebugNativeLibs`)가 실패했기 때문이다. 에뮬레이터·32비트 기기에는 설치되지 않는다.

## 3. 의존성

| 추가 | 판 | 용도 |
|---|---|---|
| `libs/sherpa-onnx-static-link-onnxruntime-1.13.7.aar` (GitHub Releases, 파일 의존성) | 1.13.7 | sherpa-onnx 오프라인 STT |

- AAR(36.1 MB)과 모델은 git 에 넣지 않는다(`.gitignore`: `/app/libs/*.aar`, `/stt_models/`).
- **빌드 전에** `bash tools/download_stt_models.sh aar` 로 AAR 을 받아야 한다(없으면 Gradle 이 실패한다).

## 4. 검증 상태

| 단계 | 상태 |
|---|---|
| 1차 정적 검증 | 완료 — 공식 kotlin-api 시그니처를 흉내 낸 스텁 + PC 의 android-35·R.jar·의존성으로 전체 컴파일 오류 0건. 모델 파일 이름·조각 상한은 공식 소스(sherpa-onnx v1.13.7, Hugging Face, omnilingual-asr README)로 확인 |
| 다운로드 스크립트 | 가짜 패키지로 받기·부분 풀기·해시·재실행 건너뛰기·잘못된 이름 거부 확인 |
| 실제 AAR 로 컴파일 | 완료(2026-10-10) — PC 에서 받은 AAR(sha256 `220d7cb2…`)의 classes.jar 로 앱 소스 37개 전체 컴파일 오류 0건. readelf 로 ABI 별 네이티브 의존성 확인(arm64-v8a·armeabi-v7a·x86_64 는 onnxruntime 정적 포함, x86 만 `libonnxruntime.so` 동적 링크) |
| Gradle 빌드 | 1차 실패(x86 `libonnxruntime.so` 중복) → `abiFilters` 로 arm64-v8a 만 남기도록 수정, 다시 빌드 확인 필요 |
| 실기기(S25 Ultra) | **미실행** |

## 5. 알려진 문제·확인 필요

- 두 onnxruntime(앱 1.25.1 + sherpa 정적) 공존, 모델별 속도·메모리는 실기기에서 확인해야 한다.
- Qwen3-ASR 의 언어 값("Korean"/"English")은 PC 와 같은 Qwen 공식 표기다. sherpa 문서에는 값 형식이 적혀 있지 않아 실기기 결과로 확인한다.
- 휴대폰 모델 폴더는 앱 전용 폴더라 **앱을 지우면 모델도 지워진다**(Android Studio Run 으로 덮어 설치하면 남는다).
- APK 는 arm64-v8a 전용이다. 에뮬레이터용으로 x86_64 를 더하려면 `abiFilters` 에 추가하면 된다(x86_64 는 정적이라 충돌 없음). 단 TEN VAD `.so` 는 공식 x86_64 판이 없다.
