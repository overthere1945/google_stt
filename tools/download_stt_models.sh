#!/usr/bin/env bash
# 파일명: tools/download_stt_models.sh
# 목적 및 기능: 2단계 sherpa-onnx STT 엔진 준비(PC 에서 실행)
#   1) sherpa-onnx 정적 링크 AAR(v1.13.7)을 app/libs/ 에 받는다 — 빌드에 꼭 필요하다
#   2) 모델 패키지(tar.bz2, 공식 GitHub Releases "asr-models")를 받아 앱이 쓰는 파일만 stt_models/<패키지>/ 에 푼다
#   3) 파일마다 sha256 을 SHA256SUMS.txt 로 남긴다(앱이 run_meta 에 그대로 기록 — 휴대폰에서 다시 해시하지 않기 위함)
#   4) adb 로 휴대폰 공용 폴더 /sdcard/Documents/stt_models/<패키지>/ 에 넣는다
#      change-hyungchul-20261010-2238 : 사용자 결정 Q24 — 예전(Q18) 앱 전용 폴더(Android/data)는 adb 가 만든 폴더를 앱이 읽지 못해 바꿨다.
#      앱은 '모든 파일 접근' 권한으로 읽는다(처음 START 때 설정 화면이 열린다). 앱을 지워도 모델은 남는다.
#      앱 설치 여부와 상관없이 넣을 수 있다(MTP/파일 관리자로 Documents/stt_models 에 복사해도 된다).
# 사용법: 프로젝트 루트에서
#   bash tools/download_stt_models.sh                 # 기본: aar + whisper-base + qwen3asr-0.6b + omnilingual-300m + nemotron3.5-560ms
#   bash tools/download_stt_models.sh aar             # AAR 만
#   bash tools/download_stt_models.sh whisper-tiny whisper-small   # 고른 모델만
#   bash tools/download_stt_models.sh all             # aar + 모델 13종 전부(약 5.5 GB)  (change-hyungchul-20261011-0150 : 12종 → 9종 → 2-3 4종 추가 13종)
#   --no-push 를 붙이면 PC 에만 받고 휴대폰에는 넣지 않는다
# 모델 이름: whisper-tiny / whisper-base / whisper-small / whisper-medium / whisper-turbo / qwen3asr-0.6b / omnilingual-300m
#           nemotron3.5-320ms / nemotron3.5-560ms (스트리밍, 각 약 0.7 GB)
#           [상업 조건부] zipformer-ko / zipformer-ko-stream / sensevoice-small / moonshine-tiny-ko (add-hyungchul-20261011-0150 : 2-3, 기본 묶음에는 넣지 않음)
# add-hyungchul-20261010-1800
# change-hyungchul-20261011-0031 : 2단계 2-2 — Nemotron 3.5 스트리밍 5종 추가(사용자 결정 Q26: 기본 받기는 560 ms)
# change-hyungchul-20261011-0150 : Nemotron 은 320 / 560 ms 2종만(사용자 결정 Q28 — 80 / 160 / 1120 ms 삭제)
# add-hyungchul-20261011-0150 : 2단계 2-3 — Zipformer KO(오프라인·스트리밍) / SenseVoice Small / Moonshine tiny KO

set -u                                                     # 정의 안 된 변수를 쓰면 멈춘다

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"                 # tools 폴더
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"                       # 프로젝트 루트
LIBS="$ROOT/app/libs"                                      # AAR 위치(app/build.gradle.kts 가 여기서 읽는다)
LOCAL="$ROOT/stt_models"                                   # PC 쪽 모델 보관 위치(git 제외)
DL="$LOCAL/_download"                                      # tar.bz2 임시 위치(풀고 나면 지운다)
APP_ID="com.example.google_stt"                            # 앱 패키지 이름
DEV_ROOT="/sdcard/Documents/stt_models"                    # change-hyungchul-20261010-2238 : 휴대폰 모델 폴더(앱 SherpaOfflineTranscriber.modelsRoot 와 같게)
SHERPA_VER="1.13.7"                                        # sherpa-onnx 판(app/build.gradle.kts 와 같게)
AAR="sherpa-onnx-static-link-onnxruntime-$SHERPA_VER.aar"  # 정적 링크 AAR 이름
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VER/$AAR"
MODELS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"   # 모델 패키지 위치
FAILED=0                                                   # 실패가 하나라도 있으면 1
PUSH=1                                                     # 휴대폰에 넣을지

# ── 인자 정리 ──
TARGETS=()
for a in "$@"; do
  if [ "$a" = "--no-push" ]; then PUSH=0; else TARGETS+=("$a"); fi
done
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(aar whisper-base qwen3asr-0.6b omnilingual-300m nemotron3.5-560ms)   # 기본 묶음(change-hyungchul-20261011-0031 : 560 ms 추가)
if [ "${TARGETS[0]}" = "all" ]; then
  TARGETS=(aar whisper-tiny whisper-base whisper-small whisper-medium whisper-turbo qwen3asr-0.6b omnilingual-300m \
           nemotron3.5-320ms nemotron3.5-560ms \
           zipformer-ko zipformer-ko-stream sensevoice-small moonshine-tiny-ko)   # change-hyungchul-20261011-0150 : 80 / 160 / 1120 ms 삭제, 2-3 4종 추가
fi

# 목적: 모델 이름 → "패키지 이름|앱이 쓰는 파일(공백 구분)" 을 출력한다(앱 stt/SherpaModels.kt 와 같은 값)
# 입력: $1 = 모델 이름 / 출력: 표준출력 한 줄 / 리턴: 모르는 이름이면 1
spec_of() {
  case "$1" in
    whisper-tiny|whisper-base|whisper-small|whisper-medium|whisper-turbo)
      local s="${1#whisper-}"                                # tiny / base / …
      echo "sherpa-onnx-whisper-$s|$s-encoder.int8.onnx $s-decoder.int8.onnx $s-tokens.txt" ;;
    qwen3asr-0.6b)
      echo "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25|conv_frontend.onnx encoder.int8.onnx decoder.int8.onnx tokenizer" ;;
    omnilingual-300m)
      echo "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12|model.int8.onnx tokens.txt LICENSE" ;;
    # add-hyungchul-20261011-0031 : Nemotron 3.5 스트리밍(앱 SherpaCatalog.nemotron 과 같은 패키지·파일)
    nemotron3.5-320ms|nemotron3.5-560ms)                     # change-hyungchul-20261011-0150 : 2종만
      local ms="${1#nemotron3.5-}"                           # 320ms / 560ms
      echo "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-$ms-int8-2026-06-11|encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt" ;;
    # add-hyungchul-20261011-0150 : 2-3 상업 조건부 엔진(앱 SherpaCatalog 와 같은 패키지·파일)
    zipformer-ko)
      echo "sherpa-onnx-zipformer-korean-2024-06-24|encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.int8.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt" ;;
    zipformer-ko-stream)
      echo "sherpa-onnx-streaming-zipformer-korean-2024-06-16|encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.int8.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt" ;;
    sensevoice-small)
      echo "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17|model.int8.onnx tokens.txt LICENSE" ;;
    moonshine-tiny-ko)
      echo "sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27|encoder_model.ort decoder_model_merged.ort tokens.txt LICENSE" ;;
    *) return 1 ;;
  esac
}

# 목적: AAR 을 app/libs/ 에 받는다(이미 있으면 건너뜀)
fetch_aar() {
  mkdir -p "$LIBS"
  if [ -s "$LIBS/$AAR" ]; then echo "  [건너뜀] $AAR — 이미 있음"; return 0; fi
  echo "  [받는 중] $AAR (sherpa-onnx v$SHERPA_VER, Apache-2.0)"
  if curl -fL --retry 3 -o "$LIBS/$AAR.tmp" "$AAR_URL"; then
    mv -f "$LIBS/$AAR.tmp" "$LIBS/$AAR"
    echo "  [완료] $(wc -c < "$LIBS/$AAR") bytes  sha256=$(sha256sum "$LIBS/$AAR" | cut -c1-16)…"
  else
    rm -f "$LIBS/$AAR.tmp"; echo "  [실패] $AAR_URL" >&2; FAILED=1
  fi
}

# 목적: 모델 1개를 받아 필요한 파일만 풀고 SHA256SUMS.txt 를 만든다
# 입력: $1 = 모델 이름
fetch_model() {
  local line pkg files f missing=0
  line="$(spec_of "$1")" || { echo "  [실패] 모르는 모델 이름: $1" >&2; FAILED=1; return 1; }
  pkg="${line%%|*}"; files="${line#*|}"                    # 패키지 이름 / 파일 목록
  for f in $files; do [ -e "$LOCAL/$pkg/$f" ] || missing=1; done
  if [ $missing -eq 0 ]; then
    echo "  [건너뜀] $pkg — 이미 풀어 둠"
  else
    mkdir -p "$DL" "$LOCAL"
    echo "  [받는 중] $pkg.tar.bz2"
    if ! curl -fL --retry 3 -o "$DL/$pkg.tar.bz2.tmp" "$MODELS_URL/$pkg.tar.bz2"; then
      rm -f "$DL/$pkg.tar.bz2.tmp"; echo "  [실패] $MODELS_URL/$pkg.tar.bz2" >&2; FAILED=1; return 1
    fi
    mv -f "$DL/$pkg.tar.bz2.tmp" "$DL/$pkg.tar.bz2"
    local members=()
    for f in $files; do members+=("$pkg/$f"); done        # tar 안 경로 = <패키지>/<파일>
    echo "  [푸는 중] ${files// /, }"
    if ! tar -xjf "$DL/$pkg.tar.bz2" -C "$LOCAL" "${members[@]}"; then
      echo "  [실패] 압축 풀기 — tar 안에 위 파일이 없을 수 있습니다." >&2; FAILED=1; return 1
    fi
    rm -f "$DL/$pkg.tar.bz2"                               # 디스크 절약(수백 MB~GB)
  fi
  # 해시 목록(폴더는 안의 파일까지) — 앱이 run_meta 의 stt_model_sha256 으로 기록한다
  (cd "$LOCAL/$pkg" && find $files -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS.txt)
  echo "  [완료] $pkg ($(du -sh "$LOCAL/$pkg" | cut -f1))"
}

# 목적: 모델 폴더를 휴대폰 공용 모델 폴더로 넣는다(change-hyungchul-20261010-2238)
# 입력: $1 = 모델 이름
push_model() {
  local line pkg
  line="$(spec_of "$1")" || return 1
  pkg="${line%%|*}"
  echo "  [휴대폰에 넣는 중] $pkg → $DEV_ROOT/$pkg"
  if adb push "$LOCAL/$pkg" "$DEV_ROOT/" > /dev/null; then
    adb shell ls -la "$DEV_ROOT/$pkg" | sed 's/^/      /'
  else
    echo "  [실패] adb push — 휴대폰 연결·용량을 확인하세요." >&2; FAILED=1
  fi
}

echo "프로젝트: $ROOT"
echo "대상: ${TARGETS[*]}  (휴대폰에 넣기: $([ $PUSH -eq 1 ] && echo 예 || echo 아니오))"
echo

# ── 1) 받기 ──
MODELS=()
for t in "${TARGETS[@]}"; do
  if [ "$t" = "aar" ]; then fetch_aar; else fetch_model "$t" && MODELS+=("$t"); fi
done

# ── 2) 휴대폰에 넣기 ──
if [ $PUSH -eq 1 ] && [ ${#MODELS[@]} -gt 0 ]; then
  echo
  if ! command -v adb > /dev/null 2>&1; then
    echo "[건너뜀] adb 가 없습니다. Android SDK platform-tools 를 PATH 에 넣거나 --no-push 로 실행하세요." >&2; FAILED=1
  elif [ "$(adb get-state 2>/dev/null)" != "device" ]; then
    echo "[건너뜀] 휴대폰이 adb 에 연결되어 있지 않습니다(USB 디버깅 허용 확인)." >&2; FAILED=1
  # change-hyungchul-20261010-2238 : 공용 폴더라 앱 설치 확인(pm path)을 하지 않는다(실기기에서 설치돼 있는데도 "없음" 으로 나온 적이 있다)
  else
    adb shell mkdir -p "$DEV_ROOT"
    for m in "${MODELS[@]}"; do push_model "$m"; done
  fi
fi

echo
if [ $FAILED -ne 0 ]; then
  echo "일부를 끝내지 못했습니다. 위 [실패]/[건너뜀] 줄을 확인하세요."; exit 1
fi
echo "완료. 앱에서 Model 을 고르고 START 하세요(처음 한 번은 '모든 파일 접근' 설정 화면이 열린다 — 켠 뒤 다시 START)."   # change-hyungchul-20261010-2238
