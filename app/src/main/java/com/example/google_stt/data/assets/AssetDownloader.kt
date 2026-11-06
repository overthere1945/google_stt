/*
 * 파일명: data/assets/AssetDownloader.kt
 * 목적 및 기능:
 * - HTTPS 로 파일 하나를 받는다(FR-05-01, FR-05-03, 3단계 3-2). 받는 중인 파일(.part)이 있으면 이어받는다.
 * - 이어받기 규칙:
 *     · 항상 원래 주소로 다시 요청한다(GitHub 릴리스는 약 1시간짜리 서명 주소로 넘겨 주므로, 넘겨받은 주소를 다시 쓰면 안 된다)
 *     · 받은 크기가 있으면 "Range: bytes=<받은 크기>-" 를 붙인다
 *     · 206 이고 Content-Range 의 시작이 받은 크기와 같으면 이어 쓴다. 200 이면(서버가 Range 를 무시) 처음부터 다시 받는다
 *     · 416(범위 오류)이거나 206 의 시작 위치가 어긋나면 받던 파일을 지우고 한 번만 처음부터 다시 받는다(E-AS-05)
 * - 오류 분류(6단계 6-2):
 *     · TLS 인증서 오류 → E-AS-01, 다시 시도하지 않는다(회사망 HTTPS 검사로 보임 → 외부망·PC 가져오기 안내)
 *     · 끊김·시간 초과·5xx → E-AS-02, 5·15·45초 뒤 다시 시도(3회)하고 그래도 안 되면 실패
 *     · 그 밖의 4xx → E-AS-03, 다시 시도하지 않는다
 * - HttpURLConnection 만 쓴다(추가 의존성 없음, https → https 넘겨 주기는 스스로 따라간다). Android 를 쓰지 않아 PC(JVM)에서 시험한다.
 * - 취소되면 연결을 끊어 읽기를 바로 멈추고 CancellationException 을 올린다. 받던 .part 는 남겨 다음에 이어받는다.
 *
 * add-hyungchul-20261106-1702 — [T4-2]
 */
package com.example.google_stt.data.assets

import com.example.google_stt.domain.AppError          // E-AS-01 / 02 / 03
import java.io.File                                    // 받는 파일
import java.io.FileOutputStream                        // 이어 쓰기
import java.io.IOException                             // 네트워크 오류
import java.net.HttpURLConnection                      // HTTP 연결
import java.net.URL                                    // 주소
import java.security.cert.CertificateException         // 인증서 오류
import javax.net.ssl.SSLHandshakeException             // TLS 악수 실패
import javax.net.ssl.SSLPeerUnverifiedException        // 상대 확인 실패
import kotlinx.coroutines.Dispatchers                  // 감시 코루틴이 돌 스레드
import kotlinx.coroutines.awaitCancellation            // 취소될 때까지 기다리기
import kotlinx.coroutines.coroutineScope               // 감시 코루틴의 범위
import kotlinx.coroutines.currentCoroutineContext      // 취소 확인
import kotlinx.coroutines.delay                        // 다시 시도 전 대기(취소에 반응)
import kotlinx.coroutines.ensureActive                 // 취소 확인
import kotlinx.coroutines.launch                       // 감시 코루틴 시작

/**
 * 목적: 파일 내려받기 도구.
 * 입력(생성자): retryDelaysMs - 다시 시도 전 대기 시간들(개수 = 다시 시도 횟수), connectTimeoutMs / readTimeoutMs - 연결·읽기 시간 제한
 */
class AssetDownloader(
    private val retryDelaysMs: List<Long> = listOf(5_000L, 15_000L, 45_000L),
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
) {
    private companion object {
        const val BUFFER = 64 * 1024   // 복사 단위 64 KB
    }

    /** 이어받을 수 없다는 내부 신호 — 416 또는 어긋난 206(한 번은 처음부터 다시 받는다) */
    private class RangeNotSatisfiable : Exception()

    /**
     * 목적: url 의 내용을 part 파일로 받는다. part 가 이미 있으면 이어받는다. 돌아오면 part 가 완성된 파일이다.
     * 입력: url - 원래 주소(https), part - 받는 파일, onProgress - (받은 바이트, 전체 바이트 또는 null) 을 받는 함수
     * 출력: part 파일이 쓰인다
     * 리턴: Long - 받은 파일의 크기
     * 예외: AppError.AssetTls(E-AS-01), AssetNetwork(E-AS-02), AssetHttp(E-AS-03). 취소되면 CancellationException
     */
    suspend fun download(url: String, part: File, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Long {
        var retries = 0                                                         // 다시 시도한 횟수
        var restarted = false                                                   // 416 으로 처음부터 다시 받았는지
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                fetchOnce(url, part, onProgress)
                return part.length()
            } catch (e: RangeNotSatisfiable) {
                if (restarted) throw AppError.AssetHttp(416, "범위 요청을 서버가 받지 않는다: $url")
                restarted = true
                part.delete()                                                   // E-AS-05: 받던 파일을 지우고 한 번만 다시
            } catch (e: AppError.AssetNetwork) {
                if (retries >= retryDelaysMs.size) throw e                      // 다시 시도를 다 썼다
                delay(retryDelaysMs[retries++])                                 // 5초 → 15초 → 45초
            }
        }
    }

    /**
     * 목적: 요청 한 번을 보내 받은 만큼 part 에 쓴다.
     * 입력: url - 원래 주소, part - 받는 파일, onProgress - 진행 알림
     * 출력: part 가 길어진다(또는 처음부터 다시 쓰인다)
     * 리턴: 없음
     * 예외: RangeNotSatisfiable(416), AppError.AssetTls / AssetNetwork / AssetHttp, 취소 시 CancellationException
     */
    private suspend fun fetchOnce(url: String, part: File, onProgress: (Long, Long?) -> Unit): Unit = coroutineScope {
        val have = if (part.isFile) part.length() else 0L                       // 이미 받은 크기
        part.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection               // 항상 원래 주소로
        // 읽기는 막혀 있는 호출이라 취소를 스스로 알지 못한다 → 감시 코루틴이 취소를 받으면 다른 스레드에서 연결을 끊어 읽기를 깨운다
        val watcher = launch(Dispatchers.IO) {
            try { awaitCancellation() } finally { conn.disconnect() }
        }
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true                                 // 302 따라가기
            conn.setRequestProperty("Accept-Encoding", "identity")              // 압축 전송을 받지 않는다(크기·범위가 원본과 같게)
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")      // 이어받기
            val code = conn.responseCode
            val append: Boolean
            val total: Long?
            when {
                code == HttpURLConnection.HTTP_PARTIAL -> {                     // 206: 이어받기
                    val range = conn.getHeaderField("Content-Range") ?: ""      // "bytes <시작>-<끝>/<전체>"
                    val start = Regex("bytes (\\d+)-").find(range)?.groupValues?.get(1)?.toLongOrNull()
                    if (start != have) throw RangeNotSatisfiable()              // 위치가 어긋나면 이어 붙이지 않고 처음부터 다시 받는다
                    append = true
                    total = range.substringAfter('/', "").toLongOrNull()
                }
                code == HttpURLConnection.HTTP_OK -> {                          // 200: 전체(서버가 Range 를 무시했거나 처음 받기)
                    append = false
                    total = conn.contentLengthLong.takeIf { it >= 0 }
                }
                code == 416 -> throw RangeNotSatisfiable()
                code >= 500 -> throw AppError.AssetNetwork("서버 오류 HTTP $code: $url")   // E-AS-04: 다시 시도
                else -> throw AppError.AssetHttp(code, url)                     // 그 밖의 3xx·4xx: 다시 시도하지 않는다
            }
            var received = if (append) have else 0L
            onProgress(received, total)
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()                // 조각마다 취소 확인
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
            }
            if (total != null && received != total) throw AppError.AssetNetwork("받다가 끊겼다($received / $total B)")   // 다음 시도에서 이어받는다
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()                            // 취소로 끊은 것이면 취소 예외를 올린다
            throw classify(e, url)
        } finally {
            watcher.cancel()                                                    // 감시를 끝낸다(끝내며 연결도 끊는다)
            conn.disconnect()
        }
    }

    /**
     * 목적: 입출력 예외를 앱 오류로 나눈다.
     * 입력: e - 예외, url - 주소(오류 문구용)
     * 출력: 없음
     * 리턴: AppError - 인증서 문제면 AssetTls(E-AS-01), 그 밖은 AssetNetwork(E-AS-02)
     */
    internal fun classify(e: IOException, url: String): AppError {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(8).toList()   // 원인 사슬(순환 방지로 8단계까지)
        val tls = chain.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException || it is CertificateException ||
            it.javaClass.name == "java.security.cert.CertPathValidatorException" }
        return if (tls) AppError.AssetTls("${e.javaClass.simpleName}: ${e.message} ($url)", e)
        else AppError.AssetNetwork("${e.javaClass.simpleName}: ${e.message} ($url)", e)
    }
}
