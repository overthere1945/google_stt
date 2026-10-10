# 릴리스 노트 — google_stt v2.4.1

- 배포일: 2026-10-11
- 이전 판: 2.4.0 (versionCode 7) → **2.4.1** (versionCode 8)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
- 이번에 Patch 를 올린 이유: 버그 수정(result.csv 열 밀림)과 런타임 라이브러리 판올림(sherpa-onnx 1.13.8)뿐이다. 기능·결과 폴더·CSV 열 이름과 순서는 바뀌지 않았다.

## 1. 버그 수정 (Fixed)

1. **result.csv 열 밀림** — `execution` 값(`ON_DEVICE(…, cpu, threads=4)`)의 쉼표를 따옴표로 감싸지 않아 v2.1.0 ~ v2.4.0 의 sherpa·Vosk 행이 2~3열씩 뒤로 밀렸다(실기기 CSV: 머리 99열 / 행 101~102열). `SttTelemetry.toCsvLine` 에서 `execution` 을 따옴표로 감싼다.
   - 시험: 쉼표·큰따옴표가 든 `execution`·`text`·`vad_params` 로 행을 만들어 머리 99열 = 행 99열, 값이 그대로 읽히는 것을 확인.
   - 예전 CSV 는 복구하지 않는다(사용자 결정 — 본 측정 전 동작 확인용 결과). v2.4.1 로 다시 측정한다.

## 2. 바뀐 점 (Changed)

- **sherpa-onnx 1.13.7 → 1.13.8** (`sherpa-onnx-static-link-onnxruntime-1.13.8.aar`, 38.7 MB, 2026-09-10 배포)
  - 공식 릴리스 노트: Moonshine v2 · Cohere Transcribe · FunASR-Nano 의 무음 환각 수정, Qwen3-ASR 등이 함께 쓰는 난수 생성기 경합 수정, 내장 onnxruntime 1.28.2.
  - Kotlin API(설정 클래스·Recognizer·Stream·Result 17개)는 1.13.7 과 같다(javap 대조). 앱 코드는 바꾸지 않았다.
  - 네이티브 구성도 같다: arm64-v8a 는 onnxruntime 정적 포함·16 KB 정렬, x86 만 libonnxruntime.so(ORT 1.28.2)를 따로 넣어 `packaging.jniLibs.excludes` 가 그대로 필요하다.
  - run_meta `stt_runtime` / CSV `execution` 이 `sherpa-onnx 1.13.8 (static-link onnxruntime)` 로 바뀐다.
  - `tools/download_stt_models.sh aar` 가 1.13.8 AAR 을 받는다(예전 1.13.7 AAR 은 지워도 된다).

## 3. 검증 상태

| 단계 | 상태 |
|---|---|
| 컴파일 | 앱 소스 40개 — 1.13.8 실제 AAR(classes.jar)로 오류 0건, Kotlin 주석 중첩 검사 0건 |
| 결과 글자 회귀(리눅스 JNI 1.13.7 ↔ 1.13.8, 앱 클래스 그대로) | whisper-base · qwen3asr-0.6b · omnilingual-300m · zipformer-ko · zipformer-ko-stream · sensevoice-small · moonshine-tiny-ko · nemotron3.5-560ms **8종 모두 한 글자도 다르지 않음**(kr customer·friend 01, 오프라인 3종은 vLog 01 도) |
| Moonshine 9 s 상한 | 1.13.8 에서도 9.5 / 10 / 12 / 15 s 조각은 빈 결과, 9 s 정상 → 상한 유지 |
| Cohere 2B(보류) | 1.13.8 에서도 끝 0.5 s 완전 무음 / 두 문장 이어 붙임에서 무너짐 → 보류 유지 |
| 스트리밍 공급 속도와 결과 | zipformer-ko-stream friend 01: REALTIME(공급이 따라감) = D10(이 PC 2코어에서 공급이 3배 밀림) 결과 글자 같음 |
| Gradle 빌드·실기기 | **미실행** — PC 에서 `bash tools/download_stt_models.sh aar` 후 빌드 확인 필요 |
