/*
 * 파일명: io/ResultStore.kt
 * 목적 및 기능:
 * - 출력 폴더(SAF)에 결과 폴더를 만들고 전사 TXT / result.csv / run_meta.csv 를 쓴다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 ensureRunDir / writeCsv / saveResult / ensureDir / writeBytes 를 내용 변경 없이 옮겼다.
 *   contentResolver 와 algoName("google") 은 생성자로 받는다.
 *
 * change-hyungchul-20261009-1530 — 결과 폴더를 PC "STT 통합 실행기" v7.7 평탄화 규칙으로 바꿨다(사용자 결정 Q10·Q13).
 *   예전: output/google/<모드>/[ns_…/][vad_…/][epd_…/]<하위>/<파일>.txt , …/result.csv , …/run_meta.csv
 *   change-hyungchul-20261010-1636 : 위 줄의 "ns_*" 뒤 "/" 가 블록 주석 끝 표시로 읽혀 주석이 일찍 닫히고
 *     ResultStore 클래스가 선언되지 않던 문제("Unresolved reference 'ResultStore'")를 고쳤다("*" → "…").
 *   지금: output/<엔진>_<모델>_<조건>/<하위>/<파일>.txt
 *         output/<엔진>_<조건>_result.csv     (CSV 는 결과 폴더 바로 아래 — PC 와 같음)
 *         output/<엔진>_<조건>_run_meta.csv   (앱 전용 실행 요약, 같은 위치)
 *   <엔진>/<모델> = ModelConfig.resultEngine / resultModel (예: google-mlkit / basic)
 *   <조건>       = [NS_…_]<VAD 프리셋>[_SEG10][_EPD_…]  (예: NOVAD, SILERO_SAFE, PYANNOTE_S500_SEG10)
 *
 * add-hyungchul-20261012-0310 — v2.6.0: saveResult 에 dirPrefix(기본 "") 추가. 목록 실행은 "_" 를 붙인 폴더에 쓰고 DONE 이면 원래 이름으로 바꾼다(Q48).
 */
package com.example.google_stt.io

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import com.example.google_stt.SttTelemetry
import com.example.google_stt.stt.ModelConfig   // change-hyungchul-20261009-1530
import java.io.IOException

/**
 * 목적: 결과 저장기.
 * 입력(생성자): contentResolver — 파일 쓰기에 쓸 ContentResolver
 *   (change-hyungchul-20261009-1530 : algoName 인자 제거 — 평탄화 규칙에서는 최상위 "google" 폴더를 만들지 않는다)
 */
class ResultStore(
    private val contentResolver: ContentResolver,
) {

    // change-hyungchul-20261009-1530 : 예전 ensureRunDir / writeCsv / saveResult(output/google/<모드>/<variant…>) 를
    //   v7.7 평탄화 규칙의 아래 함수들로 바꿨다(algo 값은 run_meta 에만 남는다).

    /**
     * 목적: 결과 폴더 이름 <엔진>_<모델>_<조건> 을 만든다.
     * 입력: model — STT 경로, condition — 조건 이름
     * 리턴: 폴더 이름
     */
    fun resultDirName(model: ModelConfig, condition: String): String =
        "${model.resultEngine}_${model.resultModel}_$condition"

    /**
     * 목적: CSV 이름 앞부분 <엔진>_<조건> 을 만든다(PC v7.7: "<엔진>_<조건>_result.csv").
     * 입력: model, condition
     * 리턴: 파일 이름 앞부분
     */
    private fun csvBase(model: ModelConfig, condition: String): String =
        "${model.resultEngine}_$condition"

    /**
     * 목적: 이번 실행의 결과 폴더(output/<엔진>_<모델>_<조건>)를 만든다.
     * 입력: outputRoot, model, condition
     * 리턴: DocumentFile
     */
    fun ensureRunDir(outputRoot: DocumentFile, model: ModelConfig, condition: String): DocumentFile =
        ensureDir(outputRoot, listOf(resultDirName(model, condition)))

    /**
     * 목적: result.csv 를 출력 폴더 바로 아래 <엔진>_<조건>_result.csv 로 쓴다(utf-8-sig).
     * 입력: outputRoot, model, condition, body(CSV 본문)
     * 리턴: 없음
     */
    fun writeCsv(outputRoot: DocumentFile, model: ModelConfig, condition: String, body: String) {
        writeBytes(
            outputRoot, csvBase(model, condition) + "_result.csv",
            SttTelemetry.BOM + body.toByteArray(Charsets.UTF_8), "text/csv",
        )
    }

    /**
     * 목적: run_meta.csv 를 출력 폴더 바로 아래 <엔진>_<조건>_run_meta.csv 로 쓴다(utf-8-sig).
     * 입력: outputRoot, model, condition, body
     * 리턴: 없음
     */
    fun writeRunMeta(outputRoot: DocumentFile, model: ModelConfig, condition: String, body: String) {
        writeBytes(
            outputRoot, csvBase(model, condition) + "_run_meta.csv",
            SttTelemetry.BOM + body.toByteArray(Charsets.UTF_8), "text/csv",
        )
    }

    /**
     * 목적: 전사 TXT 를 output/<엔진>_<모델>_<조건>/<입력 하위 폴더>/<파일명>.txt 로 쓴다.
     * 입력: outputRoot, model, condition, item(입력 파일), text(전사문),
     *       dirPrefix — 결과 폴더 이름 앞에 붙일 글자(add-hyungchul-20261012-0310 : 목록 실행 중 "_", START 는 "")
     * 리턴: 없음
     */
    fun saveResult(outputRoot: DocumentFile, model: ModelConfig, condition: String, item: AudioItem, text: String, dirPrefix: String = "") {
        val segments = mutableListOf(dirPrefix + resultDirName(model, condition))   // change-hyungchul-20261012-0310 : 접두어(기본 빈 문자열 = 예전과 같음)
        segments.addAll(item.relPath)
        val dir = ensureDir(outputRoot, segments)
        writeBytes(dir, item.nameNoExt + ".txt", text.toByteArray(Charsets.UTF_8))
    }

    fun ensureDir(root: DocumentFile, segments: List<String>): DocumentFile {
        var cur = root
        for (seg in segments) {
            val existing = cur.findFile(seg)
            cur = if (existing != null && existing.isDirectory) existing
            else cur.createDirectory(seg) ?: throw IOException("폴더 생성 실패: $seg")
        }
        return cur
    }

    fun writeBytes(
        dir: DocumentFile,
        fileName: String,
        bytes: ByteArray,
        mime: String = "text/plain",
    ) {
        dir.findFile(fileName)?.let { if (it.isFile) it.delete() }
        val file = dir.createFile(mime, fileName)
            ?: throw IOException("파일 생성 실패: $fileName")
        contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
            ?: throw IOException("파일 쓰기 실패: $fileName")
    }
}
