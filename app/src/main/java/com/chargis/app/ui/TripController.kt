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
import com.chargis.app.domain.session.AlertScheduler
import com.chargis.app.domain.session.AlertType
import com.chargis.app.domain.session.ChargingSessionTracker
import com.chargis.app.domain.session.ChargingSnapshot
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        TripUiState(
            scenarios = scenarios.map {
                ScenarioOption(it.id, it.title, it.totalDistanceKm, it.startSoc, "%02d:00".format(it.startHour))
            }
        )
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
    private var chargeFromSoc = 0.0
    private val charges = mutableListOf<Pair<String, ChargeRecord>>() // stationId to record
    private var arrivedAtSec: Double? = null
    private var captionClear: Job? = null
    // F3 충전 세션(§8)
    private var tracker: ChargingSessionTracker? = null
    private var alerts: AlertScheduler? = null
    private var snapshot: ChargingSnapshot? = null
    private var endedByLimit = false

    init {
        if (runClock) scope.launch { demo.run() }
        scope.launch {
            combine(navi.routeState, vehicle.vehicleState) { r, v -> r to v }
                .collect { (r, v) -> onTick(r, v) }
        }
        captions?.let { flow ->
            scope.launch {
                flow.collect { c ->
                    _state.update { it.copy(caption = c) }
                    captionClear?.cancel()
                    if (c != null) captionClear = scope.launch {
                        delay(CAPTION_MS)
                        _state.update { s -> if (s.caption == c) s.copy(caption = null) else s }
                    }
                }
            }
        }
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
        chargeFromSoc = soc
        endedByLimit = false
        tracker = ChargingSessionTracker(
            profile = demo.scenario.profile,
            config = config,
            stationMaxKw = rec.station.maxKw,
            targetSoc = rec.targetSocPercent,
            timeLimitMin = rec.timeLimitMin,
            startSec = chargeStartSec
        )
        val scheduler = AlertScheduler(rec.timeLimitMin).also { alerts = it }
        snapshot = tracker?.update(chargeStartSec, soc)
        // 충전 시작 감지 SDK가 없으므로 버튼으로 시작한다(§8.1). 데모에선 충전기가 제한 시각에 스스로 끊는다.
        demo.startCharging(rec.targetSocPercent, rec.timeLimitMin)
        transition(TripEvent.ChargingStarted)
        val started = scheduler.start()
        scope.launch { started.forEach { speakAlert(it) } }
    }

    fun stopCharging() {
        if (phase != TripPhase.Charging) return
        demo.stopCharging()
        recordCharge()
        transition(TripEvent.ChargingFinished)
    }

    /** §8.3 알림을 음성으로 낸다. 문구 속 숫자는 같은 스냅숏에서 가져온다. */
    private suspend fun speakAlert(type: AlertType) {
        val s = snapshot
        val rec = accepted
        val text = when (type) {
            AlertType.RULE_NOTICE -> rec?.timeLimitMin?.let(phrases::ruleNotice)
            AlertType.LIMIT_T10 -> s?.let { phrases.limitWarn(10, it.currentSoc, it.predictedSocAtLimit ?: it.currentSoc) }
            AlertType.LIMIT_T5 -> s?.let { phrases.limitWarn(5, it.currentSoc, it.predictedSocAtLimit ?: it.currentSoc) }
            AlertType.TARGET_T5 -> phrases.targetSoon()
            AlertType.TARGET_REACHED -> phrases.targetReached()
            AlertType.LIMIT_SHORTFALL -> s?.let { phrases.limitShortfall(it.currentSoc, it.targetSoc) }
        } ?: return
        val priority = if (type == AlertType.LIMIT_T5 || type == AlertType.LIMIT_SHORTFALL) Priority.HIGH else Priority.NORMAL
        announcer.announce(text, priority)
    }

    fun depart() {
        val charged = phase == TripPhase.Done
        demo.depart()
        transition(TripEvent.Depart)
        accepted = null
        tracker = null
        alerts = null
        snapshot = null
        endedByLimit = false
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
                    arrivedAtSec = demo.state.value.simTimeSec
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
                val t = tracker
                val scheduler = alerts
                if (t != null && scheduler != null) {
                    val snap = t.update(demo.state.value.simTimeSec, vehicleState.socPercent)
                    snapshot = snap
                    val due = scheduler.evaluate(snap)
                    val finished = snap.reached || snap.limitExpired || !demoCharging()
                    if (finished) {
                        if (demoCharging()) demo.stopCharging()
                        endedByLimit = snap.limitExpired && !snap.reached
                        recordCharge()
                        transition(TripEvent.ChargingFinished)
                    }
                    due.forEach { speakAlert(it) }
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

    private fun recordCharge() {
        val rec = accepted ?: return
        val sim = demo.state.value
        charges += rec.station.id to ChargeRecord(
            stationName = rec.station.name,
            fromSoc = chargeFromSoc,
            toSoc = sim.soc,
            minutes = ceil((sim.simTimeSec - chargeStartSec) / 60.0 - 1e-9).toInt(),
            endedByLimit = endedByLimit
        )
    }

    private fun transition(event: TripEvent) {
        val next = TripStateMachine.reduce(phase, event)
        // 화면이 바뀌면 이전 화면의 자막은 지운다. 새 안내가 나오면 다시 채워진다.
        if (next != phase) _state.update { it.copy(caption = null) }
        phase = next
        publish()
    }

    private fun resetTrip() {
        phase = TripStateMachine.reduce(phase, TripEvent.Reset)
        offer = null
        accepted = null
        lastResult = null
        noSafeWarned = false
        charges.clear()
        arrivedAtSec = null
        tracker = null
        alerts = null
        snapshot = null
        endedByLimit = false
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
                scenarioTitle = demo.scenario.title,
                ticketNo = "%s-%02d00".format(demo.scenario.id, demo.scenario.startHour),
                departAt = clockText(0.0),
                clock = clockText(sim.simTimeSec),
                eta = route?.remainingTimeMin?.let { min -> clockText(sim.simTimeSec + min * 60) } ?: it.eta,
                routeMarks = buildMarks(sim.traveledKm),
                progress = (sim.traveledKm / demo.scenario.totalDistanceKm).toFloat().coerceIn(0f, 1f),
                receipt = buildReceipt(v),
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
                DriveCard.Navigating(
                    stationName = acc.station.name,
                    distanceKm = acc.station.distanceAlongRouteKm,
                    targetSoc = acc.targetSocPercent,
                    arrivalSoc = acc.arrivalSocPercent,
                    chargeMinutes = acc.chargeMinutes,
                    timeLimitMin = acc.timeLimitMin
                )
            else -> when (val r = lastResult) {
                is RecommendationResult.NoSafeStop ->
                    DriveCard.NoSafeStop(r.nearest?.name, r.nearest?.distanceAlongRouteKm)
                is RecommendationResult.Recommended ->
                    DriveCard.Status(
                        chargePlanned = true,
                        plannedName = r.primary.station.name,
                        plannedKm = r.primary.station.distanceAlongRouteKm,
                        plannedTarget = r.primary.targetSocPercent,
                        plannedMinutes = r.primary.chargeMinutes
                    )
                else -> DriveCard.Status(chargePlanned = false)
            }
        }
    }

    private fun buildMarks(traveledKm: Double): List<RouteMark> {
        val sc = demo.scenario
        val chargedIds = charges.map { it.first }.toSet()
        val targetId = when (phase) {
            TripPhase.AwaitingDecision -> offer?.primary?.station?.id
            TripPhase.NavigatingToCharger, TripPhase.Arrived, TripPhase.Charging -> accepted?.station?.id
            else -> null
        }
        return sc.stations.map { s ->
            val state = when {
                s.id in chargedIds -> MarkState.CHARGED
                s.id == targetId -> MarkState.TARGET
                s.id in gate.declinedIds -> MarkState.DECLINED
                s.distanceAlongRouteKm < traveledKm -> MarkState.PASSED
                else -> MarkState.AHEAD
            }
            RouteMark(s.id, s.name, (s.distanceAlongRouteKm / sc.totalDistanceKm).toFloat(), state)
        }
    }

    private fun buildReceipt(v: VehicleState?): TripReceipt? {
        if (phase == TripPhase.Idle) return null
        val sc = demo.scenario
        val names = sc.stations.associate { it.id to it.name }
        return TripReceipt(
            departAt = clockText(0.0),
            startSoc = sc.startSoc,
            arriveAt = arrivedAtSec?.let(::clockText),
            endSoc = if (phase == TripPhase.Finished) v?.socPercent else null,
            distanceKm = sc.totalDistanceKm,
            charges = charges.map { it.second },
            declinedNames = gate.declinedIds.mapNotNull { names[it] }
        )
    }

    private fun buildStop(v: VehicleState?): StopInfo? {
        val rec = accepted ?: return null
        if (v == null || phase !in STOP_PHASES) return null
        val sim = demo.state.value
        val snap = snapshot
        if (phase == TripPhase.Charging && snap != null) {
            return StopInfo(
                stationName = rec.station.name,
                currentSoc = v.socPercent,
                targetSoc = rec.targetSocPercent,
                minutesToTarget = ceil(snap.minutesToTarget - 1e-9).toInt(),
                timeLimitMin = rec.timeLimitMin,
                minutesToLimit = snap.minutesToLimit?.let { max(0, ceil(it - 1e-9).toInt()) },
                departAt = clockText(snap.departAtSec),
                predictedSocAtLimit = snap.predictedSocAtLimit,
                willHitLimit = snap.willHitLimit
            )
        }
        // 도착(충전 전)·완료: 모델값으로 예상한다.
        val profile = demo.scenario.profile
        val toTarget = ChargeMath.chargeMinutes(v.socPercent, rec.targetSocPercent, rec.station.maxKw, profile, config)
        val atLimit = rec.timeLimitMin?.let { ChargeMath.socAfterMinutes(v.socPercent, it.toDouble(), rec.station.maxKw, profile, config) }
        return StopInfo(
            stationName = rec.station.name,
            currentSoc = v.socPercent,
            targetSoc = rec.targetSocPercent,
            minutesToTarget = ceil(toTarget).toInt(),
            timeLimitMin = rec.timeLimitMin,
            minutesToLimit = rec.timeLimitMin,
            departAt = clockText(sim.simTimeSec + toTarget * 60),
            predictedSocAtLimit = atLimit,
            willHitLimit = rec.timeLimitMin != null && toTarget > rec.timeLimitMin,
            endedByLimit = phase == TripPhase.Done && endedByLimit
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
        const val CAPTION_MS = 8_000L
        val STOP_PHASES = setOf(TripPhase.Arrived, TripPhase.Charging, TripPhase.Done)
    }
}
