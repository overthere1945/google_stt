# google_stt — 프로젝트 지침 (Claude Code)

Android 온디바이스 STT 평가 앱. 휴대폰 안에서 **엔진·모델 × VAD 조건 × 언어(kr/en)** 조합을 자동으로 모두 돌리고,
서버 실행기(`stt_all_gui_server` v8.8.0)와 같은 폴더 구조·같은 74열 CSV 로 결과를 남긴다.
현재 작업: **v3.0.0 → v4.0.0 재작성**(브랜치 `v4-rewrite`). 대상 기기 Galaxy S24 Ultra(arm64-v8a).

## 먼저 읽을 문서 (docs/AI_analyze/)
- `01_기존프로젝트분석` · `02_요구사항정의` · `03_기술타당성검증` · `04_아키텍처_상세설계` · `05_구현계획_작업분할` · `06_테스트케이스_오류처리설계` (모두 `_20261105.md`)
- `07_구현시작가이드_ClaudeCode_20261105.md`, 최신 `HANDOFF_*.md`
- 작업은 **05 문서의 Task 번호(T0-1 ~ T8-3)** 순서로 한다. 설계와 다르게 해야 하면 먼저 사용자에게 묻는다.

## 답변·진행 규칙
- 한국어, 공적이고 전문적인 문체. 아부하지 않는다. 답 첫머리에 사용자 질문을 요약한다.
- 모르면 모른다고 말한다. 요구가 애매하면 멈추고 묻는다. 방법이 여럿이면 장단점을 보이고 확인받는다.
- 사실(라이브러리 판, API 동작)은 공식 문서로 확인하고 출처를 적는다. 답 끝에 예상 꼬리 질문 4~6개.
- **Task 하나 = 커밋 하나.** 바뀐 줄 400줄 이하(옮기기만 한 줄 제외), 넘으면 나눈다.
- 커밋 전 반드시: `./gradlew assembleDebug` 성공 → `./gradlew testDebugUnitTest` 통과 → Task 의 검증 항목 통과.
- WP 마일스톤(M0~M4)마다 멈추고 보고한다: 한 일, 검증 결과, 남은 문제. 사용자 승인 후 다음 WP.
- 커밋 메시지: `<종류>(<영역>): <요약> [T<번호>]` (종류 = feat/fix/refactor/move/test/build/docs).
- 파일 옮기기(`git mv` + package 선언)와 내용 고치기는 **다른 커밋**.

## 코드 규칙 (세부는 .claude/rules/kotlin-code-rules.md)
- 파일 맨 위: 파일명·목적·기능을 한국어 주석으로. 함수 위: 목적·입력·출력·리턴 값. 줄마다 한국어 설명 주석.
- 추가 줄 `// add-hyungchul-YYYYMMDD-HHMM`, 수정 줄 `// change-hyungchul-YYYYMMDD-HHMM` (실제 날짜·시각).
- 기존 파일을 고칠 때 import·상수·함수·변수 **순서와 위치를 원본대로 유지**(사용자가 Beyond Compare 로 비교).
- 최소 코드. 요청 밖 기능·옵션·추상화 금지. 요청 범위 밖 코드·주석은 건드리지 않는다.
- 코드 작성·수정 후 1번 이상 검증(빌드·시험·공식 문서 대조).

## 빌드·시험 명령
```bash
./gradlew assembleDebug                      # 빌드
./gradlew testDebugUnitTest                  # 단위 시험(L1)
./gradlew connectedDebugAndroidTest          # 계측 시험(L2, S24 USB 연결 필요)
adb devices                                  # 기기 확인
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s GoogleStt                      # 앱 로그
./gradlew :app:dependencies --configuration debugRuntimeClasspath   # 의존성 판 확인
```

## 빌드 구성 (D20, 5단계 2장)
- AGP 9.3.1(built-in Kotlin, `org.jetbrains.kotlin.android` 플러그인 넣지 않음), Gradle 9.6.1, JDK 17
- Kotlin(KGP) 2.4.21 은 최상위 `buildscript { classpath }` 로 고정, `org.jetbrains.kotlin.plugin.compose` 2.4.21
- compileSdk 37 / targetSdk 35 / minSdk 31, arm64-v8a 전용, `packaging.jniLibs.excludes` 유지
- Compose BOM 2026.09.00, activity-compose 1.13.0, lifecycle-*-compose 2.11.0, datastore-preferences 1.3.0, commons-compress 1.28.0
- 유지: sherpa-onnx 정적 AAR **1.13.8**, onnxruntime-android 1.25.1, vosk-android 0.3.75, jna 5.18.1@aar, ML Kit genai-speech-recognition **1.0.0-alpha1**

## 패키지 구조 (04 문서 1-3)
`com.example.google_stt` 아래 `domain/`(순수 Kotlin) · `engine/`(SDK·네이티브) · `data/`(SAF·자산·DataStore·기기) · `run/`(서비스·스케줄러·파이프라인) · `ui/`(Compose)
- 의존 방향 `ui → run/data → engine → domain`. **`domain` 에서 `android.*` import 금지.**
- 객체 생성은 `AppContainer` 수동 주입(Hilt 없음). 화면 전환은 탭 3개 상태 전환(navigation-compose 없음).

## 확정 결정 요약 (전체는 02~06 문서)
- 결과 경로: `<출력>/<언어 폴더>/<엔진>_<모델>_<조건>/<시나리오>/<NN>.txt` (날짜 폴더 없음)
- CSV: `<언어 폴더>/<엔진>_<조건>_result.csv` = 서버 74열(`docs/AI_analyze/golden/STT_CSV_HEADER_v2.0.txt`), utf-8-sig
  보조 `<엔진>_<조건>_device.csv`, `<엔진>_<조건>_run_meta.csv`
- 상태 값 OK / NO_MATCH / ERROR. 실패 파일은 `.txt` 대신 `<NN>.ERROR.txt`(첫 줄 `[ERROR] …`), 성공·무음 저장 때 묵은 `.ERROR.txt` 삭제(D28)
- ML Kit 먹통 시 남은 ML Kit 작업만 SKIPPED, 다른 작업 계속(D29). 중단 작업의 `_` 폴더는 이어 하기 때 지우고 처음부터(D30)
- 시험 음원은 `app/src/androidTest/assets/audio/` 에 두되 커밋하지 않음(D31)
- 조건 이름 `[NS_<ns>_]<VAD>[_SEG10][_EPD_…][_FEED_<feed>]` (FEED 토큰은 스트리밍·Vosk 에서 Feed 를 고른 경우만)
- 노이즈 저감·EPD·Feed 기본 "사용 안 함". ML Kit 은 REALTIME 고정, 오프라인 sherpa 는 Feed 없음
- VAD 값·언어 낱말은 서버와 동일. 언어 전용 모델은 맞지 않는 언어 조합에서 자동 제외
- 동시 작업 기본 1("시간 측정"), "정확도만" 2~3. 오프라인 엔진 동시 1, ML Kit 동시 1
- 포그라운드 서비스 `specialUse` + 부분 wake lock. `MANAGE_EXTERNAL_STORAGE` 쓰지 않음
- 모델은 앱 전용 저장소(`getExternalFilesDir("models")`), 받기(외부망) + PC 에서 가져오기 병행, SHA256 검증
- whisper.cpp 는 v4.1.0 에서(이번 판 아님). sherpa 2.0.1·ML Kit alpha2 는 회귀 시험 후 별도 Minor

## 금지
- `/home/hyungchul/workspace/python/asr_stt/stt_all_gui_server` **수정 금지**(읽기만).
- 사용자 확인 없이 `git push`, 브랜치 삭제, 강제 덮어쓰기(`reset --hard`, `push -f`) 금지.
- 모델·AAR·음원·`.so` 를 저장소에 커밋 금지(`.gitignore` 규칙 유지).
- API 키·토큰 등 비밀값을 코드·문서·로그·메모리에 남기지 않는다.
- `catch (e: Exception)` 로 `CancellationException` 을 삼키지 않는다. 조용히 삼키는 `runCatching {}` 금지.

## 문서 규칙
- 판 번호 Major.Minor.Patch. 릴리스 노트는 MD + docx(`docs/RELEASE_NOTES_v<판>.md/.docx`).
- 작업을 마치거나 넘길 때 `docs/AI_analyze/HANDOFF_<YYYYMMDD>_<판>.md`(한 일·남은 일·결정·열린 문제·다음 명령·기준 커밋)를 쓰고 같이 커밋.
- README.md 는 v4.0.0 완료 때 갱신(10단계).
- 컨텍스트가 차서 압축하기 전에, 이전 대화를 `docs/AI_analyze/<날짜>_<시간>_<N>차압축_google_stt.md` 로 자세히 정리해 둔다.
