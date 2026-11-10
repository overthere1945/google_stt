/*
 * 파일명: GoldenHeaderFileTest.kt (app/src/test)
 * 목적 및 기능:
 * - 단위 시험 기반(T0-4) 확인용 첫 시험. JVM 단위 시험이 저장소의 골든 파일을 읽을 수 있는지 확인한다.
 * - 골든 파일(docs/AI_analyze/golden/STT_CSV_HEADER_v2.0.txt)은 서버 통일 CSV v2.0 의 74열 이름 목록이며,
 *   T2-8 의 골든 헤더 시험(UT-CSV-01)이 같은 경로로 이 파일을 읽는다.
 *
 * add-hyungchul-20261105-1650
 */
package com.example.google_stt

import java.io.File                      // 골든 파일 읽기
import org.junit.Assert.assertEquals     // 값 비교
import org.junit.Assert.assertTrue       // 조건 확인
import org.junit.Test                    // JUnit 4 시험 표시

/** 목적: 골든 헤더 파일의 위치와 기본 형식(74열, 중복 없음)을 확인한다. */
class GoldenHeaderFileTest {

    /**
     * 목적: 골든 헤더 파일을 줄 단위로 읽는다(빈 줄 제외).
     * 입력: 없음(Gradle 단위 시험의 작업 폴더는 모듈 폴더 app/ 이다)
     * 출력: 없음
     * 리턴: List<String> - 열 이름 목록
     */
    private fun readGoldenHeader(): List<String> {
        val file = File("../docs/AI_analyze/golden/STT_CSV_HEADER_v2.0.txt")                 // app/ 기준 상대 경로
        assertTrue("골든 헤더 파일이 없다: ${file.absolutePath}", file.isFile)                 // 경로가 틀리면 여기서 알린다
        return file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }   // 공백·빈 줄 정리
    }

    /**
     * 목적: 골든 헤더가 74열인지 확인한다(서버 stt_common.STT_CSV_HEADER 와 같은 수, D10).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun goldenHeader_has74Columns() {
        val names = readGoldenHeader()                 // 열 이름 목록
        assertEquals(74, names.size)                   // 통일 CSV v2.0 은 74열
        assertEquals("run_id", names.first())          // 첫 열
        assertEquals("text", names.last())             // 마지막 열
    }

    /**
     * 목적: 골든 헤더에 같은 열 이름이 두 번 나오지 않는지 확인한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(중복이 있으면 시험 실패)
     */
    @Test
    fun goldenHeader_hasNoDuplicates() {
        val names = readGoldenHeader()                 // 열 이름 목록
        assertEquals(names.size, names.toSet().size)   // 집합으로 바꿔도 개수가 같아야 한다
    }
}
