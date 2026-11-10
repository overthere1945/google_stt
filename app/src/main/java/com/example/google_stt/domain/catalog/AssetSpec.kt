/*
 * 파일명: domain/catalog/AssetSpec.kt
 * 목적 및 기능:
 * - 내려받을 자산(STT 모델 패키지, VAD 모델 파일) 1개의 정의를 둔다(4단계 2-1).
 * - 해시 값은 코드에 쓰지 않는다. assets/asset_manifest.json 에서 읽어 withHashes 로 합친다(코드 수정 없이 해시 교체).
 *
 * add-hyungchul-20261106-0033 — [T2-1]
 */
package com.example.google_stt.domain.catalog

/** 목적: 자산 종류. */
enum class AssetKind {
    STT_MODEL,   // STT 모델 패키지
    VAD_MODEL,   // VAD 모델 파일
}

/** 목적: 원본의 압축 형식. */
enum class ArchiveType {
    TAR_BZ2,   // tar.bz2(sherpa-onnx GitHub 릴리스)
    ZIP,       // zip(Vosk)
    NONE,      // 압축 없음 — 파일마다 "url/pathInArchive" 를 따로 받는다
}

/**
 * 목적: 자산 안의 파일 1개.
 * 필드: pathInArchive - 압축 파일 안 경로(ArchiveType.NONE 이면 url 뒤에 붙는 상대 경로),
 *       target - 자산 폴더 안에 둘 상대 경로, sha256 - 풀린 파일의 해시(없으면 null = "미검증")
 */
data class AssetFile(
    val pathInArchive: String,   // 원본 안 경로
    val target: String,          // 저장할 상대 경로
    val sha256: String? = null,  // 해시(매니페스트에서 채운다)
)

/**
 * 목적: 자산 1개의 정의.
 * 필드:
 * - id            : 자산 ID = 저장 폴더 이름(STT 는 공식 패키지 이름) 예 sherpa-onnx-whisper-base
 * - kind          : 자산 종류
 * - url           : 원본 주소 1개(압축 파일 주소, NONE 이면 파일들의 공통 앞부분)
 * - archive       : 압축 형식
 * - archiveBytes  : 압축 파일 크기(3단계 실측값, 모르면 null)
 * - archiveSha256 : 압축 파일 해시(매니페스트에서 채운다, 없으면 null)
 * - files         : 풀어서 남길 파일만(나머지는 버린다)
 * - license       : 라이선스 표기
 */
data class AssetSpec(
    val id: String,                    // 자산 ID
    val kind: AssetKind,               // 종류
    val url: String,                   // 원본 주소
    val archive: ArchiveType,          // 압축 형식
    val archiveBytes: Long?,           // 압축 파일 크기
    val archiveSha256: String? = null, // 압축 파일 해시
    val files: List<AssetFile>,        // 남길 파일
    val license: String,               // 라이선스
) {
    /** 모든 파일의 해시가 있는지(없으면 화면에 "미검증" 으로 보인다) */
    val verified: Boolean get() = files.isNotEmpty() && files.all { it.sha256 != null }

    /**
     * 목적: 매니페스트에서 읽은 해시를 합친 사본을 만든다.
     * 입력: archiveHash - 압축 파일 해시(null 이면 그대로 둔다), fileHashes - 저장 경로(target) → 해시
     * 출력: 없음(원본은 바뀌지 않는다)
     * 리턴: AssetSpec - 해시가 채워진 사본
     */
    fun withHashes(archiveHash: String?, fileHashes: Map<String, String>): AssetSpec = copy(
        archiveSha256 = archiveHash ?: archiveSha256,                               // 압축 파일 해시
        files = files.map { f -> f.copy(sha256 = fileHashes[f.target] ?: f.sha256) }, // 파일별 해시
    )
}
