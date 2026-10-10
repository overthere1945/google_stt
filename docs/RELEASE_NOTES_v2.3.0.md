# 릴리스 노트 — google_stt v2.3.0

- 배포일: 2026-10-11
- 이전 판: 2.2.0 (versionCode 5) → **2.3.0** (versionCode 6)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
- 이번에 Minor 를 올린 이유: STT 엔진 4종을 **추가**했고, 기존 엔진의 결과 폴더·CSV 컬럼·설정은 그대로라 예전 결과와 호환된다.
- 2.2.0 도 원격에 올라가기 전이며, 이번 묶음에 Nemotron 축소(Q28) 커밋이 함께 들어 있다(2.2.0 판 안의 변경).

## 1. 새 기능 (Added) — 2단계 2-3, 상업 조건부 엔진(사용자 결정 Q5)

| Model(화면) | 결과 엔진 이름 | 실행 | 패키지(sherpa-onnx asr-models) | 라이선스(commercial) |
|---|---|---|---|---|
| Zipformer KO [상업 조건부] | `zipformer-ko` | 오프라인, 조각 상한 15 s | `sherpa-onnx-zipformer-korean-2024-06-24` (int8) | Apache-2.0(icefall) / KsponSpeech 학습 (conditional) |
| Zipformer KO streaming [상업 조건부] | `zipformer-ko-stream` | 스트리밍, Feed 설정 | `sherpa-onnx-streaming-zipformer-korean-2024-06-16` (int8) | 위와 같음 (conditional) |
| SenseVoice Small [상업 조건부] | `sensevoice-small` | 오프라인, 조각 상한 30 s, `ko`/`en`, ITN 켬 | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` | FunASR MODEL_LICENSE (conditional) |
| Moonshine tiny KO [상업 조건부] | `moonshine-tiny-ko` | 오프라인, 조각 상한 9 s | `sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27` (.ort) | Moonshine Community License (conditional) |

1. 화면 이름 끝에 `[상업 조건부]`, run_meta `stt_commercial=conditional`·`stt_license` 로 남긴다.
2. Zipformer KO 스트리밍은 Nemotron 과 같은 `SherpaStreamingTranscriber`(100 ms 조각을 Feed 간격으로 공급, 0.5 s 꼬리 무음)로 돈다. 언어 옵션은 넣지 않는다(한국어 전용).
3. `tools/download_stt_models.sh` — `zipformer-ko` / `zipformer-ko-stream` / `sensevoice-small` / `moonshine-tiny-ko` 추가(`all` = 13종). 기본 묶음에는 넣지 않았다.

## 2. 바뀐 점 (Changed)

- `SherpaFamily` 에 `ZIPFORMER`, `ZIPFORMER_STREAMING`, `SENSE_VOICE`, `MOONSHINE_V2` 와 `streaming` 표시를 더했다. 스트리밍 판정을 Nemotron 이름 비교에서 이 표시로 바꿨다.
- 조각 상한을 `min(SEG10 10 s, 모델 상한)` 으로 정한다. **Moonshine tiny KO 는 10초 통일(SEG10)에서도 9 s 조각**이 된다(run_meta `stt_chunk_max_sec=9.0`). 다른 모델은 상한이 10 s 이상이라 예전과 같다.
- 오프라인 결과 글자: Zipformer KO 는 토큰으로 만들고(띄어쓰기 복원), Moonshine 은 `▁` 를 공백으로 바꾼다. Whisper·Qwen3·Omnilingual 은 예전처럼 `text` 를 쓴다.
- (2.2.0 판 안의 변경, 사용자 결정 Q28) Nemotron 3.5 스트리밍은 **320 / 560 ms 2종만** 남겼다(80 / 160 / 1120 ms 삭제).
- result.csv 컬럼(99열)·기존 엔진 동작·결과 이름 규칙은 바뀌지 않았다.

## 3. 조각 상한을 정한 근거 (실측)

이 작업 환경(x86 2코어)에서 sherpa-onnx 1.13.7 리눅스 JNI + 앱과 같은 Kotlin API 로 휴대폰 시험 음원(`kr/customer·friend·vLog/01.wav`, 84 / 65 / 88 s)을 VAD 없이 고정 길이로 잘라 돌렸다.

| 모델 | 확인 | 결과 → 정한 값 |
|---|---|---|
| Zipformer KO 오프라인 | 84 / 30 / 20 / 15 / 12 / 10 s | 20 s 이상은 첫 문장("안녕하세요.")이 빠짐, 15 s 가 가장 길고 빠짐없음 → **15 s**(공식 수치 없음) |
| SenseVoice Small | 84 / 30 / 20 / 10 s | 84 s 한 번에 125자(customer)로 내용이 크게 빠짐 → PC FunASR FSMN-VAD 단일 구간 최대와 같은 **30 s** |
| Moonshine tiny KO | 10 / 9.9 / 9.6 / 9.3 / 9.25 … 5 s | **9.3 s 이상 조각은 onnxruntime 오류**("Attempting to broadcast an axis by a dimension other than 1")로 빈 결과, 9.25 s 이하 정상 → **9 s** |
| Zipformer KO 오프라인·스트리밍 | `text` 와 토큰 비교 | 두 경로 모두 `text` 에 한국어 띄어쓰기가 없음(customer: 스트리밍 text 390자 / 토큰 480자) → 토큰으로 글자 생성 |

## 4. 검증 상태

| 단계 | 상태 |
|---|---|
| 실제 AAR 로 컴파일 | 앱 소스 전체(38개) 오류 0건, Kotlin 주석 중첩 검사 0건 |
| 앱 클래스 실음성 실행(리눅스 JVM, 시험용 Environment 로 모델 폴더만 바꿈) | `SherpaOfflineTranscriber.load/transcribe`·`SherpaStreamingTranscriber.load/transcribe` 를 그대로 불러 4종 모두 정상 동작 |
| Zipformer KO | customer 520자·6조각·5.2 s, friend 348자, vLog 564자(불러오기 2.6 s) |
| SenseVoice Small | customer 337자·3조각·9.7 s, friend 270자, vLog 537자(불러오기 1.3 s) |
| Moonshine tiny KO | customer 594자·10조각·11.3 s, friend 405자, vLog 604자(불러오기 0.6 s). **vLog 는 9 s 고정 절단 경계에서 "맞아요." 반복 환각**이 나왔다 |
| Zipformer KO 스트리밍 | friend REALTIME: 공급 65.2 s(이론 65.3 s)·끝 지연 0.18 s·첫 글자 3.7 s·363자 / customer D10: 공급 25.9 s(이론 8.4 s — 2코어가 10배속을 못 따라감) |
| Nemotron 560 ms 회귀 | friend REALTIME: 언어 `ko` 옵션 유지, 공급 65.4 s·끝 지연 0.46 s·429자(2.2.0 과 같은 동작) |
| 빠진 모델 파일 | 모델 폴더가 없으면 안내문과 함께 IOException(네이티브 호출 전) — 확인 |
| 다운로드 스크립트 | 문법 검사, 4종 모두 tar 안에 지정 파일이 있는지 확인 |
| Gradle 빌드·실기기 | **미실행** — PC 에서 빌드 후 확인 필요 |

## 5. 알려진 문제·확인 필요

- 상업 사용: 4종 모두 조건부다. Moonshine 한국어는 상업 사용 시 등록이 필요하고 **연매출 100만 달러 이상이면 라이선스가 끝나며** "Powered by Moonshine AI" 표시 의무가 있다. SenseVoice 는 "reference and learning purposes only" 문구, Zipformer KO 는 KsponSpeech(AI Hub) 약관 영향이 확인되지 않았다.
- Moonshine 의 9 s 상한은 sherpa 1.13.7 의 동작을 실측한 값이다(공식 수치 없음). 판을 올리면 다시 확인한다.
- 고정 길이 절단(VAD OFF·SEG10)은 단어 중간을 자를 수 있어 Moonshine·SenseVoice 의 반복·누락이 늘 수 있다. VAD 를 켠 조건과 함께 비교한다.
- 실기기 속도·메모리, 스트리밍의 `feed_stall_ratio`(D50·D20)는 S25 Ultra 에서 확인해야 한다.
