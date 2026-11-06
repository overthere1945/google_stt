/*
 * 파일명: data/saf/AudioScannerTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - 음원 모으기를 확인한다(FR-01-01).
 *     · 앱 전용 폴더에 만든 작은 트리: 재귀 수집, 확장자 거르기, 정렬, 하위 폴더 목록
 *     · 실제 입력 폴더: 앱이 읽기 권한을 보존한 "scarlett" 폴더가 있으면 kr 24 + en 30 = 54개를 모으고
 *       폴더 이름으로 언어가 정해지는지 확인한다(MT-UI-01 의 자료 부분). 없으면 건너뛴다.
 *
 * add-hyungchul-20261106-1636 — [T3-2]
 */
package com.example.google_stt.data.saf

import androidx.documentfile.provider.DocumentFile                 // SAF 문서
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 앱 Context 얻기
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.lang.LanguageResolver         // 폴더 이름 → 언어
import java.io.File                                                // 앱 전용 폴더
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assume.assumeTrue                                 // 조건이 안 되면 건너뛰기
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: AudioScanner 계측 시험. */
@RunWith(AndroidJUnit4::class)
class AudioScannerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext   // 시험 대상 앱의 Context

    /**
     * 목적: 작은 폴더 트리에서 음원만 재귀적으로 모으고, 상대 폴더 → 이름 순으로 정렬한다.
     * 입력: 없음 / 출력: 앱 캐시에 시험 폴더가 만들어졌다 지워진다 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun scan_collectsAudioRecursivelyAndSorted() {
        val root = File(context.cacheDir, "scan_it").apply { deleteRecursively(); mkdirs() }
        try {
            for (path in listOf("kr/B/02.wav", "kr/B/01.WAV", "kr/A/x.mp3", "kr/top.flac", "kr/A/notes.txt", "kr/A/sub/deep.opus", "en/01.m4a", "readme.md")) {
                File(root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(path.length)) }   // 크기 = 경로 길이
            }
            val kr = DocumentFile.fromFile(File(root, "kr"))
            val items = AudioScanner.scan(kr)
            assertEquals(listOf("/top.flac", "A/x.mp3", "A/sub/deep.opus", "B/01.WAV", "B/02.wav"),
                items.map { it.relDir.joinToString("/") + "/" + it.fileName })   // 최상위 → A → A/sub → B, 그 안에서 이름순
            val b01 = items.first { it.fileName == "01.WAV" }
            assertEquals("01", b01.baseName); assertEquals(".wav", b01.ext)      // 확장자는 소문자로
            assertEquals("kr/B/01.WAV".length.toLong(), b01.bytes)               // 파일 크기
            assertEquals(listOf("B"), b01.relDir)
            val subs = AudioScanner.subDirs(DocumentFile.fromFile(root))
            assertEquals(setOf("kr", "en"), subs.map { it.first }.toSet())       // 바로 아래 폴더만(파일 제외)
            assertTrue(subs.all { it.second.endsWith("/" + it.first) })          // 파일 기반 문서의 식별 글자는 경로
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * 목적: [MT-UI-01 자료 부분] 실제 입력 폴더 "scarlett" 에서 kr 24 + en 30 = 54개를 모으고 언어가 자동으로 정해진다.
     * 입력: 없음(앱이 읽기 권한을 보존한 폴더 중 이름이 scarlett 인 것을 쓴다. 없으면 건너뜀) / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun realInputFolder_scarlett_has54Files() {
        val root = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
            .mapNotNull { DocumentFile.fromTreeUri(context, it.uri) }.firstOrNull { it.name == "scarlett" }
        assumeTrue("앱이 읽기 권한을 보존한 'scarlett' 입력 폴더가 없어 건너뜀", root != null)
        val plan = LanguageResolver.plan(AudioScanner.docId(root!!), AudioScanner.subDirs(root), emptyMap())
        assertTrue(plan.splitByLang)
        assertEquals(listOf("en" to Language.EN, "kr" to Language.KO), plan.folders.map { it.name to it.lang })   // 이름으로 자동 판정
        assertTrue(plan.unknown.isEmpty())
        val counts = plan.folders.associate { f -> f.name to AudioScanner.scan(root.findFile(f.name)!!).size }
        assertEquals(mapOf("en" to 30, "kr" to 24), counts)                      // FR-01-01 수용 기준: 54개
        assertTrue(plan.folders.all { it.docId.endsWith("/scarlett/" + it.name) })   // 문서 ID(기억 값의 키)
    }
}
