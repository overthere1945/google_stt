/*
 * 파일명: engine/vad/VadDetectorFactory.kt
 * 목적 및 기능:
 * - VAD 엔진 종류로 실행기(VadDetector)를 만든다(작업 시작 때 1회 — 모델 적재 포함).
 * - 모델은 자산 폴더에서 읽는다(Silero·pyannote·FSMN·FireRed). TEN 은 APK 에 든 libten_vad.so 를 쓴다.
 * - 만들지 못하면 AppError.VadLoad(E-VAD-01) 하나로 알린다 → 시작 전 점검이 그 VAD 조합의 시작을 막는다(6단계 6-2).
 * - v3 의 VadDetectorFactory(vad/VadPipeline.kt 안)는 Context 로 APK assets 를 읽었다.
 *
 * add-hyungchul-20261106-2159 — [T5-6]
 */
package com.example.google_stt.engine.vad

import com.example.google_stt.domain.AppError            // E-VAD-01
import com.example.google_stt.domain.catalog.Catalog     // VAD 엔진 → 자산 ID
import com.example.google_stt.domain.vad.VadEngine       // VAD 엔진 종류
import java.io.File                                      // 자산 폴더

/** 목적: VAD 실행기 만들기. */
object VadDetectorFactory {

    /**
     * 목적: VAD 실행기를 만든다.
     * 입력: engine - VAD 엔진, assetDir - 자산 ID 를 주면 그 자산의 폴더를 주는 함수(AssetStore.dir)
     * 출력: 모델이 메모리에 올라간다
     * 리턴: VadDetector? - VAD 를 쓰지 않으면(NONE) null
     * 예외: AppError.VadLoad(E-VAD-01) - 모델 파일이 없거나 실행기를 만들 수 없을 때(TEN 의 .so 적재 실패 포함)
     */
    fun create(engine: VadEngine, assetDir: (String) -> File): VadDetector? {
        if (engine == VadEngine.NONE) return null                                  // VAD 를 쓰지 않는다
        val dir = Catalog.vadAssetId(engine)?.let(assetDir)                        // TEN 은 자산이 없다(null)
        return try {
            when (engine) {
                VadEngine.SILERO -> SileroVadDetector(dir!!)
                VadEngine.PYANNOTE -> PyannoteVadDetector(dir!!)
                VadEngine.FSMN -> FsmnVadDetector(dir!!)
                VadEngine.FIRERED -> FireRedVadDetector(dir!!)
                VadEngine.TEN -> TenVadDetector()
                VadEngine.NONE -> null
            }
        } catch (e: AppError) {
            throw e                                                                // 이미 분류된 오류(파일 없음)
        } catch (e: Exception) {
            throw AppError.VadLoad("${engine.name} VAD 를 만들 수 없다 — ${e.message}", e)   // ONNX 세션 오류, 모델 규격 불일치 등
        } catch (e: LinkageError) {
            throw AppError.VadLoad("${engine.name} VAD 의 네이티브 라이브러리를 불러올 수 없다 — ${e.message}", e)
        }
    }
}
