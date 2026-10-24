package com.chargis.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.chargis.app.ui.ChargisRoot
import com.chargis.app.ui.TripViewModel
import com.chargis.app.ui.theme.ChargisTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TripViewModel by viewModels {
        TripViewModel.factory((application as ChargisApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ChargisTheme { ChargisRoot(viewModel.controller) }
        }
    }
}
