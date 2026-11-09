# 릴리스 노트 — google_stt v4.0.0

- 작성일: 2026-11-09
- 이전 판: 3.0.0 (versionCode 11) → **4.0.0** (versionCode 12)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
- 이번에 Major 를 올린 이유
  - 앱을 다시 썼다(v3 코드 30개 파일 삭제, 패키지 구조·화면·저장 형식 변경).
  - 결과 CSV 가 v3 의 79열에서 **서버 통일 CSV v2.0 의 74열**로 바뀌었다.
  - 결과 경로에서 날짜 폴더가 없어졌다.
  - 모델 위치가 공용 폴더(`Documents/stt_models`)에서 앱 전용 저장소로 바뀌었고, '모든 파일 접근' 권한을 쓰지 않는다.
- 상태: 기능·회귀 시험과 코드 리뷰를 마쳤다. **24시간 연속 실행 시험(LT-01)은 아직 하지 못했다**(6장).

## 1. 호환되지 않는 변경 (Breaking)

| 항목 | 3.0.0 | 4.0.0 |
|---|---|---|
| 화면 | View 화면 1개 + 목록 실행 | Compose, 탭 3개(설정·실행·모델) |
| 실행 방식 | 화면에서 설정 하나를 START, 여러 조건은 목록 CSV | 모델 × VAD × 언어를 고르면 **조합을 자동으로 만들어** 모두 돈다 |
| 결과 경로 | `<출력>/<언어>/<날짜_공급>/<엔진>_<모델>_<조건>/…` | `<출력>/<언어 폴더>/<엔진>_<모델>_<조건>/<시나리오>/<NN>.txt` (날짜 폴더 없음) |
| result.csv | 79열(앱 고유) | **서버 74열**(utf-8-sig, CRLF) + `device.csv`(31열) |
| 상태 값 | 엔진별로 여러 값 | `OK` / `NO_MATCH` / `ERROR` 셋. 나머지는 `note` |
| 실패 파일 | 빈 TXT 또는 오류 문구가 든 TXT | `<NN>.ERROR.txt`(첫 줄 `[ERROR] …`). TXT 는 만들지 않는다 |
| 모델 위치 | `/sdcard/Documents/stt_models`(adb 로 넣음) | 앱 전용 저장소. 앱에서 받거나 PC 에서 가져온다 |
| 권한 | `MANAGE_EXTERNAL_STORAGE`, INTERNET 없음 | `MANAGE_EXTERNAL_STORAGE` 없음. INTERNET(모델 받기), 알림, 포그라운드 서비스(specialUse), wake lock |
| 공급 속도 기본 | REALTIME | **사용 안 함(최대 속도)**. `_FEED_<속도>` 는 고른 경우에만 조건 이름에 붙는다 |
| 노이즈 저감·EPD 기본 | 화면 선택 | "사용 안 함" |

- v3 의 결과 폴더와 설정은 읽지 않는다. v3 결과는 그대로 남는다.

## 2. 추가 (Added)

1. **조합 자동 실행** — 언어 폴더 × 모델 × VAD × 옵션 조합을 만들고, 언어 전용 모델은 맞지 않는 언어에서 자동으로 뺀다. 요약 카드에 작업 수·제외 내역·받을 모델·예상 시간이 나온다.
2. **앱 안에서 모델 받기**(모델 탭) — 받기 → 압축 풀기 → SHA256 검증. 끊기면 이어받는다. "Wi-Fi 에서만" 설정. 외부망이 안 되면 PC 에서 `tools/fetch_assets.py` 로 받아 [PC 에서 가져오기].
3. **포그라운드 서비스 실행** — 화면을 꺼도 계속 돈다. 알림에서 중지할 수 있다.
4. **이어 하기** — 앱이 꺼져도 같은 출력 폴더로 다시 실행하면 끝난 작업은 건너뛴다. 같은 작업 도중 2회 연속 비정상 종료하면 그 작업을 포기한다(`ERR_CRASH_LOOP`). 사용자가 끈 것은 세지 않는다.
5. **발열 대기** — 발열 단계가 SEVERE 이상이면 식을 때까지(최대 20분) 기다렸다가 진행한다.
6. **중지 2종** — "지금"(돌던 작업은 중단으로 남김) / "이 작업 끝나고".
7. **시작 전 점검** — 알림 권한, 배터리 최적화 예외, 충전 연결, VAD 적재, 저장공간.
8. **기기 계측 `device.csv`** — 배터리·발열·메모리·단계별 시간·네트워크 변화. `run_meta.csv` 에 자산 해시(`asset_sha256:<ID>`).
9. **언어를 모르는 폴더** — 처음 한 번 묻고 기억한다.
10. **Vosk 무너짐 표시** — Vosk 가 오류 없이 20초 이상 입력에서 3자/초 미만이면 `note=DECODE_COLLAPSE_SUSPECT`.
11. 출력 폴더 바로 아래 기록: `_plan.csv`, `_plan_progress.csv`, `_plan_exits.csv`, `_plan_memlog.csv`, `_summary.csv`, `_app_log.txt`.

## 3. 바뀐 것 (Changed)

- 패키지 구조: `domain`(순수 Kotlin) · `engine` · `data` · `run` · `ui`. 의존 방향 `ui → run/data → engine → domain`.
- 빌드: AGP 9.3.1(built-in Kotlin), Gradle 9.6.1, Kotlin 2.4.21, compileSdk 37, Compose BOM 2026.09.00, datastore-preferences 1.3.0, commons-compress 1.28.0, coroutines 1.11.0.
- 유지: sherpa-onnx 정적 AAR 1.13.8, onnxruntime-android 1.25.1, vosk-android 0.3.75, jna 5.18.1, ML Kit genai-speech-recognition 1.0.0-alpha1.
- 스트리밍·Vosk 의 기다림이 `Thread.sleep` 에서 코루틴 `delay` 로 바뀌어 중지에 바로 반응한다.
- 대상 기기 표기: Galaxy S24 Ultra(SM-S928N).

## 4. 지운 것 (Removed)

- v3 코드: 루트 `MainActivity`·`SileroVadProcessor`·`SttTelemetry`, `batch/`, `plan/`, `io/`, `stt/`, `vad/`, `ui/`(View 용 6개), `res/layout/activity_main.xml`.
- 의존성: appcompat, activity-ktx, lifecycle-runtime-ktx, material, constraintlayout(직접 선언). 디버그 APK 129.3 → 123.6 MB.
- 기능: 목록 CSV 불러오기 실행, VAD·노이즈 저감·EPD 세부 값 직접 입력(프리셋만), 날짜 폴더.

## 5. 검증

| 항목 | 결과 |
|---|---|
| 단위 시험 | 132개 통과 |
| 계측 시험(S24 Ultra) | 37개 통과 |
| 33조합 완주(kr·en × 모델 7 × VAD 3) | 3회 모두 33 / 33, 실패 0 |
| v3.0.0 기준 결과와 TXT 대조 | 오프라인 엔진 21 / 21 바이트 동일, Nemotron(REALTIME) 6 / 6 동일, 같은 입력 2회 동일 |
| Vosk | 영어 5 / 6 동일, 한국어는 실행마다 다름(원인: Kaldi dither — `--dither=0` 사본은 12회 동일) |
| 서버 평가기 `run_eval.py` | 수정 없이 읽고 채점(kr 18 · en 15). 서버 엔진 이름 목록에 앱 엔진 이름 19개 추가 필요 — 반영함 |
| 강제 종료 5회 | 매번 이어 하기로 복구, CSV 손상 없음 |
| 화면 끄고 실행 | 26분 완주 |
| 코드 리뷰 | 독립 검토(묶음별 5회 + 통합 2건) 지적 반영 |

- 성능 참고(S24 Ultra, 33조합, 공급 "사용 안 함"): 10분 13초. v3.0.0 은 같은 조합을 REALTIME 으로 32분.

## 6. 알려진 문제

1. **24시간 연속 실행 시험(LT-01)을 하지 못했다.** 시험 기기 배터리가 2 % 였고 PC USB 충전으로는 실행 중 방전되어 멈췄다. 벽 충전기에 연결해 다시 해야 한다.
2. **Vosk 한국어 결과가 약 30회에 1회 무너진다**(오류 없이 43~109자). 표시(`DECODE_COLLAPSE_SUSPECT`)는 붙지만 결과는 그대로다. 500자 수준의 부분 저하는 표시되지 않는다.
3. ML Kit: 시간 초과 뒤 재시도한 사실이 CSV 에 없다(`attempts` 항상 1). 세션 수를 작업마다 0 으로 돌린다. 영어 모델이 기기에 없으면 영어 작업이 실패한다.
4. 동시 2~3 에서 앱이 죽으면 원인 작업을 가리지 못한다.
5. "지금 중지" 가 한 파일의 디코딩·노이즈 저감·VAD 중, 큰 오프라인 모델의 한 조각 처리 중에는 늦게 듣는다.
6. 화면을 돌리면 대화창이 닫힌다. 빠른 측정(RTF 미리 재기), 조합 CSV 불러오기, VAD 사용자 지정 값이 화면에 없다.
7. `_plan.csv` 에 노이즈 저감·EPD 열이 없다.
8. 서버와의 조각 수 일치(RT-05)는 같은 상한을 쓰는 서버 엔진이 없어 비교하지 못했다(VAD 뒤 길이는 일치).

## 7. 올리는 방법

1. `bash tools/download_stt_models.sh aar`, `bash tools/download_models.sh` (처음 한 번).
2. `./gradlew assembleDebug` → `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. 모델 탭에서 모델을 받는다(v3 의 `Documents/stt_models` 는 쓰지 않는다 — 지워도 된다).
4. v3 가 받은 '모든 파일 접근' 권한은 더 필요 없다.

## 8. 다음 판 후보

- 4.0.x: 6장의 3~7번, `--dither=0` 적용 여부 결정.
- 4.1.0: whisper.cpp 엔진. sherpa-onnx 2.x·ML Kit alpha2 는 회귀 시험 뒤 별도 Minor.
