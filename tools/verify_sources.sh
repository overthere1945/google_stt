#!/usr/bin/env bash
# 파일명: tools/verify_sources.sh
# 목적 및 기능:
#   소스 파일이 "온전히" 들어왔는지(잘리거나 일부만 붙여넣어지지 않았는지) 확인한다.
#   Beyond Compare 로 손수 병합할 때 파일 끝부분이 누락되면
#   "Unresolved reference" 가 엉뚱한 곳에서 나서 원인을 찾기 어렵다. 그걸 미리 잡는다.
# 사용법: 프로젝트 루트에서  bash tools/verify_sources.sh
# add-hyungchul-20260828-1000 / 기대값 갱신 change-hyungchul-20260916-1000

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

FAIL=0
if command -v sha256sum >/dev/null 2>&1; then HASH="sha256sum"; else HASH="shasum -a 256"; fi

check() {
  local f="$1" want_lines="$2" want_hash="$3"
  if [ ! -f "$f" ]; then
    printf '  [없음]   %s\n' "$f"; FAIL=1; return
  fi
  local n h
  n=$(wc -l < "$f" | tr -d ' ')
  h=$($HASH "$f" | cut -d" " -f1)
  if [ "$h" = "$want_hash" ]; then
    printf '  [일치]   %-72s %s줄\n' "$f" "$n"
  elif [ "$n" -lt "$want_lines" ]; then
    printf '  [잘림!]  %-72s %s줄 (정상 %s줄) ← 파일 끝이 누락되었습니다\n' "$f" "$n" "$want_lines"; FAIL=1
  else
    printf '  [다름]   %-72s %s줄 (정상 %s줄) — 직접 수정하셨다면 정상입니다\n' "$f" "$n" "$want_lines"
  fi
}

echo "소스 무결성 확인"
echo
check "app/src/main/java/com/example/google_stt/MainActivity.kt" 2876 48009b43d685ebc353c6a589a7b0f1ff61dc0e41a50bdf34d18950e4b93272dd
check "app/src/main/java/com/example/google_stt/SttTelemetry.kt" 596 4e46950f514fc678e3ac37813c64386f697ce12434ec8e4e514284bba70f9b17
check "app/src/main/java/com/example/google_stt/AudioDecoder.kt" 333 8f4c56731ee67cfc937ee758cfd6182c39d3fb7584eb43ec31ec0394124837ff
check "app/src/main/java/com/example/google_stt/SileroVadProcessor.kt" 695 beda4983280be0adea539d266e8a7a69f67c31706215ca36c901ac5283e2a7bd
check "app/src/main/java/com/example/google_stt/SpeechEpd.kt" 348 4edcce8b48bfa1ccf0922eb64e5f712e13a29d952c8e41329512a08788916b2d
check "app/src/main/java/com/example/google_stt/denoise/AudioDsp.kt" 363 6090554f1e229b85bf518e37f30b346f33a5ba6188816e969175bd9e5acbb3d8
check "app/src/main/java/com/example/google_stt/denoise/NoiseReducer.kt" 459 b6d1e6f7707bd7344cd031c6a054fc8d705892c6c1ced0cdf29857eec72b068c
check "app/src/main/java/com/example/google_stt/denoise/SpectralNoiseReducer.kt" 162 53a59736c73f8d16595da964d5503bf0c87f749a3ee85cf083f43cc9952889e5
check "app/src/main/java/com/example/google_stt/denoise/OnnxNoiseReducer.kt" 415 ded8eeee5f8610f08ab35ec38b09bf4d4a53999f68a3d6e3d8d1205b260e6671
check "app/src/main/java/com/example/google_stt/denoise/DspNoiseReducer.kt" 355 eb4025469f0af4bd3aacf81df9d13462bf575ad7468a155e36f377cf4b2eff0e
check "app/src/main/res/layout/activity_main.xml" 551 52e72fd02617bef3eeff44cd0b6f46979e70f41777ab02cf03c365cf6a26c183
check "app/src/main/res/values/strings.xml" 83 8de646fff782d14607010087006528eb9c4b0f75ed05b141f0d98dd62ed4f419
check "app/build.gradle.kts" 82 231721873da029cb27d0d94909fc8b36440636784e50b0135de481a57705b14a
echo
if [ "$FAIL" -ne 0 ]; then
  echo "★ [잘림] 또는 [없음] 이 있습니다. 해당 파일을 zip 에서 다시 복사하세요."
  echo "  특히 denoise/NoiseReducer.kt 가 잘리면 맨 끝의 object NoiseReducerFactory 가 사라져"
  echo "  MainActivity 의 import 줄에서 'Unresolved reference' 가 납니다."
  exit 1
fi
echo "이상 없습니다."
