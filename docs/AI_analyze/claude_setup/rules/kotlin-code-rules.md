---
paths:
  - "**/*.kt"
  - "**/*.kts"
---

# Kotlin·Gradle 코드 작성 규칙 (google_stt)

## 1. 주석
- 파일 맨 위 블록 주석: `파일명`, `목적 및 기능`, 변경 이력 표시.
  ```kotlin
  /*
   * 파일명: domain/plan/ComboGenerator.kt
   * 목적 및 기능:
   * - 언어 폴더 × 모델 × VAD × 옵션 조합을 만들고 언어 전용 모델을 제외한다.
   *
   * add-hyungchul-20261106-0930
   */
  ```
- 함수 위 KDoc: 목적 및 기능, 입력 변수, 출력 변수, 리턴 값.
  ```kotlin
  /**
   * 목적: …
   * 입력: plan - 언어 판정 결과, models - 선택 모델
   * 출력: 없음(부수 효과 없음)
   * 리턴: ComboResult - 작업 목록과 제외 내역
   */
  ```
- 줄마다 끝에 한국어 설명 주석(자명한 `}` 같은 줄은 생략 가능).

## 2. 변경 표시
- 새로 넣은 줄·블록: `// add-hyungchul-YYYYMMDD-HHMM`
- 고친 줄·블록: `// change-hyungchul-YYYYMMDD-HHMM : 무엇을 왜`
- 날짜·시각은 실제 작업 시각(`date +%Y%m%d-%H%M`).

## 3. 기존 파일 수정
- import, 상수, 클래스, 함수, 변수의 **순서와 위치를 원본대로** 둔다. 새 항목은 관련 항목 바로 뒤에.
- 옮기기(`git mv` + `package` 줄)와 내용 고치기는 다른 커밋.
- 요청 범위 밖 정리·이름 바꾸기·주석 수정 금지.

## 4. 코루틴·오류
- `catch (e: CancellationException) { throw e }` 를 일반 `catch (e: Exception)` 앞에 둔다.
- 공급 대기는 `delay()`(코루틴) 또는 인터럽트 가능한 대기. 코루틴 안 `Thread.sleep` 금지.
- 마무리 쓰기는 `withContext(NonCancellable) { … }`.
- 오류는 `AppError` 코드(06 문서 6-2)로 분류하고 반드시 로그를 남긴다.

## 5. 계층
- `domain/**` 에서 `android.*`, `androidx.*` import 금지(JVM 단위 시험 대상).
- CSV 열은 `CsvColumn(이름, 계산식)` 목록 하나로만 정의(헤더·값 따로 쓰지 않음).

## 6. 크기
- 파일 500줄, 함수 100줄 이하. 넘으면 나눈다.
