/*
 * 파일명: ui/NoiseUi.kt
 * 목적 및 기능:
 * - 화면의 노이즈 저감 영역(알고리즘 스피너 / 옵션 입력칸 / 안내 문구)을 초기화하고 연동한다.
 * - START 를 누른 순간의 노이즈 저감 설정을 검증해 NoiseReduceConfig 로 만든다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 노이즈 저감 UI 멤버 변수와 setupNoiseUi / applyNoisePreset /
 *   warnIfNoiseModelMissing / readNoiseConfig, companion 의 DPDFNET_* 상수를 내용 변경 없이 옮겼다.
 *   (fillNoiseConfig 는 batch/BatchRunner.kt 로 옮겼다)
 */
package com.example.google_stt.ui

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.google_stt.R
import com.example.google_stt.denoise.NoiseAlgorithm
import com.example.google_stt.denoise.NoiseAlgorithmKind
import com.example.google_stt.denoise.NoiseReduceConfig
import java.util.Locale

/**
 * 목적: 노이즈 저감 화면 컨트롤러.
 * 입력(생성자): activity — View 를 찾고 Adapter 를 만들 Activity, log — 화면 로그 함수, toast — 토스트 함수
 */
class NoiseUi(
    private val activity: AppCompatActivity,
    private val log: (String) -> Unit,
    private val toast: (String) -> Unit,
) {

    // add-hyungchul-20260826-1100 : Noise Reduction UI
    private val nsAlgoSpinner: Spinner = activity.findViewById(R.id.nsAlgoSpinner)
    private val nsOptionsGroup: LinearLayout = activity.findViewById(R.id.nsOptionsGroup)
    private val nsVariantSpinner: Spinner = activity.findViewById(R.id.nsVariantSpinner)
    private val nsAttenLimitEdit: EditText = activity.findViewById(R.id.nsAttenLimitEdit)
    private val nsThreadsEdit: EditText = activity.findViewById(R.id.nsThreadsEdit)
    private val nsMinGainEdit: EditText = activity.findViewById(R.id.nsMinGainEdit)
    private val nsAlphaEdit: EditText = activity.findViewById(R.id.nsAlphaEdit)
    private val nsBetaEdit: EditText = activity.findViewById(R.id.nsBetaEdit)
    private val nsQuantileEdit: EditText = activity.findViewById(R.id.nsQuantileEdit)
    private val nsHint: TextView = activity.findViewById(R.id.nsHint)

    /** 알고리즘 preset 을 코드로 채우는 동안의 재진입 가드 */
    private var applyingNsPreset = false

    // ──────────── add-hyungchul-20260826-1100 : Noise Reduction UI ────────────
    /**
     * 목적: 노이즈 저감 알고리즘 스피너와 옵션 입력창을 초기화한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     * 동작:
     *  - 알고리즘을 바꾸면 그 알고리즘의 권장값으로 옵션을 다시 채우고,
     *    쓰지 않는 옵션은 비활성화한다(무슨 값을 넣어도 결과가 안 바뀌는 칸을 만지지 않도록).
     *  - ONNX 계열을 고르면 assets 에 모델이 있는지 즉시 확인해 알려 준다.
     *  - 파일 입력에 적용 불가한 항목을 고르면 사유를 바로 로그에 띄운다.
     */
    fun setupNoiseUi() {
        val algos = NoiseAlgorithm.entries   // change-hyungchul-20260828-1000
        nsAlgoSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            algos.map { it.displayName },
        )
        nsVariantSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            DPDFNET_VARIANT_LABELS,
        )

        applyingNsPreset = true
        nsAlgoSpinner.setSelection(algos.indexOf(NoiseAlgorithm.NONE))   // 기본은 기존 baseline
        applyNoisePreset(NoiseAlgorithm.NONE)
        applyingNsPreset = false

        nsAlgoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingNsPreset) return
                val algo = algos[position.coerceIn(0, algos.lastIndex)]
                applyNoisePreset(algo)
                when {
                    !algo.runnable -> {
                        log("[ns][사용 불가] ${algo.displayName}")
                        log("  → ${algo.unsupportedReason}")
                        toast("이 알고리즘은 파일 입력에 적용할 수 없습니다. 로그를 확인하세요.")
                    }

                    algo.kind == NoiseAlgorithmKind.ONNX -> warnIfNoiseModelMissing(algo)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * 목적: 알고리즘을 고르면 권장 초기값을 채우고, 쓰지 않는 옵션은 비활성화한다.
     * 입력: algo
     * 출력: 없음 (입력창 값/활성 상태와 안내 문구를 바꾼다)
     * 리턴: 없음
     */
    private fun applyNoisePreset(algo: NoiseAlgorithm) {
        val cfg = NoiseReduceConfig.defaults(algo)
        applyingNsPreset = true
        try {
            nsAttenLimitEdit.setText(String.format(Locale.US, "%.0f", cfg.attenuationLimitDb))
            nsThreadsEdit.setText(cfg.numThreads.toString())
            nsMinGainEdit.setText(String.format(Locale.US, "%.0f", cfg.minGainDb))
            nsAlphaEdit.setText(
                if (algo == NoiseAlgorithm.SPECTRAL_SUB) {
                    String.format(Locale.US, "%.2f", cfg.overSubtraction)
                } else {
                    String.format(Locale.US, "%.2f", cfg.ddAlpha)
                },
            )
            nsBetaEdit.setText(String.format(Locale.US, "%.3f", cfg.spectralFloor))
            nsQuantileEdit.setText(cfg.noiseQuantilePct.toString())
            nsVariantSpinner.setSelection(
                DPDFNET_VARIANTS.indexOf(cfg.dpdfnetVariant).coerceAtLeast(0),
            )
        } finally {
            applyingNsPreset = false
        }

        // 알고리즘별로 실제 쓰이는 옵션만 켠다.
        val onnx = algo.kind == NoiseAlgorithmKind.ONNX
        val dsp = algo.kind == NoiseAlgorithmKind.DSP
        val active = onnx || dsp
        setGroupEnabled(nsOptionsGroup, active)
        nsVariantSpinner.isEnabled = algo == NoiseAlgorithm.DPDFNET
        nsThreadsEdit.isEnabled = onnx
        nsAttenLimitEdit.isEnabled = active
        nsMinGainEdit.isEnabled = dsp || algo == NoiseAlgorithm.NSNET2
        nsAlphaEdit.isEnabled = dsp
        nsBetaEdit.isEnabled = algo == NoiseAlgorithm.SPECTRAL_SUB
        nsQuantileEdit.isEnabled = dsp

        nsHint.text = when (algo.kind) {
            NoiseAlgorithmKind.NONE ->
                "전처리를 하지 않는다. PCM 을 1 byte 도 바꾸지 않고 그대로 STT 에 넣는다(기존 baseline)."

            NoiseAlgorithmKind.UNSUPPORTED ->
                "이 알고리즘은 이 앱의 파일 입력 방식에 적용할 수 없다.\n${algo.unsupportedReason}"

            NoiseAlgorithmKind.ONNX ->
                "assets/${algo.modelAsset} 가 필요하다. Attenuation Limit 은 '최대 몇 dB 까지만 줄일지'를 " +
                    "정해 과도한 제거로 WER 이 나빠지는 것을 막는 안전장치다(0 = 무제한)."

            NoiseAlgorithmKind.DSP ->
                "모델 파일이 필요 없다. 잡음 스펙트럼은 파일 전체에서 가장 조용한 하위 N% 프레임으로 추정한다. " +
                    "비정상 소음(카페·음악)에는 딥러닝 계열보다 약하지만, 다운로드 없이 바로 비교할 수 있다."
        }
    }

    /**
     * 목적: ONNX 계열을 고른 즉시 assets 에 모델이 있는지 확인해 알린다.
     * 입력: algo
     * 리턴: 파일이 있으면 true
     * 비고: 여기서 막지는 않는다. 실제 차단은 NoiseReducerFactory 생성 시점의 예외로 처리한다.
     */
    private fun warnIfNoiseModelMissing(algo: NoiseAlgorithm): Boolean {
        val asset = algo.modelAsset ?: return true
        val exists = runCatching { activity.assets.list("")?.contains(asset) == true }.getOrDefault(false)
        if (!exists) {
            val msg = "assets/$asset 이 없습니다. tools/download_models.sh 를 실행한 뒤 다시 빌드하세요."
            log("[ns][경고] $msg")
            toast(msg)
        }
        return exists
    }

    /**
     * 목적: START 를 누른 순간의 노이즈 저감 설정을 읽어 검증한다.
     * 입력: 없음 / 출력: 없음
     * 리턴: NoiseReduceConfig
     * 예외: 값이 잘못됐거나 적용 불가 알고리즘이면 IllegalArgumentException
     */
    fun readNoiseConfig(): NoiseReduceConfig {
        val algos = NoiseAlgorithm.entries   // change-hyungchul-20260828-1000
        val algo = algos[nsAlgoSpinner.selectedItemPosition.coerceIn(0, algos.lastIndex)]
        if (algo == NoiseAlgorithm.NONE) return NoiseReduceConfig(NoiseAlgorithm.NONE)
        if (!algo.runnable) {
            throw IllegalArgumentException(
                "${algo.displayName}\n${algo.unsupportedReason}",
            )
        }

        fun num(edit: EditText, name: String): Double =
            edit.text.toString().trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("$name 값을 확인하세요.")

        val variantIndex = nsVariantSpinner.selectedItemPosition.coerceIn(0, DPDFNET_VARIANTS.lastIndex)
        return NoiseReduceConfig(
            algorithm = algo,
            attenuationLimitDb = num(nsAttenLimitEdit, "Attenuation Limit"),
            numThreads = num(nsThreadsEdit, "Threads").toInt(),
            dpdfnetVariant = DPDFNET_VARIANTS[variantIndex],
            minGainDb = num(nsMinGainEdit, "Min Gain"),
            ddAlpha = if (algo == NoiseAlgorithm.SPECTRAL_SUB) 0.98 else num(nsAlphaEdit, "alpha"),
            overSubtraction = if (algo == NoiseAlgorithm.SPECTRAL_SUB) num(nsAlphaEdit, "alpha") else 4.0,
            spectralFloor = num(nsBetaEdit, "beta"),
            noiseQuantilePct = num(nsQuantileEdit, "Noise Quantile").toInt(),
        )
    }

    private companion object {
        // add-hyungchul-20260826-1100
        /** DPDFNet 변형 모델 키. assets 파일명은 baseline→dpdfnet_baseline.onnx, 2→dpdfnet2.onnx 이다. */
        private val DPDFNET_VARIANTS = listOf("baseline", "2", "4", "8")

        /** 스피너에 보여줄 이름(파일 크기를 같이 적어 고르기 쉽게 한다). */
        private val DPDFNET_VARIANT_LABELS = listOf(
            "dpdfnet_baseline.onnx  (8.4 MB, 가장 빠름)",
            "dpdfnet2.onnx  (10.2 MB)",
            "dpdfnet4.onnx  (11.7 MB)",
            "dpdfnet8.onnx  (14.6 MB, 가장 무거움)",
        )
    }
}
