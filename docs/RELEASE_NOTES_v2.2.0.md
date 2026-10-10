# 릴리스 노트 — google_stt v2.2.0

- 배포일: 2026-10-11
- 이전 판: 2.1.1 (versionCode 4) → **2.2.0** (versionCode 5)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
- 이번에 Minor 를 올린 이유: STT 엔진(sherpa-onnx **스트리밍**)을 **추가**했고, 기존 엔진의 결과 폴더·CSV 컬럼·설정은 그대로라 예전 결과와 호환된다.

## 1. 새 기능 (Added)

1. **Nemotron 3.5 ASR 스트리밍 0.6B (int8)** — 2단계 2-2(사용자 결정 Q25~Q28)

   | Model(화면) | 결과 엔진 이름 | 패키지 | 라이선스 |
   |---|---|---|---|
   | Nemotron 3.5 streaming 320 / 560 ms | `nemotron3.5-320ms` / `nemotron3.5-560ms` | `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-<지연>-int8-2026-06-11` | OpenMDW-1.1 (commercial=allowed) |

   - 런타임: sherpa-onnx 1.13.7 `OnlineRecognizer`(transducer encoder/decoder/joiner + tokens), CPU 4 스레드, 언어는 스트림 옵션 `language=ko/en`.
   - 공급: **Feed 설정을 따른다** — 100 ms 조각을 `feed_delay_ms` 간격(시작 시각 기준)으로 넣고, 조각마다 디코딩한다. 끝에 0.5 s 무음 + `inputFinished`.
   - "엔진별 최대 길이" 면 조각을 나누지 않고 파일 전체(VAD 를 켰으면 이어 붙인 말 구간)를 스트림 하나로 넣는다. SEG10 이면 10 s 조각마다 스트림.
   - 계측: `first_result_ms`(첫 글자), `feed_ms`·`feed_expected_ms`·`feed_stall_ratio`(Google 경로와 같은 식), `tail_ms`(공급 끝 → 최종 결과). 값 뜻은 `STT_계측_CSV_설명서.md` 7장.
2. 새 파일 `stt/SherpaStreamingTranscriber.kt`. Logcat 디버그 접두어 `[D][sherpa-stream]`.
3. `tools/download_stt_models.sh` — `nemotron3.5-320ms` / `nemotron3.5-560ms` 추가, 기본 받기에 `nemotron3.5-560ms` 추가.
4. (2026-10-11, 배포 전 조정 — 사용자 결정 Q28) 처음에는 지연 5종(80 / 160 / 320 / 560 / 1120 ms)을 등록했으나 **320 / 560 ms 2종만** 남겼다. 공식 패키지는 5종이 그대로 있다.

## 2. 바뀐 점 (Changed)

- `SttEngine` 에 `SHERPA_STREAMING`, `SherpaFamily` 에 `NEMOTRON_STREAMING` 추가. `SherpaModelSpec.maxChunkSec` 는 `Double?`(null = 상한 없음, 스트리밍).
- run_meta `stt_chunk_max_sec` 는 상한이 없으면 `none`, `stt_model_load_ms` 는 스트리밍 모델의 불러오기 시간도 남긴다.
- result.csv 컬럼(99열)·기존 엔진 동작·결과 이름은 바뀌지 않았다.

## 3. 알아 둘 점 — 한국어 띄어쓰기

- sherpa 1.13.7 의 스트리밍 결과 `text` 는 **한국어(한글)의 띄어쓰기를 모두 지운다**(예: `조국이당신을위해…`). 토큰에는 단어 경계가 남아 있다.
- 그래서 앱은 **토큰을 이어 붙여** 결과 글자를 만든다(예: `조국이 당신을 위해 …`). 영어·프랑스어는 `text` 와 같은 결과가 나온다.
- 오프라인 Omnilingual 의 `text` 는 띄어쓰기가 정상이라 v2.1.x 결과에는 영향이 없다(실측 확인).

## 4. 보류 — Cohere Transcribe 2B (사용자 결정 Q25)

| 확인 | 결과(sherpa-onnx 1.13.7 리눅스 JNI + 앱과 같은 Kotlin API, `sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01`) |
|---|---|
| 짧은 시험 음원(6.9 s, 패키지 동봉) | 정상. 띄어쓰기 정상, `usePunct` 로 마침표 켜고 끔 |
| 실제 한국어 음원(customer·friend 01) | **반복("아냐, 아냐…")·영어/로마자 섞임**. 84 / 30 / 20 / 10 s 로 잘라도 해결되지 않음. vLog 01 은 비교적 양호 |
| 끝에 0.5 s 완전 무음 / 두 문장 이어 붙임 | 결과가 무너짐(영어는 정상) |
| 메모리 | 이 PC(x86, 2 스레드)에서 최대 약 3.4 GB, 모델 파일 약 2.9 GB |

- 원인이 int8 변환인지 모델 자체인지는 PC(transformers) 판 결과와 비교해 가린다. 그 뒤 다시 정한다.

## 5. 검증 상태

| 단계 | 상태 |
|---|---|
| 실제 AAR 로 컴파일 | 앱 소스 전체(38개) 오류 0건, Kotlin 주석 중첩 검사 0건 |
| 앱 클래스 실음성 실행(리눅스 JVM) | `SherpaStreamingTranscriber` 를 그대로 불러 휴대폰의 kr 음원으로 실행 — customer 01(84 s): 578자·띄어쓰기 정상, REALTIME+10 s 조각(friend 01, 65 s): 공급 65.8 s(이론 65.3 s)·조각마다 끝 지연 약 0.5 s·첫 글자 3.9 s |
| 언어 값 | `ko`·`ko-KR`·`auto` 모두 받아들여짐(지원하지 않는 값이면 네이티브가 auto 로 바꿈) |
| 꼬리 무음 | 0 / 0.3 / 0.5 / 1.0 s 결과 동일 → 공식 예제와 같은 0.5 s |
| 패키지 파일 이름 | 80 / 560 / 1120 ms 패키지 안 파일 이름 확인(같음), 5종 내려받기 주소 확인 |
| 다운로드 스크립트 | 문법 검사, 모델 이름 → 패키지·파일 해석 확인 |
| Gradle 빌드·실기기 | **미실행** — PC 에서 빌드 후 확인 필요 |

## 6. 알려진 문제·확인 필요

- 실기기 속도: 이 PC(2코어)에서는 4 스레드 디코딩이 10배속 공급을 따라가지 못했다(D10 공급 77 s). S25 Ultra 에서 REALTIME·D50 의 `feed_stall_ratio` 를 확인해야 한다.
- 메모리: 오프라인 엔진과 동시에 불러오지 않는다(배치마다 한 모델). 인코더 약 0.66 GB.
- SEG10 은 10 s 경계에서 단어가 잘릴 수 있다(오프라인 엔진과 같음).
