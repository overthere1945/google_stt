# google_stt — 계측 CSV 설명서 (v4.0.0)

- 대상 판: 4.0.0 (2026-11-09). v3.0.0 까지의 79열 CSV 설명은 태그 `v3.0.0` 의 이 파일에 있습니다.
- v4.0.0 은 파일별 결과를 **서버 통일 CSV v2.0 의 74열**(`stt_common.STT_CSV_HEADER`)로 쓰고, 휴대폰에서만 재는 값은 **`device.csv`** 로 따로 둡니다.
- 인코딩: UTF-8 with BOM(utf-8-sig), 줄 끝 CRLF. 쉼표·큰따옴표·줄바꿈이 든 칸만 큰따옴표로 감쌉니다.
- 열 정의의 원본은 코드입니다: `domain/csv/UnifiedCsv.kt`(74열), `domain/csv/DeviceCsv.kt`, `domain/csv/RunMeta.kt`. 74열 머리줄의 골든은 `docs/AI_analyze/golden/STT_CSV_HEADER_v2.0.txt`.

## 0. 파일 위치

```
<출력>/<언어 폴더>/<엔진>_<조건>_result.csv      파일 1개 = 1행 (74열)
<출력>/<언어 폴더>/<엔진>_<조건>_device.csv      파일 1개 = 1행 (31열, result.csv 와 run_id·file·rel_dir 로 맞물림)
<출력>/<언어 폴더>/<엔진>_<조건>_run_meta.csv    작업 1개의 환경·설정 (key,value)
<출력>/_plan.csv  _plan_progress.csv  _plan_exits.csv  _plan_memlog.csv  _summary.csv  _app_log.txt
```

- `<엔진>` 은 `whisper-base` 같은 결과 엔진 이름, `<조건>` 은 `[NS_<ns>_]<VAD>[_SEG10][_EPD_…][_FEED_<feed>]` 입니다.
- 작업을 다시 돌리면(이어 하기에서 중단된 작업, "처음부터") 그 작업의 CSV 3종은 지우고 새로 씁니다.

## 1. 먼저 알아 둘 것

1. **시간 비교는 `transcribe_sec` / `rtf` 로 합니다.** 다만 스트리밍·Vosk 에서 공급 속도를 고른 실행(`<조건>` 에 `_FEED_…`)은 음원을 그 속도로 흘려 넣으므로 `transcribe_sec ≈ 공급 시간` 입니다.
   그때 엔진의 처리 지연은 `proc_sec`(공급 끝 → 최종 결과)와 `device.csv` 의 `tail_ms`·`first_result_ms` 로 봅니다. 공급 속도 "사용 안 함"(기본)은 최대 속도입니다.
2. **`model_load_sec` 는 작업의 첫 파일에만 값이 있고 나머지는 0** 입니다. 모델 적재 시간은 `elapsed_sec` 에 들어가지 않습니다.
3. **`status=OK` 는 "오류 없이 끝났다" 는 뜻일 뿐입니다.** 품질 이상은 `note` 로 걸러야 합니다(4장). 특히 `DECODE_COLLAPSE_SUSPECT`, `TRUNCATED_SUSPECT`, `ERR_PARTIAL:` 이 붙은 행의 WER/CER 은 그대로 쓰지 마십시오.
4. VAD 를 쓰면 STT 에 들어가는 길이는 `vad_kept_sec` 입니다. `rtf` 의 분모는 원본 길이(`audio_sec`)입니다.
5. `rss_peak_mb` 는 Android 에서 PSS 로 잰 값입니다(서버는 RSS).

## 2. result.csv — 74열

값이 없는 칸은 빈칸입니다. "고정" 은 앱에서 항상 같은 값입니다.

### A. 실행 식별 (7)
| 열 | 뜻 |
|---|---|
| `run_id` | 실행 ID(`yyyyMMdd_HHmmss`) — 같은 [실행] 에서 나온 행은 같다 |
| `started_at` | 이 파일을 시작한 시각 |
| `algo` | 결과 엔진 이름(`whisper-base`, `vosk-small-ko`, `google-mlkit` …) |
| `model` | `sherpa-int8` / `vosk` / `basic` |
| `option` | 조건 이름(`SILERO`, `NOVAD_FEED_REALTIME` …) |
| `exec_mode` | 고정 `embedded`(온디바이스) |
| `engine_version` | 런타임 판(`sherpa-onnx 1.13.8 (static-link onnxruntime)` 등) |

### B. 입력 파일 (7)
`rel_dir`(입력 하위 폴더 = 시나리오), `file`(확장자 없는 이름), `audio_ext`, `audio_bytes`, `audio_sec`(소수 3자리), `sample_rate`, `channels`(원본 값, 모르면 빈칸)

### C. 언어·인식 옵션 (8)
`req_lang`(`ko-KR` / `en-US`), `detected_lang`(엔진이 알려 주면), 고정: `task=transcribe`, `diarization=unsupported`, `num_speakers=0`, `adaptation=off`, `phrase_count=0`, `phrase_sets=0`

### D. 실행 환경·자원 (13)
| 열 | 뜻 |
|---|---|
| `device` | 고정 `cpu` |
| `device_name` | 기기 이름 |
| `compute_type` | `int8`(sherpa-int8 만), 그 밖은 빈칸 |
| `workers` | 동시 작업 수 |
| `cpu_percent_avg`, `cpu_time_sec` | 이 파일을 처리하는 동안 앱 프로세스의 CPU |
| `rss_peak_mb` | 처리 중 최대 PSS(MB) |
| `gpu_util_avg`, `gpu_mem_peak_mb`, `gpu_energy_wh` | 빈칸(GPU 미사용) |
| `host_id` | 기기 식별 |
| `model_load_sec` | 모델 적재 시간 — 작업의 첫 파일만, 나머지 0 |
| `warmup_row` | 워밍업 행이면 1 |

### E. 시간 (14) — 초, 소수 3자리
| 열 | 뜻 |
|---|---|
| `preprocess_sec` | 디코딩 + 노이즈 저감 + VAD |
| `upload_sec`, `wait_sec` | 0(로컬 실행이라 없음) |
| `transcribe_sec` | STT 시작 → 최종 결과 |
| `cloud_total_sec`, `api_wall_sec` | `transcribe_sec` 와 같은 값(서버 열과 맞추기 위함) |
| `elapsed_sec` | 이 파일 전체(전처리 + 전사) |
| `rtf`, `rtf_api` | `transcribe_sec / audio_sec` (소수 4자리) |
| `attempts` | 시도 횟수(현재 항상 1 — ML Kit 재시도는 반영되지 않는다) |
| `feed_sec` | 음원을 넣는 데 걸린 시간(스트리밍·Vosk·ML Kit). 오프라인은 빈칸 |
| `feed_measured` | `feed_sec` 를 실제로 쟀으면 1 |
| `proc_sec` | 공급 끝 → 최종 결과(엔진의 처리 지연) |
| `rtf_proc` | `proc_sec / audio_sec` |

### F. 비용 (10)
`billed_unit`, `billed_qty`, `unit_price`, `currency`, `cost`, `price_ref`, `pricing_tier` 는 빈칸(무료). `vad_kept_sec` 는 VAD 뒤 STT 에 넣은 길이(VAD 없으면 빈칸), `billed_sec` 는 STT 에 넣은 길이, `billed_channels` 는 고정 1.

### G. 엔진 고유 (8)
`chunks` 만 값이 있습니다(STT 에 넣은 조각 수). `api`, `model_sent`, `enhanced`, `location`, `recognition_mode`, `model_version`, `locales` 는 빈칸.

### H. 결과 (7)
| 열 | 뜻 |
|---|---|
| `status` | `OK` / `NO_MATCH`(글자 없음) / `ERROR` |
| `utterances` | 엔진이 낸 발화(세션) 수 |
| `chars`, `words` | 전사문의 글자 수·낱말 수(서버 `text_stats` 규칙) |
| `text_sha1` | 전사문 해시 — 같은 결과인지 빠르게 견줄 때 |
| `note` | 주의·오류 표시, 여러 개면 `;` 로 잇는다(4장) |
| `text` | 전사문을 공백 한 칸으로 정리한 한 줄. 원문은 TXT 파일 |

## 3. device.csv — 31열

`run_id`, `file`, `rel_dir` 로 result.csv 와 맞춥니다.

| 열 | 뜻 |
|---|---|
| `batt_pct_start`, `batt_pct_end` | 배터리 % |
| `batt_uah_delta` | 충전량 변화(µAh, 음수면 소모) |
| `batt_temp_c_start`, `batt_temp_c_end` | 배터리 온도(℃) |
| `charging` | `AC` / `USB` / `WIRELESS` / `NONE` |
| `thermal_start`, `thermal_end` | 발열 단계(`NONE` … `SEVERE` …) |
| `thermal_headroom` | 발열 여유 예측값 |
| `pss_peak_mb`, `cpu_time_sec` | result.csv 의 `rss_peak_mb`, `cpu_time_sec` 와 같은 값 |
| `net_rx_delta`, `net_tx_delta` | 처리 중 앱 UID 의 수신·송신 바이트 — 0 이 아니면 `WARN_NET_DELTA` |
| `decode_sec`, `ns_sec`, `vad_sec` | 전처리 단계별 시간(합 = `preprocess_sec`) |
| `vad_segments`, `vad_kept_sec` | VAD 구간 수, VAD 뒤 길이 |
| `chunks`, `chunk_max_sec` | STT 에 넣은 조각 수, 가장 긴 조각 |
| `first_result_ms` | STT 시작 → 첫 글자 |
| `tail_ms` | 공급 끝 → 최종 결과 |
| `feed_ms` | 공급 시간(ms) |
| `feed_stall` | 공급 시간 / 이론값 — 2 를 넘으면 `FEED_STALL` |
| `model_load_sec`, `workers` | result.csv 와 같은 값 |
| `purpose` | `timing`(시간 측정) / `accuracy`(정확도만) |
| `download_overlap` | 모델 받기와 겹쳐 돌았으면 1 — 시간 분석에서 빼십시오 |

## 4. `note` 코드

| 코드 | 뜻 | 그 행을 어떻게 볼까 |
|---|---|---|
| `VAD_NO_SPEECH` | VAD 가 말을 찾지 못해 STT 를 부르지 않음(`NO_MATCH`) | 무음으로 채점 |
| `DECODE_COLLAPSE_SUSPECT` | **Vosk** 가 오류 없이 끝났는데 20초 이상 입력에서 3자/초 미만 | **정확도에 쓰지 말 것.** 다시 돌리면 달라진다. VAD 없음 + 말이 드문 긴 음원에도 붙을 수 있다 |
| `TRUNCATED_SUSPECT` | **ML Kit** 61초 넘는 한 세션에서 3자/초 미만(앞부분 유실 의심) | 정확도에 쓰지 말 것 |
| `ERR_PARTIAL:<문구>` | ML Kit 이 글 일부를 낸 뒤 오류를 냄 | 뒤쪽이 빠졌을 수 있다 |
| `FEED_STALL` | 공급이 이론값의 2배 넘게 걸림 | 시간 값을 믿지 말 것 |
| `WARN_NET_DELTA` | 처리 중 앱의 네트워크 송수신이 있었음 | 결과는 유효. 온디바이스 증명용 표시 |
| `ERR_<단계>:<예외>` | 그 파일의 처리 실패(`ERR_DECODE`, `ERR_STT` 등) — `status=ERROR`, `<NN>.ERROR.txt` | 실패로 집계 |
| `ERR_TIMEOUT`, `ERR_OOM` | 파일 시간 초과 / 메모리 부족 | 실패로 집계 |
| 엔진 문구 | ML Kit 이 "말이 없다" 고 답한 경우 등 엔진이 준 설명 | — |

작업 단위 사유는 `_plan_progress.csv` 의 `note` 에 남습니다: `ERR_MODEL_LOAD` / `ERR_VAD_LOAD` / `ERR_NS_LOAD`(적재 실패), `ERR_ASSET:…`(모델을 받지 못함),
`ERR_MLKIT_STUCK`(ML Kit 먹통 — 남은 ML Kit 작업은 `SKIPPED`), `ERR_ENGINE_DEAD`, `ERR_CRASH_LOOP`(같은 작업 도중 2회 연속 비정상 종료), `ERR_INTERNAL:…`, `WARN_RENAME`, `사용자 중지`.

## 5. run_meta.csv

`key,value` 두 열입니다. 주요 항목:

- 실행·기기: `run_started_at`, `app_version`, `manufacturer`, `model`, `soc`, `android_release`, `sdk_int`, `build_fingerprint`, `abi`, `cpu_cores`, `device_key`
- 시작 때 상태: `net_type_at_start`, `airplane_at_start`, `thermal_at_start`, `battery_pct_at_start`, `battery_temp_c_at_start`
- 작업: `algo`, `engine`, `lang`, `condition`, `result_dir`, `file_count`, `parallel_jobs`, `run_purpose`, `feed_mode`, `feed_delay_ms`, `seg_mode`, `pipeline_order`
- STT: `stt_runtime`, `stt_model_dir`, `stt_num_threads`, `stt_model_load_ms`, `stt_chunk_max_sec`, `stt_license`, `stt_commercial`, `execution_declared`
- VAD: `vad_enabled`, `vad_engine`, `vad_preset`, `vad_params`, `vad_threshold`, `vad_min_speech_ms`, `vad_min_silence_ms`, `vad_speech_pad_ms`, `vad_join_silence_ms`, `vad_license`, `vad_commercial`
- 노이즈 저감·EPD: `ns_enabled`, `ns_algorithm`, `ns_config`, `epd_mode`, `epd_config`
- 재현성: **`asset_sha256:<자산 ID>`** — 이 작업이 쓴 모델·VAD 자산의 해시. `download_source` 는 자산을 받은 경로입니다.

## 6. 출력 폴더 바로 아래 파일

| 파일 | 내용 |
|---|---|
| `_plan.csv` | 이번 실행의 조합(`lang,model,feed,vad,seg`). 노이즈 저감·EPD 는 들어가지 않는다 |
| `_plan_progress.csv` | `key,status,worker,started,finished,ok,fail,note` — 상태는 `DONE` / `FAILED` / `SKIPPED` / `INTERRUPTED`. 돌고 있는 작업은 `INTERRUPTED` + `RUNNING:<n>` |
| `_plan_exits.csv` | 앱 프로세스가 꺼진 시각·이유(`USER_REQUESTED`, `LOW_MEMORY`, `CRASH_NATIVE` …)·그때 메모리 |
| `_plan_memlog.csv` | 30초마다 `time,avail_mb,total_mb,threshold_mb,low_memory,app_pss_mb,thermal,finished,total,running` |
| `_summary.csv` | `key,status,files,ok,fail,elapsed_sec,audio_sec,rtf` — 작업별 요약. 이어 하기 때 앞선 줄을 이어 쓴다 |
| `_app_log.txt` | 앱 로그 |
| `plan_<시각>.csv` | [조합 CSV 저장] 으로 남긴 조합 목록 |

## 7. 분석 예시 (pandas)

```python
import glob, pandas as pd

res = pd.concat(pd.read_csv(p, encoding="utf-8-sig") for p in glob.glob("result/kr/*_result.csv"))
dev = pd.concat(pd.read_csv(p, encoding="utf-8-sig") for p in glob.glob("result/kr/*_device.csv"))
df = res.merge(dev, on=["run_id", "file", "rel_dir"], suffixes=("", "_dev"))

ok = df[(df.status == "OK") & ~df.note.fillna("").str.contains("SUSPECT|ERR_PARTIAL")]   # 품질 의심 행 제외
print(ok.groupby(["algo", "option"]).agg(rtf=("rtf", "median"), pss=("rss_peak_mb", "max"), n=("file", "count")))

timing = ok[(ok.download_overlap == 0) & ~ok.note.fillna("").str.contains("FEED_STALL")]  # 시간 분석용
print(timing.groupby("algo")[["preprocess_sec", "transcribe_sec", "tail_ms"]].median())

print(df[df.net_rx_delta.fillna(0) + df.net_tx_delta.fillna(0) > 0][["algo", "file", "net_rx_delta", "net_tx_delta"]])  # 온디바이스 확인
```
