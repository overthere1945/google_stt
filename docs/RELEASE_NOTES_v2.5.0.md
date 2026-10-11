# 릴리스 노트 — google_stt v2.5.0

- 배포일: 2026-10-11
- 이전 판: 2.4.1 (versionCode 8) → **2.5.0** (versionCode 9)
- 판 번호 규칙: Major.Minor.Patch (Semantic Versioning)
- 이번에 Minor 를 올린 이유: 기능 추가(목록 실행)이며 기존 START·결과 폴더·result.csv 열은 그대로다(하위 호환). run_meta 에 키 1개(`parallel_jobs`)만 더했다.

## 1. 추가 (Added)

### 1-1. 목록 실행 — 여러 조건을 줄 세워 한 번에
- 화면 START 아래에 **[목록에 추가] [목록 비우기] [목록 저장] [목록 불러오기] [목록 실행] [중지]** 와 목록 요약·상태 글을 넣었다.
- 목록 = CSV 파일(`/sdcard/Documents/stt_plan/*.csv`, UTF-8 BOM). 머리줄 `lang,model,feed,vad,seg`, 칸 안 `;` 로 여러 값 → 조합으로 펼침, `#` 주석.
  화면에서 만든 목록과 PC 에서 고친 파일이 같은 형식이다(사용자 결정 Q34 C안).
- 작업 1개 = START 한 번과 같은 배치. 노이즈 저감 없음·EPD 없음 고정, Google 엔진·VAD CUSTOM 은 목록에 넣을 수 없다(이번 목록 실행에서 Google 은 개별 START 로 — 사용자 결정).
- 검증: 머리줄·칸 수·언어(kr/en)·모델 이름·언어 전용 모델(zipformer-ko·zipformer-ko-stream·moonshine-tiny-ko·vosk-small-ko 는 kr, vosk-small-en 은 en)·공급 속도·VAD 프리셋 이름·구간 처리. 오류는 줄 번호와 함께 보여 주고 읽지 않는다.
- 견본: `docs/stt_plan_samples/plan_kr_en_app_REALTIME_VAD21.csv` — kr 14모델 / en 11모델 × REALTIME × VAD 21개(SILERO 8·WEBRTC 3·PYANNOTE 3·FSMN 3·FIRERED 3·NOVAD, TEN 제외) × 엔진별 최대 길이 = **525 작업**(사용자 결정 Q40).

### 1-2. 동시 실행 — 3개, 오프라인 엔진은 1개만 (사용자 결정 Q42)
- work 3개가 대기열 앞에서부터 작업을 가져간다. 오프라인 엔진(Whisper·Qwen3-ASR·Omnilingual·Zipformer KO·SenseVoice·Moonshine) 작업은 동시에 1개까지 — CPU 4스레드·메모리를 많이 쓰기 때문.
- 남은 칸은 스트리밍 엔진(Nemotron·Zipformer KO 스트리밍·Vosk) 작업을 당겨 온다. REALTIME 스트리밍은 CPU 를 거의 쓰지 않고 음원 길이만큼 기다리므로 겹쳐 돌리면 전체 시간이 크게 준다.
- 작업마다 전사기·VAD·배치 실행기를 따로 만든다(공유 상태 없음 — 코드 확인).

### 1-3. 폴더와 이어 하기
- 입력 = `<고른 입력 폴더>/<kr|en>`, 출력 = `<고른 출력 폴더>/<kr|en>/<날짜>_<FEED>/<엔진>_<모델>_<조건>/…` (공급 속도가 폴더 이름에 없어 서로 덮어쓰는 것을 막고, PC 평가기의 `<lang>/<date>` 단계에 맞춤).
- 출력 폴더 바로 아래 `_plan_progress.csv` 에 작업이 끝날 때마다 `DONE / FAILED / INTERRUPTED` 를 남긴다. 같은 출력 폴더로 다시 실행하면 DONE 은 건너뛰고 같은 날짜 폴더에 이어 쓴다. 끊긴 작업은 첫 파일부터 다시 돈다. 작업 식별은 결과 폴더 경로다(CSV 줄을 고쳐도 맞는다).

### 1-4. 안전장치
- 작업 사이 쉬기(사용자 결정 Q35): 다음 작업을 잡기 전 발열이 SEVERE 이상이면 MODERATE 이하가 될 때까지 기다린다(최대 20분, 넘으면 기록 후 진행). 잡을 작업이 없으면 기다리지 않는다.
- 실행 중 화면 켜 두기(FLAG_KEEP_SCREEN_ON)·회전 잠금(Activity 재생성으로 끊기는 것을 막음), 끝나면 되돌린다.
- [중지] 선택 창(사용자 결정 Q39): "지금 바로"(지금 파일이 끝나는 대로 멈추고 그 작업은 INTERRUPTED) / "이 작업 끝나고"(새 작업을 잡지 않음). 어느 쪽이든 목록 전체가 멈춘다.
- 작업 하나가 메모리 부족 등 오류로 끝나도 그 작업만 FAILED 로 남기고 목록은 계속 간다.

## 2. 바뀐 점 (Changed)
- `BatchRequest` 에 `inSub`·`outSub`·`parallelJobs` 추가(기본값이면 START 와 같다). `BatchRunner.lastCompleted/lastOk/lastFail` 추가(목록이 작업 결과를 판정). catch·finally 의 마지막 CSV 저장도 하위 폴더에 쓴다.
- run_meta.csv 끝에 `parallel_jobs`(START 1, 목록 3) — 3 이면 시간·자원 열은 다른 작업과 섞인 값이다(전사 글자 비교용).
- 화면 로그가 20만 글자를 넘으면 앞 절반을 줄 단위로 잘라 낸다(며칠 실행 대비, Logcat 에는 전부 남는다).

## 3. 알려진 제한
- 결과 파일을 쓸 때마다 SAF 가 날짜 폴더 목록을 다시 읽어, 날짜 폴더에 결과가 쌓일수록 저장이 조금씩 느려진다(전사 결과에는 영향 없음).
- "지금 바로 중지" 는 지금 파일이 끝나야 멈춘다(REALTIME 스트리밍은 그 파일 길이만큼).
- Google 엔진을 목록에 넣는 기능(세션 한도 자동 묶음·재시도·PC adb 초기화)은 이번 판에 넣지 않았다 — 이번 측정에서 Google 은 개별 START.

## 4. 검증 상태

| 단계 | 상태 |
|---|---|
| 컴파일 | 앱 소스 43개 — 1.13.8 AAR·PC jar·새 R(id 8·string 7) 로 오류 0건, Kotlin 주석 중첩 검사 0건 |
| 목록 CSV(JVM 시험) | 펼침·중복 제거·줄 번호 오류·대소문자 정리·검증 5종·저장→읽기 왕복·진행 기록 왕복 모두 통과 |
| 견본 CSV(실제 앱 카탈로그로 검증) | 오류 0, 작업 525(kr 294 / en 231), VAD 21종, 오프라인 378 / 스트리밍 147, 결과 폴더 중복 0 |
| 독립 코드 리뷰 | 1차 지적 10건 중 "지금 바로 중지 후 남은 작업을 빈 CSV 로 훑고 지나감"(높음) 등 7건 수정, 2차 리뷰에서 수정 확인 |
| Gradle 빌드·실기기 | **미실행** — PC 에서 Gradle Sync·Run 후 확인 필요 |
