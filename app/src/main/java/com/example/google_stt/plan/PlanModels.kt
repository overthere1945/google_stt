/*
 * 파일명: plan/PlanModels.kt
 * 목적 및 기능:
 * - "목록으로 줄 세워 한 번에 실행"(v2.5.0)의 순수 Kotlin 부분 — Android 를 쓰지 않아 PC(JVM)에서 바로 시험할 수 있다.
 * - 목록 CSV(Documents/stt_plan 폴더의 .csv) 읽기·쓰기: 머리줄 lang,model,feed,vad,seg / 칸 안 ';' 로 여러 값 → 조합 펼침 / '#' 주석
 * - 작업 검증: 언어(kr·en), 모델 이름, 언어 전용 모델, Google 엔진 제외, 공급 속도, VAD 프리셋 이름, 구간 처리
 * - 진행 기록(_plan_progress.csv) 읽기·쓰기: 작업이 끝날 때마다 한 줄, 이어 하기 때 DONE 만 건너뛴다
 *
 * 결정(2026-10-11): Q34 목록 형식 C안(화면 추가 + 같은 CSV), Q39 중지 선택 창, Q40 TEN·SEG10 제외 견본,
 *   Q42 동시 작업 3개(오프라인은 1개만), 이번 목록 실행에서 Google 엔진 제외, 노이즈 저감 없음, 공급 속도 REALTIME.
 *
 * add-hyungchul-20261011-2000 — v2.5.0 목록 실행
 * add-hyungchul-20261012-0310 — v2.6.0: 동시 작업 수 선택(Q47)·목록 사본/메모리/종료 기록 파일 이름·작업 중 폴더 접두어(Q48) 상수 추가
 */
package com.example.google_stt.plan

/** 목록 CSV 머리줄(열 순서 고정) */
const val PLAN_HEADER = "lang,model,feed,vad,seg"

/** 동시 작업(work) 수 — 사용자 결정 Q42 / change-hyungchul-20261012-0310 : v2.6.0 부터는 화면에서 고르는 값의 기본값(Q47) */
const val PLAN_WORKERS = 3

/** add-hyungchul-20261012-0310 : 고를 수 있는 동시 작업 수의 최댓값(1·2·3) */
const val PLAN_WORKERS_MAX = 3

/** 오프라인(sherpa OfflineRecognizer) 작업의 동시 상한 — CPU 4스레드·메모리를 많이 써서 1개만 */
const val PLAN_OFFLINE_MAX = 1

/** 진행 기록 파일 이름(출력 폴더 바로 아래) */
const val PLAN_PROGRESS_FILE = "_plan_progress.csv"

/** add-hyungchul-20261012-0310 : [목록 실행] 때 자동 저장하는 목록 사본(출력 폴더 바로 아래, Q45) — 이어 하기 창이 읽는다 */
const val PLAN_FILE = "_plan.csv"

/** add-hyungchul-20261012-0310 : 실행 중 메모리 기록(30초마다 한 줄, Q46) */
const val PLAN_MEMLOG_FILE = "_plan_memlog.csv"

/** add-hyungchul-20261012-0310 : 앱이 꺼진 이유 기록(다음 실행 때 한 줄, Q46) */
const val PLAN_EXITS_FILE = "_plan_exits.csv"

/** add-hyungchul-20261012-0310 : 메모리 기록 간격(ms) */
const val PLAN_MEMLOG_INTERVAL_MS = 30_000L

/**
 * add-hyungchul-20261012-0310 : 작업 중인 결과 폴더 앞에 붙이는 글자(Q48).
 * PC 평가기는 '_' 로 시작하는 폴더를 건너뛴다(평가기 v1.1.0 규칙) → 끝나지 않은 결과가 채점에 섞이지 않는다. DONE 이면 원래 이름으로 바꾼다.
 */
const val RUN_DIR_PREFIX = "_"

/** 구간 처리 토큰: 엔진별 최대 길이 */
const val SEG_MAX = "MAX"

/** 구간 처리 토큰: 10초 통일 */
const val SEG_10 = "SEG10"

/**
 * 목적: 펼쳐진 작업 1개(목록 CSV 의 조합 하나).
 * 필드: lang — kr / en (입력·출력 하위 폴더 이름과 같다), model — 모델 폴더 이름(SttCatalog.models 의 folder),
 *       feed — 공급 속도 이름(REALTIME …), vad — VAD 프리셋 이름(NOVAD / SILERO_SAFE …), seg — MAX / SEG10,
 *       lineNo — 이 작업이 나온 CSV 줄 번호(화면 추가는 0)
 */
data class PlanJob(
    val lang: String,          // 언어 = 하위 폴더 이름
    val model: String,         // 모델 폴더 이름
    val feed: String,          // 공급 속도 이름
    val vad: String,           // VAD 프리셋 이름
    val seg: String,           // 구간 처리 토큰
    val lineNo: Int = 0,       // CSV 줄 번호(0 = 화면에서 추가)
) {
    /** 결과 조건 이름 — 노이즈 저감 없음·EPD 없음(앱 엔진)이므로 VAD 프리셋 [+ _SEG10] 만 남는다(START 와 같은 규칙) */
    val condition: String get() = if (seg == SEG_10) "${vad}_$SEG_10" else vad

    /** CSV 한 줄(펼친 형태) — 저장·중복 판정에 쓴다 */
    val csvLine: String get() = "$lang,$model,$feed,$vad,$seg"

    /** 로그용 짧은 이름: kr/whisper-base/REALTIME/SILERO_SAFE/MAX */
    val label: String get() = "$lang/$model/$feed/$vad/$seg"
}

/**
 * 목적: 목록 CSV 를 읽은 결과.
 * 필드: jobs — 펼친 작업(중복 제거, 처음 나온 순서), errors — 줄 번호가 붙은 오류 문장, duplicates — 지운 중복 수
 */
data class PlanParse(
    val jobs: List<PlanJob>,   // 펼친 작업
    val errors: List<String>,  // 형식 오류
    val duplicates: Int,       // 지운 중복 수
)

/** 목적: 목록 CSV 읽기·쓰기 */
object PlanCsv {

    /**
     * 목적: 목록 CSV 글을 읽어 작업으로 펼친다.
     * 입력: text — 파일 내용(UTF-8, BOM 있어도 됨)
     * 출력: 없음
     * 리턴: PlanParse(jobs, errors, duplicates)
     * 규칙: 빈 줄·'#' 줄은 건너뜀 / 첫 내용 줄은 머리줄(PLAN_HEADER, 대소문자·공백 무시) /
     *       한 줄 = 칸 5개, 칸 안 ';' 로 여러 값 → lang × model × feed × vad × seg 순서로 펼침
     */
    fun parse(text: String): PlanParse {
        val jobs = LinkedHashMap<String, PlanJob>()          // csvLine → 작업(처음 나온 것만 남긴다)
        val errors = mutableListOf<String>()                 // 오류 문장
        var duplicates = 0                                   // 중복 수
        var headerSeen = false                               // 머리줄을 읽었는지
        val lines = text.removePrefix("﻿").split("\n")  // BOM 제거 후 줄로 나눈다
        for ((i, raw) in lines.withIndex()) {
            val lineNo = i + 1                               // 사람이 보는 줄 번호(1부터)
            val line = raw.trim().removeSuffix("\r").trim()  // CRLF·앞뒤 공백 제거
            if (line.isEmpty() || line.startsWith("#")) continue   // 빈 줄·주석
            if (!headerSeen) {                               // 첫 내용 줄 = 머리줄
                if (line.replace(" ", "").lowercase() != PLAN_HEADER) {
                    errors += "${lineNo}행: 머리줄이 '$PLAN_HEADER' 가 아니다 — '$line'"
                    return PlanParse(emptyList(), errors, 0) // 머리줄이 틀리면 열 뜻을 알 수 없으므로 멈춘다
                }
                headerSeen = true
                continue
            }
            val cells = line.split(",")                      // 칸 나누기(값 안에 쉼표는 쓰지 않는다)
            if (cells.size != 5) {
                errors += "${lineNo}행: 칸이 5개가 아니다(${cells.size}개) — '$line'"
                continue
            }
            // 칸마다 ';' 로 나누고 공백·빈 값 제거. 열마다 표기를 맞춘다(lang 소문자, feed·vad·seg 대문자, model 그대로)
            val values = cells.map { c -> c.split(";").map { it.trim() }.filter { it.isNotEmpty() } }
            val names = listOf("lang", "model", "feed", "vad", "seg")
            val emptyCol = values.indexOfFirst { it.isEmpty() }
            if (emptyCol >= 0) {
                errors += "${lineNo}행: ${names[emptyCol]} 칸이 비어 있다"
                continue
            }
            for (lang in values[0]) for (model in values[1]) for (feed in values[2]) for (vad in values[3]) for (seg in values[4]) {
                val job = PlanJob(
                    lang = lang.lowercase(),                 // kr / en
                    model = model,                           // 모델 폴더 이름(대소문자 그대로)
                    feed = feed.uppercase(),                 // REALTIME …
                    vad = vad.uppercase(),                   // SILERO_SAFE …
                    seg = seg.uppercase(),                   // MAX / SEG10
                    lineNo = lineNo,
                )
                if (jobs.containsKey(job.csvLine)) duplicates++   // 같은 조합이 이미 있으면 센다
                else jobs[job.csvLine] = job
            }
        }
        if (!headerSeen) errors += "머리줄('$PLAN_HEADER')이 없다"
        return PlanParse(jobs.values.toList(), errors, duplicates)
    }

    /**
     * 목적: 작업 목록을 CSV 글로 만든다(한 줄 = 작업 1개, 펼친 형태).
     * 입력: jobs — 작업 목록, comment — 맨 위 주석 한 줄
     * 리턴: UTF-8 BOM 으로 시작하는 CSV 글(엑셀에서 한글이 깨지지 않게)
     */
    fun format(jobs: List<PlanJob>, comment: String): String =
        "﻿# ${comment.replace("\n", " ")}\n$PLAN_HEADER\n" +   // BOM + 주석 + 머리줄
            jobs.joinToString("") { it.csvLine + "\n" }              // 작업 한 줄씩
}

/** 목적: 작업 검증(Android 목록·모델 정의는 인자로 받는다 — 순수 함수로 두어 PC 에서 시험) */
object PlanCheck {
    /** 지원 언어 = 입력 하위 폴더 이름 */
    val LANGS = setOf("kr", "en")

    /** 한국어 전용 모델(영어 음원에 쓰면 결과가 무의미) */
    val KO_ONLY = setOf("zipformer-ko", "zipformer-ko-stream", "moonshine-tiny-ko", "vosk-small-ko")

    /** 영어 전용 모델 */
    val EN_ONLY = setOf("vosk-small-en")

    /**
     * 목적: 작업 목록의 오류를 찾는다.
     * 입력: jobs — 작업, appModels — 목록에 쓸 수 있는 앱 엔진 모델 이름, googleModels — Google 엔진 모델 이름(목록 실행 제외),
     *       feeds — 공급 속도 이름, vads — 고정 VAD 프리셋 이름(+NOVAD)
     * 리턴: 오류 문장 목록(같은 줄·같은 이유는 한 번만, 없으면 빈 목록)
     */
    fun errors(
        jobs: List<PlanJob>,
        appModels: Set<String>,
        googleModels: Set<String>,
        feeds: Set<String>,
        vads: Set<String>,
    ): List<String> {
        val out = LinkedHashSet<String>()                                // 순서 유지 + 중복 제거
        for (j in jobs) {
            val at = if (j.lineNo > 0) "${j.lineNo}행" else "화면 추가"   // 어디서 온 작업인지
            if (j.lang !in LANGS) out += "$at: lang '${j.lang}' 는 kr / en 만 된다"
            when {
                j.model in googleModels -> out += "$at: '${j.model}' 는 Google 엔진 — 목록 실행에서 제외(개별 START 로 실행)"
                j.model !in appModels -> out += "$at: 모델 '${j.model}' 없음"
                j.lang == "en" && j.model in KO_ONLY -> out += "$at: '${j.model}' 는 한국어 전용 — en 에 쓸 수 없다"
                j.lang == "kr" && j.model in EN_ONLY -> out += "$at: '${j.model}' 는 영어 전용 — kr 에 쓸 수 없다"
            }
            if (j.feed !in feeds) out += "$at: 공급 속도 '${j.feed}' 없음"
            if (j.vad !in vads) out += "$at: VAD '${j.vad}' 없음(고정 프리셋·NOVAD 만, CUSTOM 은 목록에 쓸 수 없다)"
            if (j.seg != SEG_MAX && j.seg != SEG_10) out += "$at: seg '${j.seg}' 는 $SEG_MAX / $SEG_10 만 된다"
        }
        return out.toList()
    }
}

/**
 * 목적: 진행 기록 한 줄(작업 1개의 끝 상태).
 * 필드: key — 결과 위치 "<lang>/<날짜>_<feed>/<결과 폴더>", status — DONE / FAILED / INTERRUPTED,
 *       worker — 실행한 work 번호, started / finished — 시각(yyyy-MM-dd HH:mm:ss), ok / fail — 파일 성공·실패 수, note — 메모
 */
data class PlanProgressRow(
    val key: String,        // 결과 위치(작업 식별)
    val status: String,     // 끝 상태
    val worker: Int,        // work 번호
    val started: String,    // 시작 시각
    val finished: String,   // 끝 시각
    val ok: Int,            // 성공 파일 수
    val fail: Int,          // 실패 파일 수
    val note: String,       // 메모
)

/** 목적: 진행 기록(_plan_progress.csv) 읽기·쓰기 */
object PlanProgress {
    const val DONE = "DONE"                    // 배치가 끝까지 돌았다(일부 파일 ERROR 여도 완료 — 평가기가 실패도 채점)
    const val FAILED = "FAILED"                // 시작하지 못했거나 예외로 끝났다 → 이어 하기 때 다시
    const val INTERRUPTED = "INTERRUPTED"      // [중지] 지금 바로 / 앱 종료로 끊겼다 → 이어 하기 때 처음부터 다시

    /** 진행 기록 머리줄 */
    const val HEADER = "key,status,worker,started,finished,ok,fail,note"

    /**
     * 목적: 작업 식별 키(= 출력 폴더 아래 결과 폴더 경로)를 만든다.
     * 입력: lang, date(yyyyMMdd), feed, resultDir(<엔진>_<모델>_<조건>)
     * 리턴: "kr/20261012_REALTIME/whisper-base_sherpa-int8_SILERO_SAFE"
     */
    fun key(lang: String, date: String, feed: String, resultDir: String): String = "$lang/${date}_$feed/$resultDir"

    /**
     * 목적: 진행 기록 글을 만든다.
     * 입력: date — 이 목록의 날짜 폴더 값, plan — 목록 이름, rows — 기록(키별 마지막 상태)
     * 리턴: UTF-8 BOM + "# date=" + "# plan=" + 머리줄 + 줄들
     */
    fun format(date: String, plan: String, rows: Collection<PlanProgressRow>): String {
        val sb = StringBuilder("﻿")
        sb.append("# date=").append(date).append('\n')                     // 이어 하기 때 같은 날짜 폴더를 쓰기 위한 값
        sb.append("# plan=").append(clean(plan)).append('\n')              // 참고용 목록 이름
        sb.append(HEADER).append('\n')
        for (r in rows) {
            sb.append(listOf(r.key, r.status, r.worker, r.started, r.finished, r.ok, r.fail, clean(r.note)).joinToString(","))
                .append('\n')
        }
        return sb.toString()
    }

    /**
     * 목적: 진행 기록 글을 읽는다.
     * 입력: text — 파일 내용
     * 리턴: Pair(date 또는 null, key → 마지막 기록)
     */
    fun parse(text: String): Pair<String?, Map<String, PlanProgressRow>> {
        var date: String? = null
        val rows = LinkedHashMap<String, PlanProgressRow>()
        for (raw in text.removePrefix("﻿").split("\n")) {
            val line = raw.trim().removeSuffix("\r")
            if (line.startsWith("# date=")) { date = line.removePrefix("# date=").trim(); continue }   // 날짜 값
            if (line.isEmpty() || line.startsWith("#") || line == HEADER) continue                     // 주석·머리줄
            val c = line.split(",")
            if (c.size < 8) continue                                                                    // 깨진 줄은 무시(다시 돌리면 된다)
            rows[c[0]] = PlanProgressRow(
                key = c[0], status = c[1], worker = c[2].toIntOrNull() ?: 0,
                started = c[3], finished = c[4],
                ok = c[5].toIntOrNull() ?: 0, fail = c[6].toIntOrNull() ?: 0,
                note = c.subList(7, c.size).joinToString(" "),
            )
        }
        return Pair(date, rows)
    }

    /** 목적: CSV 칸을 깨뜨리는 쉼표·줄바꿈을 공백으로 바꾼다. 입력: s / 리턴: 정리된 문자열 */
    private fun clean(s: String): String = s.replace(",", " ").replace("\r", " ").replace("\n", " ")
}
