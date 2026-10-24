package com.chargis.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.chargis.app.ui.ChargisRoot
import com.chargis.app.ui.TripViewModel
import com.chargis.app.ui.theme.ChargisTheme

/**
 * 앱의 화면 하나(Activity)다. 이 앱은 화면이 이것 하나뿐이고, 안에서 내용만 바꿔 그린다.
 * 사용자가 앱 아이콘을 누르면 안드로이드가 이 클래스를 띄운다.
 */
class MainActivity : ComponentActivity() {
    // 화면을 돌리거나 잠깐 다른 앱을 다녀와도 여행 상태가 사라지지 않도록 ViewModel에 보관한다.
    private val viewModel: TripViewModel by viewModels {
        TripViewModel.factory((application as ChargisApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 주행 중 화면이 꺼지지 않게 한다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 테마(색·글꼴)를 씌우고 그 안에 앱 화면을 그린다.
        setContent {
            ChargisTheme { ChargisRoot(viewModel.controller) }
        }
    }
}
