# Google On-device STT — 계측 CSV 설명서

출력 위치: `output/google/<mode>/result.csv` (파일별), `output/google/<mode>/run_meta.csv` (실행 1회)
인코딩: UTF-8 with BOM (utf-8-sig) — 기존 파이썬 파이프라인과 동일

---

## 0. 가장 먼저 읽어야 할 것 — 왜 `stt_wall_ms` 로 엔진을 비교하면 안 되는가

이 앱은 오디오를 **파이프로 실시간 속도(초당 32 KB)로 흘려보내는** 구조다.
ML Kit 공식 문서가 그렇게 요구하고, 플랫폼 `EXTRA_AUDIO_SOURCE` 도 스트리밍 세션이기 때문이다.

그래서 REALTIME 모드에서는

```
stt_wall_ms ≈ 음원 길이 × 1000   (엔진이 아무리 빨라도)
```

가 되어 **엔진 속도 비교에 쓸 수 없다.** 대신 이렇게 본다.

| 지표 | 의미 | 언제 쓰나 |
|---|---|---|
| **`tail_ms`** | 오디오 공급이 끝난(EOF) 순간부터 최종 결과가 나올 때까지 | ★ **엔진의 순수 처리 지연**. 공급 속도와 무관하므로 엔진 간 비교의 기본 지표 |
| **`tail_rtf`** | `tail_ms / (audio_sec × 1000)` | 음원 길이로 정규화한 처리 지연. 파일 길이가 제각각일 때 비교용 |
| `first_result_ms` | 세션 시작 → 첫 응답 | 체감 응답성(TTFT). 실시간 자막 용도라면 이게 핵심 |
| `ready_ms` | `startListening()` → `onReadyForSpeech()` | 엔진 바인딩·모델 로드 웜업 지연 (플랫폼 경로만) |
| `stt_wall_ms` | 세션 시작 → 최종 결과 | FAST/MAX 모드에서만 처리량 지표로 의미 있음 |
| `rtf` | `stt_wall_ms / (audio_sec × 1000)` | 위와 동일. REALTIME 모드에서는 항상 1.0 근처가 나온다 |

**처리량(throughput)을 재고 싶으면** Feed 스피너를 `FAST`(15 ms) 또는 `MAX`(0 ms)로 바꾼다.
그러면 `stt_wall_ms` / `rtf` 가 실제 처리 속도를 반영한다.
다만 엔진이 빠른 입력을 감당 못하면 결과가 잘릴 수 있으니, **몇 개 파일로 REALTIME 결과와 대조 검증한 뒤** 사용한다.
어떤 조건으로 돌렸는지는 `feed_mode` / `feed_delay_ms` 컬럼에 항상 남는다.

---

## 1. `result.csv` 컬럼 (44 + Silero VAD 18 + Noise Reduction 17 = 79개)

### 식별
| 컬럼 | 설명 |
|---|---|
| `path` | 입력 루트 기준 상대 경로(확장자 제외) |
| `file` | 파일명(확장자 제외) |
| `status` | `OK` / `NO_MATCH` / `ERROR` |
| `engine` | `ML_KIT` / `ANDROID_PLATFORM_ON_DEVICE` |
| `mode` | `base` / `advanced` / `android_ondevice` |
| `execution` | API 계약상 실행 위치. 예: `ON_DEVICE(platform on-device service)` |
| `lang` | BCP-47 (`ko-KR`, `en-US`) |

### 입력 오디오
| 컬럼 | 설명 |
|---|---|
| `audio_sec` | ★ **음원 길이(초)**. 16 kHz mono PCM 으로 변환된 결과 기준 |
| `src_mime` | 원본 코덱 (`audio/mpeg`, `audio/mp4a-latm`, `audio/raw` …) |
| `src_rate` | 원본 샘플레이트(Hz) |
| `src_ch` | 원본 채널 수 |
| `src_bytes` | 원본 파일 크기(byte) |

### 공급 조건 (지연 해석용)
| 컬럼 | 설명 |
|---|---|
| `decode_ms` | 디코딩 + 리샘플링 소요(ms) |
| `feed_mode` | `REALTIME` / `FAST` / `MAX` |
| `feed_delay_ms` | 청크(100 ms 분량) 간 대기 시간 |
| `feed_ms` | 오디오 공급에 걸린 시간 |

### 지연
| 컬럼 | 설명 |
|---|---|
| `ready_ms` | 세션 시작 → 엔진 준비 완료. ML Kit 경로는 `-1`(콜백 없음) |
| `first_result_ms` | 세션 시작 → 첫 응답 |
| `tail_ms` | ★ EOF → 최종 결과 |
| `stt_wall_ms` | 세션 시작 → 최종 결과 |
| `total_ms` | `decode_ms + stt_wall_ms` |
| `rtf`, `tail_rtf` | 음원 길이로 나눈 값 |

> 측정하지 못한 값은 `-1`, 계산 불가한 실수는 빈 칸이다. **`0` 과 구분된다.**

### 출력
| 컬럼 | 설명 |
|---|---|
| `chars` | 전사 결과 글자 수 |
| `words` | 공백 기준 어절/단어 수 |
| `segments` | 세그먼트 결과 수(플랫폼) / final 응답 수(ML Kit) |

### 모델 상태
| 컬럼 | 설명 |
|---|---|
| `mlkit_status` | `AVAILABLE` / `DOWNLOADABLE` / `UNAVAILABLE` … (ML Kit 경로만) |
| `check_status_ms` | 가용성 조회 소요 |
| `download_ms` | 모델 다운로드 소요. 보통 첫 파일에서만 값이 있다 |

### ★ 온디바이스 실행 판정 근거
| 컬럼 | 설명 |
|---|---|
| `net_type` | `WIFI` / `CELLULAR` / `ETHERNET` / `VPN` / `NONE` |
| `airplane` | 비행기 모드 `1`/`0` |
| `app_rx_delta`, `app_tx_delta` | **이 앱 UID** 의 수신/송신 증가분(byte). 온디바이스면 0 이어야 한다 |
| `dev_rx_delta`, `dev_tx_delta` | **기기 전체** 증가분. 다른 앱 트래픽이 섞이므로 참고용 |

### 자원
| 컬럼 | 설명 |
|---|---|
| `cpu_ms_delta` | 이 앱 프로세스의 CPU 시간 증가분(ms) |
| `pss_kb` | 처리 직후 이 앱의 PSS 메모리(KB) |
| `batt_uah_delta` | 배터리 소모(µAh). 파일 1건당 에너지 비용 |
| `batt_pct` | 배터리 잔량(%) |
| `batt_temp_c` | 배터리 온도(°C) |
| `thermal` | `NONE`/`LIGHT`/`MODERATE`/`SEVERE`… — ★ 뒤쪽 파일 지연이 나빠지는 원인 설명 |

### 기타
`started_at`(ISO-8601), `error`, `text`

---

## 1-2. Silero VAD 컬럼 (add-hyungchul-20260825-1430, 45~62번째)

> **호환성**: VAD 컬럼은 기존 44개 컬럼 **뒤에** 붙였다.
> 기존 파이썬 후처리 스크립트가 컬럼 index 를 쓰고 있어도 고칠 필요가 없다.
> VAD OFF 로 돌리면 `vad_enabled=0`, `vad_profile=OFF` 이고 앞의 44개 값은 기존과 동일하게 나온다.

### VAD 설정 (파일마다 같은 값)
| 컬럼 | 설명 |
|---|---|
| `vad_enabled` | `1` = VAD ON, `0` = OFF(기존 baseline) |
| `vad_model` | 모델 파일명. `silero_vad_16k_op15.onnx` |
| `vad_profile` | `ASR Safe` / `Balanced` / `Aggressive` / `Custom` / `OFF` |
| `vad_threshold` | speech 판정 임계값 |
| `vad_neg_threshold` | 무음 판정 임계값. Silero 공식 규칙 `max(threshold − 0.15, 0.01)` 로 자동 계산 |
| `vad_min_speech_ms` | 이보다 짧은 speech 구간은 버린다 |
| `vad_min_silence_ms` | 이보다 짧은 무음은 구간을 끊지 않는다 |
| `vad_speech_pad_ms` | 구간 앞뒤 여유. **작을수록 앞말/뒷말이 잘려 WER 이 나빠진다** |
| `vad_max_speech_sec` | 구간 최대 길이(초). 빈 칸이면 Unlimited |

### VAD 처리 결과 (파일마다 다름)
| 컬럼 | 설명 |
|---|---|
| `vad_process_ms` | ★ VAD 추론 + PCM 재구성에 걸린 시간. **VAD 도입 비용이 바로 이 값이다** |
| `vad_original_sec` | VAD 통과 전 음원 길이 = `audio_sec` |
| `vad_output_sec` | ★ **STT 에 실제로 넣은 길이**. VAD OFF 면 `audio_sec` 과 같다 |
| `vad_detected_speech_sec` | padding 적용 **전** 순수 검출 speech 길이. OFF 면 빈 칸 |
| `vad_removed_sec` | 잘라낸 무음 길이 = `vad_original_sec − vad_output_sec` |
| `vad_removed_ratio` | 제거 비율(%). 0 이면 아무것도 안 지웠다는 뜻 |
| `vad_segment_count` | 검출된 speech 구간 개수 |

### 파생 지표
| 컬럼 | 설명 |
|---|---|
| `rtf_stt_input` | ★ `stt_wall_ms / (vad_output_sec × 1000)`. **엔진 속도 비교는 이 값으로 한다** |
| `total_rtf` | `total_ms / (audio_sec × 1000)`. decode + VAD + STT 를 모두 포함한 end-to-end 지표 |

### ★ 반드시 알아야 할 함정 — VAD ON 에서 `rtf` 를 그대로 쓰면 안 된다

`rtf` 의 정의는 **바꾸지 않았다**(= `stt_wall_ms ÷ 원본 음원 길이`).
그래서 VAD ON 이면 분모는 원본 길이인데 분자는 짧아진 오디오로 잰 시간이라,
**엔진이 빨라진 것처럼 보인다.** 이건 착시다.

| 비교하고 싶은 것 | 써야 할 컬럼 |
|---|---|
| 엔진 자체의 속도 (VAD ON/OFF 공정 비교) | `rtf_stt_input` |
| 사용자 체감 end-to-end (VAD 비용 포함) | `total_rtf` |
| 예전 baseline 실행과의 연속성 | `rtf` (VAD OFF 끼리만) |

### `status` 에 값이 하나 늘었다

| 값 | 의미 |
|---|---|
| `NO_SPEECH_BY_VAD` | VAD 가 speech 를 하나도 못 찾아 **STT 를 아예 호출하지 않았다**. `.txt` 는 빈 파일로 저장된다 |

`error` 컬럼이 `VAD: ...` 로 시작하면 VAD 단계에서 실패한 것이다.
**이때 조용히 baseline 으로 되돌리지 않는다.** 되돌리면 CSV 상으로는 VAD ON 인데
실제로는 OFF 로 돈 행이 섞여 비교가 통째로 깨지기 때문이다.

### `total_ms` 정의가 확장됐다

```
total_ms = decode_ms + vad_process_ms + stt_wall_ms
```

VAD OFF 면 `vad_process_ms = 0` 이라 **기존 값과 완전히 같다.**

### 출력 폴더가 갈린다

```
VAD OFF : output/google/<mode>/...                    ← 기존 경로 그대로
VAD ON  : output/google/<mode>/vad_asr_safe/...
          output/google/<mode>/vad_balanced/...
          output/google/<mode>/vad_aggressive/...
          output/google/<mode>/vad_custom_t50_sp100_si300_pad200_maxinf/...
```

baseline 결과를 덮어쓰지 않으므로 **같은 음원으로 두 번 돌려 WER/CER 을 나란히 비교**할 수 있다.

---

## 1-3. Noise Reduction 컬럼 (add-hyungchul-20260826-1100, 63~79번째)

> **호환성**: VAD 때와 같이 기존 컬럼 **뒤에** 붙였다. 앞 62개 index 는 그대로다.
> 노이즈 저감을 안 쓰면 `ns_enabled=0`, `ns_algorithm=NONE` 이고 앞 62개 값도 기존과 동일하다.

### 알고리즘/설정
| 컬럼 | 설명 |
|---|---|
| `ns_enabled` | `1` = 사용, `0` = 미사용 |
| `ns_algorithm` | `GTCRN` / `DPDFNET` / `NSNET2` / `MMSE_STSA` / `WIENER` / `SPECTRAL_SUB` / `NONE` |
| `ns_label` | 실제 모델 파일명 또는 구현 라벨 (예: `gtcrn_simple.onnx`) |
| `ns_config` | 실행 설정 요약 (threads, attenLimit, alpha 등) |

### ★ 단계별 소요 시간 — 이번에 추가한 핵심
| 컬럼 | 설명 |
|---|---|
| `ns_frames` | 처리한 STFT 프레임 수. 알고리즘마다 hop 이 달라 값이 다르다 (GTCRN 256, DPDFNet/NSNet2 160, 전통 DSP 256) |
| `ns_prepare_ms` | PCM→float 변환·패딩 |
| `ns_stft_ms` | STFT(분석) 누적. 전통 DSP 의 잡음 추정 사전 패스와 NSNet2 의 특징 추출 패스도 여기 들어간다 |
| **`ns_infer_ms`** | ★ **모델 추론 또는 DSP 이득 계산 누적. 이게 알고리즘의 진짜 비용이다** |
| `ns_istft_ms` | iSTFT(합성·overlap-add) 누적 |
| `ns_post_ms` | 감쇠 제한 적용 + float→PCM 변환 |
| `ns_total_ms` | 위 전부를 합한 벽시계 시간 |
| **`ns_infer_per_frame_us`** | ★ **프레임 1개당 추론 시간(µs). 알고리즘 간 속도 비교는 이 값으로 한다** |

`ns_infer_per_frame_us` 를 쓰는 이유: `ns_total_ms` 는 파일 길이와 hop 크기에 좌우된다.
GTCRN(hop 256)과 DPDFNet(hop 160)은 같은 85초 파일에서도 프레임 수가 각각 약 5,300 / 8,500 으로 다르다.
프레임당 시간으로 정규화해야 "어느 모델이 무거운가"가 제대로 드러난다.

### 처리 결과
| 컬럼 | 설명 |
|---|---|
| `ns_in_rms_db` | 입력 RMS (dBFS). **음수가 정상**이라 미측정은 빈 칸으로 표시된다 |
| `ns_out_rms_db` | 출력 RMS (dBFS) |
| `ns_reduction_db` | `in − out`. 클수록 많이 깎았다는 뜻 |
| `ns_peak` | 출력 최대 진폭. **1.0 을 넘으면 클리핑**이 일어난 것이라 WER 이 나빠질 수 있다 |
| `ns_note` | 알고리즘 부가 정보 (전통 DSP 는 잡음 추정에 쓴 프레임 수 등) |

### `total_ms` 정의가 또 확장됐다

```
total_ms = decode_ms + ns_total_ms + vad_process_ms + stt_wall_ms
```

노이즈 저감을 안 쓰면 `ns_total_ms = 0` 이라 **기존 값과 완전히 같다.**

### 파이프라인 순서와 출력 폴더

```
디코딩 → [Noise Reduction] → [Silero VAD] → STT
```

VAD 는 **노이즈 저감을 끝낸 신호**를 본다(잡음을 먼저 없애야 음성 구간 판정이 정확해진다).

```
output/google/<mode>/                                   ← 둘 다 미사용 (기존 baseline)
output/google/<mode>/vad_asr_safe/                      ← VAD 만
output/google/<mode>/ns_gtcrn_lim12/                    ← NS 만
output/google/<mode>/ns_gtcrn_lim12/vad_asr_safe/       ← 둘 다
```

폴더명에 설정이 녹아 있어(`_lim12` = Attenuation Limit 12 dB) 조합을 여러 번 돌려도 서로 덮어쓰지 않는다.

### ★ Attenuation Limit — 과도한 제거를 막는 안전장치

첨부하신 조사 문서의 원칙 1번("과도한 노이즈 제거 금지")을 코드로 강제한 장치다.

```
lim = 10^(-attenuationLimitDb / 20)
출력 = lim × 원음 + (1 − lim) × 처리음
```

"출력이 원음보다 최대 몇 dB 까지만 작아지게" 원음을 섞는다.
DeepFilterNet 의 `atten_lim_db` 와 같은 방식이고, sherpa-onnx 는 DPDFNet 에 12 dB 를 기본으로 쓴다.
딥러닝 계열 기본값을 12 dB 로 둔 이유가 이것이다. `0` 이면 제한하지 않는다.

**WER 이 나빠졌다면 이 값을 먼저 의심하라.** 12 → 6 dB 로 낮추면 원음이 더 많이 남는다.

---

## 2. "정말 온디바이스인가"를 증명하는 절차

앱이 다른 프로세스(음성인식 서비스)의 통신량을 직접 측정하는 것은 **불가능하다.**
Android 7(API 24)부터 `TrafficStats.getUidRxBytes()` 는 자기 UID 외에는 `UNSUPPORTED(-1)` 를 돌려주기 때문이다.
그래서 다음 순서로 **재현 가능한 증거**를 만든다.

1. **평소대로 1회 실행** — `net_type=WIFI`, `airplane=0` 인 기준 데이터를 얻는다.
2. **비행기 모드 ON + Wi-Fi/데이터 OFF 로 같은 폴더를 다시 실행**한다.
   - `status=OK` 가 그대로 나오면 → **네트워크 없이 전사에 성공했다는 직접 증거**다.
   - `run_meta.csv` 의 `airplane_at_start=1`, `result.csv` 의 `net_type=NONE`, `airplane=1` 이 기록으로 남는다.
3. 두 실행의 `text` 를 비교한다. 동일하면 클라우드 폴백이 없었다는 뜻이다.
4. `app_rx_delta` / `app_tx_delta` 가 0 인지 확인한다. 이 앱은 `INTERNET` 권한 자체를 선언하지 않으므로 항상 0이어야 한다.

> 참고: `NetworkStatsManager` 를 쓰면 다른 UID 의 통신량도 볼 수 있지만
> `PACKAGE_USAGE_STATS`(설정 → 사용 정보 접근) 권한을 사용자가 직접 켜야 해서 이 앱에는 넣지 않았다.
> 위 2번 절차가 더 간단하고 증거력도 강하다.

---

## 3. `run_meta.csv` (key,value)

`manufacturer`, `model`, `device`, `hardware`, `soc`, `android_release`, `sdk_int`,
`build_fingerprint`, `abi`, `cpu_cores`, `app_version`,
`net_type_at_start`, `airplane_at_start`, `thermal_at_start`,
`battery_pct_at_start`, `battery_temp_c_at_start`,
`recognition_services`(단말에 설치된 RecognitionService 목록과 버전),
`default_voice_recognition_service`,
`algo`, `mode`, `engine`, `execution_declared`, `lang`,
`feed_mode`, `feed_delay_ms`, `file_count`, `preflight`

**add-hyungchul-20260825-1430 — VAD 관련 key 추가**

`vad_enabled`, `vad_profile`, `vad_model`, `vad_threshold`, `vad_neg_threshold`,
`vad_min_speech_ms`, `vad_min_silence_ms`, `vad_speech_pad_ms`, `vad_max_speech_sec`,
`onnxruntime_version`, `silero_model_sha256`, `output_variant`, `feed_chunk_audio_ms`

**add-hyungchul-20260826-1100 — Noise Reduction key 추가**

`ns_enabled`, `ns_algorithm`, `ns_label`, `ns_config`, `ns_model_sha256`, `pipeline_order`

> ★ `silero_model_sha256` 은 **보고서에 반드시 함께 적어라.**
> "어떤 모델 파일로 낸 수치인지"가 이 해시 하나로 증명된다.
> `onnxruntime_version` 도 마찬가지다. ONNX Runtime 버전이 다르면 `vad_process_ms` 를 직접 비교하면 안 된다.

여러 기기의 `result.csv` 를 합쳐 비교할 때 **반드시 `run_meta.csv` 를 함께 보관**한다.
같은 엔진이라도 SoC·Android 버전·인식 서비스 버전이 다르면 지연이 크게 달라진다.

---

## 4. 실험 설계 권장 사항

1. **워밍업 1회를 버린다.** 첫 파일은 서비스 바인딩·모델 로드 때문에 `ready_ms`/`tail_ms` 가 크게 나온다.
   분석할 때 첫 1~2건은 제외하거나 따로 표시한다.
2. **같은 순서로 3회 반복**하고 중앙값을 쓴다. 한 번의 측정은 열/스케줄러 영향을 많이 받는다.
3. **`thermal` 이 `MODERATE` 이상으로 올라간 구간은 따로 표시**한다. 그 구간의 지연은 엔진 성능이 아니라 발열 때문이다.
4. 배터리는 **충전기를 뽑고** 측정한다. 충전 중이면 `batt_uah_delta` 가 의미를 잃는다.
5. 화면을 켠 채로 두고(또는 개발자 옵션의 "화면 켜짐 유지") 실행한다. 화면이 꺼지면 CPU 정책이 바뀌어 지연이 달라진다.
6. `base` 와 `android_ondevice` 는 같은 엔진일 가능성이 높다(ML Kit `MODE_BASIC` = 플랫폼 `SpeechRecognizer`).
   두 모드의 `tail_ms` 분포가 겹치는지 보면 이 가설을 데이터로 확인할 수 있다.
7. **VAD 는 반드시 baseline 과 짝지어 돌린다.** 같은 음원 / 같은 Feed / 같은 모드로
   ① VAD OFF → ② VAD ON(ASR Safe) → ③ VAD ON(Balanced) → ④ VAD ON(Aggressive) 순으로 4회 돌리고,
   `output/google/<mode>/` 와 `.../vad_*/` 의 `.txt` 를 정답 스크립트와 대조해 WER/CER 을 낸다.
8. **VAD 가 WER/CER 을 개선한다고 미리 단정하지 않는다.**
   무음이 줄면 좋아질 수도 있지만, padding 이 작으면 앞말/뒷말이 잘려 오히려 나빠진다.
   `vad_removed_ratio` 가 큰데 WER 이 나빠졌다면 `vad_speech_pad_ms` 를 키워 다시 본다.
9. VAD ON/OFF 의 속도 비교는 `rtf` 가 아니라 **`rtf_stt_input`** 으로 한다(위 1-2절의 함정 참고).
10. **노이즈 저감은 한 번에 하나씩만 바꾼다.** VAD 설정을 고정하고 알고리즘만 갈아 끼운 뒤
    `.txt` 를 정답과 대조해 WER/CER 을 낸다. NS 와 VAD 를 동시에 바꾸면 원인을 못 가린다.
11. **`ns_peak` 를 매번 확인한다.** 1.0 을 넘으면 클리핑이라, WER 이 나빠진 원인이
    알고리즘 품질이 아니라 단순 클리핑일 수 있다. 이때는 Attenuation Limit 을 키운다.
12. **속도 비교는 `ns_infer_per_frame_us`** 로 한다. `ns_total_ms` 는 hop 이 달라 직접 비교가 안 된다.

---

## 5. 분석 예시 (pandas)

```python
import pandas as pd

df = pd.read_csv("result.csv", encoding="utf-8-sig")

# 워밍업 제외
df = df.iloc[2:]

ok = df[df.status == "OK"]
print(ok.groupby("mode")[["audio_sec", "tail_ms", "tail_rtf", "first_result_ms"]]
        .agg(["median", "mean", "max"]))

# 발열 구간 확인
print(df.thermal.value_counts())

# 온디바이스 검증: 앱 트래픽이 정말 0인지
print(ok[["app_rx_delta", "app_tx_delta"]].max())
```

### VAD ON/OFF 비교 (add-hyungchul-20260825-1430)

```python
import pandas as pd

base = pd.read_csv("output/google/base/result.csv", encoding="utf-8-sig")
vad  = pd.read_csv("output/google/base/vad_asr_safe/result.csv", encoding="utf-8-sig")

m = base.merge(vad, on="path", suffixes=("_off", "_on"))

# 1) VAD 가 실제로 얼마나 깎았나
print(m[["vad_removed_ratio_on", "vad_segment_count_on"]].describe())

# 2) VAD 도입 비용 (파일당 몇 ms 를 더 쓰는가)
print("vad_process_ms 중앙값:", m.vad_process_ms_on.median())

# 3) 엔진 속도 비교 — rtf 가 아니라 rtf_stt_input 으로!
print(m[["rtf_stt_input_off", "rtf_stt_input_on"]].median())

# 4) end-to-end 비교 (VAD 비용 포함)
print(m[["total_rtf_off", "total_rtf_on"]].median())

# 5) 전사 결과가 바뀐 파일만 추리기 → 여기서 WER/CER 개선/악화가 갈린다
diff = m[m.text_off.fillna("") != m.text_on.fillna("")]
print("텍스트가 바뀐 파일:", len(diff), "/", len(m))
print(diff[["path", "chars_off", "chars_on", "vad_removed_ratio_on"]])
```

### 노이즈 저감 알고리즘 비교 (add-hyungchul-20260826-1100)

```python
import pandas as pd, glob, os

rows = []
for f in glob.glob("output/google/base/**/result.csv", recursive=True):
    d = pd.read_csv(f, encoding="utf-8-sig")
    d["variant"] = os.path.relpath(os.path.dirname(f), "output/google/base") or "baseline"
    rows.append(d)
df = pd.concat(rows, ignore_index=True)
ok = df[df.status == "OK"]

# 1) 알고리즘별 비용과 감쇠량
print(ok.groupby("ns_algorithm").agg(
    files=("path", "count"),
    infer_us_per_frame=("ns_infer_per_frame_us", "median"),
    ns_total_ms=("ns_total_ms", "median"),
    reduction_db=("ns_reduction_db", "median"),
    peak_max=("ns_peak", "max"),
))

# 2) 단계별 시간 분해 — 어디서 시간을 쓰는지
print(ok.groupby("ns_algorithm")[
    ["ns_prepare_ms", "ns_stft_ms", "ns_infer_ms", "ns_istft_ms", "ns_post_ms"]
].median())

# 3) end-to-end 비용 (전처리 포함)
print(ok.groupby("ns_algorithm")[["total_ms", "total_rtf", "rtf_stt_input"]].median())

# 4) 클리핑 사고 확인 (1.0 초과면 WER 악화 원인이 알고리즘이 아니라 클리핑일 수 있다)
print(ok[ok.ns_peak > 0.999][["variant", "path", "ns_peak"]])
```

WER/CER 자체는 `.txt` 를 정답 스크립트와 대조해 따로 계산하고,
그 결과를 `variant` 컬럼 기준으로 위 표에 붙이면 "품질 vs 비용" 표가 완성된다.
