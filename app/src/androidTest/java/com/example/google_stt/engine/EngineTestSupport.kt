/*
 * 파일명: engine/EngineTestSupport.kt (app/src/androidTest — 계측 시험 공용 도우미)
 * 목적 및 기능:
 * - 엔진·VAD 계측 시험이 같이 쓰는 준비 작업을 모은다.
 *     · 자산 준비: 앱 전용 저장소에 없으면, 앱이 권한을 보존한 폴더 아래 "stt_assets/<자산 ID>" 에서 가져온다(D13 의 B 방법).
 *       PC 에서 한 번 옮겨 두면 된다:  adb push stt_models/stt_assets/<자산 ID> /sdcard/Documents/<권한을 준 폴더>/stt_assets/
 *     · 시험 음원: 시험 APK 의 assets/audio/<언어>/…/01.wav 를 16 kHz PCM 으로 디코딩한다(음원은 저장소에 넣지 않는다 — D31)
 *     · 기준 전사문: assets/audio/expected/<언어>/<결과 폴더 이름>/01.txt (docs/baseline_v3 의 사본, 저장소에 넣지 않는다)
 *   준비물이 없으면 시험을 건너뛴다(실패가 아니다).
 *
 * add-hyungchul-20261106-2136 — [T5-2]
 */
package com.example.google_stt.engine

import android.net.Uri                                             // 음원 파일 주소
import androidx.documentfile.provider.DocumentFile                 // 가져올 폴더
import androidx.test.platform.app.InstrumentationRegistry          // 앱·시험 APK 의 Context
import com.example.google_stt.SttApp                               // 객체 묶음
import com.example.google_stt.data.assets.AssetImporter            // 폴더에서 가져오기
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.catalog.ModelSpec             // 모델 정의
import com.example.google_stt.domain.vad.SegmentPacker             // 조각 묶기
import com.example.google_stt.engine.audio.AudioDecoder            // 디코딩
import com.example.google_stt.engine.stt.AudioChunk                // STT 조각
import java.io.File                                                // 임시 파일·자산 폴더
import java.io.IOException                                         // 자산 없음
import kotlinx.coroutines.runBlocking                              // suspend 함수 부르기
import org.junit.Assume.assumeTrue                                 // 준비물이 없으면 건너뛰기

/** 목적: 엔진 계측 시험 도우미. */
object EngineTestSupport {

    private val inst = InstrumentationRegistry.getInstrumentation()
    val context = inst.targetContext!!                                                  // 시험 대상 앱의 Context
    val container get() = (context.applicationContext as SttApp).container             // 앱과 같은 객체 묶음

    /** 언어별 시험 음원(시험 APK assets 기준 경로) */
    private val AUDIO = mapOf(Language.KO to "audio/kr/Customer_Service_KR/01.wav", Language.EN to "audio/en/Customer_Service_EN/01.wav")

    /**
     * 목적: 자산을 준비하고 그 폴더를 준다(없으면 가져오고, 가져올 곳도 없으면 시험을 건너뛴다).
     * 입력: id - 자산 ID / 출력: 앱 전용 저장소에 자산이 준비될 수 있다 / 리턴: File - 자산 폴더
     */
    fun assetDir(id: String): File {
        val spec = container.assetSpecs.getValue(id)
        val store = container.assetStore
        if (!store.isReady(spec)) {
            val src = context.contentResolver.persistedUriPermissions
                .mapNotNull { DocumentFile.fromTreeUri(context, it.uri)?.findFile("stt_assets")?.findFile(id) }.firstOrNull { it.isDirectory }
            assumeTrue("자산 $id 가 준비되지 않았고 가져올 폴더(stt_assets/$id)도 없어 건너뜀", src != null)
            runBlocking { AssetImporter(context.contentResolver, store).import(spec, src!!) }   // 복사 + SHA256 확인
        }
        return store.dir(id)
    }

    /** 목적: 모델의 자산 폴더를 준다. 입력: spec - 모델 정의 / 출력: assetDir 와 같다 / 리턴: File */
    fun modelDir(spec: ModelSpec): File = assetDir(spec.assetId!!)

    /**
     * 목적: 시험 음원을 16 kHz / mono / PCM16 LE 로 디코딩한다.
     * 입력: lang - 언어 / 출력: 앱 캐시에 임시 파일 / 리턴: ByteArray(음원이 없으면 시험을 건너뛴다)
     */
    fun pcm(lang: Language): ByteArray {
        val bytes = testAsset(AUDIO.getValue(lang))
        assumeTrue("시험 음원 ${AUDIO.getValue(lang)} 이 없어 건너뜀(app/src/androidTest/assets/audio/ 에 둔다)", bytes != null)
        val file = File(context.cacheDir, "engine_test_${lang.code}.wav").apply { writeBytes(bytes!!) }
        return AudioDecoder.decodeDetailed(context, Uri.fromFile(file)).pcm
    }

    /**
     * 목적: v3 기준 전사문을 읽는다.
     * 입력: lang - 언어, resultDir - 결과 폴더 이름(예 "whisper-base_sherpa-int8_NOVAD")
     * 출력: 없음 / 리턴: ByteArray? - 기준이 없으면 null
     */
    fun expected(lang: Language, resultDir: String): ByteArray? =
        testAsset("audio/expected/${if (lang == Language.KO) "kr" else "en"}/$resultDir/01.txt")

    /**
     * 목적: VAD 를 쓰지 않을 때의 STT 조각을 만든다(v3 BatchRunner 와 같은 규칙).
     * 입력: pcm - 파일 전체, maxSec - 모델의 조각 상한(스트리밍·Vosk 는 null = 파일 전체 1개)
     * 출력: 없음 / 리턴: List<AudioChunk>
     */
    fun chunks(pcm: ByteArray, maxSec: Int?): List<AudioChunk> =
        if (maxSec == null) listOf(AudioChunk(pcm))
        else SegmentPacker.pack(pcm, null, maxSec.toDouble(), 200).chunks.map { AudioChunk(it) }

    /** 목적: 시험 APK 의 자산을 읽는다. 입력: path - assets 기준 경로 / 출력: 없음 / 리턴: ByteArray? - 없으면 null */
    private fun testAsset(path: String): ByteArray? =
        try { inst.context.assets.open(path).use { it.readBytes() } } catch (e: IOException) { null }
}
