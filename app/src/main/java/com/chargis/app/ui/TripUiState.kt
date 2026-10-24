package com.chargis.app.ui

import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.session.TripPhase

/** 주행 중 화면에 올라가는 카드 1장(plan.md §9.2). */
sealed interface DriveCard {
    data class Recommend(val rec: Recommendation) : DriveCard
    data class Congested(val primary: Recommendation, val alternative: Recommendation) : DriveCard
    data class Navigating(val stationName: String, val distanceKm: Double, val targetSoc: Double) : DriveCard
    data class Status(val chargePlanned: Boolean) : DriveCard
    data class NoSafeStop(val nearestName: String?, val nearestKm: Double?) : DriveCard
}

data class StopInfo(
    val stationName: String,
    val currentSoc: Double,
    val targetSoc: Double,
    val minutesToTarget: Int,
    val timeLimitMin: Int?,
    val minutesToLimit: Int?,
    val departAt: String
)

data class ScenarioOption(val id: String, val title: String)

data class DevState(
    val clock: String = "",
    val traveledKm: Double = 0.0,
    val speedMultiplier: Int = 1,
    val paused: Boolean = false
)

data class TripUiState(
    val phase: TripPhase = TripPhase.Idle,
    val isDriving: Boolean = false,
    val socPercent: Double = 0.0,
    val dataSource: DataSource = DataSource.SIMULATED,
    val remainingKm: Double = 0.0,
    val card: DriveCard = DriveCard.Status(chargePlanned = false),
    val stop: StopInfo? = null,
    val candidates: List<Recommendation> = emptyList(),
    val scenarios: List<ScenarioOption> = emptyList(),
    val dev: DevState = DevState(),
    val caption: String? = null
)
