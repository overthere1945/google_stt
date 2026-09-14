# assets 모델 파일 안내

add-hyungchul-20260826-1100

이 폴더에는 앱이 쓰는 ONNX 모델이 들어간다.

## 한 줄 요약

프로젝트 루트에서 아래 한 줄이면 필요한 모델이 다 채워진다.

```bash
bash tools/download_models.sh          # VAD + GTCRN + DPDFNet baseline
bash tools/download_models.sh all      # 위 + NSNet2 + DPDFNet 2/4/8
```

받은 뒤에는 **Android Studio 에서 다시 빌드**해야 한다(assets 는 APK 안에 구워진다).

## 파일 목록

| 파일 | 쓰는 곳 | 크기 | 라이선스 | zip 포함 |
|---|---|---|---|---|
| `gtcrn_simple.onnx` | Noise Reduction 1순위 GTCRN | 523 KB | MIT | **✅ 포함** |
| `silero_vad_16k_op15.onnx` | Silero VAD | 약 1.2 MB | MIT | ❌ 받아야 함 |
| `dpdfnet_baseline.onnx` | Noise Reduction 2순위 DPDFNet | 8.4 MB | Apache-2.0 | ❌ 받아야 함 |
| `dpdfnet2/4/8.onnx` | DPDFNet 상위 변형 | 10~15 MB | Apache-2.0 | ❌ 선택 |
| `nsnet2-20ms-baseline.onnx` | Noise Reduction 4순위 NSNet2 | 10.3 MB | MIT | ❌ 선택 |

`gtcrn_simple.onnx` 만 zip 에 넣은 이유: 523 KB 로 작고 1순위 알고리즘이라,
아무것도 안 받아도 바로 GTCRN 비교를 시작할 수 있게 하기 위해서다.
나머지는 용량이 커서 스크립트로 받는 편이 낫다.

## 무결성 확인

change-hyungchul-20260914-1700: 모델 3개의 정상 해시를 같은 폴더의 `models.sha256` 에 모아 두었다.

```bash
cd app/src/main/assets && sha256sum -c models.sha256
```

`gtcrn_simple.onnx` 의 값은 sherpa-onnx 릴리스의 `checksum.txt` 와 일치함을 확인했다.

> ★ ONNX 모델 자체는 git 에 넣지 않는다(합계 약 10.6 MB, 이진 파일이라 history 가 영구히 무거워진다).
> `.gitignore` 에서 `/app/src/main/assets/*.onnx` 로 제외했고, 재현성은 `models.sha256` 과
> 실행마다 기록되는 `run_meta.csv` 의 `silero_model_sha256` / `ns_model_sha256` 대조로 보장한다.
앱 실행 시 로그와 `run_meta.csv` 의 `ns_model_sha256` / `silero_model_sha256` 에도
실제로 쓰인 파일의 해시가 남는다. **보고서에는 이 해시를 함께 적어라.**

## 모델이 없으면 어떻게 되나

- 해당 알고리즘을 **고르는 순간** 화면 로그와 토스트로 "파일이 없다"고 알린다.
- START 를 누르면 그 자리에서 실패한다. **조용히 원음으로 넘어가지 않는다.**
  넘어가면 CSV 에는 처리한 것으로 기록되는데 실제로는 안 한 행이 섞여 비교가 통째로 깨지기 때문이다.
- 모델이 없어도 **전통 DSP 3종(MMSE-STSA / Wiener / Spectral Subtraction)** 과
  **Noise Reduction 사용 안 함** 은 그대로 동작한다.

## 출처

- Silero VAD — https://github.com/snakers4/silero-vad (MIT)
- GTCRN — https://github.com/Xiaobin-Rong/gtcrn (MIT), 배포본은 sherpa-onnx 릴리스
- DPDFNet — https://github.com/ceva-ip/DPDFNet (Apache-2.0), 배포본은 sherpa-onnx 릴리스
- NSNet2 — https://github.com/microsoft/DNS-Challenge (MIT), `icassp2021-final` 브랜치
