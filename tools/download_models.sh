#!/usr/bin/env bash
# 파일명: tools/download_models.sh
# 목적 및 기능:
#   이 앱이 쓰는 ONNX 모델을 전부 app/src/main/assets/ 에 내려받는다.
#     - Silero VAD          silero_vad_16k_op15.onnx      (MIT)
#     - GTCRN               gtcrn_simple.onnx             (MIT)        ※ zip 에 이미 들어 있음
#     - DPDFNet             dpdfnet_baseline.onnx 등      (Apache-2.0)
#     - NSNet2              nsnet2-20ms-baseline.onnx     (MIT)
#   add-hyungchul-20261009-1530 : VAD 엔진 추가분(vads)
#     - pyannote seg-3.0    pyannote_seg3.onnx            (MIT, sherpa-onnx 변환본 model.onnx)
#     - FunASR FSMN-VAD     fsmn_vad_quant.onnx, fsmn_vad_am.mvn  (Apache-2.0, ModelScope)
#     - FireRedVAD          fireredvad_vad.onnx, fireredvad_cmvn.ark (Apache-2.0, GitHub)
#     - TEN VAD             app/src/main/jniLibs/<abi>/libten_vad.so (Apache-2.0 + 추가 조건, GitHub)
#     - WebRTC VAD 는 Gradle 의존성(android-vad)이라 받을 파일이 없다.
# 사용법: 프로젝트 루트에서  bash tools/download_models.sh  [all|vad|vads|gtcrn|dpdfnet|nsnet2]
#         인자를 안 주면 꼭 필요한 것(vad + vads + gtcrn + dpdfnet_baseline)만 받는다.
# add-hyungchul-20260826-1100

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${SCRIPT_DIR}/../app/src/main/assets"
mkdir -p "${DEST}"

WHAT="${1:-default}"
FAILED=0
JNI="${SCRIPT_DIR}/../app/src/main/jniLibs"   # add-hyungchul-20261009-1530 : TEN VAD 네이티브 라이브러리 위치

# 인자: URL, 저장 파일명, 최소 크기(byte), 설명
fetch() {
  local url="$1" name="$2" minsize="$3" desc="$4"
  local out="${DEST}/${name}"
  if [ -s "${out}" ]; then
    echo "  [건너뜀] ${name} — 이미 있음 ($(wc -c < "${out}") bytes)"
    return 0
  fi
  echo "  [받는 중] ${name}  (${desc})"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "${out}.tmp" "${url}" 2>/dev/null
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "${out}.tmp" "${url}"
  else
    echo "  [실패] curl 도 wget 도 없습니다." >&2; FAILED=1; return 1
  fi
  if [ ! -f "${out}.tmp" ]; then
    echo "  [실패] ${name} — 내려받지 못했습니다: ${url}" >&2; FAILED=1; return 1
  fi
  local size; size=$(wc -c < "${out}.tmp" | tr -d ' ')
  if [ "${size}" -lt "${minsize}" ]; then
    echo "  [실패] ${name} — 파일이 너무 작습니다(${size} bytes). HTML 오류 페이지일 수 있습니다." >&2
    rm -f "${out}.tmp"; FAILED=1; return 1
  fi
  mv -f "${out}.tmp" "${out}"
  echo "  [완료] ${name}  ${size} bytes"
  return 0
}

SILERO="https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad_16k_op15.onnx"

# ── add-hyungchul-20261009-1530 : VAD 엔진 추가분 ──
PYANNOTE_TAR="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
FSMN_MS="https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master"
FIRERED="https://raw.githubusercontent.com/FireRedTeam/FireRedVAD/c30ec49e8cc69642b0ee65362eba11b9d11c6e54/examples/pretrained_models/onnx_models"
TENVAD="https://raw.githubusercontent.com/TEN-framework/ten-vad/22a3bcd4509d0faaa8eef4881e8af5f39c178950/lib/Android"

# 인자: URL, 저장 경로(절대), 최소 크기, 설명 — fetch() 와 같지만 assets 밖(jniLibs)에도 쓴다
fetch_to() {
  local url="$1" out="$2" minsize="$3" desc="$4"
  mkdir -p "$(dirname "${out}")"
  if [ -s "${out}" ]; then
    echo "  [건너뜀] ${out#${SCRIPT_DIR}/../} — 이미 있음"; return 0
  fi
  echo "  [받는 중] ${out#${SCRIPT_DIR}/../}  (${desc})"
  curl -fL --retry 3 -o "${out}.tmp" "${url}" 2>/dev/null || wget -q -O "${out}.tmp" "${url}"
  local size; size=$(wc -c < "${out}.tmp" 2>/dev/null | tr -d ' ')
  if [ -z "${size}" ] || [ "${size}" -lt "${minsize}" ]; then
    echo "  [실패] ${url}" >&2; rm -f "${out}.tmp"; FAILED=1; return 1
  fi
  mv -f "${out}.tmp" "${out}"; echo "  [완료] ${size} bytes"
}

# pyannote: sherpa-onnx 배포 tar.bz2 에서 model.onnx(fp32)와 LICENSE 만 꺼낸다
fetch_pyannote() {
  local out="${DEST}/pyannote_seg3.onnx"
  if [ -s "${out}" ]; then echo "  [건너뜀] pyannote_seg3.onnx — 이미 있음"; return 0; fi
  local tmp; tmp="$(mktemp -d)"
  echo "  [받는 중] pyannote_seg3.onnx  (sherpa-onnx-pyannote-segmentation-3-0, MIT)"
  if curl -fL --retry 3 -o "${tmp}/p.tar.bz2" "${PYANNOTE_TAR}" 2>/dev/null && tar -xjf "${tmp}/p.tar.bz2" -C "${tmp}"; then
    local m; m="$(find "${tmp}" -name model.onnx | head -1)"
    local l; l="$(find "${tmp}" -name LICENSE | head -1)"
    if [ -n "${m}" ]; then
      cp -f "${m}" "${out}"; [ -n "${l}" ] && cp -f "${l}" "${DEST}/pyannote_seg3.LICENSE.txt"
      echo "  [완료] $(wc -c < "${out}") bytes"
    else
      echo "  [실패] tar 안에 model.onnx 가 없습니다." >&2; FAILED=1
    fi
  else
    echo "  [실패] ${PYANNOTE_TAR}" >&2; FAILED=1
  fi
  rm -rf "${tmp}"
}

fetch_vads() {
  fetch_pyannote
  fetch "${FSMN_MS}/model_quant.onnx" "fsmn_vad_quant.onnx" 300000 "FunASR FSMN-VAD, Apache-2.0"
  fetch "${FSMN_MS}/am.mvn" "fsmn_vad_am.mvn" 1000 "FSMN-VAD CMVN"
  fetch "${FIRERED}/fireredvad_vad.onnx" "fireredvad_vad.onnx" 2000000 "FireRedVAD, Apache-2.0"
  fetch "${FIRERED}/cmvn.ark" "fireredvad_cmvn.ark" 1000 "FireRedVAD CMVN"
  fetch_to "${TENVAD}/arm64-v8a/libten_vad.so" "${JNI}/arm64-v8a/libten_vad.so" 100000 "TEN VAD arm64, Apache-2.0+조건"
  fetch_to "${TENVAD}/armeabi-v7a/libten_vad.so" "${JNI}/armeabi-v7a/libten_vad.so" 100000 "TEN VAD armv7"
}
SHERPA="https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models"
NSNET2="https://raw.githubusercontent.com/microsoft/DNS-Challenge/icassp2021-final/NSNet2-baseline/nsnet2-20ms-baseline.onnx"

echo "저장 위치: ${DEST}"
echo

case "${WHAT}" in
  vad)      fetch "${SILERO}" "silero_vad_16k_op15.onnx" 300000 "Silero VAD, MIT" ;;
  vads)     fetch_vads ;;   # add-hyungchul-20261009-1530
  gtcrn)    fetch "${SHERPA}/gtcrn_simple.onnx" "gtcrn_simple.onnx" 400000 "GTCRN 1순위, MIT" ;;
  nsnet2)   fetch "${NSNET2}" "nsnet2-20ms-baseline.onnx" 8000000 "NSNet2, MIT" ;;
  dpdfnet)
    fetch "${SHERPA}/dpdfnet_baseline.onnx" "dpdfnet_baseline.onnx" 8000000 "DPDFNet baseline, Apache-2.0"
    fetch "${SHERPA}/dpdfnet2.onnx" "dpdfnet2.onnx" 9000000 "DPDFNet 2"
    fetch "${SHERPA}/dpdfnet4.onnx" "dpdfnet4.onnx" 10000000 "DPDFNet 4"
    fetch "${SHERPA}/dpdfnet8.onnx" "dpdfnet8.onnx" 12000000 "DPDFNet 8"
    ;;
  all)
    fetch "${SILERO}" "silero_vad_16k_op15.onnx" 300000 "Silero VAD, MIT"
    fetch "${SHERPA}/gtcrn_simple.onnx" "gtcrn_simple.onnx" 400000 "GTCRN 1순위, MIT"
    fetch "${SHERPA}/dpdfnet_baseline.onnx" "dpdfnet_baseline.onnx" 8000000 "DPDFNet baseline, Apache-2.0"
    fetch "${SHERPA}/dpdfnet2.onnx" "dpdfnet2.onnx" 9000000 "DPDFNet 2"
    fetch "${SHERPA}/dpdfnet4.onnx" "dpdfnet4.onnx" 10000000 "DPDFNet 4"
    fetch "${SHERPA}/dpdfnet8.onnx" "dpdfnet8.onnx" 12000000 "DPDFNet 8"
    fetch "${NSNET2}" "nsnet2-20ms-baseline.onnx" 8000000 "NSNet2, MIT"
    fetch_vads   # add-hyungchul-20261009-1530
    ;;
  *)
    fetch "${SILERO}" "silero_vad_16k_op15.onnx" 300000 "Silero VAD, MIT"
    fetch "${SHERPA}/gtcrn_simple.onnx" "gtcrn_simple.onnx" 400000 "GTCRN 1순위, MIT"
    fetch "${SHERPA}/dpdfnet_baseline.onnx" "dpdfnet_baseline.onnx" 8000000 "DPDFNet baseline, Apache-2.0"
    fetch_vads   # add-hyungchul-20261009-1530 : pyannote / FSMN / FireRed / TEN
    echo
    echo "※ NSNet2 와 DPDFNet 2/4/8 도 받으려면:  bash tools/download_models.sh all"
    ;;
esac

echo
echo "== assets 현황 =="
ls -l "${DEST}" 2>/dev/null | grep -i onnx || echo "  (onnx 파일 없음)"
echo
echo "== sha256 (run_meta.csv 의 값과 대조하세요) =="
if command -v sha256sum >/dev/null 2>&1; then
  (cd "${DEST}" && sha256sum *.onnx *.mvn *.ark 2>/dev/null)
  (cd "${JNI}" 2>/dev/null && sha256sum */libten_vad.so 2>/dev/null)   # add-hyungchul-20261009-1530
elif command -v shasum >/dev/null 2>&1; then
  (cd "${DEST}" && shasum -a 256 *.onnx 2>/dev/null)
fi
echo
echo "참고: gtcrn_simple.onnx 의 정상 sha256 은"
echo "      e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534 입니다."
echo
if [ "${FAILED}" -ne 0 ]; then
  echo "일부 파일을 받지 못했습니다. 위 [실패] 줄을 확인하세요." >&2
  exit 1
fi
echo "완료. Android Studio 에서 다시 빌드하세요(assets 는 APK 에 구워집니다)."
