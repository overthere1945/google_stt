/*
 * 파일명: domain/csv/CsvFormatTest.kt (app/src/test)
 * 목적 및 기능:
 * - CSV 글자 형식 규칙을 확인한다(서버 파이썬 csv·stt_common 과 같은 결과).
 *     · UT-CSV-03 따옴표(쉼표·큰따옴표·줄바꿈), 전사문 한 줄 정리
 *     · UT-CSV-04 BOM 은 파일 앞 1회(다시 만들어도 겹치지 않음)
 *     · UT-CSV-05 상태 값은 대문자 3종
 * - 숫자 자릿수, 나눗셈(분모 0), 전사문 통계(글자 수·어절 수·SHA1)를 확인한다.
 *
 * add-hyungchul-20261106-0049 — [T2-7]
 */
package com.example.google_stt.domain.csv

import org.junit.Assert.assertEquals   // 값 비교
import org.junit.Assert.assertNull     // null 확인
import org.junit.Test                  // JUnit 4 시험 표시

/** 목적: CSV 형식 시험. */
class CsvFormatTest {

    /**
     * 목적: [UT-CSV-03] 쉼표·큰따옴표·줄바꿈이 든 칸만 큰따옴표로 감싸고, 안의 큰따옴표는 두 번 쓴다(RFC 4180).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun quote_followsRfc4180() {
        assertEquals("plain", CsvFormat.quote("plain"))
        assertEquals("", CsvFormat.quote(""))
        assertEquals("\"a,b\"", CsvFormat.quote("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvFormat.quote("say \"hi\""))
        assertEquals("\"line1\nline2\"", CsvFormat.quote("line1\nline2"))
        assertEquals("\"cr\rhere\"", CsvFormat.quote("cr\rhere"))
        assertEquals("안녕 하세요", CsvFormat.quote("안녕 하세요"))                 // 공백·한글은 감싸지 않는다
        assertEquals("a,\"b,c\",\"d\"\"e\"\r\n", CsvFormat.line(listOf("a", "b,c", "d\"e")))   // 줄 끝 CRLF
    }

    /**
     * 목적: [UT-CSV-04] 표는 BOM 1회 + 머리줄 + 행이고, 같은 자료로 다시 만들어도 내용이 같다(BOM 이 겹치지 않는다).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun table_hasSingleBomAndHeader() {
        val cols = listOf(CsvColumn<Pair<String, Int>>("name") { it.first }, CsvColumn("n") { it.second.toString() })
        val rows = listOf("a,b" to 1, "c" to 2)
        val csv = CsvFormat.table(cols, rows)
        assertEquals("\uFEFFname,n\r\n\"a,b\",1\r\nc,2\r\n", csv)
        assertEquals(1, csv.count { it == '\uFEFF' })                          // BOM 은 한 번
        assertEquals(csv, CsvFormat.table(cols, rows))                         // 다시 만들어도 같다
        assertEquals("\uFEFFname,n\r\n", CsvFormat.table(cols, emptyList()))   // 행이 없어도 머리줄은 있다
        assertEquals("\uFEFFkey,value\r\napp_version,4.0.0\r\nnote,\"a,b\"\r\n",
            CsvFormat.keyValue(listOf("app_version" to "4.0.0", "note" to "a,b")))
    }

    /**
     * 목적: [UT-CSV-05] 상태 값은 OK / NO_MATCH / ERROR 대문자 3종뿐이다(서버 normalize_status 결과와 같은 글자).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun status_isUppercaseThree() {
        assertEquals(listOf("OK", "NO_MATCH", "ERROR"), SttStatus.values().map { it.name })
    }

    /**
     * 목적: 숫자는 자릿수 고정, 값이 없으면 빈 칸. 나눗셈은 분모가 0 이하면 값 없음.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun numbers_fixedDigitsOrBlank() {
        assertEquals("84.130", CsvFormat.num(84.13, 3))
        assertEquals("0.0240", CsvFormat.num(0.024, 4))
        assertEquals("12.3", CsvFormat.num(12.34, 1))
        assertEquals("0.000", CsvFormat.num(0.0, 3))                           // 0 은 값이다(빈 칸이 아니다)
        assertEquals("", CsvFormat.num(null, 3))
        assertEquals("", CsvFormat.num(Double.NaN, 3))
        assertEquals(0.5, CsvFormat.ratio(42.0, 84.0)!!, 1e-9)
        assertNull(CsvFormat.ratio(42.0, 0.0))                                 // 음원 길이 0 → RTF 없음
        assertNull(CsvFormat.ratio(null, 84.0))                                // 시간 값 없음
    }

    /**
     * 목적: [UT-CSV-03] 전사문은 공백류 연속을 공백 1개로 줄여 한 줄로 만들고, 통계는 그 한 줄에서 센다.
     * 비고: SHA1 기대값은 알려진 값 — "abc" 의 SHA1 은 a9993e364706816aba3e25717850c26c9cd0d89d.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun text_oneLineAndStats() {
        assertEquals("안녕하세요 반갑습니다 끝", CsvFormat.oneLine("  안녕하세요\n반갑습니다\t\t끝 \r\n"))
        assertEquals("", CsvFormat.oneLine(" \n "))
        assertEquals(Triple(0, 0, ""), CsvFormat.textStats(""))
        assertEquals(Triple(0, 0, ""), CsvFormat.textStats("  \n"))
        assertEquals(Triple(3, 1, "a9993e364706"), CsvFormat.textStats("abc"))
        assertEquals(Triple(3, 1, "a9993e364706"), CsvFormat.textStats(" abc\n"))   // 정리한 뒤 센다
        val (chars, words, sha) = CsvFormat.textStats("안녕하세요\n반갑습니다  끝")
        assertEquals(13, chars)                                                // "안녕하세요 반갑습니다 끝" = 5 + 1 + 5 + 1 + 1
        assertEquals(3, words)
        assertEquals("bc638079b461", sha)                                      // 서버와 같은 식(파이썬 hashlib)으로 구한 값
        assertEquals(1, CsvFormat.textStats("😀").first)                        // 코드 포인트 수(파이썬 len 과 같다)
    }
}
