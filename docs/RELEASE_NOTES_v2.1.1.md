# 릴리스 노트 — google_stt v2.1.1

- 배포일: 2026-10-10
- 이전 판: 2.1.0 (versionCode 3) → **2.1.1** (versionCode 4)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
- 이번에 Patch 를 올린 이유: 2.1.0 의 sherpa-onnx 엔진이 실기기에서 모델을 읽지 못하던 **결함 수정**이 중심이다. 결과 폴더·CSV 컬럼·설정 화면은 바뀌지 않았다. 2.1.0 은 원격 브랜치(`feature/v2-vad-stt`)에 이미 올라가 있어 판을 올렸다.

## 1. 고친 점 (Fixed)

1. **sherpa 모델을 앱이 읽지 못하던 문제**
   - 증상: 모델을 넣었는데도 `[preflight] … = MISSING(model.int8.onnx,tokens.txt)` 로 배치가 시작되지 않았다.
   - 원인(실기기 Logcat): `modelsRoot=…/Android/data/com.example.google_stt/files/models exists=true canRead=false children=null`
     — 앱 전용 폴더(`Android/data/<앱>`) 안에 adb 가 만든 폴더를 앱이 읽지 못했다(Android 11+ 의 앱 전용 폴더 소유자 규칙으로 보이나 공식 문서에는 명시가 없다).
   - 수정: 모델 위치를 공용 폴더 **`/sdcard/Documents/stt_models/<패키지>/`** 로 바꾸고, 앱은 **'모든 파일 접근'(MANAGE_EXTERNAL_STORAGE)** 으로 읽는다(사용자 결정 Q24).
2. `tools/download_stt_models.sh` 가 앱이 설치돼 있는데도 "앱이 없습니다" 로 휴대폰 넣기를 건너뛰던 문제 — 공용 폴더라 앱 설치 확인(`pm path`)을 없앴다.

## 2. 새 기능 (Added)

1. sherpa 엔진으로 START 할 때 '모든 파일 접근' 이 꺼져 있으면 배치를 시작하지 않고 **이 앱의 '모든 파일 접근 허용' 설정 화면**을 연다(`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, 없으면 전체 목록 화면). 켠 뒤 다시 START 하면 된다.
2. **Logcat 디버그 출력** — 태그 `GoogleStt`, 접두어 `[D][batch]` / `[D][file]` / `[D][stt]` / `[D][sherpa]`(Log.d). 화면 로그창에는 넣지 않는다. 파일·단계·조각 단위로만 남기고 프레임 단위 출력은 없다(사용자 결정 Q22·Q23).
   - 필터 예: `tag:GoogleStt message:[D]`, sherpa 만 `tag:GoogleStt message:[D][sherpa]`
   - `[D][sherpa] dir report:` 줄에 '모든 파일 접근' 상태, 모델 폴더·파일의 존재·읽기 권한·크기가 나온다.

## 3. 바뀐 점 (Changed)

| 항목 | 2.1.0 | 2.1.1 |
|---|---|---|
| 휴대폰 모델 위치 | `/sdcard/Android/data/com.example.google_stt/files/models/<패키지>/` | `/sdcard/Documents/stt_models/<패키지>/` |
| 앱 권한 | (없음) | `MANAGE_EXTERNAL_STORAGE`('모든 파일 접근', 설정 화면에서 사용자가 켬) |
| 앱을 지우면 | 모델도 함께 지워짐 | **모델은 남음**(다시 넣을 필요 없음) |
| 넣는 방법 | adb(앱 설치 후에만) | adb(앱 설치와 무관) 또는 MTP·파일 관리자로 `Documents/stt_models` 에 복사 |

- run_meta 의 `stt_model_dir` 값이 새 경로로 바뀐다(키 이름은 그대로).
- 2.1.0 의 build 설정(`abiFilters` arm64-v8a, `packaging.jniLibs.excludes`)은 그대로다.

## 4. 옮기는 방법(2.1.0 에서 올 때)

```bash
adb shell rm -rf /sdcard/Android/data/com.example.google_stt/files/models   # 앱이 못 읽던 예전 폴더 정리
bash tools/download_stt_models.sh                                            # 공용 폴더로 다시 넣기(이미 받은 모델은 다시 받지 않음)
```
앱에서 처음 START 하면 '모든 파일 접근' 설정 화면이 열린다 → 이 앱을 켜고 돌아와 다시 START.

## 5. 검증 상태

| 단계 | 상태 |
|---|---|
| 실제 AAR 로 컴파일 | 앱 소스 전체 컴파일 오류 0건(kotlin 2.0.21, PC 의 android-35·R.jar·의존성·sherpa AAR classes.jar), Kotlin 주석 중첩 검사 0건 |
| 권한·설정 상수 | android-35 android.jar 에 `MANAGE_EXTERNAL_STORAGE`, `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, `Environment.isExternalStorageManager()` 존재 확인 |
| 다운로드 스크립트 | 가짜 adb 로 모의 실행 — `get-state` → `mkdir -p /sdcard/Documents/stt_models` → `push` → `ls` 순서 확인, `pm path` 호출 없음 |
| Gradle 빌드·실기기 | **미실행** — PC 에서 빌드 후 확인 필요 |

## 6. 알려진 문제·확인 필요

- '모든 파일 접근' 은 Google Play 정책 심사 대상 권한이다(내부 평가용이라 해당 없음, 스토어 배포 시 다시 검토).
- 두 onnxruntime(앱 1.25.1 + sherpa 정적) 공존, 모델별 속도·메모리, Qwen3-ASR 언어 값(`Korean`/`English`)은 실기기 결과로 확인해야 한다.
- APK 는 arm64-v8a 전용이다(2.1.0 과 같음).
