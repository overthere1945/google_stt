/*
 * 파일명: data/device/AppLog.kt
 * 목적 및 기능:
 * - 앱 로그를 세 곳에 남긴다(FR-10-01, 6단계 6-4): Logcat(태그 GoogleStt), 화면용 최근 줄 목록, 출력 폴더의 _app_log.txt.
 *   세 곳에 같은 문장이 나간다. 디버그 줄은 "[D]" 로 시작한다(화면에서 숨길 수 있게).
 * - 파일은 모아서 쓴다(50줄마다 또는 flush 를 부를 때). 20 MB 를 넘기 전에 _app_log.1.txt 로 돌리고 새로 시작한다(앞 것 1개만 보관).
 * - 출력 폴더는 실행이 시작될 때 attach 로 연결한다. 연결 전의 줄은 Logcat 과 화면 목록에만 남는다.
 * - 파일에 쓰지 못해도 실행을 멈추지 않는다(그 사실은 Logcat 에 남긴다). 출력 폴더 문제는 결과 쓰기 쪽이 E-OUT-01 로 잡는다.
 * - 기록 금지: 토큰·비밀값, 음성 내용 밖의 개인정보.
 *
 * add-hyungchul-20261106-1650 — [T3-5a]
 */
package com.example.google_stt.data.device

import android.util.Log                                       // Logcat
import com.example.google_stt.data.saf.OutputLayout           // 로그 파일 이름
import com.example.google_stt.data.saf.SafTree                // 출력 폴더에 덧붙이기
import com.example.google_stt.domain.AppError                 // 쓰기 실패
import java.text.SimpleDateFormat                             // 시각 글자
import java.util.Date                                         // 시각
import java.util.Locale                                       // 시각 형식 고정
import kotlinx.coroutines.flow.MutableStateFlow               // 최근 줄 목록
import kotlinx.coroutines.flow.StateFlow                      // 화면이 구독하는 흐름

/**
 * 목적: 앱 로그.
 * 입력(생성자): maxBytes - 로그 파일 최대 크기(이 값을 넘기 전에 돌린다. 기본 20 MB)
 */
class AppLog(private val maxBytes: Long = 20L * 1024 * 1024) {

    companion object {
        const val TAG = "GoogleStt"       // Logcat 태그(adb logcat -s GoogleStt)
        const val DEBUG_PREFIX = "[D] "   // 디버그 줄 앞 표시
        const val RECENT_MAX = 200        // 화면용으로 기억하는 최근 줄 수
        const val FLUSH_LINES = 50        // 이만큼 모이면 파일에 쓴다
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)   // 파일 줄 앞의 시각(동기화된 곳에서만 쓴다)
    private val _recent = MutableStateFlow<List<String>>(emptyList())

    /** 최근 줄 목록(오래된 것이 앞) — 실행 화면이 구독한다 */
    val recent: StateFlow<List<String>> = _recent

    private var tree: SafTree? = null          // 연결된 출력 폴더(없으면 파일에 쓰지 않는다)
    private val pending = StringBuilder()      // 아직 파일에 쓰지 않은 줄
    private var pendingLines = 0               // 모인 줄 수
    private var fileBytes = 0L                 // 지금 로그 파일 크기

    /**
     * 목적: 출력 폴더를 연결하거나 뗀다.
     * 입력: tree - 출력 폴더 트리(null = 떼기)
     * 출력: 앞 폴더에 모아 둔 줄을 먼저 쓴다. 새 폴더의 로그 파일 크기를 읽는다
     * 리턴: 없음
     */
    @Synchronized
    fun attach(tree: SafTree?) {
        flush()                                                                // 앞 폴더 몫을 마저 쓴다
        this.tree = tree
        fileBytes = tree?.size(tree.root, OutputLayout.APP_LOG_FILE) ?: 0L     // 이어 쓰는 파일의 크기
    }

    /** 목적: 정보 줄을 남긴다. 입력: msg - 문장 / 출력: 로그 / 리턴: 없음 */
    fun i(msg: String) = write(Log.INFO, msg)

    /** 목적: 디버그 줄을 남긴다("[D] " 가 앞에 붙는다). 입력: msg - 문장 / 출력: 로그 / 리턴: 없음 */
    fun d(msg: String) = write(Log.DEBUG, DEBUG_PREFIX + msg)

    /** 목적: 경고 줄을 남긴다. 입력: msg - 문장 / 출력: 로그 / 리턴: 없음 */
    fun w(msg: String) = write(Log.WARN, msg)

    /**
     * 목적: 오류 줄을 남긴다.
     * 입력: msg - 문장(오류 코드 포함), cause - 원래 예외(있으면 종류와 메시지를 덧붙인다) / 출력: 로그 / 리턴: 없음
     */
    fun e(msg: String, cause: Throwable? = null) =
        write(Log.ERROR, if (cause == null) msg else "$msg — ${cause.javaClass.simpleName}: ${cause.message}")

    /**
     * 목적: 한 줄을 세 곳에 남긴다.
     * 입력: priority - Logcat 수준, line - 문장(줄바꿈은 공백으로 바꾼다) / 출력: Logcat·최근 목록·대기 줄 / 리턴: 없음
     */
    @Synchronized
    private fun write(priority: Int, line: String) {
        val text = line.replace('\n', ' ').replace('\r', ' ')                  // 파일에서 한 줄 = 로그 1건
        Log.println(priority, TAG, text)                                       // Logcat
        _recent.value = (_recent.value + text).takeLast(RECENT_MAX)            // 화면용 목록
        if (tree == null) return                                               // 출력 폴더가 없으면 파일용으로 모으지 않는다(메모리가 계속 늘지 않게)
        pending.append(stamp.format(Date())).append(' ').append(text).append('\n')
        if (++pendingLines >= FLUSH_LINES) flush()                             // 모이면 쓴다
    }

    /**
     * 목적: 모아 둔 줄을 로그 파일에 쓴다(작업·실행이 끝날 때도 부른다).
     * 입력: 없음
     * 출력: _app_log.txt 가 길어진다. 크기 한도를 넘게 되면 먼저 _app_log.1.txt 로 돌린다
     * 리턴: 없음
     */
    @Synchronized
    fun flush() {
        val t = tree
        if (t == null || pendingLines == 0) return                             // 폴더가 없거나 쓸 것이 없다
        val bytes = pending.toString().toByteArray(Charsets.UTF_8)
        pending.setLength(0); pendingLines = 0                                 // 실패해도 쌓아 두지 않는다(메모리가 계속 늘지 않게)
        try {
            if (fileBytes > 0 && fileBytes + bytes.size > maxBytes) {          // 한도를 넘기 전에 돌린다
                t.delete(t.root, OutputLayout.APP_LOG_OLD_FILE)                // 앞의 앞 로그는 버린다(1개만 보관)
                t.rename(t.root, OutputLayout.APP_LOG_FILE, OutputLayout.APP_LOG_OLD_FILE)
                fileBytes = 0L
            }
            t.append(t.root, OutputLayout.APP_LOG_FILE, bytes)
            fileBytes += bytes.size
        } catch (e: AppError) {
            Log.e(TAG, "${e.code}: 로그 파일에 쓰지 못했다(이 줄들은 Logcat 에만 있다) — ${e.message}")   // 실행은 계속한다
        }
    }
}
