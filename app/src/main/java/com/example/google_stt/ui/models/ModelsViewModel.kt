/*
 * 파일명: ui/models/ModelsViewModel.kt
 * 목적 및 기능:
 * - 모델 탭의 상태와 동작(4단계 4-4, FR-05): 자산 목록과 상태, 받기·삭제·필요한 것 모두 받기·PC 에서 가져오기.
 * - 받기는 포그라운드 서비스(RunService 받기 모드)에 맡긴다 → 화면이 꺼지거나 앱이 뒤로 가도 이어진다.
 * - 가져오기는 고른 폴더 아래 "<자산 ID>" 폴더를 찾아 앱 전용 저장소로 복사하고 SHA256 을 확인한다(D13 의 B 방법).
 *
 * add-hyungchul-20261106-2329 — [T7-4]
 */
package com.example.google_stt.ui.models

import android.content.Context                                       // 서비스 시작·폴더 열기
import android.net.Uri                                               // 가져올 폴더
import androidx.documentfile.provider.DocumentFile                   // 폴더
import androidx.lifecycle.ViewModel                                  // 화면 상태 보관
import androidx.lifecycle.viewModelScope                             // 화면 수명의 코루틴
import com.example.google_stt.AppContainer                           // 객체 묶음
import com.example.google_stt.data.assets.AssetImporter              // 폴더에서 가져오기
import com.example.google_stt.data.assets.AssetState                 // 자산 상태
import com.example.google_stt.domain.AppError                        // 가져오기 오류
import com.example.google_stt.domain.catalog.AssetKind               // 자산 종류
import com.example.google_stt.domain.catalog.Catalog                 // 모델 목록(상업 조건부 표시)
import com.example.google_stt.domain.catalog.Commercial              // 상업 판정
import com.example.google_stt.run.RunService                         // 받기 서비스
import kotlinx.coroutines.Dispatchers                                // 입출력 스레드
import kotlinx.coroutines.delay                                      // 갱신 간격
import kotlinx.coroutines.flow.MutableStateFlow                      // 상태 보관
import kotlinx.coroutines.flow.StateFlow                             // 상태 읽기
import kotlinx.coroutines.flow.update                                // 상태 고치기
import kotlinx.coroutines.launch                                     // 코루틴 시작
import kotlinx.coroutines.withContext                                // 스레드 바꾸기

/**
 * 목적: 자산 한 줄.
 * 필드: id - 자산 ID, kind - 종류(STT / VAD), state - 상태, bytes - 받을 크기(모르면 null), license - 라이선스, conditional - 상업 조건부인지
 */
data class AssetUi(val id: String, val kind: AssetKind, val state: AssetState, val bytes: Long?, val license: String, val conditional: Boolean)

/**
 * 목적: 모델 탭의 화면 상태.
 * 필드: assets - 자산 목록(Catalog 순서), freeBytes - 여유 공간, busy - 가져오는 중, message - 안내·오류 한 줄
 */
data class ModelsUiState(val assets: List<AssetUi> = emptyList(), val freeBytes: Long = 0, val busy: Boolean = false, val message: String = "")

/**
 * 목적: 모델 탭 ViewModel.
 * 입력(생성자): c - 객체 묶음, context - Application Context
 */
class ModelsViewModel(private val c: AppContainer, private val context: Context) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())

    /** 화면 상태 */
    val state: StateFlow<ModelsUiState> = _state

    /** 상업 조건부 모델이 쓰는 자산 ID 들 */
    private val conditional = Catalog.models.filter { it.commercial == Commercial.CONDITIONAL }.mapNotNull { it.assetId }.toSet()

    /**
     * 목적: 자산 상태를 0.5초마다 다시 읽는다(받는 중의 진행률이 바뀐다). 화면이 보이는 동안만 부른다 — 취소될 때까지 돈다.
     * 입력: 없음 / 출력: 상태 / 리턴: 없음
     */
    suspend fun poll() {   // change-hyungchul-20261107-0000 : WP7 독립 검토 7·9 — ViewModel 수명 내내 돌지 않고, 파일 확인은 입출력 스레드에서
        while (true) {
            val (rows, free) = withContext(Dispatchers.IO) {
                c.assetSpecs.values.map { s -> AssetUi(s.id, s.kind, c.assets.state(s.id).value, s.archiveBytes, s.license, s.id in conditional) } to c.assetStore.freeBytes()
            }
            _state.update { it.copy(assets = rows, freeBytes = free) }
            delay(500)
        }
    }

    /** 목적: 자산들을 받는다. 입력: ids - 자산 ID 들 / 출력: 받기 서비스 시작 / 리턴: 없음 */
    fun download(ids: Collection<String>) {
        if (ids.isEmpty()) { _state.update { it.copy(message = "받을 것이 없다(필요한 모델이 모두 준비됐다)") }; return }
        try {
            RunService.download(context, ids)
            _state.update { it.copy(message = "받기 시작: ${ids.size}개") }
        } catch (e: IllegalStateException) {                                        // 앱이 뒤에 있으면 서비스를 시작할 수 없다
            _state.update { it.copy(message = "받기를 시작하지 못했다 — 앱 화면에서 다시 누른다(${e.message})") }
        }
    }

    /** 목적: 받기를 멈춘다(받던 파일은 남아 다음에 이어받는다). 입력: 없음 / 출력: 서비스에 요청 / 리턴: 없음 */
    fun stopDownload() = RunService.stopDownloads(context)   // change-hyungchul-20261107-0000 : WP7 독립 검토 1 — 실행은 멈추지 않는다

    /** 목적: 자산을 지운다. 입력: id - 자산 ID / 출력: 파일 삭제 / 리턴: 없음 */
    fun delete(id: String) {
        if (c.controller.state.value.phase.active) {   // change-hyungchul-20261107-0000 : WP7 독립 검토 11 — 실행이 쓰고 있을 수 있다
            _state.update { it.copy(message = "실행 중에는 모델을 지울 수 없다") }
            return
        }
        viewModelScope.launch { c.assets.delete(id) }
    }

    /**
     * 목적: 폴더에서 자산을 가져온다(PC 에서 받아 옮겨 둔 것).
     * 입력: uri - 고른 폴더(그 아래에 "<자산 ID>" 폴더들이 있어야 한다. "stt_assets" 폴더 자체나 그 위 폴더를 골라도 된다)
     * 출력: 앱 전용 저장소에 자산, 상태의 안내 문구 / 리턴: 없음
     */
    fun importFrom(uri: Uri) = viewModelScope.launch {
        if (c.controller.state.value.phase.active) {                           // change-hyungchul-20261109-1019 : 9단계 통합 검토 B4 — 실행이 쓰는 모델을 바꾸지 않는다(삭제와 같은 규칙)
            _state.update { it.copy(message = "실행 중에는 모델을 가져올 수 없다") }
            return@launch
        }
        _state.update { it.copy(busy = true, message = "가져오는 중…") }
        val message = withContext(Dispatchers.IO) {
            val picked = DocumentFile.fromTreeUri(context, uri) ?: return@withContext "폴더를 열 수 없다"
            val root = picked.findFile("stt_assets")?.takeIf { it.isDirectory } ?: picked   // 위 폴더를 골랐으면 안의 stt_assets 를 쓴다
            val importer = AssetImporter(context.contentResolver, c.assetStore)
            val found = importer.find(root, c.assetSpecs.values)
            if (found.isEmpty()) return@withContext "가져올 자산 폴더가 없다(폴더 이름이 자산 ID 와 같아야 한다)"
            var ok = 0; val failed = ArrayList<String>(); var kept = 0
            for ((id, dir) in found) {
                if (c.assets.state(id).value is AssetState.Ready) { kept++; continue }   // change-hyungchul-20261109-1019 : 9단계 통합 검토 B4 — 이미 준비된 자산은 건드리지 않는다(가져오기가 실패하면 멀쩡한 모델이 지워진다)
                try {
                    importer.import(c.assetSpecs.getValue(id), dir); ok++
                } catch (e: AppError) {
                    failed += "$id(${e.code})"; c.log.w("가져오기 실패 $id: ${e.message}")
                }
            }
            "가져오기: 성공 ${ok}개" + (if (kept > 0) ", 이미 준비됨 ${kept}개(건너뜀)" else "") + if (failed.isEmpty()) "" else ", 실패 ${failed.joinToString()}"
        }
        c.assets.refresh()
        _state.update { it.copy(busy = false, message = message) }
    }
}
