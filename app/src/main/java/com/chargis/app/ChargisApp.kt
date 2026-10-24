package com.chargis.app

import android.app.Application

/**
 * 앱 전체를 대표하는 객체. 앱이 켜질 때 화면보다 먼저 한 번 만들어진다.
 * 여기서 부품 조립대([AppContainer])를 만들어 두고, 화면은 필요할 때 꺼내 쓴다.
 */
class ChargisApp : Application() {
    // lateinit = "지금은 비어 있지만 onCreate에서 반드시 채운다"는 약속
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
