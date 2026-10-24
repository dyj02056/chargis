package com.chargis.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chargis.app.domain.engine.station
import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.StationKind
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.model.WaitSource
import com.chargis.app.ui.drive.DriveCardScreen
import com.chargis.app.ui.drive.TicketMeta
import com.chargis.app.ui.parked.ParkedScreen
import com.chargis.app.ui.theme.ChargisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * Compose 화면 테스트. 에뮬레이터 없이 Robolectric으로 JVM에서 화면을 그려 글자와 버튼을 확인한다.
 * 차량 화면처럼 가로로 넓은 크기로 그린다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land")
class ScreenTest {
    @get:Rule
    val compose = createComposeRule()

    // 경로에서 2.5km 벗어난 마트. 시간 제한은 확인하지 못했다.
    private val mart = station("mart", 46.0, maxKw = 200.0, detourKm = 2.5, kind = StationKind.COMMERCIAL, openHours = OpenHours(600, 1320))
        .let { it.copy(name = "I 마트", rule = ChargerRule(timeLimitMin = null, amountLimitKrw = null, timeLimitKnown = false)) }

    private val rec = Recommendation(
        station = mart,
        arrivalSocPercent = 37.0,
        targetSocPercent = 50.0,
        chargeMinutes = 9,
        waitMinutes = 0,
        timeLimitMin = null,
        willHitTimeLimit = false,
        socAtTimeLimitPercent = null,
        needsAnotherStop = false,
        reasonCodes = emptyList(),
        waitSource = WaitSource.LIVE    // 대기는 알고 있다(시간 제한 칸만 "정보 없음"이 되게)
    )

    /** 추천 카드: 휴게소가 아닌 곳은 종류·왕복 우회·운영 시간을 덧붙이고, 확인하지 못한 시간 제한은 "정보 없음", 버튼은 "다음 충전소". */
    @Test
    fun recommendCard_showsOffRouteMetaAndUnknownLimit() {
        var accepted: String? = null
        compose.setContent {
            ChargisTheme(night = true) {
                DriveCardScreen(
                    card = DriveCard.Recommend(rec),
                    eta = "11:20",
                    remainingKm = 160.0,
                    meta = TicketMeta("S5-1100", "11:00"),
                    routeMarks = emptyList(),
                    progress = 0.2f,
                    onAccept = { accepted = it },
                    onDecline = {}
                )
            }
        }
        compose.onNodeWithText("I 마트").assertIsDisplayed()
        compose.onNodeWithText("상업시설 · 왕복 5km 우회 · 10:00~22:00 운영").assertIsDisplayed()
        compose.onNodeWithText("정보 없음").assertIsDisplayed()
        compose.onNodeWithText("다음 충전소").assertIsDisplayed()
        compose.onNodeWithText("경유지로 수락").performClick()
        assertEquals("mart", accepted)
    }

    /** 고속도로 휴게소에 제한 없음이 확인된 충전기면 덧붙이는 줄이 없고 제한 칸은 "없음". */
    @Test
    fun recommendCard_highwayRestStopKeepsPlainLook() {
        val rest = rec.copy(station = station("B", 46.0).copy(name = "B 휴게소"))
        compose.setContent {
            ChargisTheme(night = true) {
                DriveCardScreen(DriveCard.Recommend(rest), "11:20", 160.0, TicketMeta("S1-0900", "09:00"), emptyList(), 0.2f, {}, {})
            }
        }
        compose.onNodeWithText("B 휴게소").assertIsDisplayed()
        compose.onNodeWithText("없음").assertIsDisplayed()
        assertFalse(compose.onAllNodesWithTextExists("왕복"))
    }

    /** 설정: "휴게소·쉼터만"을 누르면 휴게소 밖 충전소를 끄는 설정으로 바뀐다. */
    @Test
    fun settings_restOnlyToggle() {
        var settings = UserSettings(onboarded = true)
        compose.setContent {
            ChargisTheme(night = true) {
                ParkedScreen(
                    state = TripUiState(settings = settings),
                    onStartScenario = {}, onStartCharging = {}, onStopCharging = {}, onDepart = {}, onRestart = {},
                    onSettings = { transform -> settings = transform(settings) }
                )
            }
        }
        compose.onNodeWithText("충전소 범위").assertIsDisplayed()
        compose.onNodeWithText("휴게소·쉼터만").performClick()
        assertFalse(settings.includeNonRoadside)
    }

    // 글자 일부("왕복")가 들어간 노드가 하나라도 있는지
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextExists(part: String) =
        onAllNodes(androidx.compose.ui.test.hasText(part, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
