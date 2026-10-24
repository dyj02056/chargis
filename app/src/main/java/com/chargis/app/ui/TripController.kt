package com.chargis.app.ui

import com.chargis.app.domain.engine.AnnouncementGate
import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.engine.RecommendationEngine
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RecommendationResult
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.domain.session.TripEvent
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.domain.session.TripStateMachine
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.NaviPort
import com.chargis.app.port.VehiclePort
import com.chargis.app.sim.Scenario
import com.chargis.app.sim.ScenarioSimulator
import com.chargis.app.sim.SimPhase
import com.chargis.app.voice.VoiceAnnouncer
import com.chargis.app.voice.VoicePhrases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

/**
 * plan.md §8.1 상태머신을 돌리는 여행 코디네이터. 안드로이드 의존이 없어 JVM 테스트로 시나리오를 끝까지 돌릴 수 있다.
 * [demo]는 데모 모드에서 시나리오 시작/충전/출발 같은 "현실에서 운전자가 하는 일"을 흉내 낸다.
 */
class TripController(
    private val scope: CoroutineScope,
    private val navi: NaviPort,
    private val vehicle: VehiclePort,
    private val chargerData: ChargerDataPort,
    private val announcer: VoiceAnnouncer,
    private val phrases: VoicePhrases,
    private val demo: ScenarioSimulator,
    private val scenarios: List<Scenario>,
    private val config: RecommendationConfig = RecommendationConfig(),
    captions: Flow<String?>? = null,
    runClock: Boolean = true
) {
    private val engine = RecommendationEngine(config)
    private val gate = AnnouncementGate(config.announceWithinKm)

    private val _state = MutableStateFlow(
        TripUiState(scenarios = scenarios.map { ScenarioOption(it.id, it.title) })
    )
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    private var phase = TripPhase.Idle
    private var offer: RecommendationResult.Recommended? = null
    private var accepted: Recommendation? = null
    private var lastResult: RecommendationResult? = null
    private var lastRoute: RouteState? = null
    private var lastVehicle: VehicleState? = null
    private var stations: List<ChargingStation> = emptyList()
    private var noSafeWarned = false
    private var chargeStartSec = 0.0

    init {
        if (runClock) scope.launch { demo.run() }
        scope.launch {
            combine(navi.routeState, vehicle.vehicleState) { r, v -> r to v }
                .collect { (r, v) -> onTick(r, v) }
        }
        captions?.let { flow -> scope.launch { flow.collect { c -> _state.update { it.copy(caption = c) } } } }
    }

    // ---- 사용자 액션 ----

    fun startScenario(id: String) {
        demo.load(scenarios.first { it.id == id })
        resetTrip()
        demo.start()
        transition(TripEvent.DestinationSet)
    }

    fun restart() {
        demo.load(demo.scenario)
        resetTrip()
        publish()
    }

    /** 추천 카드의 [수락] 또는 혼잡 비교 카드에서 휴게소 선택. */
    fun accept(stationId: String) {
        val current = offer ?: return
        val rec = (listOf(current.primary) + current.alternatives).firstOrNull { it.station.id == stationId } ?: return
        scope.launch {
            navi.addWaypoint(rec.station)
                .onSuccess {
                    navi.requestReRoute()
                    if (rec.station.id != current.primary.station.id) gate.markDeclined(current.primary.station.id)
                    gate.markAnnounced(rec.station.id)
                    accepted = rec
                    offer = null
                    transition(TripEvent.Accepted)
                    announcer.announce(phrases.waypointAdded(rec.station.name))
                }
                .onFailure { announcer.announce(phrases.waypointFailed(), Priority.HIGH) }
        }
    }

    /** [다음 휴게소]: 이번 1순위를 거절하고 다음 후보를 기다린다. */
    fun decline() {
        val current = offer ?: return
        gate.markDeclined(current.primary.station.id)
        offer = null
        transition(TripEvent.Declined)
    }

    fun startCharging() {
        val rec = accepted ?: return
        if (phase != TripPhase.Arrived) return
        val soc = lastVehicle?.socPercent ?: return
        if (rec.targetSocPercent <= soc) {
            transition(TripEvent.ChargingStarted)
            transition(TripEvent.ChargingFinished)
            return
        }
        chargeStartSec = demo.state.value.simTimeSec
        demo.startCharging(rec.targetSocPercent)
        transition(TripEvent.ChargingStarted)
        rec.timeLimitMin?.let { limit -> scope.launch { announcer.announce(phrases.ruleNotice(limit)) } }
    }

    fun stopCharging() {
        if (phase != TripPhase.Charging) return
        demo.stopCharging()
        transition(TripEvent.ChargingFinished)
    }

    fun depart() {
        val charged = phase == TripPhase.Done
        demo.depart()
        transition(TripEvent.Depart)
        accepted = null
        if (charged) scope.launch { announcer.announce(phrases.resume()) }
    }

    // ---- 개발자 오버레이 ----

    fun devSetSpeed(multiplier: Int) = demo.setSpeed(multiplier).also { publish() }
    fun devTogglePause() = demo.togglePause().also { publish() }
    fun devJump(km: Double) = demo.jumpAhead(km)

    // ---- 내부 ----

    private suspend fun onTick(route: RouteState?, vehicleState: VehicleState) {
        lastVehicle = vehicleState
        if (route == null) {
            publish()
            return
        }
        lastRoute = route

        when (phase) {
            TripPhase.TripPlanned -> {
                val result = evaluate(route, vehicleState)
                val stops = when (result) {
                    is RecommendationResult.Recommended -> if (result.primary.needsAnotherStop) 2 else 1
                    is RecommendationResult.NoSafeStop -> 1
                    RecommendationResult.NotNeeded -> 0
                }
                transition(TripEvent.PlanEvaluated)
                announcer.announce(phrases.tripSummary(stops))
            }
            TripPhase.Driving -> {
                if (route.remainingDistanceKm <= ARRIVED_KM) {
                    transition(TripEvent.DestinationReached)
                } else {
                    when (val result = evaluate(route, vehicleState)) {
                        is RecommendationResult.Recommended ->
                            if (gate.shouldAnnounce(result.primary)) announce(result)
                        is RecommendationResult.NoSafeStop -> if (!noSafeWarned) {
                            noSafeWarned = true
                            announcer.announce(phrases.noSafeStop(), Priority.HIGH)
                        }
                        RecommendationResult.NotNeeded -> Unit
                    }
                }
            }
            TripPhase.AwaitingDecision -> refreshOffer(route, vehicleState)
            TripPhase.NavigatingToCharger -> {
                if (!vehicleState.isDriving) {
                    transition(TripEvent.ArrivedAtStop)
                } else {
                    keepOrSwitchAccepted(route, vehicleState)
                }
            }
            TripPhase.Charging -> {
                if (!demoCharging()) {
                    transition(TripEvent.ChargingFinished)
                    announcer.announce(phrases.targetReached())
                }
            }
            TripPhase.Resuming -> transition(TripEvent.ResumeComplete)
            TripPhase.Idle, TripPhase.Arrived, TripPhase.Done, TripPhase.Finished -> Unit
        }
        publish()
    }

    private suspend fun evaluate(route: RouteState, v: VehicleState): RecommendationResult {
        stations = chargerData.stationsAlongRoute(route).filter { it.id !in gate.declinedIds }
        return engine.recommend(route, v, stations, demo.scenario.profile).also { lastResult = it }
    }

    private suspend fun announce(result: RecommendationResult.Recommended) {
        offer = result
        gate.markAnnounced(result.primary.station.id)
        transition(TripEvent.RecommendationAnnounced)
        val p = result.primary
        val alt = result.alternatives.firstOrNull()
        if (result.congested && alt != null) {
            announcer.announce(phrases.congested(p.station.name, p.waitMinutes, alt.station.name))
        } else {
            announcer.announce(phrases.recommend(p.station.distanceAlongRouteKm, p.station.name, p.targetSocPercent))
            if (p.needsAnotherStop) announcer.announce(phrases.needAnother())
        }
    }

    /** 안내 후 결정 대기 중: 거리 표시를 갱신하고, 휴게소를 지나쳤으면 거절로 처리한다. */
    private suspend fun refreshOffer(route: RouteState, v: VehicleState) {
        val current = offer ?: return
        val result = evaluate(route, v)
        val ids = stations.map { it.id }.toSet()
        if (current.primary.station.id !in ids) {
            decline()
            return
        }
        if (result is RecommendationResult.Recommended && result.primary.station.id == current.primary.station.id) {
            offer = result.copy(congested = current.congested)
        } else {
            offer = current.copy(primary = current.primary.withDistanceFrom(stations))
        }
    }

    /** §7.3 히스테리시스: 수락한 휴게소는 크게 나빠지거나 사라질 때만 바꾼다. */
    private suspend fun keepOrSwitchAccepted(route: RouteState, v: VehicleState) {
        val acc = accepted ?: return
        val result = evaluate(route, v)
        if (stations.none { it.id == acc.station.id }) return // 도착 직전/직후 — 바꾸지 않는다
        val replacement = gate.replacementFor(acc, result)
        if (replacement != null && replacement.station.id != acc.station.id) {
            navi.addWaypoint(replacement.station).onSuccess {
                accepted = replacement
                gate.markAnnounced(replacement.station.id)
                announcer.announce(phrases.waypointAdded(replacement.station.name))
            }
        } else {
            accepted = acc.withDistanceFrom(stations)
        }
    }

    private fun Recommendation.withDistanceFrom(list: List<ChargingStation>): Recommendation {
        val s = list.firstOrNull { it.id == station.id } ?: return this
        return copy(station = station.copy(distanceAlongRouteKm = s.distanceAlongRouteKm))
    }

    private fun demoCharging() = demo.state.value.phase == SimPhase.CHARGING

    private fun transition(event: TripEvent) {
        phase = TripStateMachine.reduce(phase, event)
        publish()
    }

    private fun resetTrip() {
        phase = TripStateMachine.reduce(phase, TripEvent.Reset)
        offer = null
        accepted = null
        lastResult = null
        noSafeWarned = false
        gate.reset()
        announcer.reset()
    }

    private fun publish() {
        val v = lastVehicle
        val route = lastRoute
        val sim = demo.state.value
        _state.update {
            it.copy(
                phase = phase,
                isDriving = v?.isDriving ?: false,
                socPercent = v?.socPercent ?: 0.0,
                dataSource = v?.source ?: it.dataSource,
                remainingKm = route?.remainingDistanceKm ?: 0.0,
                card = buildCard(),
                stop = buildStop(v),
                candidates = (lastResult as? RecommendationResult.Recommended)
                    ?.let { r -> listOf(r.primary) + r.alternatives }.orEmpty(),
                dev = DevState(
                    clock = clockText(sim.simTimeSec),
                    traveledKm = sim.traveledKm,
                    speedMultiplier = sim.speedMultiplier,
                    paused = sim.paused
                )
            )
        }
    }

    private fun buildCard(): DriveCard {
        val o = offer
        val acc = accepted
        return when {
            phase == TripPhase.AwaitingDecision && o != null -> {
                val alt = o.alternatives.firstOrNull()
                if (o.congested && alt != null) DriveCard.Congested(o.primary, alt) else DriveCard.Recommend(o.primary)
            }
            phase == TripPhase.NavigatingToCharger && acc != null ->
                DriveCard.Navigating(acc.station.name, acc.station.distanceAlongRouteKm, acc.targetSocPercent)
            else -> when (val r = lastResult) {
                is RecommendationResult.NoSafeStop ->
                    DriveCard.NoSafeStop(r.nearest?.name, r.nearest?.distanceAlongRouteKm)
                is RecommendationResult.Recommended -> DriveCard.Status(chargePlanned = true)
                else -> DriveCard.Status(chargePlanned = false)
            }
        }
    }

    private fun buildStop(v: VehicleState?): StopInfo? {
        val rec = accepted ?: return null
        if (v == null || phase !in STOP_PHASES) return null
        val profile = demo.scenario.profile
        val toTarget = ChargeMath.chargeMinutes(v.socPercent, rec.targetSocPercent, rec.station.maxKw, profile, config)
        val sim = demo.state.value
        val elapsedMin = if (phase == TripPhase.Charging) (sim.simTimeSec - chargeStartSec) / 60.0 else 0.0
        val toLimit = rec.timeLimitMin?.let { max(0, ceil(it - elapsedMin).toInt()) }
        return StopInfo(
            stationName = rec.station.name,
            currentSoc = v.socPercent,
            targetSoc = rec.targetSocPercent,
            minutesToTarget = ceil(toTarget).toInt(),
            timeLimitMin = rec.timeLimitMin,
            minutesToLimit = toLimit,
            departAt = clockText(sim.simTimeSec + toTarget * 60)
        )
    }

    private fun clockText(simSec: Double): String {
        val total = demo.scenario.startHour * 3600 + simSec.toLong()
        val h = (total / 3600) % 24
        val m = (total % 3600) / 60
        return "%02d:%02d".format(h, m)
    }

    private companion object {
        const val ARRIVED_KM = 0.05
        val STOP_PHASES = setOf(TripPhase.Arrived, TripPhase.Charging, TripPhase.Done)
    }
}
