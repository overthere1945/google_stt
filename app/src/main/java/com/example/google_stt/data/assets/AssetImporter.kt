/*
 * 파일명: data/assets/AssetImporter.kt
 * 목적 및 기능:
 * - PC 에서 미리 받아 풀어 둔 자산을 휴대폰의 폴더(SAF)에서 앱 전용 저장소로 가져온다(D13 의 B 방법, FR-05-04).
 *   회사망의 HTTPS 검사 때문에 휴대폰이 직접 받지 못할 때 쓰는 길이다(D12).
 *     가져올 폴더(예: Documents/stt_assets)/<자산 ID>/<파일…>   ← PC 의 tools/fetch_assets.py 가 만든 낱개 파일(압축 해제 없음)
 * - 파일을 스트림으로 복사한 뒤 AssetStore.verify 로 SHA256 을 확인하고 준비 표시를 쓴다. 원본 폴더는 그대로 둔다.
 * - adb 로 Android/data 에 직접 넣은 파일은 앱이 읽지 못한 기록이 있어(v2.1.0) 이 방식(SAF 로 읽어 앱이 직접 쓰기)을 쓴다.
 *
 * add-hyungchul-20261106-1705 — [T4-4]
 */
package com.example.google_stt.data.assets

import android.content.ContentResolver                    // 문서 열기
import androidx.documentfile.provider.DocumentFile        // SAF 문서
import com.example.google_stt.domain.AppError             // 오류 분류
import com.example.google_stt.domain.catalog.AssetSpec    // 자산 정의
import java.io.File                                       // 복사 대상
import java.io.IOException                                // 읽기·쓰기 오류
import kotlinx.coroutines.currentCoroutineContext         // 취소 확인
import kotlinx.coroutines.ensureActive                    // 취소 확인

/**
 * 목적: 폴더에서 자산 가져오기.
 * 입력(생성자): resolver - 문서를 여는 ContentResolver, store - 자산 저장소
 */
class AssetImporter(private val resolver: ContentResolver, private val store: AssetStore) {

    private companion object {
        const val BUFFER = 64 * 1024   // 복사 단위 64 KB
    }

    /**
     * 목적: 가져올 폴더 안에서 자산 ID 와 이름이 같은 하위 폴더를 찾는다.
     * 입력: root - 사용자가 고른 폴더, specs - 자산 정의들
     * 출력: 없음
     * 리턴: Map<String, DocumentFile> - 자산 ID → 그 자산의 폴더(없는 자산은 빠진다)
     */
    fun find(root: DocumentFile, specs: Collection<AssetSpec>): Map<String, DocumentFile> {
        val dirs = root.listFiles().filter { it.isDirectory }.associateBy { it.name }   // 이름 → 폴더
        return specs.mapNotNull { s -> dirs[s.id]?.let { s.id to it } }.toMap()
    }

    /**
     * 목적: 자산 하나를 가져온다.
     * 입력: spec - 자산 정의(매니페스트 해시가 합쳐진 것), sourceDir - 그 자산의 낱개 파일이 든 폴더,
     *       onBytes - 복사한 바이트 수를 받는 함수(진행 표시용)
     * 출력: 앱 전용 저장소에 파일이 복사되고 준비 표시가 쓰인다. 실패하면 복사하던 것을 지운다
     * 리턴: ReadyInfo - 파일별 해시와 검증 여부
     * 예외: AppError.AssetExtract(E-AS-07) - 필요한 파일이 폴더에 없거나 읽을 수 없을 때,
     *       AppError.AssetHashMismatch(E-AS-06) - 해시가 다를 때. 취소되면 CancellationException
     */
    suspend fun import(spec: AssetSpec, sourceDir: DocumentFile, onBytes: (Long) -> Unit = {}): ReadyInfo {
        try {
            for (f in spec.files) {
                currentCoroutineContext().ensureActive()                          // 파일마다 취소 확인
                val src = locate(sourceDir, f.target)
                    ?: throw AppError.AssetExtract("${spec.id}: 가져올 폴더에 파일이 없다 — ${f.target}")
                copy(src, store.file(spec.id, f.target), onBytes)
            }
            return store.verify(spec, "import")                                   // 해시 확인 + 준비 표시
        } catch (e: AppError) {
            store.delete(spec.id)                                                 // 반쯤 가져온 자산을 남기지 않는다
            throw e
        }
    }

    /**
     * 목적: 폴더 아래에서 상대 경로("tokenizer/vocab.json" 같은)로 파일을 찾는다.
     * 입력: dir - 시작 폴더, relPath - 슬래시로 나눈 상대 경로 / 출력: 없음 / 리턴: DocumentFile? - 없으면 null
     */
    private fun locate(dir: DocumentFile, relPath: String): DocumentFile? {
        var cur: DocumentFile = dir
        for (seg in relPath.split('/')) cur = cur.findFile(seg) ?: return null     // 한 단계씩 내려간다
        return cur.takeIf { it.isFile }
    }

    /**
     * 목적: 문서 하나를 파일로 복사한다(".part" 로 쓴 뒤 이름을 바꾼다).
     * 입력: src - 원본 문서, dest - 대상 파일, onBytes - 진행 알림 / 출력: dest 가 쓰인다 / 리턴: 없음
     * 예외: AppError.AssetExtract - 읽기·쓰기 실패
     */
    private suspend fun copy(src: DocumentFile, dest: File, onBytes: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        try {
            val input = resolver.openInputStream(src.uri) ?: throw AppError.AssetExtract("원본을 열 수 없다: ${src.name}")
            input.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()                  // 조각마다 취소 확인
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        } catch (e: IOException) {
            throw AppError.AssetExtract("복사 실패(${src.name}): ${e.message}", e)
        } catch (e: SecurityException) {
            throw AppError.AssetExtract("폴더 권한이 없다(${src.name}): ${e.message}", e)
        }
        if (dest.exists()) dest.delete()                                          // 옛 파일 지우기
        if (!part.renameTo(dest)) throw AppError.AssetExtract("파일 이름을 바꿀 수 없다: ${dest.path}")
    }
}
