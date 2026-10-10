# 릴리스 노트 — google_stt v2.4.0

- 배포일: 2026-10-11
- 이전 판: 2.3.0 (versionCode 6) → **2.4.0** (versionCode 7)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
- 이번에 Minor 를 올린 이유: STT 엔진(Vosk)을 **추가**했고, 기존 엔진의 결과 폴더·CSV 컬럼·설정은 그대로라 예전 결과와 호환된다.

## 1. 새 기능 (Added) — 2단계 2-4, Vosk

| Model(화면) | 결과 엔진 이름 / 모델 | 모델(공식 이름·크기·공식 WER) | 라이선스(commercial) |
|---|---|---|---|
| Vosk small KO 0.22 (Kaldi) | `vosk-small-ko` / `vosk` | `vosk-model-small-ko-0.22`, 82 MB, 28.1 (Zeroth test) | Apache-2.0 (allowed) |
| Vosk small EN-US 0.15 (Kaldi) | `vosk-small-en` / `vosk` | `vosk-model-small-en-us-0.15`, 40 MB, 9.85 (LibriSpeech test-clean) | Apache-2.0 (allowed) |

1. 라이브러리: `com.alphacephei:vosk-android:0.3.75`(Maven Central). arm64-v8a 의 libvosk.so 는 16 KB 페이지 대응·libstdc++ 정적 링크(공식 build-vosk.sh 확인)라 다른 네이티브 라이브러리와 이름이 겹치지 않는다.
2. 새 파일 `stt/VoskTranscriber.kt`: 100 ms 조각을 Feed 간격으로 넣는다(사용자 결정 Q19·Q27 — 스트리밍 엔진은 Feed 설정을 따름). Vosk 내부 끝점에서 확정된 문장(`getResult`)을 잇고, 끝에 `getFinalResult` 로 남은 오디오를 처리한다. Logcat 접두어 `[D][vosk]`.
3. 새 파일 `stt/StreamingSttTranscriber.kt`: 스트리밍 전사기 공통 약속. `SherpaStreamingTranscriber` 와 `VoskTranscriber` 가 같은 자리(배치 1회 불러오기·Feed 공급·계측·해제)에서 쓰인다.
4. `tools/download_stt_models.sh` — `vosk-small-ko` / `vosk-small-en` 추가(alphacephei.com zip 을 폴더째 풀고 폴더 전체 sha256 을 남긴다, `all` = 15종). 기본 묶음에는 넣지 않았다.

## 2. 바뀐 점 (Changed)

- **JNA 5.17.0 → 5.18.1**: vosk-android 0.3.75 의 POM 이 `jna:5.18.1@aar` 를 요구한다. 그대로 두어도 Gradle 이 5.18.1 을 고르지만, TEN VAD 가 쓰는 판이 바뀌는 것을 드러내려고 `app/build.gradle.kts` 의 JNA 줄도 5.18.1 로 맞췄다.
- `SttEngine.VOSK`, `SherpaFamily.VOSK`(streaming), `SherpaRole.CONF` 추가. Vosk 모델 정의는 모델 폴더·필수 파일 점검·run_meta 기록 규칙이 같아 `SherpaModelSpec` 을 함께 쓴다(필수 파일 `am/final.mdl` + `conf/model.conf` — vosk-api `src/model.cc` 의 v2 배치 확인 규칙).
- run_meta `stt_runtime` / `stt_num_threads` 는 Vosk 면 `vosk-android 0.3.75 (Kaldi)` / `1`. 나머지 엔진 값은 바뀌지 않았다.
- result.csv 컬럼(99열)·기존 엔진 동작·결과 이름 규칙은 바뀌지 않았다. 값 뜻은 `STT_계측_CSV_설명서.md` 9장.

## 3. 검증 상태

| 단계 | 상태 |
|---|---|
| 컴파일 | 앱 소스 전체(40개) 오류 0건 — sherpa 실제 AAR + **vosk-api 0.3.75 공식 소스(org.vosk)를 JNA 로 컴파일한 클래스**로 확인(Maven 이 막혀 vosk-android AAR 자체는 못 받음). Kotlin 주석 중첩 검사 0건 |
| 의존성 확인 | Maven Central `vosk-android 0.3.75` POM: `jna 5.18.1@aar` 1개(전이 의존성 제외). 공식 build-vosk.sh: arm64 `max-page-size=16384`, `-static-libstdc++` |
| 다운로드 스크립트 | 문법 검사, 시험용 curl 로 Vosk zip 분기(풀기·필수 파일 확인·폴더 전체 해시·다시 실행 시 건너뜀)와 기존 tar.bz2 분기(Moonshine) 모두 확인 |
| **실음성 실행** | **미실행** — 이 작업 환경은 alphacephei.com(모델)·Maven Central 접속이 막혀 있다. PC 에서 모델을 받은 뒤 확인 필요(HANDOFF 4-1) |
| Gradle 빌드·실기기 | **미실행** — PC 에서 빌드 후 확인 필요 |

## 4. 알려진 문제·확인 필요

- Gradle 이 `vosk-android:0.3.75` 와 `jna:5.18.1` 을 받아야 빌드된다(PC 의 Maven Central 접속 필요).
- TEN VAD 가 JNA 5.18.1 로 바뀐다. TEN VAD 조건 결과가 예전(5.17.0)과 같은지 한 번 확인한다.
- Vosk 의 정확도는 공식 표에서도 한국어 WER 28.1 로 낮다(작은 모델). 비교 기준용으로 쓴다.
- Vosk 모델은 언어별이다. Language 와 맞지 않는 모델을 고르면 결과가 의미 없다.
