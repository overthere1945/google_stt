/*
 * 파일명: data/assets/AssetRepository.kt
 * 목적 및 기능:
 * - 자산(STT 모델·VAD 모델)마다 상태를 들고, 필요한 자산을 받아 쓸 수 있게 만든다(FR-05, 4단계 5-4·6장).
 *     MISSING → DOWNLOADING(%) → [VERIFYING: 압축 파일 해시] → EXTRACTING → VERIFYING → READY | FAILED(이유)
 *   압축 파일 해시가 매니페스트에 있으면 풀기 전에 먼저 확인한다(검증되지 않은 압축 파일을 풀지 않으려고).
 * - 규칙
 *     · 받는 순서는 작은 것부터(VAD → 작은 STT → 큰 STT) — 첫 작업을 빨리 시작하려고
 *     · 시작 전에 필요 용량(압축 × 2 + 1 GB)을 본다. 모자라면 받기를 시작하지 않는다(E-AS-08)
 *     · 해시 불일치(E-AS-06)·해제 실패(E-AS-07)는 받은 것을 지우고 한 번만 다시 받는다
 *     · TLS·네트워크·HTTP 오류는 받던 파일(.part)을 남긴다 — 다음에 이어받는다(다시 시도는 내려받기 도구가 이미 했다)
 *     · 한 번에 하나씩 받는다(Mutex). 같은 자산을 두 곳에서 요청해도 한 번만 받는다
 * - 실제 내려받기는 함수로 받는다(앱에서는 AssetDownloader::download) → PC(JVM)에서 가짜 함수로 상태 전이를 시험한다.
 *
 * add-hyungchul-20261106-1714 — [T4-5]
 */
package com.example.google_stt.data.assets

import com.example.google_stt.domain.AppError              // 오류 분류
import com.example.google_stt.domain.catalog.ArchiveType   // 압축 형식
import com.example.google_stt.domain.catalog.AssetSpec     // 자산 정의
import java.io.File                                        // 받는 파일
import java.io.IOException                                 // 파일 입출력 오류
import kotlinx.coroutines.CancellationException            // 취소
import kotlinx.coroutines.CoroutineDispatcher              // 입출력 스레드 지정
import kotlinx.coroutines.Dispatchers                      // 기본 입출력 스레드
import kotlinx.coroutines.flow.MutableStateFlow            // 상태 보관
import kotlinx.coroutines.flow.StateFlow                   // 상태 읽기
import kotlinx.coroutines.sync.Mutex                       // 한 번에 하나씩
import kotlinx.coroutines.sync.withLock                    // 잠금 구간
import kotlinx.coroutines.withContext                      // 스레드 바꾸기

/** 목적: 실패 이유(화면 안내 문구를 고르는 데 쓴다). */
enum class FailReason {
    TLS,       // E-AS-01 인증서 오류(회사망) → 외부망·PC 가져오기 안내
    NETWORK,   // E-AS-02 / 04 끊김·시간 초과·서버 오류 → 다시 시도 버튼
    HTTP,      // E-AS-03 주소가 바뀌었을 수 있음
    HASH,      // E-AS-06 파일 손상
    EXTRACT,   // E-AS-07 압축 해제 실패
    SPACE,     // E-AS-08 저장공간 부족
}

/** 목적: 자산 1개의 상태. */
sealed interface AssetState {
    /** 없음(받다 만 것 포함) */
    data object Missing : AssetState

    /** 받는 중 — received / total 바이트(total 은 모르면 null) */
    data class Downloading(val received: Long, val total: Long?) : AssetState {
        /** 진행률(0~100, 전체 크기를 모르면 null) */
        val percent: Int? get() = total?.takeIf { it > 0 }?.let { (received * 100 / it).toInt().coerceIn(0, 100) }
    }

    /** 압축 푸는 중 */
    data object Extracting : AssetState

    /** SHA256 확인 중 */
    data object Verifying : AssetState

    /** 준비됨 — verified = 매니페스트 해시와 모두 맞았는지(false 면 "미검증") */
    data class Ready(val verified: Boolean) : AssetState

    /** 실패 — reason 이유, error 원래 오류(코드·문구) */
    data class Failed(val reason: FailReason, val error: AppError) : AssetState
}

/**
 * 목적: 자산 상태 저장소.
 * 입력(생성자): store - 자산 저장 위치, specs - 자산 ID → 정의(매니페스트 해시가 합쳐진 것),
 *               fetch - 내려받기 함수 (주소, 받는 파일, 진행 알림) → 받은 크기. 앱에서는 AssetDownloader::download,
 *               io - 파일·네트워크 일을 돌릴 스레드, freeBytes - 여유 공간을 읽는 함수, log - 로그 한 줄을 받는 함수
 */
class AssetRepository(
    private val store: AssetStore,
    private val specs: Map<String, AssetSpec>,
    private val fetch: suspend (String, File, (Long, Long?) -> Unit) -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val freeBytes: () -> Long = store::freeBytes,
    private val log: (String) -> Unit = {},
) {
    private companion object {
        const val SOURCE = "download"                 // 준비 표시에 적는 출처
        const val PROGRESS_STEP = 1024L * 1024L       // 진행 상태는 1 MB 마다 바꾼다(조각마다 바꾸면 너무 잦다)
        const val MAX_ATTEMPTS = 2                    // 처음 1번 + 다시 받기 1번
    }

    private val mutex = Mutex()                                                               // 받기·지우기는 한 번에 하나
    private val flows = specs.mapValues { MutableStateFlow(onDisk(it.value)) }                // 자산 ID → 상태

    /** 모든 자산의 상태(자산 ID → 상태 흐름) */
    val states: Map<String, StateFlow<AssetState>> get() = flows

    /** 목적: 자산 하나의 상태 흐름을 준다. 입력: id - 자산 ID / 출력: 없음 / 리턴: StateFlow<AssetState> */
    fun state(id: String): StateFlow<AssetState> = flows.getValue(id)

    /**
     * 목적: 아직 준비되지 않은 자산을 받을 순서(작은 것부터)로 돌려준다.
     * 입력: ids - 필요한 자산 ID 들 / 출력: 없음 / 리턴: List<AssetSpec> - 크기를 모르는 자산은 맨 뒤
     */
    fun missing(ids: Collection<String>): List<AssetSpec> =
        ids.distinct().map { specs.getValue(it) }.filter { !store.isReady(it) }.sortedBy { it.archiveBytes ?: Long.MAX_VALUE }

    /** 목적: 받는 데 필요한 저장공간을 구한다. 입력: ids - 필요한 자산 ID 들 / 출력: 없음 / 리턴: Long - 바이트(받을 것이 없으면 0) */
    fun requiredBytes(ids: Collection<String>): Long = AssetStore.requiredBytes(missing(ids))

    /**
     * 목적: 자산들을 쓸 수 있게 만든다(없는 것만 받는다).
     * 입력: ids - 필요한 자산 ID 들
     * 출력: 파일이 받아지고 상태 흐름이 바뀐다
     * 리턴: Map<String, AssetState.Failed> - 실패한 자산 ID → 실패 상태(비어 있으면 모두 준비됨). 하나가 실패해도 나머지는 계속 받는다
     * 예외: 취소되면 CancellationException(받던 .part 는 남고 상태는 MISSING 으로 돌아간다)
     */
    suspend fun ensureReady(ids: Collection<String>): Map<String, AssetState.Failed> = mutex.withLock {
        withContext(io) {
            val need = missing(ids)                                                           // 작은 것부터
            for (id in ids) if (store.isReady(specs.getValue(id))) flows.getValue(id).value = onDisk(specs.getValue(id))   // 이미 있는 것은 상태만 맞춘다
            val failed = LinkedHashMap<String, AssetState.Failed>()
            if (need.isEmpty()) return@withContext failed
            val required = AssetStore.requiredBytes(need)
            val free = freeBytes()
            if (free < required) {                                                            // E-AS-08: 받기를 시작하지 않는다
                val err = AppError.AssetNoSpace(required, free)
                log("${err.message} — 받기를 시작하지 않는다")
                for (s in need) failed[s.id] = AssetState.Failed(FailReason.SPACE, err).also { flows.getValue(s.id).value = it }
                return@withContext failed
            }
            for (s in need) (obtain(s) as? AssetState.Failed)?.let { failed[s.id] = it }
            failed
        }
    }

    /**
     * 목적: 자산을 지운다(폴더와 받다 만 파일).
     * 입력: id - 자산 ID / 출력: 파일이 지워지고 상태가 MISSING 이 된다 / 리턴: Boolean - 지운 것이 있으면 true
     */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(io) { store.delete(id) }.also { flows.getValue(id).value = AssetState.Missing }
    }

    /**
     * 목적: 저장소를 다시 읽어 상태를 맞춘다(가져오기처럼 이 저장소 밖에서 자산 폴더가 바뀐 뒤 부른다). 실패 표시도 지워진다.
     * 입력: 없음 / 출력: 상태 흐름이 바뀐다 / 리턴: 없음
     */
    suspend fun refresh() = mutex.withLock {
        withContext(io) { for ((id, flow) in flows) flow.value = onDisk(specs.getValue(id)) }
    }

    /** 목적: 저장소에 놓인 것으로 상태를 정한다. 입력: spec - 자산 정의 / 출력: 없음 / 리턴: AssetState - READY 또는 MISSING */
    private fun onDisk(spec: AssetSpec): AssetState =
        if (store.isReady(spec)) AssetState.Ready(store.readyInfo(spec.id)?.verified == true) else AssetState.Missing

    /**
     * 목적: 자산 하나를 받아 준비 상태로 만든다(손상이면 지우고 한 번 다시).
     * 입력: spec - 자산 정의 / 출력: 파일·준비 표시가 쓰이고 상태 흐름이 바뀐다 / 리턴: AssetState - READY 또는 FAILED
     */
    private suspend fun obtain(spec: AssetSpec): AssetState {
        val flow = flows.getValue(spec.id)
        try {
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    if (spec.archive == ArchiveType.NONE) fetchFiles(spec, flow) else fetchArchive(spec, flow)
                    flow.value = AssetState.Verifying
                    val info = try {
                        store.verify(spec, SOURCE)                                            // 파일별 해시 확인 + 준비 표시
                    } catch (e: IOException) {
                        throw AppError.AssetExtract("${spec.id}: 파일을 읽을 수 없다 — ${e.message}", e)
                    }
                    log("자산 준비됨: ${spec.id}" + if (info.verified) "" else " (미검증 — 매니페스트에 해시가 없다)")
                    return AssetState.Ready(info.verified).also { flow.value = it }
                } catch (e: AppError) {
                    val damaged = e is AppError.AssetHashMismatch || e is AppError.AssetExtract   // 받은 것이 못 쓰는 상태
                    val again = damaged && attempt < MAX_ATTEMPTS
                    log("${spec.id}: ${e.message}" + if (again) " — 지우고 한 번 다시 받는다" else "")
                    if (damaged) store.delete(spec.id)                                        // 압축 파일(.part)까지 지운다
                    if (!again) return AssetState.Failed(reason(e), e).also { flow.value = it }
                }
            }
            error("도달하지 않는다")                                                          // 반복은 항상 return 으로 끝난다
        } catch (e: CancellationException) {
            flow.value = AssetState.Missing                                                   // 받던 .part 는 남아 다음에 이어받는다
            throw e
        }
    }

    /**
     * 목적: 압축 자산을 받아 푼다.
     * 입력: spec - 자산 정의(TAR_BZ2 / ZIP), flow - 상태를 적을 곳 / 출력: 자산 폴더에 파일이 풀린다 / 리턴: 없음
     * 예외: AppError(내려받기·해시·해제)
     */
    private suspend fun fetchArchive(spec: AssetSpec, flow: MutableStateFlow<AssetState>) {
        val part = store.partFile(spec.id)
        // 이미 끝까지 받은 파일이면 다시 요청하지 않는다 — 요청하면 서버가 416 을 주고, 내려받기 도구는 지우고 처음부터 다시 받는다
        if (!part.isFile || part.length() != spec.archiveBytes) fetch(spec.url, part, progress(flow, 0L, spec.archiveBytes))
        try {
            if (spec.archiveSha256 != null) {
                flow.value = AssetState.Verifying
                val actual = Sha256.of(part)
                if (actual != spec.archiveSha256) throw AppError.AssetHashMismatch("${spec.id} 압축 파일: 기대 ${spec.archiveSha256}, 실제 $actual")
            }
            flow.value = AssetState.Extracting
            ArchiveExtractor.extract(part, spec.archive, spec.files, store.dir(spec.id))
        } catch (e: IOException) {
            throw AppError.AssetExtract("${spec.id}: 압축 파일을 읽을 수 없다 — ${e.message}", e)
        }
        part.delete()                                                                         // 다 풀었으면 압축 파일은 지운다
    }

    /**
     * 목적: 압축이 없는 자산의 파일들을 하나씩 받는다(주소 = url + "/" + 원본 안 경로).
     * 입력: spec - 자산 정의(NONE), flow - 상태를 적을 곳 / 출력: 자산 폴더에 파일이 놓인다 / 리턴: 없음
     * 예외: AppError(내려받기), AppError.AssetExtract - 받은 파일을 옮길 수 없을 때
     */
    private suspend fun fetchFiles(spec: AssetSpec, flow: MutableStateFlow<AssetState>) {
        var base = 0L                                                                         // 앞 파일들까지 받은 크기
        spec.files.forEachIndexed { i, f ->
            val part = store.partFile("${spec.id}~$i")                                        // 파일별 임시 파일
            val size = fetch("${spec.url}/${f.pathInArchive}", part, progress(flow, base, spec.archiveBytes))
            val dest = store.file(spec.id, f.target)
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()                                                  // 옛 파일 지우기
            if (!part.renameTo(dest)) throw AppError.AssetExtract("받은 파일을 옮길 수 없다: ${dest.path}")
            base += size
        }
    }

    /**
     * 목적: 내려받기 진행 알림을 상태로 옮기는 함수를 만든다(1 MB 마다, 그리고 끝났을 때).
     * 입력: flow - 상태를 적을 곳, base - 앞서 받은 크기, known - 카탈로그에 적힌 전체 크기(없으면 서버가 알려 준 값을 쓴다)
     * 출력: 없음 / 리턴: (받은 바이트, 서버가 알려 준 전체 바이트) 를 받는 함수
     */
    private fun progress(flow: MutableStateFlow<AssetState>, base: Long, known: Long?): (Long, Long?) -> Unit {
        var last = -PROGRESS_STEP                                                             // 마지막으로 알린 위치(첫 알림은 바로 나가게)
        return { received, reported ->
            val now = base + received
            val total = known ?: reported
            if (now - last >= PROGRESS_STEP || now == total) {
                last = now
                flow.value = AssetState.Downloading(now, total)
            }
        }
    }

    /**
     * 목적: 오류를 실패 이유로 나눈다.
     * 입력: e - 오류 / 출력: 없음 / 리턴: FailReason - 원인 사슬에 ENOSPC 가 있으면 SPACE(받는 중 저장공간이 바닥난 경우)
     */
    private fun reason(e: AppError): FailReason = when {
        e is AppError.AssetNoSpace -> FailReason.SPACE
        generateSequence<Throwable>(e) { it.cause }.take(8).any { it.message?.contains("ENOSPC") == true } -> FailReason.SPACE
        e is AppError.AssetTls -> FailReason.TLS
        e is AppError.AssetHttp -> FailReason.HTTP
        e is AppError.AssetHashMismatch -> FailReason.HASH
        e is AppError.AssetExtract -> FailReason.EXTRACT
        else -> FailReason.NETWORK
    }
}
