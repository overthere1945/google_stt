/*
 * 파일명: domain/catalog/CatalogAssets.kt
 * 목적 및 기능:
 * - 내려받을 자산 22개를 정의한다: STT 18(sherpa-onnx tar.bz2 16 + Vosk zip 2) + VAD 4(Silero, pyannote, FSMN, FireRed).
 *   TEN VAD 는 네이티브 라이브러리(libten_vad.so)를 APK 에 넣으므로 내려받는 자산이 아니다(FR-05-01).
 * - STT 자산의 파일 목록은 ModelSpec.files 에서 만든다(모델 정의와 자산 정의가 어긋나지 않게).
 * - 주소는 v3 의 tools/download_stt_models.sh·tools/download_models.sh 와 같다. 압축 파일 크기는 3단계 3-3 실측값,
 *   Vosk zip 과 pyannote tar.bz2 는 2026-11-06 실측값이다. 해시는 여기 쓰지 않고 asset_manifest.json 에서 합친다.
 *
 * add-hyungchul-20261106-0037 — [T2-2a]
 */
package com.example.google_stt.domain.catalog

import com.example.google_stt.domain.vad.VadEngine   // VAD 엔진 → 자산 ID 대응

/** 목적: 자산 목록 만들기(Catalog.assets 가 쓴다). */
internal object CatalogAssets {

    private const val SHERPA_ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"   // sherpa-onnx 모델 릴리스
    private const val VOSK = "https://alphacephei.com/vosk/models"                                         // Vosk 공식 모델 위치

    /** 받을 크기(bytes): 자산 ID → 압축 파일 크기(압축이 없으면 파일 크기 합) */
    private val BYTES: Map<String, Long> = mapOf(
        "sherpa-onnx-whisper-tiny" to 116_204_861L,
        "sherpa-onnx-whisper-base" to 207_557_382L,
        "sherpa-onnx-whisper-small" to 639_387_718L,
        "sherpa-onnx-whisper-medium" to 1_931_372_882L,
        "sherpa-onnx-whisper-turbo" to 563_790_207L,
        "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25" to 878_702_423L,
        "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12" to 292_571_207L,
        "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-320ms-int8-2026-06-11" to 475_272_949L,
        "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11" to 475_271_763L,
        "sherpa-onnx-zipformer-korean-2024-06-24" to 329_740_690L,
        "sherpa-onnx-streaming-zipformer-korean-2024-06-16" to 418_218_652L,
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17" to 163_002_883L,
        "sherpa-onnx-zipformer-en-2023-06-26" to 307_666_046L,
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8" to 482_468_385L,
        "sherpa-onnx-moonshine-tiny-en-int8" to 107_600_538L,
        "sherpa-onnx-moonshine-base-en-int8" to 250_807_309L,
        "vosk-model-small-ko-0.22" to 86_914_329L,
        "vosk-model-small-en-us-0.15" to 41_205_931L,
    )

    /** 폴더로 적힌 역할(Qwen3 tokenizer)의 실제 파일들 */
    private val DIR_FILES = mapOf("tokenizer" to listOf("merges.txt", "tokenizer_config.json", "vocab.json"))

    /** Vosk small 모델이 공통으로 가진 파일(README 는 쓰지 않아 뺀다) */
    private val VOSK_FILES = listOf(
        "am/final.mdl", "conf/mfcc.conf", "conf/model.conf",
        "graph/Gr.fst", "graph/HCLr.fst", "graph/disambig_tid.int", "graph/phones/word_boundary.int",
        "ivector/final.dubm", "ivector/final.ie", "ivector/final.mat",
        "ivector/global_cmvn.stats", "ivector/online_cmvn.conf", "ivector/splice.conf",
    )

    /** Vosk 모델마다 더 있는 파일 */
    private val VOSK_EXTRA = mapOf("vosk-model-small-ko-0.22" to listOf("graph/phones.txt"))

    /**
     * 목적: STT 모델 1개의 자산 정의를 만든다.
     * 입력: m - 모델 정의(assetId 가 null 이 아니어야 한다)
     * 출력: 없음
     * 리턴: AssetSpec - 압축 안 경로는 "<자산 ID>/<파일>", 저장 경로는 "<파일>"
     */
    private fun sttAsset(m: ModelSpec): AssetSpec {
        val id = requireNotNull(m.assetId) { "${m.engine}: assetId 가 없다" }           // 자산 ID(= 패키지 폴더 이름)
        val isVosk = m.runtime == Runtime.VOSK                                          // Vosk 는 zip, 폴더 전체를 쓴다
        val names = if (isVosk) VOSK_FILES + (VOSK_EXTRA[id] ?: emptyList())
        else m.files.values.flatMap { f -> DIR_FILES[f]?.map { "$f/$it" } ?: listOf(f) }   // 폴더 역할은 안의 파일로 펼친다
        return AssetSpec(
            id = id,
            kind = AssetKind.STT_MODEL,
            url = if (isVosk) "$VOSK/$id.zip" else "$SHERPA_ASR/$id.tar.bz2",           // 원본 압축 파일 주소
            archive = if (isVosk) ArchiveType.ZIP else ArchiveType.TAR_BZ2,
            archiveBytes = BYTES[id],
            files = names.map { AssetFile(pathInArchive = "$id/$it", target = it) },
            license = m.license,
        )
    }

    /** VAD 자산 4개(주소·커밋 고정은 tools/download_models.sh 와 같다) */
    private val VAD_ASSETS = listOf(
        AssetSpec(
            id = "silero-vad", kind = AssetKind.VAD_MODEL,
            url = "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data",
            archive = ArchiveType.NONE, archiveBytes = 1_289_603L,
            files = listOf(AssetFile("silero_vad_16k_op15.onnx", "silero_vad_16k_op15.onnx")),
            license = VadEngine.SILERO.license,
        ),
        AssetSpec(   // sherpa-onnx 배포 tar.bz2 에서 fp32 model.onnx 와 LICENSE 만 꺼낸다
            id = "pyannote-seg3", kind = AssetKind.VAD_MODEL,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/" +
                "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            archive = ArchiveType.TAR_BZ2, archiveBytes = 6_958_444L,
            files = listOf(
                AssetFile("sherpa-onnx-pyannote-segmentation-3-0/model.onnx", "pyannote_seg3.onnx"),
                AssetFile("sherpa-onnx-pyannote-segmentation-3-0/LICENSE", "pyannote_seg3.LICENSE.txt"),
            ),
            license = VadEngine.PYANNOTE.license,
        ),
        AssetSpec(
            id = "fsmn-vad", kind = AssetKind.VAD_MODEL,
            url = "https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master",
            archive = ArchiveType.NONE, archiveBytes = 514_784L,
            files = listOf(AssetFile("model_quant.onnx", "fsmn_vad_quant.onnx"), AssetFile("am.mvn", "fsmn_vad_am.mvn")),
            license = VadEngine.FSMN.license,
        ),
        AssetSpec(
            id = "firered-vad", kind = AssetKind.VAD_MODEL,
            url = "https://raw.githubusercontent.com/FireRedTeam/FireRedVAD/c30ec49e8cc69642b0ee65362eba11b9d11c6e54/" +
                "pretrained_models/onnx_models",
            archive = ArchiveType.NONE, archiveBytes = 2_389_799L,
            files = listOf(AssetFile("fireredvad_vad.onnx", "fireredvad_vad.onnx"), AssetFile("cmvn.ark", "fireredvad_cmvn.ark")),
            license = VadEngine.FIRERED.license,
        ),
    )

    /**
     * 목적: 전체 자산 표를 만든다.
     * 입력: models - 모델 목록(자산이 있는 모델만 쓴다)
     * 출력: 없음
     * 리턴: Map<String, AssetSpec> - 자산 ID → 정의(STT 가 먼저, VAD 가 뒤)
     */
    fun all(models: List<ModelSpec>): Map<String, AssetSpec> =
        (models.filter { it.assetId != null }.map { sttAsset(it) } + VAD_ASSETS).associateBy { it.id }   // ID 로 찾게 맵으로

    /**
     * 목적: VAD 엔진이 필요로 하는 자산 ID 를 알려 준다.
     * 입력: engine - VAD 엔진
     * 출력: 없음
     * 리턴: String? - 자산 ID. NONE(VAD 안 씀)과 TEN(APK 에 포함)은 null
     */
    fun vadAssetId(engine: VadEngine): String? = when (engine) {
        VadEngine.SILERO -> "silero-vad"
        VadEngine.PYANNOTE -> "pyannote-seg3"
        VadEngine.FSMN -> "fsmn-vad"
        VadEngine.FIRERED -> "firered-vad"
        VadEngine.TEN, VadEngine.NONE -> null   // 내려받을 것이 없다
    }
}
