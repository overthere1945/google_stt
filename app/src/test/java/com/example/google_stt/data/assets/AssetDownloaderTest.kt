/*
 * 파일명: data/assets/AssetDownloaderTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-ASSET-04] 내려받기 규칙을 PC 안의 작은 HTTP 서버(시험이 직접 띄운다)로 확인한다:
 *   302 따라감 / 206 이어받기 / Range 무시 200 → 처음부터 / 416 → 받던 파일 지우고 1회 다시 / 404 다시 시도 없음 /
 *   503 은 3회 다시 시도 / 받다 끊기면 이어받기 / TLS 오류 분류 / 취소
 *
 * add-hyungchul-20261106-1702 — [T4-2]
 */
package com.example.google_stt.data.assets

import com.example.google_stt.domain.AppError          // 오류 분류
import java.io.File                                    // 받는 파일
import java.io.IOException                             // 네트워크 오류
import java.net.InetAddress                            // 루프백 주소
import java.net.ServerSocket                           // 시험용 서버
import java.net.Socket                                 // 연결 1개
import java.security.cert.CertPathValidatorException   // 인증서 경로 오류
import java.util.concurrent.CopyOnWriteArrayList       // 요청 기록
import javax.net.ssl.SSLHandshakeException             // TLS 악수 실패
import kotlin.concurrent.thread                        // 서버 스레드
import kotlinx.coroutines.Dispatchers                  // 입출력 스레드
import kotlinx.coroutines.cancelAndJoin                // 취소 후 대기
import kotlinx.coroutines.delay                        // 잠깐 기다리기
import kotlinx.coroutines.launch                       // 받기를 따로 돌리기
import kotlinx.coroutines.runBlocking                  // suspend 함수 부르기
import org.junit.After                                 // 시험 뒤 정리
import org.junit.Assert.assertArrayEquals              // 바이트 비교
import org.junit.Assert.assertEquals                   // 값 비교
import org.junit.Assert.assertTrue                     // 조건 확인
import org.junit.Assert.fail                           // 예외가 안 났을 때 실패
import org.junit.Rule                                  // 시험 규칙
import org.junit.Test                                  // JUnit 4 시험 표시
import org.junit.rules.TemporaryFolder                 // 임시 폴더

/** 목적: AssetDownloader 시험. */
class AssetDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()                                                       // 시험이 끝나면 지워지는 폴더

    /** 시험용 원본 파일 내용(200,000 바이트 — 64 KB 버퍼 3번 넘게) */
    private val payload = ByteArray(200_000) { (it * 31 % 251).toByte() }

    /** 목적: 서버가 받은 요청 1건. 필드: path - 경로, range - Range 머리말의 시작 위치(없으면 null) */
    private data class Request(val path: String, val range: Long?)

    /**
     * 목적: 서버가 돌려줄 응답.
     * 필드: status - 상태 코드, headers - 머리말, body - 본문, declaredLength - Content-Length 로 알릴 크기(본문보다 크면 "받다 끊김" 이 된다),
     *       stallMs - 머리말을 보낸 뒤 본문 전에 멈춰 있을 시간(취소 시험용)
     */
    private class Response(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0),
                           val declaredLength: Int? = null, val stallMs: Long = 0)

    /**
     * 목적: 요청마다 handler 가 정한 응답을 돌려주는 아주 작은 HTTP 서버(연결 1개 = 요청 1개).
     * 입력(생성자): handler - (요청, 이 서버가 지금까지 받은 요청 수) → 응답
     */
    private inner class FakeServer(private val handler: (Request, Int) -> Response) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())    // 빈 포트
        val requests = CopyOnWriteArrayList<Request>()                                // 받은 요청 기록
        val base = "http://127.0.0.1:${socket.localPort}"                             // 주소 앞부분
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }   // 닫히면 끝낸다
                thread(isDaemon = true) { client.use { serve(it) } }
            }
        }

        /** 목적: 연결 하나를 처리한다. 입력: client - 연결 / 출력: 응답을 쓴다 / 리턴: 없음 */
        private fun serve(client: Socket) {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val path = (reader.readLine() ?: return).split(" ").getOrNull(1) ?: return   // "GET /path HTTP/1.1"
            var range: Long? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break                                             // 머리말 끝
                if (line.startsWith("Range:", ignoreCase = true)) range = Regex("bytes=(\\d+)-").find(line)?.groupValues?.get(1)?.toLong()
            }
            val req = Request(path, range)
            requests += req
            val res = handler(req, requests.size)
            val out = client.getOutputStream()
            val head = StringBuilder("HTTP/1.1 ${res.status} X\r\n")
            for ((k, v) in res.headers) head.append("$k: $v\r\n")
            head.append("Content-Length: ${res.declaredLength ?: res.body.size}\r\nConnection: close\r\n\r\n")
            try {
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
                if (res.stallMs > 0) Thread.sleep(res.stallMs)                        // 본문을 늦게 보낸다
                out.write(res.body); out.flush()
            } catch (e: IOException) {
                // 받는 쪽이 먼저 끊은 경우(취소 시험) — 서버 쪽에서는 할 일이 없다
            }
        }

        override fun close() { socket.close() }
    }

    private var server: FakeServer? = null

    /** 목적: 시험 뒤 서버를 닫는다. 입력: 없음 / 출력: 포트가 닫힌다 / 리턴: 없음 */
    @After
    fun tearDown() { server?.close() }

    /**
     * 목적: Range 를 제대로 지원하는 서버의 응답을 만든다(GitHub 릴리스와 같은 동작).
     * 입력: req - 요청 / 출력: 없음 / 리턴: Response - 범위가 없으면 200 전체, 있으면 206 나머지, 범위가 끝을 넘으면 416
     */
    private fun rangeAware(req: Request): Response {
        val from = req.range ?: return Response(200, body = payload)
        if (from >= payload.size) return Response(416, mapOf("Content-Range" to "bytes */${payload.size}"))
        return Response(206, mapOf("Content-Range" to "bytes $from-${payload.size - 1}/${payload.size}"), payload.copyOfRange(from.toInt(), payload.size))
    }

    private val fast = AssetDownloader(retryDelaysMs = listOf(10L, 10L, 10L), connectTimeoutMs = 3_000, readTimeoutMs = 3_000)   // 시험용 짧은 대기

    /**
     * 목적: [UT-ASSET-04] 302 를 따라가 전체를 받고, 진행 알림의 마지막 값이 전체 크기다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun followsRedirect_andReportsProgress() = runBlocking {
        val s = FakeServer { req, _ -> if (req.path == "/release") Response(302, mapOf("Location" to "/signed?x=1")) else rangeAware(req) }.also { server = it }
        val part = File(tmp.root, "downloads/a.part")                                 // 폴더가 없어도 만든다
        var last = 0L to (null as Long?)
        val size = fast.download("${s.base}/release", part) { got, total -> last = got to total }
        assertEquals(payload.size.toLong(), size)
        assertArrayEquals(payload, part.readBytes())
        assertEquals(payload.size.toLong() to payload.size.toLong(), last)
        assertEquals(listOf("/release", "/signed?x=1"), s.requests.map { it.path })
    }

    /**
     * 목적: [UT-ASSET-04] 받던 파일이 있으면 Range 로 이어받는다(206). 다시 요청할 때도 원래 주소로 보낸다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun resumesWithRange() = runBlocking {
        val s = FakeServer { req, _ -> if (req.path == "/release") Response(302, mapOf("Location" to "/signed")) else rangeAware(req) }.also { server = it }
        val part = tmp.newFile("a.part").apply { writeBytes(payload.copyOf(70_000)) }  // 70,000 바이트까지 받은 상태
        fast.download("${s.base}/release", part)
        assertArrayEquals(payload, part.readBytes())                                   // 이어 붙인 결과가 원본과 같다
        assertEquals(Request("/release", 70_000L), s.requests[0])                      // 원래 주소 + Range
        assertEquals(70_000L, s.requests[1].range)                                     // 넘겨받은 주소에도 Range 가 간다
    }

    /**
     * 목적: [UT-ASSET-04] 서버가 Range 를 무시하고 200 전체를 주면 처음부터 다시 쓴다(이어 붙이지 않는다).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun rangeIgnored_restartsFromZero() = runBlocking {
        val s = FakeServer { _, _ -> Response(200, body = payload) }.also { server = it }
        val part = tmp.newFile("a.part").apply { writeBytes(ByteArray(50_000) { 1 }) }  // 엉뚱한 내용 50,000 바이트
        fast.download("${s.base}/f", part)
        assertArrayEquals(payload, part.readBytes())                                   // 길이 250,000 이 아니라 원본 그대로
    }

    /**
     * 목적: [UT-ASSET-04] 416 이면 받던 파일을 지우고 한 번만 처음부터 다시 받는다. 어긋난 206 도 같다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun status416_deletesPartAndRestartsOnce() = runBlocking {
        val s = FakeServer { req, _ -> rangeAware(req) }.also { server = it }
        val part = tmp.newFile("a.part").apply { writeBytes(ByteArray(payload.size + 10)) }   // 원본보다 큰(망가진) 파일
        fast.download("${s.base}/f", part)
        assertArrayEquals(payload, part.readBytes())
        assertEquals(listOf<Long?>(payload.size + 10L, null), s.requests.map { it.range })    // 416 뒤 Range 없이 다시
        val always416 = FakeServer { _, _ -> Response(416) }.also { server?.close(); server = it }
        part.writeBytes(ByteArray(5))
        try { fast.download("${always416.base}/f", part); fail("오류가 나야 한다") } catch (e: AppError.AssetHttp) { assertEquals(416, e.status) }
        assertEquals(2, always416.requests.size)                                       // 한 번만 다시 시도했다
        val shifted = FakeServer { req, n ->                                           // 206 인데 시작 위치가 어긋난 서버
            if (n == 1) Response(206, mapOf("Content-Range" to "bytes 0-9/${payload.size}"), payload.copyOf(10)) else rangeAware(req)
        }.also { server?.close(); server = it }
        part.writeBytes(payload.copyOf(100))
        fast.download("${shifted.base}/f", part)
        assertArrayEquals(payload, part.readBytes())                                   // 이어 붙이지 않고 처음부터 받았다
    }

    /**
     * 목적: [UT-ASSET-04] 404 는 다시 시도하지 않고 E-AS-03, 503 은 3회 다시 시도한 뒤 E-AS-02. 중간에 회복되면 성공한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun http404NoRetry_and503RetriesThreeTimes() = runBlocking {
        val notFound = FakeServer { _, _ -> Response(404) }.also { server = it }
        val part = File(tmp.root, "a.part")
        try { fast.download("${notFound.base}/f", part); fail("오류가 나야 한다") } catch (e: AppError.AssetHttp) {
            assertEquals("E-AS-03", e.code); assertEquals(404, e.status); assertTrue(!e.retryable)
        }
        assertEquals(1, notFound.requests.size)                                        // 다시 시도 없음
        val down = FakeServer { _, _ -> Response(503) }.also { server?.close(); server = it }
        try { fast.download("${down.base}/f", part); fail("오류가 나야 한다") } catch (e: AppError.AssetNetwork) { assertEquals("E-AS-02", e.code) }
        assertEquals(4, down.requests.size)                                            // 처음 1 + 다시 시도 3
        val flaky = FakeServer { req, n -> if (n <= 2) Response(503) else rangeAware(req) }.also { server?.close(); server = it }
        fast.download("${flaky.base}/f", part)
        assertArrayEquals(payload, part.readBytes())                                   // 세 번째에 성공
    }

    /**
     * 목적: 받다가 끊기면(알린 크기보다 덜 옴) 다시 시도에서 이어받아 완성한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun truncatedBody_resumesOnRetry() = runBlocking {
        val s = FakeServer { req, n ->
            if (n == 1) Response(200, body = payload.copyOf(90_000), declaredLength = payload.size) else rangeAware(req)   // 첫 응답은 90,000 에서 끊긴다
        }.also { server = it }
        val part = File(tmp.root, "a.part")
        fast.download("${s.base}/f", part)
        assertArrayEquals(payload, part.readBytes())
        assertEquals(listOf<Long?>(null, 90_000L), s.requests.map { it.range })        // 두 번째 요청은 받은 곳부터
    }

    /**
     * 목적: TLS 인증서 오류는 E-AS-01(다시 시도 안 함), 그 밖의 입출력 오류는 E-AS-02(다시 시도)로 나눈다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun classify_tlsVersusNetwork() {
        val d = AssetDownloader()
        val tls = d.classify(SSLHandshakeException("PKIX path building failed"), "https://x")
        assertTrue(tls is AppError.AssetTls); assertEquals("E-AS-01", tls.code); assertTrue(!tls.retryable)
        val nested = d.classify(IOException("wrapped", CertPathValidatorException("Trust anchor for certification path not found.")), "https://x")
        assertTrue("원인 사슬 안의 인증서 오류도 TLS 로 본다", nested is AppError.AssetTls)
        val net = d.classify(java.net.SocketTimeoutException("timeout"), "https://x")
        assertTrue(net is AppError.AssetNetwork); assertEquals("E-AS-02", net.code); assertTrue(net.retryable)
        assertTrue(d.classify(java.net.UnknownHostException("github.com"), "https://x") is AppError.AssetNetwork)
    }

    /**
     * 목적: 받는 중에 취소하면 취소로 끝나고(네트워크 오류로 보고 다시 시도하지 않는다) 받던 파일은 지우지 않는다.
     * 비고: "곧바로 멈춘다" 는 여기서 확인하지 않는다 — PC 의 JDK 는 읽는 중인 연결을 다른 스레드에서 끊어도 읽기가 끝날 때까지 기다린다.
     *       Android 의 HttpURLConnection 은 끊는 즉시 읽기가 멈추며, 그 확인은 실기기 계측 시험(AssetDownloadDeviceTest)이 한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun cancel_endsAsCancellation_withoutRetry() = runBlocking {
        val s = FakeServer { _, _ -> Response(200, body = payload, stallMs = 1_000) }.also { server = it }   // 본문을 1초 뒤에 보낸다
        val part = File(tmp.root, "a.part")
        val slow = AssetDownloader(retryDelaysMs = listOf(10L, 10L, 10L), connectTimeoutMs = 3_000, readTimeoutMs = 20_000)
        val job = launch(Dispatchers.IO) { slow.download("${s.base}/f", part) }
        delay(300)                                                                     // 머리말을 받고 본문을 기다리는 중
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, s.requests.size)                                               // 취소를 네트워크 오류로 보고 다시 시도하지 않았다
        assertTrue(part.length() < payload.size)                                       // 다 받지 않고 멈췄다
    }
}
