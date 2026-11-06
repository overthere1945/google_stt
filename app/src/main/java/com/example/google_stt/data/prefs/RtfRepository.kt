/*
 * 파일명: data/prefs/RtfRepository.kt
 * 목적 및 기능:
 * - 실측 RTF 를 기기·엔진·VAD 묶음·동시 작업 수별로 쌓아 두고 예상 소요 시간 계산에 쓴다(D11, FR-08-04 학습형).
 *     "rtf:<기기>|<엔진>|<VAD 묶음>|<동시 작업 수>" → 최근 20개 RTF 의 JSON 배열(중앙값을 쓴다 — domain/eta/RtfSamples)
 *     "cal:<기기>|<엔진>" → 빠른 측정 RTF(엔진별 짧은 파일 1개로 잰 값)
 * - 읽으면 domain/eta/RtfTable 이 된다. 다른 기기의 값도 같은 파일에 있을 수 있고, 섞지 않는 규칙은 RtfTable.lookup 이 지킨다.
 *
 * add-hyungchul-20261106-1645 — [T3-4]
 */
package com.example.google_stt.data.prefs

import androidx.datastore.core.DataStore                           // 저장소
import androidx.datastore.preferences.core.Preferences             // 키-값 묶음
import androidx.datastore.preferences.core.edit                    // 값 바꾸기
import androidx.datastore.preferences.core.stringPreferencesKey    // String 키
import com.example.google_stt.domain.eta.RtfKey                    // 실측 키
import com.example.google_stt.domain.eta.RtfSamples                // 최근 20개 규칙
import com.example.google_stt.domain.eta.RtfTable                  // RTF 표
import kotlinx.coroutines.flow.Flow                                // 값 흐름
import kotlinx.coroutines.flow.map                                 // 변환
import org.json.JSONArray                                          // 숫자 배열 글자
import org.json.JSONException                                      // 깨진 값

/**
 * 목적: 실측 RTF 저장소.
 * 입력(생성자): store - rtf 저장소
 */
class RtfRepository(private val store: DataStore<Preferences>) {

    private companion object {
        const val MEASURED = "rtf:"   // 실측 키 앞부분
        const val CALIB = "cal:"      // 빠른 측정 키 앞부분
        const val SEP = "|"           // 키 안의 구분 글자(기기 이름·엔진 이름에 쓰이지 않는다)
    }

    /** RTF 표의 흐름(저장될 때마다 새 표가 나온다) */
    val table: Flow<RtfTable> = store.data.map { p ->
        val measured = LinkedHashMap<RtfKey, List<Double>>()
        val calibration = LinkedHashMap<Pair<String, String>, Double>()
        for ((key, value) in p.asMap()) {
            val text = value as? String ?: continue                               // 글자 값만 쓴다
            val parts = key.name.substringAfter(':').split(SEP)
            if (key.name.startsWith(MEASURED) && parts.size == 4) {               // 기기|엔진|VAD 묶음|동시 작업 수
                val workers = parts[3].toIntOrNull() ?: continue
                measured[RtfKey(parts[0], parts[1], parts[2], workers)] = decode(text)
            } else if (key.name.startsWith(CALIB) && parts.size == 2) {           // 기기|엔진
                text.toDoubleOrNull()?.let { calibration[parts[0] to parts[1]] = it }
            }
        }
        RtfTable(measured, calibration)
    }

    /**
     * 목적: 실측 RTF 하나를 더한다(키마다 최근 20개만 남는다).
     * 입력: key - 조건, rtf - 작업 소요 / 음원 길이
     * 출력: 저장소 파일이 바뀐다
     * 리턴: 없음
     */
    suspend fun addMeasured(key: RtfKey, rtf: Double) {
        val name = stringPreferencesKey(MEASURED + listOf(key.deviceKey, key.engine, key.vadGroup, key.workers).joinToString(SEP))
        store.edit { p -> p[name] = encode(RtfSamples.add(decode(p[name] ?: "[]"), rtf)) }
    }

    /**
     * 목적: 빠른 측정 RTF 를 저장한다(같은 기기·엔진의 앞 값을 바꾼다).
     * 입력: deviceKey - 기기 식별, engine - 결과 엔진 이름, rtf - 잰 값 / 출력: 저장소 파일이 바뀐다 / 리턴: 없음
     */
    suspend fun setCalibration(deviceKey: String, engine: String, rtf: Double) {
        store.edit { it[stringPreferencesKey(CALIB + deviceKey + SEP + engine)] = rtf.toString() }
    }

    /**
     * 목적: 숫자 목록을 JSON 배열 글자로 만든다.
     * 입력: values - 숫자 목록 / 출력: 없음 / 리턴: String - 예 "[0.1,0.12]"
     */
    private fun encode(values: List<Double>): String = JSONArray(values).toString()

    /**
     * 목적: JSON 배열 글자를 숫자 목록으로 읽는다.
     * 입력: text - 저장된 글자 / 출력: 없음 / 리턴: List<Double> - 깨진 글자면 빈 목록(그 키는 처음부터 다시 쌓인다)
     */
    private fun decode(text: String): List<Double> =
        try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getDouble(it) }
        } catch (e: JSONException) {
            emptyList()                                                           // 깨진 값은 버린다(추정에만 쓰는 값이라 다시 쌓으면 된다)
        }
}
