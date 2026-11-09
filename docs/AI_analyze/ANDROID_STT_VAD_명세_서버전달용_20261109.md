# Android 앱(google_stt v4.0.0)의 STT 엔진·모델과 VAD — 서버(Python) 구현 전달용

- 작성: 2026-11-09
- 대상: PC 서버 실행기(`stt_all_gui_server`, Python)를 고치는 사람. 이 문서만 보고 **앱이 무엇을 어떤 설정으로 돌리는지** 알 수 있게 썼다.
- 기준 코드: 저장소 `/home/hyungchul/workspace/AndroidStudioProjects/2026/google_stt`, 브랜치 `v4-rewrite`, 커밋 `aabd9f7`. 앱 판 4.0.0(versionCode 12).
- 이 문서의 값은 모두 위 커밋의 코드에서 읽은 것이다. 값마다 근거 파일을 적었다(경로는 `app/src/main/java/com/example/google_stt/` 기준).
- 실측이라고 적은 것은 Galaxy S24 Ultra(SM-S928N) 에서 잰 값이다. 서버 동작에 관한 것은 "확인함" 이라고 적은 것만 확인했다.

## 0. 한눈에 보기

| 구분 | 내용 |
|---|---|
| STT 런타임 | sherpa-onnx **1.13.8**(정적 링크 AAR, onnxruntime 1.28.2 내장), vosk-android **0.3.75**(Kaldi), ML Kit genai-speech-recognition **1.0.0-alpha1** |
| STT 모델 | 19종 = sherpa 오프라인 13(Whisper 5종 포함) + sherpa 스트리밍 3 + Vosk 2 + ML Kit 1 — 2장 |
| VAD 엔진 | 5종: Silero, pyannote segmentation-3.0, FunASR FSMN-VAD, FireRedVAD, TEN VAD (+ 사용 안 함) — 4장 |
| VAD 프리셋 | 22개(사용 안 함 1 + Silero 9 + pyannote 3 + FSMN 3 + FireRed 3 + TEN 3) — 5장 |
| VAD 런타임 | Silero·pyannote·FSMN·FireRed 는 onnxruntime-android **1.25.1**, TEN 은 공식 `libten_vad.so` + JNA 5.18.1 |
| 입력 오디오 | 모든 음원을 **16 kHz / mono / PCM16 LE** 로 바꿔 쓴다 — 1장 |
| 실행 장치 | CPU 만. sherpa 는 4 스레드, Vosk 는 1 스레드 |
| 언어 | 한국어(`ko`, `ko-KR`) / 영어(`en`, `en-US`). 입력 하위 폴더 이름으로 정한다 — 7장 |

## 1. 처리 순서와 공통 규칙

```
입력 파일 → 디코딩(16 kHz mono PCM16) → [노이즈 저감] → [VAD] → 조각 만들기 → [Speech EPD] → STT → TXT · CSV
```

근거: `run/PipelineSteps.kt`, `engine/audio/AudioDecoder.kt`.

1. **디코딩**: Android MediaExtractor/MediaCodec 으로 PCM 을 얻은 뒤, 채널은 **평균으로 모노 다운믹스**, 표본화 주파수는 **windowed-sinc(Blackman) 리샘플러**로 16 kHz 로 바꾼다(선형 보간 아님).
2. **노이즈 저감**(기본 "사용 안 함"): GTCRN / DPDFNet / NSNet2 / MMSE-STSA / Wiener / Spectral Subtraction. 이 문서에서는 다루지 않는다.
3. **VAD**: 말 구간 목록을 만든다(4·5장). VAD 를 쓰지 않으면 파일 전체가 한 구간이다.
4. **조각 만들기**: 엔진이 한 번에 받을 수 있는 길이에 맞춰 STT 입력 조각을 만든다(6장). **여기가 서버와 가장 다르다.**
5. **STT**: 조각마다 **새 스트림**으로 디코딩한다(앞 조각의 문맥을 넘기지 않는다). 조각 결과는 **공백 한 칸**으로 잇는다.
6. 결과 TXT 는 전사문만 담는다(화자·시간 없음). 글자가 하나도 없으면 `NO_MATCH`.

## 2. STT 모델 19종

근거: `domain/catalog/Catalog.kt`(모델 정의), `domain/catalog/CatalogAssets.kt`(받는 주소·크기).

- **결과 엔진 이름**은 결과 폴더·CSV 에 쓰는 이름이다(CSV 의 `algo` 열). **모델 이름**은 CSV 의 `model` 열이다: sherpa 는 모두 `sherpa-int8`, Vosk 는 `vosk`, ML Kit 은 `basic`.
- 결과 폴더는 `<결과 엔진 이름>_<모델 이름>_<조건>`, CSV 는 `<결과 엔진 이름>_<조건>_result.csv` 다. 예: `whisper-base_sherpa-int8_SILERO/`, `whisper-base_SILERO_result.csv`.
- **조각 상한**은 한 번의 디코딩에 넣는 최대 길이(초)다. "없음" 은 길이 제한 없이 한 스트림으로 넣는다는 뜻이다.
- sherpa 모델의 받는 주소: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/<패키지>.tar.bz2`
- Vosk 모델의 받는 주소: `https://alphacephei.com/vosk/models/<패키지>.zip`

### 2-1. sherpa-onnx 오프라인 (13종) — 조각을 받자마자 최대 속도로 디코딩

| 결과 엔진 이름 | sherpa 패키지 | 쓰는 파일 | 조각 상한 | 언어 | 라이선스(상업) | 압축 크기(B) |
|---|---|---|---|---|---|---|
| `whisper-tiny` | `sherpa-onnx-whisper-tiny` | `tiny-encoder.int8.onnx`, `tiny-decoder.int8.onnx`, `tiny-tokens.txt` | 29 s | 다국어 | MIT (allowed) | 116,204,861 |
| `whisper-base` | `sherpa-onnx-whisper-base` | `base-…` (위와 같은 규칙) | 29 s | 다국어 | MIT (allowed) | 207,557,382 |
| `whisper-small` | `sherpa-onnx-whisper-small` | `small-…` | 29 s | 다국어 | MIT (allowed) | 639,387,718 |
| `whisper-medium` | `sherpa-onnx-whisper-medium` | `medium-…` | 29 s | 다국어 | MIT (allowed) | 1,931,372,882 |
| `whisper-turbo` | `sherpa-onnx-whisper-turbo` | `turbo-…` | 29 s | 다국어 | MIT (allowed) | 563,790,207 |
| `qwen3asr-0.6b` | `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` | `conv_frontend.onnx`, `encoder.int8.onnx`, `decoder.int8.onnx`, `tokenizer/`(`merges.txt`, `tokenizer_config.json`, `vocab.json`) | 30 s | 다국어 | Apache-2.0 (allowed) | 878,702,423 |
| `omnilingual-300m` | `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12` | `model.int8.onnx`, `tokens.txt` | 39 s | 다국어 | Apache-2.0 (allowed) | 292,571,207 |
| `zipformer-ko` | `sherpa-onnx-zipformer-korean-2024-06-24` | `encoder-epoch-99-avg-1.int8.onnx`, `decoder-epoch-99-avg-1.int8.onnx`, `joiner-epoch-99-avg-1.int8.onnx`, `tokens.txt` | **15 s** | 한국어 전용 | Apache-2.0(icefall) / KsponSpeech (**conditional**) | 329,740,690 |
| `sensevoice-small` | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` | `model.int8.onnx`, `tokens.txt` | 30 s | 다국어 | FunASR MODEL_LICENSE (**conditional**) | 163,002,883 |
| `zipformer-en` | `sherpa-onnx-zipformer-en-2023-06-26` | `encoder-epoch-99-avg-1.int8.onnx`, **`decoder-epoch-99-avg-1.onnx`(fp32)**, `joiner-epoch-99-avg-1.int8.onnx`, `tokens.txt` | **15 s** | 영어 전용 | Apache-2.0(icefall) / LibriSpeech CC-BY-4.0 (allowed) | 307,666,046 |
| `parakeet-tdt-0.6b-v2` | `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` | `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt` | 30 s | 영어 전용 | CC-BY-4.0 (allowed) | 482,468,385 |
| `moonshine-tiny-en` | `sherpa-onnx-moonshine-tiny-en-int8` | `preprocess.onnx`, `encode.int8.onnx`, `uncached_decode.int8.onnx`, `cached_decode.int8.onnx`, `tokens.txt` | 25 s | 영어 전용 | MIT (allowed) | 107,600,538 |
| `moonshine-base-en` | `sherpa-onnx-moonshine-base-en-int8` | 위와 같음 | 25 s | 영어 전용 | MIT (allowed) | 250,807,309 |

조각 상한의 근거(코드 주석):
- Whisper 29 s — sherpa-onnx 가 29.5 s 에서 자르므로 끝이 잘리지 않게.
- Qwen3-ASR 30 s — 공식 수치는 없다. 공식 문서 예제는 VAD 로 나눠 넣는다.
- Omnilingual 39 s — 공식 추론이 40초 미만만 받는다.
- Zipformer KO·EN 15 s — 실측(15 s 는 빠짐없음, 24 s 부터 단어가 빠진다).
- SenseVoice 30 s — PC 의 FSMN-VAD 최대 구간과 같게.
- Parakeet-TDT 30 s — 실측(48.7 s 까지 정상, 73 s 부터 뒷부분이 빠진다).
- Moonshine 25 s — 서버 `moonshine_stt.py` 기본과 같게.

### 2-2. sherpa-onnx 스트리밍 (3종) — 100 ms 조각을 넣으며 디코딩

| 결과 엔진 이름 | sherpa 패키지 | 쓰는 파일 | 조각 상한 | 언어 | 라이선스(상업) | 압축 크기(B) |
|---|---|---|---|---|---|---|
| `nemotron3.5-320ms` | `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11` | `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt` | 없음 | 다국어 | OpenMDW-1.1 (allowed) | 475,272,949 |
| `nemotron3.5-560ms` | `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11` | 위와 같음 | 없음 | 다국어 | OpenMDW-1.1 (allowed) | 475,271,763 |
| `zipformer-ko-stream` | `sherpa-onnx-streaming-zipformer-korean-2024-06-16` | `encoder-epoch-99-avg-1.int8.onnx`, `decoder-epoch-99-avg-1.int8.onnx`, `joiner-epoch-99-avg-1.int8.onnx`, `tokens.txt` | 없음 | 한국어 전용 | Apache-2.0(icefall) / KsponSpeech (**conditional**) | 418,218,652 |

### 2-3. Vosk (2종) — 100 ms 조각을 넣으며 디코딩

| 결과 엔진 이름 | Vosk 모델 폴더 | 조각 상한 | 언어 | 라이선스(상업) | 압축 크기(B) |
|---|---|---|---|---|---|
| `vosk-small-ko` | `vosk-model-small-ko-0.22` | 없음 | 한국어 전용 | Apache-2.0 (allowed) | 86,914,329 |
| `vosk-small-en` | `vosk-model-small-en-us-0.15` | 없음 | 영어 전용 | Apache-2.0 (allowed) | 41,205,931 |

쓰는 파일(둘 공통): `am/final.mdl`, `conf/mfcc.conf`, `conf/model.conf`, `graph/Gr.fst`, `graph/HCLr.fst`, `graph/disambig_tid.int`, `graph/phones/word_boundary.int`,
`ivector/final.dubm`, `ivector/final.ie`, `ivector/final.mat`, `ivector/global_cmvn.stats`, `ivector/online_cmvn.conf`, `ivector/splice.conf`. 한국어 모델은 `graph/phones.txt` 가 더 있다.
**모델 파일은 고치지 않고 배포본 그대로 쓴다.**

### 2-4. ML Kit (1종)

| 결과 엔진 이름 | 모델 이름 | 조각 상한 | 언어 | 비고 |
|---|---|---|---|---|
| `google-mlkit` | `basic` | 없음(세션) | `ko-KR` / `en-US` | Google ML Kit GenAI Speech Recognition Basic. 모델은 기기(Google)가 관리한다. Android 전용이라 서버에는 대응이 없다 |

### 2-5. 언어 전용 모델

- 한국어 전용: `zipformer-ko`, `zipformer-ko-stream`, `vosk-small-ko`
- 영어 전용: `zipformer-en`, `parakeet-tdt-0.6b-v2`, `moonshine-tiny-en`, `moonshine-base-en`, `vosk-small-en`
- 앱은 맞지 않는 언어 폴더에서 이 모델들을 자동으로 뺀다.

## 3. STT 엔진별 디코딩 설정

### 3-1. sherpa-onnx 오프라인 — `engine/stt/SherpaOfflineAdapter.kt`

공통: `OfflineRecognizer`, `numThreads = 4`, `provider = "cpu"`, 디코딩 방식은 sherpa 기본값(따로 주지 않는다). 조각마다 `createStream()` → `acceptWaveform(samples, 16000)` → `decode` → `getResult`.
입력은 PCM16 을 float(−1~1)로 바꾼 것이다.

| 계열 | sherpa 설정 | 언어 주는 방법 | 결과 글자 |
|---|---|---|---|
| Whisper | `OfflineWhisperModelConfig(encoder, decoder, language, task="transcribe")`, `modelType="whisper"` | 설정의 `language` = `"ko"` / `"en"` | `result.text.trim()` |
| Qwen3-ASR | `OfflineQwen3AsrModelConfig(convFrontend, encoder, decoder, tokenizer, maxTotalLen=1024, maxNewTokens=1024)` | 스트림 옵션 `setOption("language", "Korean" / "English")` | `text.trim()` |
| Omnilingual | `OfflineOmnilingualAsrCtcModelConfig(model)` | 없음(CTC) | `text.trim()` |
| Zipformer KO | `OfflineTransducerModelConfig(encoder, decoder, joiner)` | 없음 | **토큰을 이어 붙인다**: 토큰의 `▁` 를 공백으로 바꿔 모두 잇고, 연속 공백을 한 칸으로 줄이고 앞뒤를 자른다. `text` 는 한글 띄어쓰기가 없어 쓰지 않는다(실측) |
| Zipformer EN | 위와 같은 transducer 설정 | 없음 | `text.trim()` |
| SenseVoice | `OfflineSenseVoiceModelConfig(model, language, useInverseTextNormalization=true)` | 설정의 `language` = `"ko"` / `"en"` | `text.trim()` |
| Parakeet-TDT | transducer 설정 + `modelType="nemo_transducer"` | 없음 | `text.trim()` |
| Moonshine v1 | `OfflineMoonshineModelConfig(preprocessor, encoder, uncachedDecoder, cachedDecoder)` | 없음 | `text.trim()` |

### 3-2. sherpa-onnx 스트리밍 — `engine/stt/SherpaStreamingAdapter.kt`

- `OnlineRecognizer`, `OnlineTransducerModelConfig(encoder, decoder, joiner)`, `numThreads = 4`, `provider = "cpu"`, **`enableEndpoint = false`**(끝점에서 끊지 않고 파일 끝까지 한 스트림).
- 넣는 단위: **100 ms(3,200 바이트 = 1,600 샘플)**. 넣을 때마다 `isReady` 인 동안 `decode` 한다.
- 끝: **0.5 s 무음(8,000 샘플)** 을 더 넣고 `inputFinished()` → 남은 것을 디코딩 → `getResult(stream).tokens`.
- 결과 글자: **토큰을 이어 붙인다**(연속 공백을 한 칸으로, 앞뒤 자름). sherpa 스트리밍의 `text` 가 한국어 띄어쓰기를 지우기 때문이다.
- 언어: Nemotron 은 스트림 옵션 `setOption("language", "ko" / "en")`. Zipformer KO 스트리밍은 주지 않는다.
- 공급 속도: 3-5 참고.

### 3-3. Vosk — `engine/stt/VoskAdapter.kt`

- `Model(모델 폴더)`, 조각마다 `Recognizer(model, 16000f)`. 디코딩은 부른 스레드 1개.
- 넣는 단위: 100 ms(3,200 바이트) PCM16 LE 를 `acceptWaveForm(bytes, len)` 에 넣는다.
  - `true` 를 돌려주면(말 사이 무음 = 끝점) `getResult()` 의 JSON `text` 를 문장으로 확정한다.
  - 다 넣은 뒤 `getFinalResult()` 의 `text` 를 마지막 문장으로 붙인다.
  - 문장들은 공백 한 칸으로 잇는다.
- 끝점 규칙은 모델의 `conf/model.conf` 그대로다(앱이 바꾸지 않는다).
- **결과가 실행마다 달라진다(한국어 모델에서 확인).** 원인은 Kaldi 특징 추출의 난수 잡음(dither)이다 — 배포 모델의 `conf/mfcc.conf` 에 `--dither=0` 이 없어 기본값 1.0 이 쓰인다.
  - 실측: `--dither=0` 을 넣은 사본은 12회 모두 같은 결과(603자). 원래 모델은 50회 모두 다른 본문(628~649자).
  - 약 30회에 1회는 오류 없이 글자가 거의 나오지 않는다(84초 음원에서 43~109자). 앱은 이런 행에 `note=DECODE_COLLAPSE_SUSPECT`(20초 이상 입력에서 3자/초 미만)를 붙인다.
  - 영어 모델은 v3 기준과 6건 중 5건이 바이트까지 같았다.
  - 서버의 Vosk 가 같은 현상을 보이는지는 확인하지 않았다. **같은 모델 파일을 쓰면 같은 원인이 있을 가능성이 높다.**

### 3-4. ML Kit — `engine/stt/MlKitAdapter.kt`, `GoogleSttSession.kt`

- 실시간 속도(REALTIME)로만 공급한다(공식 문서의 요구). 61초를 넘는 한 세션은 앞부분이 빠지는 결함이 있어 Speech EPD 로 세션을 나눈다.
- 서버에는 대응 엔진이 없으므로 자세한 내용은 생략한다.

### 3-5. 공급 속도(스트리밍·Vosk) — `domain/condition/RunOptions.kt`

100 ms 조각 하나를 넣는 간격이다. 오프라인 sherpa 에는 해당이 없다.

| 이름 | 간격 | 실시간 대비 | 조건 이름 |
|---|---|---|---|
| 사용 안 함(기본) | 기다리지 않음 | 최대 속도 | 붙지 않음 |
| `REALTIME` | 100 ms | ×1.0 | `_FEED_REALTIME` |
| `D50` | 50 ms | ×2.0 | `_FEED_D50` |
| `D30` | 30 ms | ×3.3 | `_FEED_D30` |
| `D20` | 20 ms | ×5.0 | `_FEED_D20` |
| `D10` | 10 ms | ×10 | `_FEED_D10` |

실측(S24 Ultra, 한국어 84초 1개, SILERO_FW): Nemotron 560ms 와 Vosk KO 모두 **D20 까지 공급 시간이 이론값과 같았고**, D10 은 엔진이 따라가지 못했다(Nemotron 은 약 13초, Vosk 는 약 10~14초가 바닥).
Nemotron 의 전사 결과는 공급 속도 6종 모두 글자 하나까지 같았다.

## 4. VAD 엔진 5종 — 구현 방식

공통: 입력은 16 kHz mono PCM16. 출력은 **말 구간 목록(샘플 좌표)** 이다. 모든 엔진이 같은 뒤처리(6장)를 거친다.

| 엔진 | 조건 이름 앞부분 | 모델 파일(앱이 쓰는 이름 ← 원본) | 받는 곳 | 라이선스(상업) |
|---|---|---|---|---|
| Silero VAD | `SILERO` | `silero_vad_16k_op15.onnx` | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/` | MIT (allowed) |
| pyannote segmentation-3.0 | `PYANNOTE` | `pyannote_seg3.onnx` ← `sherpa-onnx-pyannote-segmentation-3-0/model.onnx`(fp32) | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` | MIT, 원본은 HF 동의 필요 (allowed) |
| FunASR FSMN-VAD | `FSMN` | `fsmn_vad_quant.onnx` ← `model_quant.onnx`, `fsmn_vad_am.mvn` ← `am.mvn` | `https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master/` | Apache-2.0 + 출처·모델명 표기 (allowed) |
| FireRedVAD | `FIRERED` | `fireredvad_vad.onnx`, `fireredvad_cmvn.ark` ← `cmvn.ark` | `https://raw.githubusercontent.com/FireRedTeam/FireRedVAD/c30ec49e8cc69642b0ee65362eba11b9d11c6e54/pretrained_models/onnx_models/` | Apache-2.0 (allowed) |
| TEN VAD | `TEN` | `libten_vad.so`(공식 네이티브 라이브러리, APK 에 포함) | `tools/download_models.sh` | Apache-2.0 + 추가 조건 (**conditional**) |
| 사용 안 함 | `NOVAD` | — | — | — |

### 4-1. Silero — `engine/vad/SileroVadDetector.kt`, `domain/vad/FrameSegmenter.kt`

- 추론: 공식 `OnnxWrapper` 와 같다. 창 **512 샘플(32 ms)**, 앞 문맥 **64 샘플**, 순환 상태 `(2, 1, 128)`, 입력 `{input, state, sr=16000}`.
- 구간 결정: 공식 `get_speech_timestamps()` 를 옮겼다.
  - `neg_threshold = max(threshold − 0.15, 0.01)` 히스테리시스
  - `min_speech`, `min_silence`, `speech_pad`, `max_speech`(가장 긴 쉼에서 자르기), `min_silence_samples_at_max_speech = 16000 × 98 / 1000`
- 실측: 같은 음원에서 서버 `sherpa_onnx_stt.py --vad silero`(기본값 0.5/250/400/150, join 200)와 **VAD 뒤 길이가 80.252 s 로 일치**했다(확인함).

### 4-2. pyannote segmentation-3.0 — `engine/vad/PyannoteVadDetector.kt`

1. **10 s 창**(모델 메타데이터 `window_size`, 없으면 160,000 샘플)을 **1 s(창의 10 %) 간격**으로 밀며 추론한다. 출력은 `(N, 589, 7)` log 확률.
2. 프레임마다 `argmax ≠ 0` 이면 말(powerset → 화자 1명 이상).
3. 겹치는 창은 hamming 가중 평균으로 합친다.
4. Binarize: `onset`/`offset` 히스테리시스 → `min_duration_off` 보다 짧은 무음 메움 → `min_duration_on` 보다 짧은 말 제거.
5. 앞뒤 여유(`pad_onset`, `pad_offset`)를 붙이고, 겹치거나 맞닿은 구간을 합친 뒤 0~음원 길이로 자른다.
- 1~3 은 sherpa-onnx `scripts/pyannote/segmentation/vad-onnx.py`, 4 는 pyannote-audio `utils/signal.py` Binarize, 5 는 서버 `vad_segments.py` 의 `pad_and_merge()` 순서를 따른다.

### 4-3. FunASR FSMN-VAD — `engine/vad/FsmnVadDetector.kt`

1. Kaldi fbank 80(hamming) → LFR(m=5, n=1, 앞 2프레임 복사) → CMVN(`am.mvn`: `(x + shift) × scale`).
2. FSMN 인코더(ONNX): 입력 `feats (1, T, 400)` + cache 4개 `(1, 128, 19, 1)`, 출력은 프레임별 248 클래스 확률(0번 = 무음). 긴 음원은 3,000 프레임씩 나눠 cache 를 이어 준다.
3. E2E VAD 상태 기계: FunASR `runtime/llama.cpp/funasr-common/funasr_vad.h` 를 옮겼다.
   - 말 판정: `(1 − p_sil) >= p_sil + speech_noise_thres`
   - 창 200 ms(20 프레임) 안의 말 프레임 수 ≥ 15 → 말 시작, ≤ 15 → 말 끝 후보
   - 시작점은 창 + 200 ms 앞으로 당기고, 끝점은 lookahead 100 ms 를 반영한다.
   - **동적 끝 무음**(`max_end_silence = 0`, 프리셋 FSMN_DEFAULT): 말 누적 0~10 s 는 2000 ms → 20 s 1000 → 30 s 800 → 40 s 600 → 50 s 400 → 60 s 200 → 그 뒤 100 ms. 이때 `speech_noise_thres` 는 0.5 로 고정된다(FunASR 1.3.4+ 동작).
   - **고정 끝 무음**(`max_end_silence > 0`): `max_end_sil = (값 − 150 ms) / 10 ms`, `speech_noise_thres` 는 설정값.

### 4-4. FireRedVAD — `engine/vad/FireRedVadDetector.kt`

1. Kaldi fbank 80(povey 창, int16 스케일) → CMVN(`cmvn.ark`: `(x − mean) × 1/std`).
2. ONNX: `feat (1, T, 80)` → `probs (1, T, 1)`. 프레임은 10 ms. 긴 입력은 30,000 프레임(300 s)씩 따로 추론해 잇는다.
3. 뒤처리: 공식 `fireredvad/core/vad_postprocessor.py`(커밋 `c30ec49`)를 옮겼다 —
   `_smooth_prob → _apply_threshold → _smooth_preds_with_state_machine → _fix_smooth_window_start → _merge_short_silence_segments → _extend_speech_segments → _split_long_speech_segments → decision_to_segment`.

### 4-5. TEN VAD — `engine/vad/TenVadDetector.kt`

- 공식 `libten_vad.so` 의 C API 를 JNA 로 부른다: `ten_vad_create(handle, hop_size, threshold)`, `ten_vad_process(handle, int16*, len, &prob, &flag)`, `ten_vad_destroy`.
- hop **256 샘플(16 ms)** 마다 말 확률을 얻고, 그 확률을 **Silero 와 같은 상태 기계**(`FrameSegmenter`, 창 = hop)로 구간으로 만든다.
- 핸들은 파일마다 새로 만든다.

## 5. VAD 프리셋 22개 — 값

근거: `domain/vad/VadModels.kt` 의 `VadPresetCatalog`. 조건 이름이 곧 프리셋 이름이다. 화면에서는 프리셋만 고를 수 있다(값 직접 입력 없음).

"사이 무음" 은 말 구간을 이어 붙일 때 구간 사이에 넣는 디지털 무음 길이다(6장). 따로 적지 않으면 **200 ms** 다.

### 5-1. Silero (9개) — threshold / 최소 말(ms) / 최소 무음(ms) / 앞뒤 여유(ms). 최대 말 길이는 모두 무제한

| 프리셋 | threshold | min_speech_ms | min_silence_ms | speech_pad_ms | 사이 무음(ms) | 출처 |
|---|---|---|---|---|---|---|
| `SILERO` | 0.5 | 250 | 400 | 150 | 200 | 프로젝트 기준선(Balanced) |
| `SILERO_SAFE` | 0.3 | 150 | 600 | 300 | 200 | 프로젝트 ASR Safe |
| `SILERO_FW` | 0.5 | 0 | 2000 | 400 | **0** | faster-whisper VadOptions |
| `SILERO_DEFAULT` | 0.5 | 250 | 100 | 30 | 200 | silero-vad 공식 기본값 |
| `SILERO_SHERPA` | 0.5 | 250 | 500 | 0 | 200 | sherpa-onnx SileroVadModelConfig |
| `SILERO_QWEN3` | 0.5 | 1500 | 500 | 30 | 200 | Qwen3-ASR-Toolkit |
| `SILERO_WS` | 0.5 | 0 | 500 | 100 | 200 | whisper_streaming VADIterator |
| `SILERO_USER` | 0.4 | 250 | 300 | 100 | 200 | 사용자 정의 |
| `SILERO_FILE` | 0.5 | 250 | 400 | 150 | 200 | 서버 "VAD 단계 Silero(파일)" — `SILERO` 와 같은 값, 이름만 다르다 |

### 5-2. pyannote (3개) — 사이 무음 200 ms

| 프리셋 | onset | offset | min_duration_on(s) | min_duration_off(s) | pad_onset(s) | pad_offset(s) | 출처 |
|---|---|---|---|---|---|---|---|
| `PYANNOTE_DEFAULT` | 0.5 | 0.5 | 0 | 0 | 0 | 0 | pyannote 공식 기본 |
| `PYANNOTE_S500` | 0.5 | 0.5 | 0 | 0.5 | 0.15 | 0.15 | PC GUI v8.1 세밀 |
| `PYANNOTE_S2000` | 0.5 | 0.5 | 0 | 2.0 | 0.15 | 0.15 | PC GUI v8.1 길게 |

### 5-3. FSMN (3개) — 사이 무음 200 ms

| 프리셋 | max_end_silence(ms) | speech_noise_thres | max_single_segment(ms) | 출처 |
|---|---|---|---|---|
| `FSMN_DEFAULT` | 0 (= 동적) | 0.5 | 60000 | FunASR 공식 기본 |
| `FSMN_CONFIG` | 800 | 0.6 | 60000 | 모델 `config.yaml` 고정값 |
| `FSMN_S500` | 500 | 0.6 | 60000 | PC GUI v8.1 세밀 |

### 5-4. FireRed (3개) — 값은 프레임 수(1 프레임 = 10 ms), 사이 무음 200 ms

| 프리셋 | threshold | smooth_window | min_speech_frame | max_speech_frame | min_silence_frame | merge_silence_frame | extend_speech_frame | 출처 |
|---|---|---|---|---|---|---|---|---|
| `FIRERED_DEFAULT` | 0.4 | 5 | 20 | 2000 | 20 | 0 | 0 | FireRedVadConfig 공식 기본 |
| `FIRERED_S500` | 0.4 | 5 | 20 | 2000 | 50 | 0 | 15 | PC GUI v8.1 세밀 |
| `FIRERED_S2000` | 0.4 | 5 | 20 | 2000 | 200 | 0 | 15 | PC GUI v8.1 길게 |

### 5-5. TEN (3개) — 사이 무음 200 ms

| 프리셋 | threshold | hop(샘플) | min_speech_ms | min_silence_ms | speech_pad_ms | max_speech(s) | 출처 |
|---|---|---|---|---|---|---|---|
| `TEN_DEFAULT` | 0.5 | 256 | 0 | 0 | 0 | 무제한 | ten-vad 공식(후처리 없음) |
| `TEN_SHERPA` | 0.5 | 256 | 250 | 500 | 0 | 20 | sherpa-onnx TenVadModelConfig |
| `TEN_ASR` | 0.5 | 256 | 250 | 400 | 150 | 무제한 | 프로젝트 제안(공식 근거 없음) |

### 5-6. 사용 안 함 (1개)

`NOVAD` — VAD 를 돌리지 않고 파일 전체를 한 구간으로 본다.

## 6. VAD 뒤 STT 입력 만들기 — 서버와 다른 부분

근거: `run/PipelineSteps.kt` 의 `pack()`, `domain/vad/SegmentPacker.kt`, `engine/vad/VadPipeline.kt` 의 `VadPcmJoiner`.

### 6-1. 규칙

```
상한 = min(구간 처리의 상한, 모델의 조각 상한)      # 둘 중 있는 것. 둘 다 없으면 "상한 없음"
  · 구간 처리 "엔진별 최대 길이"(기본): 구간 처리의 상한 없음 → 모델의 조각 상한(2장)만 쓴다
  · 구간 처리 "10초 통일"(SEG10): 구간 처리의 상한 = 10 s
```

**(가) 상한이 없는 엔진 — 스트리밍 3종, Vosk, ML Kit (구간 처리가 기본일 때)**
- 말 구간의 원본 PCM 을 순서대로 이어 붙이고, **구간 사이마다 "사이 무음" 만큼 0 을 넣는다**. 맨 앞·맨 뒤에는 넣지 않는다.
- 그 결과 1개를 한 스트림으로 넣는다(조각 수 1).
- VAD 를 쓰지 않으면 파일 전체 1개.

**(나) 상한이 있는 엔진 — sherpa 오프라인 전부, 그리고 SEG10 을 고른 모든 엔진** (`SegmentPacker.pack`)
1. 상한보다 긴 말 구간은 자른다. 자르는 위치는 **구간 시작 + 상한의 50~100 % 범위에서 30 ms 창(480 샘플, 15 ms 간격으로 이동) 에너지가 가장 작은 곳**의 창 가운데다.
   (서버 `stt_common.split_long_segments(samples=)` 와 같은 규칙으로 만들었다.)
2. 구간들을 순서대로 한 조각에 담는다. 같은 조각 안의 구간 사이에는 "사이 무음" 을 넣는다. 다음 구간을 넣으면 상한을 넘을 때 조각을 닫고 새 조각을 시작한다.
3. VAD 를 쓰지 않으면 파일 전체를 한 구간으로 보고 1 을 적용한다.
4. 조각마다 새 스트림으로 디코딩하고 결과를 공백 한 칸으로 잇는다.

### 6-2. 서버와의 차이(확인함)

같은 한국어 음원(84.135 s) · Silero 기본값으로 견준 결과:

| | 서버 `sherpa_onnx_stt.py` (zipformer-ko, `--vad silero`) | 앱 (`zipformer-ko`, `SILERO`) |
|---|---|---|
| VAD 뒤 길이 | 80.252 s | 80.252 s(상한 없는 경로) / 79.052 s(15 s 조각으로 묶은 합) |
| STT 에 넣은 조각 수 | **1** | **7** |
| 글자 수 | 359 | 504 |

- VAD 구간은 같다. 다른 것은 **조각 만들기**다. 서버 스크립트는 zipformer 에 조각 상한을 두지 않고 통째로 넣고, 앱은 15 s 상한으로 나눠 넣는다.
- Zipformer 는 긴 입력에서 단어가 빠지므로(실측: 24 s 부터) 서버 쪽 결과가 짧게 나온 것으로 보인다. **서버에 2장의 조각 상한과 6-1 의 묶기 규칙을 넣으면 앱과 같은 방식이 된다.**
- 앱과 결과를 맞추려면 Zipformer KO 의 결과 글자를 토큰 이어 붙이기(3-1)로 만드는 것도 필요하다.

## 7. 언어와 이름 규칙

- 언어 판정(`domain/lang/LanguageResolver.kt`): 입력 폴더 바로 아래 하위 폴더 이름으로 정한다. 대소문자를 가리지 않고, 이름이 낱말과 같거나 `_<낱말>` 로 끝나면 해당한다.
  - 한국어: `kr`, `ko`, `kor`, `korea`, `korean`, `한국어`
  - 영어: `en`, `eng`, `english`, `영어`
- CSV `req_lang`: `ko-KR` / `en-US`. 엔진에 주는 값은 3장 표와 같다.
- 조건 이름: `[NS_<노이즈>_]<VAD 프리셋 또는 NOVAD>[_SEG10][_EPD_…][_FEED_<속도>]`
- 결과 경로: `<출력>/<언어 폴더>/<결과 엔진 이름>_<모델 이름>_<조건>/<시나리오>/<NN>.txt`
- CSV: `<출력>/<언어 폴더>/<결과 엔진 이름>_<조건>_result.csv` — 서버 통일 CSV v2.0 의 74열(`stt_common.STT_CSV_HEADER`). 열 설명은 저장소의 `STT_계측_CSV_설명서.md`.

## 8. 서버 쪽에서 알아야 할 것

1. **엔진 이름 목록**: 서버 `stt_common.split_algo_option` 의 `known_algos` 에 앱의 결과 엔진 이름 19개가 들어 있어야 `run_eval.py` 가 앱 결과의 CSV 행과 TXT 를 같은 이름으로 등록한다.
   2026-11-07 에 넣었다(패치: 저장소 `docs/AI_analyze/patches/server_stt_common_app_engine_names_20261107.diff`). **앱에 엔진을 더하면 서버 목록에도 더해야 한다.**
   ```
   whisper-tiny, whisper-base, whisper-small, whisper-medium, whisper-turbo, qwen3asr-0.6b, omnilingual-300m,
   nemotron3.5-320ms, nemotron3.5-560ms, zipformer-ko, zipformer-ko-stream, sensevoice-small, zipformer-en,
   parakeet-tdt-0.6b-v2, moonshine-tiny-en, moonshine-base-en, vosk-small-ko, vosk-small-en, google-mlkit
   ```
2. **이름 체계가 다르다**: 서버는 `algo` = 런타임(`sherpa-onnx`, `vosk` …), `model` = 모델(`zipformer-ko` …)이고, 앱은 `algo` = 모델별 엔진 이름(`zipformer-ko`), `model` = `sherpa-int8` / `vosk` / `basic` 이다.
   같은 가중치라도 결과 폴더 이름이 다르다(서버 `sherpa-onnx_zipformer-ko_SILERO`, 앱 `zipformer-ko_sherpa-int8_SILERO`).
3. **서버 `sherpa_onnx_stt.py` 가 아는 모델 키**(확인함, `--help`): `zipformer-ko`, `streaming-zipformer-ko`, `zipformer-en`, `parakeet-tdt-0.6b-v2`, `sense-voice`.
   앱의 `zipformer-ko-stream` ↔ 서버 `streaming-zipformer-ko`, 앱 `sensevoice-small` ↔ 서버 `sense-voice` 로 이름이 다르다. 그 밖의 서버 엔진(Whisper, Qwen3, Moonshine, Nemotron, Vosk, Omnilingual)과의 대응은 이 문서를 쓰면서 확인하지 않았다.
4. **정밀도**: 앱은 모두 int8 패키지를 쓴다(Zipformer EN 의 decoder 만 fp32). 서버 `sherpa_onnx_stt.py` 는 위 6-2 실행에서 zipformer-ko 를 **fp32** 로 돌렸다. 결과를 글자까지 맞추려면 정밀도도 맞춰야 한다.
5. **sherpa-onnx 판**: 앱 1.13.8(onnxruntime 1.28.2 내장). 서버도 6-2 실행에서 `1.13.8+cuda12.cudnn9.onnxruntime1.28.2` 였다(확인함).
6. **조각 상한과 묶기 규칙**(2장, 6-1)을 서버에 넣을지 정해야 한다. 지금은 Zipformer 에서 결과가 크게 다르다(6-2).
7. **Vosk 한국어의 결과 흔들림**(3-3)은 앱의 문제가 아니라 모델 설정(dither)에서 온다. 서버에서 정확도를 견줄 때 같은 주의가 필요하다.
8. **VAD 프리셋 이름과 값**(5장)은 서버와 같게 맞춘 것이다(`SILERO_FILE`, `PYANNOTE_S500` 등). 다만 pyannote · FSMN · FireRed 는 공식 구현을 Kotlin 으로 옮긴 것이라 서버(PyTorch/공식 런타임)와 프레임 단위로 다를 수 있다. Silero 만 서버와 길이가 일치함을 확인했다.

## 9. 근거 파일 목록

| 내용 | 파일 (`app/src/main/java/com/example/google_stt/` 기준) |
|---|---|
| 모델 19종 정의 | `domain/catalog/Catalog.kt`, `domain/catalog/ModelSpec.kt` |
| 받는 주소·크기·파일 목록 | `domain/catalog/CatalogAssets.kt`, 해시는 `app/src/main/assets/asset_manifest.json` |
| sherpa 오프라인 설정 | `engine/stt/SherpaOfflineAdapter.kt` |
| sherpa 스트리밍 설정 | `engine/stt/SherpaStreamingAdapter.kt` |
| Vosk | `engine/stt/VoskAdapter.kt` |
| ML Kit | `engine/stt/MlKitAdapter.kt`, `engine/stt/GoogleSttSession.kt` |
| 공급 속도·실행 옵션 | `domain/condition/RunOptions.kt` |
| VAD 엔진·프리셋 값 | `domain/vad/VadModels.kt` |
| VAD 구현 | `engine/vad/{Silero,Pyannote,Fsmn,FireRed,Ten}VadDetector.kt`, `engine/vad/KaldiFbank.kt` |
| 구간 상태 기계(Silero 방식) | `domain/vad/FrameSegmenter.kt` |
| 말 구간 이어 붙이기 | `engine/vad/VadPipeline.kt` (`VadPcmJoiner`) |
| 조각 묶기 | `domain/vad/SegmentPacker.kt` |
| 처리 순서 | `run/PipelineSteps.kt`, `run/JobPipeline.kt` |
| 오디오 디코딩·리샘플 | `engine/audio/AudioDecoder.kt` |
| 언어 판정 | `domain/lang/LanguageResolver.kt` |
| 74열 CSV | `domain/csv/UnifiedCsv.kt`, 골든 `docs/AI_analyze/golden/STT_CSV_HEADER_v2.0.txt` |
