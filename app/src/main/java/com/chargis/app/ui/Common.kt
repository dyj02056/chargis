package com.chargis.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.domain.model.DataSource
import com.chargis.app.ui.theme.ChargisColors
import kotlin.math.roundToInt

/** 주행 중에도 쓰는 큰 터치 타깃(≥ 96dp 높이). */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 96.dp),
        shape = RoundedCornerShape(20.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = ChargisColors.SurfaceHigh,
                contentColor = ChargisColors.OnSurface
            )
        }
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** 상단 상태줄: 배터리, 남은 거리, 데이터 출처(§9.4: 출처 구분 표시). 터치 요소 없음. */
@Composable
fun StatusStrip(soc: Double, remainingKm: Double, source: DataSource, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.card_status_sub, soc.roundToInt(), remainingKm.roundToInt()),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(
                when (source) {
                    DataSource.SDK -> R.string.source_sdk
                    DataSource.MANUAL -> R.string.source_manual
                    DataSource.SIMULATED -> R.string.source_simulated
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            color = ChargisColors.Muted
        )
    }
}

/** 음성 안내 자막. */
@Composable
fun CaptionBar(caption: String?, modifier: Modifier = Modifier) {
    if (caption.isNullOrBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ChargisColors.Surface
    ) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
            Text("🔊 $caption", style = MaterialTheme.typography.bodyLarge, color = ChargisColors.OnSurface)
        }
    }
}
