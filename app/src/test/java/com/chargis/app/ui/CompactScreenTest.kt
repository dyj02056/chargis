package com.chargis.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.chargis.app.domain.engine.station
import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.model.WaitSource
import com.chargis.app.ui.drive.DriveCardScreen
import com.chargis.app.ui.drive.TicketMeta
import com.chargis.app.ui.parked.ParkedScreen
import com.chargis.app.ui.theme.ChargisTheme
import com.chargis.app.ui.ticket.LocalCompact
import com.chargis.app.ui.ticket.LocalShrinkFigures
import com.chargis.app.ui.ticket.ProvideCompact
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 좁은 창 화면 테스트. Pleos Connect는 앱 창에 앱을 1~2개 띄우고 가로·세로를 모두 쓴다.
 * 기본 크기는 앱 2개 분할(640×800dp)이고, 화면을 [ProvideCompact]로 감싸 실제 앱처럼 폭을 재게 한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h800dp-port")
class CompactScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val rec = Recommendation(
        station = station("B", 46.0).copy(name = "B 휴게소", rule = ChargerRule(timeLimitMin = 40, amountLimitKrw = null, timeLimitKnown = true)),
        arrivalSocPercent = 37.0,
        targetSocPercent = 50.0,
        chargeMinutes = 9,
        waitMinutes = 6,
        timeLimitMin = 40,
        willHitTimeLimit = false,
        socAtTimeLimitPercent = null,
        needsAnotherStop = false,
        reasonCodes = emptyList(),
        waitSource = WaitSource.LIVE
    )

    /** 창 크기에 따라 좁은 창·숫자 줄이기가 정해진다: 분할은 둘 다, 세로는 좁은 창만, 가로는 둘 다 아님. */
    @Test
    fun provideCompact_split() = assertWindow(compact = true, shrink = true)

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port")
    fun provideCompact_portrait() = assertWindow(compact = true, shrink = false)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun provideCompact_landscape() = assertWindow(compact = false, shrink = false)

    private fun assertWindow(compact: Boolean, shrink: Boolean) {
        var seen: Pair<Boolean, Boolean>? = null
        compose.setContent { ProvideCompact { seen = LocalCompact.current to LocalShrinkFigures.current } }
        compose.waitForIdle()
        assertEquals(compact to shrink, seen)
    }

    /** 분할 화면 추천 카드: 스크롤 없이 네 칸과 버튼 두 개가 모두 화면 안에 보인다. */
    @Test
    fun recommendCard_fitsSplitWindow() {
        var accepted: String? = null
        compose.setContent {
            ChargisTheme(night = true) {
                ProvideCompact {
                    DriveCardScreen(DriveCard.Recommend(rec), "11:20", 160.0, TicketMeta("S1-0900", "09:00"), emptyList(), 0.2f, { accepted = it }, {})
                }
            }
        }
        listOf("B 휴게소", "목표 충전", "충전", "대기", "도착 시", "제한", "다음 충전소").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("경유지로 수락").assertIsDisplayed().performClick()
        assertEquals("B", accepted)
    }

    /** 분할 화면 발권: 시나리오 표와 설정 요약을 한 줄로 쌓아 스크롤로 모두 볼 수 있다. */
    @Test
    fun issueScreen_stacksInSplitWindow() {
        val scenarios = (1..6).map { ScenarioOption("s$it", "S$it 시나리오 · 서울→속초(가상)", 210.0, 60.0, "09:00") }
        var started: String? = null
        compose.setContent {
            ChargisTheme(night = true) {
                ProvideCompact {
                    ParkedScreen(
                        state = TripUiState(settings = UserSettings(onboarded = true), scenarios = scenarios),
                        onStartScenario = { started = it }, onStartCharging = {}, onStopCharging = {}, onDepart = {}, onRestart = {}
                    )
                }
            }
        }
        compose.onNodeWithText("S1 시나리오 · 서울→속초(가상)").assertIsDisplayed()
        compose.onNodeWithText("S6 시나리오 · 서울→속초(가상)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("설정").performScrollTo().assertIsDisplayed()
        assertEquals(null, started)
    }
}
