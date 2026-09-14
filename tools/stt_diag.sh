#!/usr/bin/env bash
# 파일명: stt_diag.sh  (v3 - 2026-09-16)
# 목적 및 기능:
#   "세션 36번째부터 엔진이 응답하지 않는" 원인을 잡는 로그 수집기 + 자동 분석기.
#
#   [v2 에서 배운 것]
#   - 엔진은 com.google.android.tts 안의 SODA 다 (AICore 아님).
#   - 세션 1~30 은 Creating/Deleting 이 정확히 짝을 이룬다 → SODA 인스턴스 누수는 아니다.
#   - v2 수집은 세션 30에서 Ctrl+C 를 눌러 정작 먹통(36)을 못 담았다.
#     → v3 은 "배치가 끝날 때까지" 자동으로 기다렸다가 스스로 멈춘다. 타이밍을 맞출 필요가 없다.
#   - 세션 30개 시점의 tts 프로세스에 Local Binders 가 122개였다.
#     → v3 은 배치 내내 10초마다 자원 수치를 찍어 "세션이 늘수록 무엇이 증가하는가" 를 본다.
#
# 사용법: bash stt_diag.sh          (휴대폰 USB 연결 + adb 인식 상태에서)
#   ※ 앱에서 START 를 누르기만 하면 되고, 종료 타이밍은 신경 쓰지 않아도 된다.

set -u
APP=com.example.google_stt
TTS=com.google.android.tts
OUT="stt_diag_$(date +%Y%m%d_%H%M%S)"
MAXWAIT=2400          # 최대 40분 기다린다
mkdir -p "$OUT"

echo "[1/6] 기기 확인"
adb devices -l | tee "$OUT/00_devices.txt"

echo
echo "[2/6] 시작 전 상태"
adb shell dumpsys activity exit-info $TTS > "$OUT/01_exitinfo_tts_before.txt" 2>&1
adb shell ps -A | grep -iE "tts|aicore|android\.as|google_stt" > "$OUT/03_ps_before.txt" 2>&1
echo "  ※ 앱에 assets/silero_vad_16k_op15.onnx 가 없으면 배치가 즉시 실패해 재현이 안 됩니다."
echo "    START 후 '[vad][경고]' 가 뜨면 중단하고 tools/download_models.sh 실행 후 재빌드하세요."

echo
echo "[3/6] 로그 버퍼 확장 및 초기화"
adb logcat -G 64M; adb logcat -c; adb logcat -b crash -c 2>/dev/null

echo
echo "  ==> 지금 휴대폰에서 앱 배치를 실행(START)하세요."
echo "  ==> 배치가 끝나면 이 스크립트가 알아서 멈춥니다. Ctrl+C 를 누르지 마세요."
echo

echo "[4/6] 로그 수집 + 자원 추적 시작"
adb logcat -v threadtime > "$OUT/10_logcat_full.log" 2>&1 &
LOGPID=$!

# ── 자원 추적: 10초마다 tts 프로세스의 스레드/FD/바인더 수를 찍는다 ──
(
  echo "time_s,tts_pid,threads,fds,local_binders,proxy_binders,java_kb,native_kb"
  T=0
  while true; do
    PID=$(adb shell pidof $TTS 2>/dev/null | tr -d '\r' | awk '{print $1}')
    if [ -n "${PID:-}" ]; then
      TH=$(adb shell cat /proc/$PID/status 2>/dev/null | grep -i '^Threads:' | awk '{print $2}' | tr -d '\r')
      FD=$(adb shell "ls /proc/$PID/fd 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r')
      MI=$(adb shell dumpsys meminfo $TTS 2>/dev/null | tr -d '\r')
      LB=$(echo "$MI" | grep -o 'Local Binders: *[0-9]*' | awk '{print $3}')
      PB=$(echo "$MI" | grep -o 'Proxy Binders: *[0-9]*' | awk '{print $3}')
      JH=$(echo "$MI" | grep -m1 'Java Heap:' | awk '{print $3}')
      NH=$(echo "$MI" | grep -m1 'Native Heap:' | awk '{print $3}')
      echo "$T,$PID,${TH:-},${FD:-},${LB:-},${PB:-},${JH:-},${NH:-}"
    else
      echo "$T,,,,,,,"      # 프로세스가 죽은 순간 = 빈 줄로 표시된다
    fi
    sleep 10; T=$((T+10))
  done
) > "$OUT/40_resources.csv" 2>/dev/null &
RESPID=$!

# ── 배치 종료를 자동 감지 ──
L="$OUT/10_logcat_full.log"
ELAPSED=0
while [ $ELAPSED -lt $MAXWAIT ]; do
  sleep 5; ELAPSED=$((ELAPSED+5))
  if grep -qE "GoogleStt: \[중단\]|GoogleStt: \[요약\]|GoogleStt: \[실패\] 실행이 중단" "$L" 2>/dev/null; then
    echo "  배치 종료를 감지했습니다 (${ELAPSED}초)."; sleep 10; break
  fi
  [ $((ELAPSED % 60)) -eq 0 ] && \
    echo "  ... ${ELAPSED}초 경과 (완료 $(grep -c '\[base\] 완료' "$L" 2>/dev/null)건 / 실패 $(grep -c '\[base\] 실패' "$L" 2>/dev/null)건)"
done
kill $LOGPID $RESPID 2>/dev/null; wait 2>/dev/null

echo
echo "[5/6] 종료 직후 덤프"
adb logcat -b crash -d                       > "$OUT/11_logcat_crash.log" 2>&1
adb shell dumpsys activity exit-info $TTS    > "$OUT/12_exitinfo_tts_after.txt" 2>&1
adb shell dumpsys activity services $TTS     > "$OUT/14_services_tts.txt" 2>&1
adb shell ps -A | grep -iE "tts|aicore|android\.as|google_stt" > "$OUT/15_ps_after.txt" 2>&1
adb shell dumpsys meminfo $TTS               > "$OUT/16_meminfo_tts.txt" 2>&1

echo "[6/6] 자동 분석"
grep -iE "soda|speechrecog|NGSA|genai|mlkit|aicore|GoogleStt|ANR|lmkd|died" "$L" > "$OUT/20_filtered.log" 2>/dev/null
{
  echo "===== SODA 인스턴스 수지 ====="
  C=$(grep -c 'Creating SODA' "$L"); D=$(grep -c 'Deleting SODA' "$L")
  printf "  Creating %s / Deleting %s → 차이 %s\n" "$C" "$D" "$((C-D))"
  echo "  (차이 1 = 마지막 진행 중 세션. 2 이상이면 SODA 누수 확정)"
  echo
  echo "===== 세션 수 ====="
  printf "  앱 세션(Initializing SODA) : %s\n" "$(grep -c 'Initializing SODA for com.example' "$L")"
  printf "  NGSA 고유 세션 ID          : %s\n" "$(grep -o 'NGSA\.Recognition: \[[a-z0-9]*\]' "$L" | sort -u | wc -l)"
  printf "  checkModelAvailability     : %s\n" "$(grep -c '#checkModelAvailability' "$L")"
  printf "  파일 완료 / 실패           : %s / %s\n" "$(grep -c '\[base\] 완료' "$L")" "$(grep -c '\[base\] 실패' "$L")"
  echo
  echo "===== 먹통 시작 지점 (첫 실패 전후 SODA/NGSA 로그) ====="
  FL=$(grep -n 'GoogleStt: \[base\] 실패' "$L" | head -1 | cut -d: -f1)
  if [ -n "${FL:-}" ]; then
    S=$((FL>400 ? FL-400 : 1))
    sed -n "${S},$((FL+40))p" "$L" | grep -iE "ConcurrentSodaManager|SodaSpeechRecognizer|NGSA\.(Recognition|GoogleAsrService)|GoogleStt" | tail -60
  else
    echo "  실패 행이 없다 — 배치가 끝까지 정상이었거나 수집이 일찍 끝났다."
  fi
  echo
  echo "===== 자원 추이 (세션이 늘수록 증가하는 항목이 범인) ====="
  head -1 "$OUT/40_resources.csv" 2>/dev/null
  awk -F, 'NR>1 && NR%3==1' "$OUT/40_resources.csv" 2>/dev/null | head -40
  echo
  echo "===== 엔진 프로세스 사망 여부 ====="
  grep -A 3 "ApplicationExitInfo #0" "$OUT/12_exitinfo_tts_after.txt" 2>/dev/null | head -5
} > "$OUT/30_analysis.txt" 2>&1

cat "$OUT/30_analysis.txt"
tar czf "$OUT.tar.gz" "$OUT" 2>/dev/null
echo; echo "수집 완료 → $OUT.tar.gz  (이 파일을 올려 주세요)"
