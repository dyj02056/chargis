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
 *
 * 앱의 "지휘자"다. 하는 일은 크게 세 가지다.
 * 1. 내비와 차량에서 새 값이 들어올 때마다([onTick]) 지금 단계에 맞는 일을 한다.
 *    예) 주행 중이면 추천을 계산하고, 충전 중이면 남은 시간과 알림을 계산한다.
 * 2. 운전자가 버튼을 누르면([accept], [decline], [startCharging], [depart] 등) 단계를 넘긴다.
 * 3. 화면에 보여 줄 모든 값을 [state] 하나로 묶어 내보낸다([publish]). 화면은 이 값만 보고 그린다.
 *
 * 코루틴(CoroutineScope, launch): 화면을 멈추지 않고 뒤에서 동시에 일을 처리하는 코틀린의 방법이다.
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
    captions: Flow<String?>? = null,   // 음성 안내 문구. 화면 하단 자막으로도 띄운다.
    runClock: Boolean = true           // false면 시뮬레이터 시계를 자동으로 돌리지 않는다(테스트용).
) {
    private val engine = RecommendationEngine(config)
    private val gate = AnnouncementGate(config.announceWithinKm)

    // 화면 상태. _state(밑줄)는 이 클래스 안에서만 고칠 수 있고, 바깥(화면)에는 읽기 전용 state로 보여 준다.
    private val _state = MutableStateFlow(
        TripUiState(
            scenarios = scenarios.map {
                ScenarioOption(it.id, it.title, it.totalDistanceKm, it.startSoc, "%02d:00".format(it.startHour))
            }
        )
    )
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    // ---- 여행 진행 중 기억해 두는 값들 ----
    private var phase = TripPhase.Idle                       // 지금 여행 단계
    // offer = 안내했지만 아직 답을 받지 못한 추천, accepted = 운전자가 수락한 추천
    private var offer: RecommendationResult.Recommended? = null
    private var accepted: Recommendation? = null
    private var lastResult: RecommendationResult? = null
    private var lastRoute: RouteState? = null
    private var lastVehicle: VehicleState? = null
    private var stations: List<ChargingStation> = emptyList()
    private var noSafeWarned = false                         // "갈 수 있는 충전소 없음" 경고를 이미 했는지
    private var chargeStartSec = 0.0
    private var chargeFromSoc = 0.0
    private val charges = mutableListOf<Pair<String, ChargeRecord>>() // stationId to record
    private var arrivedAtSec: Double? = null                 // 목적지 도착 시각(영수증용)
    private var captionClear: Job? = null                    // 자막을 일정 시간 뒤 지우는 예약 작업
    // F3 충전 세션(§8)
    private var tracker: ChargingSessionTracker? = null
    private var alerts: AlertScheduler? = null
    private var snapshot: ChargingSnapshot? = null
    private var endedByLimit = false

    // 만들어지자마자 실행되는 준비 작업
    init {
        // 데모 시계를 돌린다(가상의 차가 움직이기 시작).
        if (runClock) scope.launch { demo.run() }
        // 경로나 차량 값 중 하나라도 바뀌면 둘을 묶어 onTick을 부른다.
        scope.launch {
            combine(navi.routeState, vehicle.vehicleState) { r, v -> r to v }
                .collect { (r, v) -> onTick(r, v) }
        }
        // 새 음성 안내가 나오면 자막으로 띄우고, 8초 뒤 자동으로 지운다.
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
    // 화면의 버튼을 누르면 불리는 함수들이다.

    /** 시나리오(예: S1 서울→속초) 하나를 골라 새 여행을 시작한다. */
    fun startScenario(id: String) {
        demo.load(scenarios.first { it.id == id })
        resetTrip()
        demo.start()
        transition(TripEvent.DestinationSet)
    }

    /** 같은 시나리오를 처음 상태로 되돌린다. */
    fun restart() {
        demo.load(demo.scenario)
        resetTrip()
        publish()
    }

    /** 추천 카드의 [수락] 또는 혼잡 비교 카드에서 휴게소 선택. */
    fun accept(stationId: String) {
        val current = offer ?: return
        val rec = (listOf(current.primary) + current.alternatives).firstOrNull { it.station.id == stationId } ?: return
        // 내비에 경유지를 추가한다. 성공하면 수락 처리, 실패하면 음성으로 알린다.
        scope.launch {
            navi.addWaypoint(rec.station)
                .onSuccess {
                    navi.requestReRoute()
                    // 1순위 대신 대안을 골랐다면 1순위는 거절한 것으로 친다.
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

    /** 휴게소에 도착한 뒤 [충전 시작]. 충전 추적기와 알림 스케줄러를 새로 만든다. */
    fun startCharging() {
        val rec = accepted ?: return
        if (phase != TripPhase.Arrived) return
        val soc = lastVehicle?.socPercent ?: return
        // 이미 목표 이상이면 충전할 필요 없이 바로 완료 처리한다.
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
        // 시간 제한이 있으면 "이 충전기는 N분 제한입니다" 안내
        val started = scheduler.start()
        scope.launch { started.forEach { speakAlert(it) } }
    }

    /** 운전자가 직접 [충전 종료]를 누른 경우. */
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
        // 시간 제한 5분 전과 충전 끊김은 급한 소식이라 다른 안내보다 먼저 말한다.
        val priority = if (type == AlertType.LIMIT_T5 || type == AlertType.LIMIT_SHORTFALL) Priority.HIGH else Priority.NORMAL
        announcer.announce(text, priority)
    }

    /** [출발]. 충전 관련 기록을 비우고 다시 주행 단계로 돌아간다. */
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
    // 시연할 때 쓰는 숨은 도구: 시간 배속, 일시정지, N km 앞으로 순간 이동

    fun devSetSpeed(multiplier: Int) = demo.setSpeed(multiplier).also { publish() }
    fun devTogglePause() = demo.togglePause().also { publish() }
    fun devJump(km: Double) = demo.jumpAhead(km)

    // ---- 내부 ----

    /**
     * 새 경로·차량 값이 들어올 때마다 불린다. 지금 단계(phase)에 따라 할 일이 다르다.
     * - TripPlanned: 처음 한 번 전체 계획을 세워 "충전 N번 필요" 안내
     * - Driving: 목적지 도착 확인, 아니면 추천 계산 후 안내할 때가 됐는지 확인
     * - AwaitingDecision: 안내한 추천의 거리 정보를 갱신
     * - NavigatingToCharger: 차가 멈추면 도착 처리, 아니면 더 나은 곳이 생겼는지 확인
     * - Charging: 남은 시간 계산, 알림 발송, 충전 종료 판단
     */
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
                // 충전이 몇 번 필요한지 센다. 한 번으로 모자라면 2번.
                val stops = when (result) {
                    is RecommendationResult.Recommended -> if (result.primary.needsAnotherStop) 2 else 1
                    is RecommendationResult.NoSafeStop -> 1
                    RecommendationResult.NotNeeded -> 0
                }
                transition(TripEvent.PlanEvaluated)
                announcer.announce(phrases.tripSummary(stops))
            }
            TripPhase.Driving -> {
                // 남은 거리가 50m 이하면 목적지 도착
                if (route.remainingDistanceKm <= ARRIVED_KM) {
                    arrivedAtSec = demo.state.value.simTimeSec
                    transition(TripEvent.DestinationReached)
                } else {
                    when (val result = evaluate(route, vehicleState)) {
                        is RecommendationResult.Recommended ->
                            if (gate.shouldAnnounce(result.primary)) announce(result)
                        // 갈 수 있는 충전소가 없다는 경고는 한 번만 한다.
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
                // 차가 멈췄다 = 휴게소에 도착했다고 본다.
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
                    // 충전이 끝나는 경우: 목표 도달, 시간 제한 만료, 또는 충전기가 스스로 멈춤
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

    /** 경로상 충전소를 새로 받아(거절한 곳은 빼고) 추천 엔진을 돌린다. */
    private suspend fun evaluate(route: RouteState, v: VehicleState): RecommendationResult {
        stations = chargerData.stationsAlongRoute(route).filter { it.id !in gate.declinedIds }
        return engine.recommend(route, v, stations, demo.scenario.profile).also { lastResult = it }
    }

    /** 추천을 운전자에게 알린다. 1순위가 붐비면 대안과 비교하는 문구로, 아니면 일반 추천 문구로 말한다. */
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
        // 1순위가 그대로면 새 계산값을 쓰고(혼잡 여부는 처음 안내한 그대로 유지),
        // 1순위가 바뀌었으면 화면이 갑자기 바뀌지 않게 기존 추천의 거리만 갱신한다.
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

    // 추천 내용은 그대로 두고 "남은 거리"만 최신 값으로 바꾼다.
    private fun Recommendation.withDistanceFrom(list: List<ChargingStation>): Recommendation {
        val s = list.firstOrNull { it.id == station.id } ?: return this
        return copy(station = station.copy(distanceAlongRouteKm = s.distanceAlongRouteKm))
    }

    private fun demoCharging() = demo.state.value.phase == SimPhase.CHARGING

    // 이번 충전 결과(몇 %→몇 %, 몇 분)를 영수증에 남긴다.
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

    // 상태 머신에 사건을 넣어 다음 단계로 넘어가고, 화면을 갱신한다.
    private fun transition(event: TripEvent) {
        val next = TripStateMachine.reduce(phase, event)
        // 화면이 바뀌면 이전 화면의 자막은 지운다. 새 안내가 나오면 다시 채워진다.
        if (next != phase) _state.update { it.copy(caption = null) }
        phase = next
        publish()
    }

    // 새 여행을 위해 기억해 둔 값을 모두 처음으로 돌린다.
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

    /** 지금까지 계산한 모든 값을 화면용 상태([TripUiState]) 하나로 묶어 내보낸다. 화면은 이것만 보고 다시 그린다. */
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

    /** 주행 화면 아래쪽에 띄울 카드를 고른다: 추천 / 혼잡 비교 / 휴게소로 이동 중 / 경고 / 평소 상태 */
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

    /** 경로 막대 위에 휴게소 표시를 만든다. 각 휴게소를 충전함/목표/거절/지나침/앞에 있음 중 하나로 칠한다. */
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

    /** 통행권 모양 영수증에 들어갈 여행 기록(출발·도착 시각, 충전 내역, 거절한 휴게소). */
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

    /** 휴게소 정차 화면(도착·충전 중·완료)에 보여 줄 숫자들. 충전 중에는 실측 보정값을 쓴다. */
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

    // 시뮬레이터 경과 초를 "14:05" 같은 시각 글자로 바꾼다. 시나리오 출발 시각에서부터 센다.
    private fun clockText(simSec: Double): String {
        val total = demo.scenario.startHour * 3600 + simSec.toLong()
        val h = (total / 3600) % 24
        val m = (total % 3600) / 60
        return "%02d:%02d".format(h, m)
    }

    private companion object {
        const val ARRIVED_KM = 0.05      // 목적지까지 50m 이내면 도착으로 본다.
        const val CAPTION_MS = 8_000L    // 자막을 띄워 두는 시간(8초)
        // 정차 화면을 보여 주는 단계들
        val STOP_PHASES = setOf(TripPhase.Arrived, TripPhase.Charging, TripPhase.Done)
    }
}
