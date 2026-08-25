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

## 1. `result.csv` 컬럼 (44개)

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
