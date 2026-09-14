# Silero VAD 적용 노트 — 2026-08-25

대상 저장소: `https://github.com/overthere1945/google_stt.git`
기준 커밋: 위 저장소 `main` HEAD (2026-08-25 clone)
작성: hyungchul-20260825-1430

---

## 0. 한 줄 요약

**Silero VAD 는 추가할 수 있다.** 앱 화면에서 On/Off 하고 5개 파라미터를 조절할 수 있게 넣었다.
단, **모델 파일 `silero_vad_16k_op15.onnx` 는 직접 받아서 `app/src/main/assets/` 에 넣어야** 빌드 후 동작한다.

---

## 1. 질문에 대한 답

### Q1. 우리 앱에 Silero VAD 같은 것을 추가할 수 있는가?

**있다.** 조건이 이미 다 맞아떨어진다.

| 조건 | 현재 앱 상태 | 판정 |
|---|---|---|
| 입력이 16 kHz mono PCM16 이어야 함 | `AudioDecoder` 가 모든 입력을 그렇게 통일해서 넘김 | ✅ 그대로 |
| ONNX Runtime 을 Android 에 넣을 수 있어야 함 | Maven Central `com.microsoft.onnxruntime:onnxruntime-android` | ✅ 의존성 1줄 |
| STT 전에 PCM 을 가로챌 지점이 있어야 함 | `decoded.pcm` → `transcribeMlKit()` 사이 | ✅ 한 곳뿐 |
| 라이선스 | Silero VAD = MIT | ✅ 상업 이용 가능 |

### Q2. 앱 화면에서 On/Off 하고 어떤 옵션을 줄 수 있는가?

Feed 스피너 바로 아래에 붙였다.

```
☐ Silero VAD 사용 (OFF = 기존 baseline 과 100% 동일)
   ├ VAD Profile      [ASR Safe ▾]   ASR Safe / Balanced / Aggressive / Custom
   ├ Threshold        [0.50]         0.01 ~ 0.99, speech 판정 임계값
   ├ Min Speech (ms)  [100]          이보다 짧은 말은 버린다
   ├ Min Silence (ms) [300]          이보다 짧은 무음은 구간을 끊지 않는다
   ├ Speech Padding   [200]          ★ 구간 앞뒤 여유. 작으면 앞말/뒷말이 잘린다
   └ Max Speech (sec) [0]            0 = Unlimited
```

- **값을 직접 고치면 Profile 이 자동으로 `Custom` 으로 바뀐다.**
- Silero 가 제공하는 파라미터는 이 5개가 전부다. 더 줄 것이 없다.
- `neg_threshold`(무음 임계값)는 공식 규칙 `max(threshold − 0.15, 0.01)` 로 자동 계산해서 CSV 에 기록한다.

### Q2-1. Profile 3개는 어디서 온 값인가

**Silero 공식 preset 이 아니다.** 우리 프로젝트에서 WER/CER 비교용으로 정한 초기값이다.
보고서에 "Silero 권장값"이라고 쓰면 안 된다.

| | threshold | min speech | min silence | **padding** | 성격 |
|---|---|---|---|---|---|
| Silero **공식 기본값** | 0.50 | 250 ms | 100 ms | **30 ms** | 범용 |
| **ASR Safe** (기본 추천) | 0.50 | 100 ms | 300 ms | **200 ms** | 잘림 최소화 |
| **Balanced** | 0.50 | 150 ms | 300 ms | **100 ms** | 절충 |
| **Aggressive** | 0.60 | 250 ms | 150 ms | **50 ms** | 무음 최대 제거 |

공식 기본값(padding 30 ms)을 ASR 앞단에 그대로 쓰면 앞말이 잘려 WER 이 나빠지기 쉬워서,
`ASR Safe` 는 padding 을 200 ms 로 키우고 min silence 를 300 ms 로 늘려 구간을 덜 끊게 했다.

### Q3. 무음을 빼면 WER/CER 이 좋아지는가?

**아직 모른다. 수치로 확인해야 한다.**
좋아질 근거(엔진이 무음에서 잡음을 말로 오인하는 것을 줄임)와
나빠질 근거(padding 이 모자라면 앞말/뒷말이 잘림)가 둘 다 있다.
그래서 이번 코드는 **baseline 을 덮어쓰지 않도록 출력 폴더를 분리**했다. 반드시 짝지어 돌려서 비교하라.

---

## 2. 첨부해 주신 ChatGPT 코드를 어떻게 처리했는가

### 2-1. 결론: 설계와 로직은 대부분 맞다. 그대로 넣지는 않았다.

검증 결과 **VAD 알고리즘과 파이프라인 설계는 정확했다.** 다만 **그대로 반영하면 안 되는 이유**가 있었다.

### 2-2. 그대로 넣지 않은 가장 큰 이유 — Beyond Compare 가 못 쓰게 된다

ChatGPT 판 `MainActivity.kt` 는 VAD 와 무관한 부분까지 광범위하게 다시 썼다.

- 변수 이름을 전부 바꿈: `v`→`view`, `m`→`mode`, `st`→`result`, `def`→`default`, `p`→`path`, `msg`→`message` …
- **기존 한국어 주석을 대량으로 삭제** (tail_ms 를 왜 봐야 하는지, FD 누수 주의, PcmFeeder 를 왜 별도 스레드로 두는지 등 지금까지 쌓아온 설명들)
- `; return` 을 두 줄로 나누는 식의 순수 스타일 변경

그 결과 **원본 대비 약 200줄이 "삭제"로 잡힌다.** Beyond Compare 로 열면
어디가 VAD 때문에 바뀐 건지 찾을 수 없다.

**그래서 git 원본을 기준으로 VAD 변경분만 다시 얹었다.**

| | 추가된 줄 | 삭제된 줄 |
|---|---|---|
| ChatGPT 판 `MainActivity.kt` | 약 500 | **약 200** |
| **이번 zip** | 413 | **13** |

삭제된 13줄은 전부 **의도한 시그니처 변경**이다.

```
- saveResult(outputRoot, modelFolder, item, ...)          → vadFolder 인자 추가
- writeCsv(outputRoot, modelFolder, csv.toString())       → vadFolder 인자 추가
- transcribeMlKit(decoded.pcm, ...)                       → sttPcm 으로 교체
- transcribeAndroidOnDevice(decoded.pcm, ...)             → sttPcm 으로 교체
- val dir = ensureDir(outputRoot, listOf(algoName, ...))  → ensureRunDir 로 교체
- // ── 2) STT ──                                          → // ── 3) STT ── 로 번호 밀림
```

`activity_main.xml` / `strings.xml` 도 마찬가지로 **삭제 0줄, 순수 추가**로 만들었다.
(ChatGPT 판은 두 파일에서도 기존 주석을 지웠다)

### 2-3. 로직에서 고친 곳

| # | 위치 | 내용 |
|---|---|---|
| 1 | `collectSpeechSegments()` max-speech 분기 | 공식 Python 은 `if next_start < prev_end` 로 판정하는데 ChatGPT 는 `nextStart < prevEnd + curSample` 로 썼다. 결과는 사실상 같지만 근거가 다르므로 공식대로 되돌렸다 |
| 2 | `sr` 입력 tensor | 프레임마다 새로 만들던 것을 배치 시작 시 1회만 만들도록 바꿨다(30초 음원이면 약 940회 → 1회). ORT 버전 대비 fallback 도 넣었다 |
| 3 | `VadResult` | `data class` 인데 `ByteArray` 를 담고 있어 `equals` 가 참조 비교가 된다(Kotlin 경고). 일반 class 로 바꿨다 |
| 4 | `String.format` | 기본 Locale 을 쓰던 곳을 `Locale.US` 로 고정했다 |
| 5 | `close()` | 각 자원을 `runCatching` 으로 감싸 하나가 실패해도 나머지가 닫히게 했다 |
| 5-1 | 실패 표시 (add-hyungchul-20260825-1620) | 실행이 중단되면 토스트뿐 아니라 **화면 진행 로그에도** 원인을 남긴다. 토스트는 금방 사라져 원인을 놓친다 |
| 5-2 | 모델 파일 사전 확인 (add-hyungchul-20260825-1620) | VAD 스위치를 켜는 **즉시** assets 에 모델이 있는지 보고 없으면 알린다. START 후 preflight 까지 다 돌고 실패하는 낭비를 없앴다 |
| 6 | `rtf` 착시 | **아래 2-4 참고 — 이게 제일 중요하다** |

### 2-4. ★ 계측에서 반드시 고쳐야 했던 것 — `rtf` 착시

ChatGPT 판은 `rtf` 를 그대로 뒀다.

```
rtf = stt_wall_ms ÷ 원본 음원 길이
```

VAD ON 이면 **분자는 짧아진 오디오로 잰 시간인데 분모는 원본 길이**다.
30초 음원에서 무음 40%를 깎으면 `rtf` 가 저절로 0.6배가 된다.
**엔진이 빨라진 게 아니라 오디오가 짧아진 것뿐인데 수치는 좋아 보인다.**
PL 에게 이 숫자를 그대로 보고하면 잘못된 결론으로 이어진다.

그래서 컬럼 2개를 추가했다.

| 컬럼 | 정의 | 용도 |
|---|---|---|
| `rtf_stt_input` | `stt_wall_ms ÷ vad_output_sec` | ★ **엔진 속도 비교는 이걸로** (VAD OFF 면 `rtf` 와 같다) |
| `total_rtf` | `total_ms ÷ audio_sec` | 사용자 체감 end-to-end (decode + VAD + STT 전부 포함) |

`rtf` 자체의 정의는 **바꾸지 않았다.** 예전 실행분과의 연속성을 지키기 위해서다.

### 2-5. ChatGPT 가 안 준 것 — 이게 없으면 빌드가 안 된다

| 빠진 것 | 이번 zip 에서 |
|---|---|
| `app/build.gradle.kts` (ONNX Runtime 의존성) | ✅ 넣음. 버전 **숫자로 고정** |
| `silero_vad_16k_op15.onnx` 모델 파일 | ❌ **넣을 수 없었다** (아래 4절) |
| `AudioDecoder.kt`, `AndroidManifest.xml`, `themes.xml`, `colors.xml`, gradle wrapper 등 | ✅ git 원본 그대로 포함 |

ONNX Runtime 버전은 **`1.25.1`** 로 고정했다.

- Maven Central 최신은 `1.29.0`(2026-08-12)이지만 배포 13일차라 벤치마크용으로는 피했다.
- `1.25.1` 은 2026-04-28 배포된 patch 릴리스로 약 4개월 검증됐다.
- **`latest.release` 를 쓰면 안 된다.** 빌드할 때마다 런타임이 바뀌어 `vad_process_ms` 비교가 무의미해진다.
- 실제로 어떤 버전이 돌았는지는 `run_meta.csv` 의 `onnxruntime_version` 에 기록된다.

---

## 3. 검증한 것 / 검증하지 못한 것

### 3-1. 공식 문서로 확인한 것 (2026-08-25)

| 항목 | 확인한 값 | 출처 |
|---|---|---|
| ONNX 입력 이름 | `input`, `state`, `sr` | `utils_vad.py` `OnnxWrapper.__call__` 의 `ort_inputs` |
| state shape | `(2, batch=1, 128)` | `reset_states()` 의 `torch.zeros((2, batch_size, 128))` |
| window / context | 512 / 64 sample (16 kHz) | `__call__` 의 `num_samples` / `context_size` |
| context 갱신 | `self._context = x[..., -64:]` | 같은 함수 |
| `neg_threshold` | `max(threshold − 0.15, 0.01)` | `get_speech_timestamps()` |
| `min_silence_samples_at_max_speech` | `sr × 98 / 1000` | 같은 함수 |
| `use_max_poss_sil_at_max_speech` | 존재하며 **기본값 True** | 같은 함수 |
| 모델 파일명 | opset 15 → `silero_vad_16k_op15.onnx`, opset 16 → `silero_vad.onnx` | `model.py` `load_silero_vad()` |
| `OrtEnvironment.getVersion()` | **instance 메서드** (static 아님) → Kotlin `env.version` 가능 | ONNX Runtime Java API 문서 |
| onnxruntime-android 버전/날짜 | 1.25.1(2026-04-28) … 1.29.0(2026-08-12) | Maven Central `maven-metadata.xml` |

### 3-2. 알고리즘 동치성 — 무작위 1,600 케이스 대조

`collectSpeechSegments()` 이식본을 Python 으로 다시 옮겨,
공식 `get_speech_timestamps()` 로직과 **무작위 확률열 1,600 케이스**로 대조했다.

- Profile 4종 (ASR Safe / Balanced / Aggressive / Silero 공식 기본값) × 400 케이스
- 길이 5 ~ 900 프레임(최대 약 29초), 말/무음이 번갈아 나오는 현실적 패턴
- threshold, neg_threshold 경계값을 일부러 섞음

**결과: 1,600 / 1,600 완전 일치 (불일치 0건).** raw 구간과 padding 후 구간 모두 동일.

### 3-3. 정합성 검사 (정적)

| 검사 | 결과 |
|---|---|
| `R.id.*` 참조 20개 ↔ 레이아웃 id | 20/20 존재 ✅ |
| `R.string.*` / `@string/*` 참조 42개 ↔ `strings.xml` | 42/42 존재 ✅ |
| `@color/*` 참조 ↔ `colors.xml` | 전부 존재 ✅ |
| `CSV_HEADER` 62개 ↔ `toCsvLine()` 62개 | 일치 ✅ |
| **기존 44개 컬럼 순서** | 원본과 **완전 동일** ✅ |
| `row.*` 대입 35개 ↔ `SttRow` 필드 57개 | 전부 존재 ✅ |
| `vadResult.*` / `VadConfig.*` 참조 | 전부 존재 ✅ |
| Kotlin 파일 4개 괄호 균형 | 전부 균형 ✅ |

### 3-4. ★ 검증하지 못한 것 (솔직히 적는다)

| 항목 | 이유 |
|---|---|
| **실제 컴파일** | 이 작업 환경에 Android SDK / Kotlin 컴파일러가 없고 Maven Central 접근이 막혀 있다 |
| **실제 ONNX 모델 로딩** | 모델 파일을 받을 수 없어 `session.inputNames` 를 실물로 확인하지 못했다 (문서 근거만 확보) |
| **VAD 가 WER/CER 을 개선하는지** | 이건 원래 실측으로만 답할 수 있다. 코드가 아니라 실험의 몫이다 |
| **max-speech 분할 분기의 수치 대조** | Custom 에서 Max Speech > 0 을 넣었을 때만 실행된다. 기본 Profile 3개는 전부 Unlimited 라 이 분기를 타지 않는다 |

Android Studio 빌드에서 에러가 나면 그 로그를 그대로 주면 고쳐드리겠다.

---

## 4. ★ 지금 바로 해야 할 일 — 모델 파일 넣기

zip 에는 모델 바이너리가 없다. **이것만 하면 된다.**

### Linux (이 작업 PC) — 프로젝트 루트에서

```bash
cd ~/workspace/AndroidStudioProjects/2026/google_stt
bash tools/download_silero_vad_model.sh
```

### Windows 라면

```powershell
powershell -ExecutionPolicy Bypass -File tools\download_silero_vad_model.ps1
```

### 또는 브라우저로 직접

`https://github.com/snakers4/silero-vad/blob/master/src/silero_vad/data/silero_vad_16k_op15.onnx`
→ **Download raw file** → `app/src/main/assets/silero_vad_16k_op15.onnx` 로 저장

자세한 내용은 `app/src/main/assets/README_SILERO_MODEL.md` 참고.

### 잘 됐는지 확인

앱에서 VAD 스위치를 켜고 START 를 누르면 로그 첫 줄에:

```
[vad] model=silero_vad_16k_op15.onnx, ORT=1.25.1, sha256=xxxxxxxxxxxxxxxx...
```

파일이 없으면 **조용히 VAD OFF 로 넘어가지 않고** 다음 메시지로 즉시 실패한다.

```
assets/silero_vad_16k_op15.onnx 이 없습니다. tools/download_silero_vad_model.sh 를 실행하거나 ...
```

---

## 5. 이번 zip 의 변경 파일 목록

| 파일 | 상태 | 내용 |
|---|---|---|
| `app/src/main/java/.../SileroVadProcessor.kt` | **신규** | VadProfile / VadConfig / VadPresets / VadSegment / VadResult / SileroVadProcessor |
| `app/src/main/java/.../MainActivity.kt` | 수정 | +413 / −13. VAD UI + 전처리 단계 + 출력 경로 분기 |
| `app/src/main/java/.../SttTelemetry.kt` | 수정 | VAD 컬럼 18개를 **맨 뒤에** append, `total_ms` 확장, `rtf_stt_input` / `total_rtf` 추가 |
| `app/src/main/res/layout/activity_main.xml` | 수정 | +117 / −0. VAD 스위치 + 옵션 그룹 |
| `app/src/main/res/values/strings.xml` | 수정 | +12 / −0. VAD 문자열 8개 |
| `app/build.gradle.kts` | 수정 | onnxruntime-android **1.25.1** 추가 |
| `app/src/main/assets/README_SILERO_MODEL.md` | **신규** | 모델 파일 받는 방법 |
| `tools/download_silero_vad_model.ps1` / `.sh` | **신규** | 모델 다운로드 스크립트 |
| `STT_계측_CSV_설명서.md` | 수정 | VAD 컬럼 18개 설명 + `rtf` 착시 경고 + 비교용 pandas 예제 |
| 그 외 전부 | 무변경 | git 원본 그대로 |

`AudioDecoder.kt`, `AndroidManifest.xml`, `themes.xml`, `colors.xml`, gradle wrapper, `.idea`, 아이콘 리소스는
**손대지 않았다.** Beyond Compare 로 열어도 차이가 없어야 정상이다.

---

## 6. 다음 실험 설계 (권장 순서)

같은 음원 24개 / 같은 모드(`base`) / 같은 Feed(`D10`, 10 ms) 로 4회 돌린다.

| # | 조건 | 출력 폴더 |
|---|---|---|
| 1 | VAD OFF | `output/google/base/` |
| 2 | VAD ON — ASR Safe | `output/google/base/vad_asr_safe/` |
| 3 | VAD ON — Balanced | `output/google/base/vad_balanced/` |
| 4 | VAD ON — Aggressive | `output/google/base/vad_aggressive/` |

그리고 이 4개를 놓고 본다.

1. `vad_removed_ratio` — VAD 가 실제로 얼마나 깎았나
2. `vad_process_ms` — VAD 도입 비용 (파일당 몇 ms)
3. `rtf_stt_input` — 엔진 속도가 정말 변했나 (변하면 안 되는 게 정상이다)
4. `total_rtf` — end-to-end 로는 이득인가
5. **`.txt` 를 정답 스크립트와 대조한 WER/CER** — 이게 최종 판정이다

### 이미 알고 있는 함정 하나

지난 실측에서 `customer/04`(112자, 중앙값 587자)와 `friend/04`(19자, 중앙값 436자)는
**음원 앞부분을 통째로 잃었고, 이는 100 ms / 15 ms / 10 ms 모두에서 재현됐다.**
공급 속도 문제가 아니라 엔진의 세션/endpointing 결함이다.

**VAD ON 으로 이 두 파일이 좋아지는지 반드시 따로 확인하라.**
VAD 가 앞쪽 무음을 잘라내면 엔진이 첫 발화를 놓치지 않을 가능성이 있다.
만약 좋아진다면 그게 이번 VAD 도입의 가장 큰 성과가 된다.

---

## 7. 참고 (2026-08-25 확인)

- Silero VAD (MIT): https://github.com/snakers4/silero-vad
- `utils_vad.py`: https://github.com/snakers4/silero-vad/blob/master/src/silero_vad/utils_vad.py
- ONNX Runtime Java API: https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtEnvironment.html
- onnxruntime-android (Maven Central): https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android/versions
