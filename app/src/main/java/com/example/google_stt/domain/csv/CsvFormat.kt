/*
 * 파일명: domain/csv/CsvFormat.kt
 * 목적 및 기능:
 * - CSV 열 정의(CsvColumn)와 글자 형식 규칙(CsvFormat)을 둔다.
 * - 열은 "(이름, 값 계산식)" 한 쌍으로만 정의한다. 머리줄과 값이 같은 목록에서 나오므로 열이 밀릴 수 없다
 *   (v3 는 SttRow 약 100개 변수 ↔ CSV_HEADER ↔ toCsvLine 순서를 사람이 맞춰 열 밀림 사고가 있었다 — 1단계 위험 8).
 * - 형식은 서버 stt_common.py(파이썬 csv 모듈, utf-8-sig)와 같게 맞춘다:
 *     · 파일 맨 앞 BOM 1회, 줄 끝 CRLF, 쉼표·큰따옴표·줄바꿈이 든 칸만 큰따옴표로 감싸고 안의 큰따옴표는 두 번 쓴다(RFC 4180)
 *     · 숫자는 소수 자릿수 고정(초 3자리, RTF 4자리 등), 값이 없으면 빈 칸
 *     · 전사문은 공백류를 공백 1개로 줄여 한 줄로(one_line), 글자 수·어절 수·SHA1 앞 12자(text_stats)
 *
 * add-hyungchul-20261106-0049 — [T2-7]
 */
package com.example.google_stt.domain.csv

import java.security.MessageDigest   // 전사문 SHA1
import java.util.Locale              // 숫자 형식 고정(소수점은 항상 '.')

/**
 * 목적: CSV 열 1개.
 * 필드: name - 머리줄에 쓰는 열 이름, value - 행 자료에서 칸 값을 만드는 계산식
 */
class CsvColumn<T>(val name: String, val value: (T) -> String)

/** 목적: CSV 글자 형식 규칙. */
object CsvFormat {

    /** UTF-8 BOM(utf-8-sig) — 엑셀에서 한글이 깨지지 않게 파일 맨 앞에 1회만 쓴다 */
    const val BOM = "﻿"

    /** 줄 끝(파이썬 csv 모듈 기본값과 같다) */
    const val EOL = "\r\n"

    private val WHITESPACE = Regex("\\s+")   // 공백류 연속(줄바꿈·탭 포함)

    /**
     * 목적: 칸 값 하나를 CSV 규칙에 맞게 만든다.
     * 입력: value - 원래 값
     * 출력: 없음
     * 리턴: String - 쉼표·큰따옴표·줄바꿈이 있으면 큰따옴표로 감싼 값, 없으면 그대로
     */
    fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\""
        else value                                                            // 감쌀 필요 없음

    /**
     * 목적: 칸 값들을 CSV 한 줄로 잇는다(줄 끝 포함).
     * 입력: values - 칸 값 목록 / 출력: 없음 / 리턴: String - "a,b,…" + CRLF
     */
    fun line(values: List<String>): String = values.joinToString(",") { quote(it) } + EOL

    /**
     * 목적: 열 정의와 행 자료로 CSV 파일 내용 전체를 만든다.
     * 입력: columns - 열 정의(이 순서가 곧 열 순서), rows - 행 자료
     * 출력: 없음
     * 리턴: String - BOM + 머리줄 + 행들. 매번 처음부터 만들므로 다시 써도 BOM 이 겹치지 않는다
     */
    fun <T> table(columns: List<CsvColumn<T>>, rows: List<T>): String {
        val sb = StringBuilder(BOM)                                           // 맨 앞 BOM
        sb.append(line(columns.map { it.name }))                              // 머리줄
        for (r in rows) sb.append(line(columns.map { it.value(r) }))          // 행마다 같은 열 목록으로 값 계산
        return sb.toString()
    }

    /**
     * 목적: (키, 값) 표를 key,value 두 열 CSV 로 만든다(run_meta 용).
     * 입력: rows - (키, 값) 목록 / 출력: 없음 / 리턴: String - BOM + "key,value" + 줄들
     */
    fun keyValue(rows: List<Pair<String, String>>): String {
        val sb = StringBuilder(BOM)
        sb.append(line(listOf("key", "value")))                               // 머리줄
        for ((k, v) in rows) sb.append(line(listOf(k, v)))
        return sb.toString()
    }

    /**
     * 목적: 숫자를 자릿수 고정 글자로 만든다(서버 _fmt 와 같다).
     * 입력: value - 값(null = 없음), digits - 소수 자릿수
     * 출력: 없음
     * 리턴: String - 예 "12.340". 값이 없거나 숫자가 아니면(NaN·무한대) 빈 글자
     */
    fun num(value: Double?, digits: Int): String =
        if (value == null || value.isNaN() || value.isInfinite()) "" else String.format(Locale.US, "%.${digits}f", value)

    /**
     * 목적: 나눗셈 결과(RTF 등)를 구한다.
     * 입력: numerator - 분자(null = 없음), denominator - 분모
     * 출력: 없음
     * 리턴: Double? - 분모가 0 이하이거나 분자가 없으면 null(빈 칸이 된다)
     */
    fun ratio(numerator: Double?, denominator: Double): Double? =
        if (numerator == null || denominator <= 0.0) null else numerator / denominator

    /**
     * 목적: 전사문을 CSV 한 칸에 넣을 한 줄로 정리한다(서버 one_line 과 같다).
     * 입력: text - 원래 전사문 / 출력: 없음 / 리턴: String - 공백류 연속을 공백 1개로 줄이고 앞뒤 공백을 뗀 글
     */
    fun oneLine(text: String): String = WHITESPACE.replace(text, " ").trim()

    /**
     * 목적: 전사문의 글자 수·어절 수·내용 해시를 구한다(서버 text_stats 와 같다).
     * 입력: text - 전사문
     * 출력: 없음
     * 리턴: Triple(글자 수, 어절 수, SHA1 앞 12자) - 빈 전사문이면 (0, 0, "")
     */
    fun textStats(text: String): Triple<Int, Int, String> {
        val cleaned = oneLine(text)                                           // 한 줄로 정리한 뒤 센다
        if (cleaned.isEmpty()) return Triple(0, 0, "")
        val chars = cleaned.codePointCount(0, cleaned.length)                 // 파이썬 len() 과 같게 코드 포인트 수
        val words = cleaned.split(' ').count { it.isNotEmpty() }              // 공백 기준 어절 수
        val sha1 = MessageDigest.getInstance("SHA-1").digest(cleaned.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)                  // 16진수 앞 12자
        return Triple(chars, words, sha1)
    }
}
