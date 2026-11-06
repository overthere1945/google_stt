/*
 * 파일명: data/assets/AssetStore.kt
 * 목적 및 기능:
 * - 내려받은 자산(STT 모델·VAD 모델)의 저장 위치와 준비 표시를 다룬다(FR-05-04, 4단계 6장).
 *     <뿌리>/<자산 ID>/<파일…>            풀린 파일(엔진이 경로 문자열로 읽는다)
 *     <뿌리>/<자산 ID>/.ready             준비 표시 — 출처·검증 여부·파일별 SHA256 기록
 *     <뿌리>/downloads/<자산 ID>.part     받는 중인 파일(이어받기용)
 *   뿌리는 앱 전용 외부 저장소 getExternalFilesDir("models") 다 → '모든 파일 접근' 권한이 필요 없다(v3 는 공용 Documents/stt_models 였다).
 *   앱을 지우면 자산도 지워진다.
 * - 준비 판정: .ready 가 있고 정의된 파일이 모두 있을 때만 준비됨으로 본다(받다 만 자산을 쓰지 않게).
 * - SHA256 계산(Sha256)과 필요 용량 계산도 여기 둔다. java.io 만 써서 PC(JVM)에서 시험한다.
 *
 * add-hyungchul-20261106-1656 — [T4-1]
 */
package com.example.google_stt.data.assets

import com.example.google_stt.domain.catalog.ArchiveType   // 압축 형식(필요 용량 계산)
import com.example.google_stt.domain.catalog.AssetSpec     // 자산 정의
import java.io.File                                        // 파일
import java.io.InputStream                                 // 해시 입력
import java.security.MessageDigest                         // SHA-256

/** 목적: SHA256 계산. */
object Sha256 {

    private const val BUFFER = 64 * 1024   // 읽기 단위 64 KB

    /**
     * 목적: 스트림 내용의 SHA256 을 구한다(큰 파일도 메모리에 다 올리지 않는다).
     * 입력: input - 읽을 스트림(이 함수는 닫지 않는다)
     * 출력: 없음
     * 리턴: String - 16진수 소문자 64자
     */
    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)                                            // 읽은 바이트 수(-1 = 끝)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }             // 16진수 글자로
    }

    /** 목적: 파일의 SHA256 을 구한다. 입력: file - 파일 / 출력: 없음 / 리턴: String - 16진수 소문자 64자 */
    fun of(file: File): String = file.inputStream().use { of(it) }

    /** 목적: 바이트 배열의 SHA256 을 구한다. 입력: bytes - 내용 / 출력: 없음 / 리턴: String - 16진수 소문자 64자 */
    fun of(bytes: ByteArray): String = of(bytes.inputStream())
}

/**
 * 목적: 준비 표시(.ready)의 내용.
 * 필드: source - 자산을 구한 방법(download / import), verified - 매니페스트의 해시와 모두 맞았는지(해시가 없던 자산은 false = "미검증"),
 *       fileHashes - 저장 경로 → 실제로 계산한 SHA256
 */
data class ReadyInfo(
    val source: String,                     // 출처
    val verified: Boolean,                  // 검증 여부
    val fileHashes: Map<String, String>,    // 파일별 해시
) {
    /** 자산 전체를 대표하는 해시 1개 — 파일 경로순 "경로=해시" 줄들의 SHA256. run_meta 의 asset_sha256:<ID> 값 */
    val summary: String
        get() = Sha256.of(fileHashes.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8))
}

/**
 * 목적: 자산 저장소.
 * 입력(생성자): root - 자산 뿌리 폴더(앱에서는 getExternalFilesDir("models"))
 */
class AssetStore(val root: File) {

    companion object {
        const val READY_FILE = ".ready"                  // 준비 표시 파일 이름
        const val MARGIN_BYTES = 1024L * 1024L * 1024L   // 여유 1 GB(NFR-04)

        /**
         * 목적: 자산들을 받는 데 필요한 저장공간을 구한다(3단계 3-3: 압축 파일 + 풀린 파일이 잠시 함께 있다).
         * 입력: specs - 받을 자산들
         * 출력: 없음
         * 리턴: Long - Σ(압축 자산은 받을 크기 × 2, 압축이 없는 자산은 받을 크기 × 1) + 여유 1 GB. 받을 것이 없으면 0
         */
        fun requiredBytes(specs: Collection<AssetSpec>): Long {
            if (specs.isEmpty()) return 0L
            return specs.sumOf { s -> (s.archiveBytes ?: 0L) * (if (s.archive == ArchiveType.NONE) 1 else 2) } + MARGIN_BYTES
        }
    }

    /** 목적: 자산 폴더를 준다. 입력: id - 자산 ID / 출력: 없음 / 리턴: File(없을 수 있다) */
    fun dir(id: String): File = File(root, id)

    /** 목적: 자산 안 파일의 위치를 준다. 입력: id - 자산 ID, target - 저장 경로 / 출력: 없음 / 리턴: File */
    fun file(id: String, target: String): File = File(dir(id), target)

    /** 목적: 받는 중인 파일의 위치를 준다. 입력: name - 이름(자산 ID 또는 자산 ID + 파일 표시) / 출력: 없음 / 리턴: File */
    fun partFile(name: String): File = File(File(root, "downloads"), "$name.part")

    /** 목적: 여유 공간을 읽는다. 입력: 없음 / 출력: 없음 / 리턴: Long - 바이트(뿌리가 아직 없으면 그 위 폴더 기준) */
    fun freeBytes(): Long = generateSequence(root) { it.parentFile }.first { it.exists() }.usableSpace

    /**
     * 목적: 자산이 쓸 수 있는 상태인지 본다.
     * 입력: spec - 자산 정의 / 출력: 없음 / 리턴: Boolean - .ready 가 있고 정의된 파일이 모두 있으면 true
     */
    fun isReady(spec: AssetSpec): Boolean =
        File(dir(spec.id), READY_FILE).isFile && spec.files.all { file(spec.id, it.target).isFile }

    /**
     * 목적: 준비 표시를 쓴다(모든 파일을 받고 검증한 뒤 마지막에 부른다).
     * 입력: spec - 자산 정의, info - 출처·검증 여부·파일별 해시
     * 출력: <자산 폴더>/.ready 가 쓰인다(임시 이름으로 쓴 뒤 이름을 바꾼다 — 쓰다 죽어도 반쪽 표시가 남지 않게)
     * 리턴: 없음
     */
    fun markReady(spec: AssetSpec, info: ReadyInfo) {
        val sb = StringBuilder()
        sb.append("id=").append(spec.id).append('\n')
        sb.append("source=").append(info.source).append('\n')
        sb.append("verified=").append(info.verified).append('\n')
        sb.append("summary=").append(info.summary).append('\n')
        for ((target, hash) in info.fileHashes.toSortedMap()) sb.append("sha256:").append(target).append('=').append(hash).append('\n')
        val tmp = File(dir(spec.id), "$READY_FILE.tmp")
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        val ready = File(dir(spec.id), READY_FILE)
        if (ready.exists()) ready.delete()                                     // 옛 표시 지우기
        check(tmp.renameTo(ready)) { "준비 표시를 쓰지 못했다: ${ready.path}" }   // 같은 폴더 안 이름 바꾸기
    }

    /**
     * 목적: 준비 표시를 읽는다.
     * 입력: id - 자산 ID / 출력: 없음 / 리턴: ReadyInfo? - 표시가 없으면 null
     */
    fun readyInfo(id: String): ReadyInfo? {
        val f = File(dir(id), READY_FILE)
        if (!f.isFile) return null
        val lines = f.readLines(Charsets.UTF_8).filter { '=' in it }           // "키=값" 줄만
        val map = lines.associate { it.substringBefore('=') to it.substringAfter('=') }
        val hashes = lines.filter { it.startsWith("sha256:") }
            .associate { it.removePrefix("sha256:").substringBeforeLast('=') to it.substringAfterLast('=') }   // 경로에 '=' 가 있어도 해시는 마지막 값
        return ReadyInfo(map["source"] ?: "", map["verified"] == "true", hashes)
    }

    /**
     * 목적: 자산을 지운다(폴더와 받다 만 파일).
     * 입력: id - 자산 ID / 출력: 파일이 지워진다 / 리턴: Boolean - 지운 것이 있으면 true
     */
    fun delete(id: String): Boolean {
        val had = dir(id).exists() || partFile(id).exists()
        dir(id).deleteRecursively()
        File(root, "downloads").listFiles { f -> f.name == "$id.part" || f.name.startsWith("$id~") }?.forEach { it.delete() }   // 파일별 part 포함
        return had
    }
}
