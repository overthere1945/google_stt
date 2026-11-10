/*
 * 파일명: SttApp.kt
 * 목적 및 기능:
 * - 앱의 Application. 객체 묶음(AppContainer)을 하나 만들어 들고 있는다(수동 주입, 4단계 1-2).
 *   화면·서비스는 (application as SttApp).container 로 같은 저장소를 쓴다.
 * - 지금은 자산 받기(T4-7)에 필요한 것만 있다. 실행 쪽 객체는 T6-7 에서 AppContainer 에 더한다.
 *
 * add-hyungchul-20261106-1721 — [T4-7]
 */
package com.example.google_stt

import android.app.Application   // 앱 수명 동안 살아 있는 객체

/** 목적: Application. */
class SttApp : Application() {

    /** 앱에 하나뿐인 객체 묶음(처음 쓸 때 만든다) */
    val container: AppContainer by lazy { AppContainer(this) }
}
