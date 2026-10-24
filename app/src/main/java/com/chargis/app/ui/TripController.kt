package com.chargis.app.ui

import com.chargis.app.domain.engine.AnnouncementGate
import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.engine.ConsumptionCalibrator
import com.chargis.app.domain.engine.ConsumptionModel
import com.chargis.app.domain.engine.RecommendationEngine
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.RecommendContext
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RecommendationResult
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.model.VehicleProfile
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.domain.model.VoiceCommand
import com.chargis.app.domain.session.AlertType
import com.chargis.app.domain.session.ChargeSession
import com.chargis.app.domain.session.TripEvent
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.domain.session.TripStateMachine
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.NaviPort
import com.chargis.app.port.SettingsStore
import com.chargis.app.port.SttPort
import com.chargis.app.port.TripStore
import com.chargis.app.port.VehiclePort
import com.chargis.app.sim.Scenario
import com.chargis.app.sim.ScenarioSimulator
import com.chargis.app.sim.SimPhase
import com.chargis.app.voice.VoiceAnnouncer
import com.chargis.app.voice.VoicePhrases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * plan.md §8.1 상태머신을 돌리는 여행 코디네이터. 안드로이드 의존이 없어 JVM 테스트로 시나리오를 끝까지 돌릴 수 있다.
 * [demo]는 데모 모드에서 시나리오 시작/충전/출발 같은 "현실에서 운전자가 하는 일"을 흉내 낸다.
 *
 * 앱의 "지휘자"다. 하는 일은 크게 세 가지다.
 * 1. 내비와 차량에서 새 값이 들어올 때마다([onTick]) 지금 단계에 맞는 일을 한다.
 *    예) 주행 중이면 추천을 계산하고, 충전 중이면 남은 시간과 알림을 계산한다.
 * 2. 운전자가 버튼을 누르거나 말하면([accept], [decline], [startCharging], [depart] 등) 단계를 넘긴다.
 * 3. 화면에 보여 줄 모든 값을 [state] 하나로 묶어 내보낸다([publish]).
 *
 * 역할을 나눠 두었다(B5): 여행 중 기억은 [TripMemory], 화면 상태 조립은 [TripPresenter],
 * 충전 한 번의 추적과 알림은 [ChargeSession], 앱을 껐다 켜도 이어 가기는 [TripStore]가 맡는다.
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
    private val baseConfig: RecommendationConfig = RecommendationConfig(),
    captions: Flow<String?>? = null,              // 음성 안내 문구. 화면 하단 자막으로도 띄운다.
    runClock: Boolean = true,                     // false면 시뮬레이터 시계를 자동으로 돌리지 않는다(테스트용).
    private val profiles: List<VehicleProfile> = emptyList(),  // 설정에서 고를 수 있는 차종(F5)
    private val settingsStore: SettingsStore? = null,          // 운전자 설정(F5). 없으면 기본값
    private val tripStore: TripStore? = null,                  // 진행 중인 여행 저장(B5). 없으면 저장하지 않음
    private val stt: SttPort? = null                           // 음성 명령(F5). 없으면 버튼만
) {
    private var config = baseConfig
    private var engine = RecommendationEngine(config)
    private val gate = AnnouncementGate(config.announceWithinKm, config.switchMinutesGap)
    private val presenter = TripPresenter()
    private val m = TripMemory()
    private val calibrator = ConsumptionCalibrator()
    private var settings = UserSettings()

    // 화면 상태. _state(밑줄)는 이 클래스 안에서만 고칠 수 있고, 바깥(화면)에는 읽기 전용 state로 보여 준다.
    private val _state = MutableStateFlow(
        TripUiState(
            scenarios = scenarios.map {
                ScenarioOption(it.id, it.title, it.totalDistanceKm, it.startSoc, "%02d:00".format(it.startHour))
            },
            profiles = profiles.map { ProfileOption(it.id, it.displayName, it.batteryKwh, it.maxChargeKw) }
        )
    )
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    private var captionClear: Job? = null      // 자막을 일정 시간 뒤 지우는 예약 작업
    private var listenJob: Job? = null         // 음성 명령을 듣는 작업(F5)
    private var ticksSinceSave = 0
    // 저장 요청 줄. 가장 최근 요청만 남겨(CONFLATED) 오래된 저장이 새 저장을 덮지 않게 한다.
    private val saves = Channel<String?>(Channel.CONFLATED)

    // 만들어지자마자 실행되는 준비 작업
    init {
        scope.launch {
            // 설정과 저장해 둔 여행을 먼저 불러온 뒤에 시계와 데이터 구독을 시작한다.
            settingsStore?.let { store -> launch { store.settings.collect(::applySettings) } }
            restoreSaved()
            // 데모 시계를 돌린다(가상의 차가 움직이기 시작).
            if (runClock) launch { demo.run() }
            // 경로나 차량 값 중 하나라도 바뀌면 둘을 묶어 onTick을 부른다.
            launch {
                combine(navi.routeState, vehicle.vehicleState) { r, v -> r to v }
                    .collect { (r, v) -> onTick(r, v) }
            }
            // 저장 요청을 하나씩 순서대로 기기에 쓴다.
            tripStore?.let { store -> launch { for (json in saves) store.save(json) } }
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

    /** 시나리오(예: S1 서울→속초) 하나를 골라 새 여행을 시작한다. 설정에서 고른 차종으로 달린다. */
    fun startScenario(id: String) {
        demo.load(scenarios.first { it.id == id })
        selectedProfile()?.let(demo::useProfile)
        resetTrip()
        demo.start()
        transition(TripEvent.DestinationSet)
    }

    /** 같은 시나리오를 처음 상태로 되돌린다. */
    fun restart() {
        demo.load(scenarios.firstOrNull { it.id == demo.scenario.id } ?: demo.scenario)
        selectedProfile()?.let(demo::useProfile)
        resetTrip()
        publish()
    }

    /** 추천 카드의 [수락] 또는 혼잡 비교 카드에서 충전소 선택. */
    fun accept(stationId: String) {
        val current = m.offer ?: return
        val rec = (listOf(current.primary) + current.alternatives).firstOrNull { it.station.id == stationId } ?: return
        // 내비에 경유지를 추가한다. 성공하면 수락 처리, 실패하면 음성으로 알린다.
        scope.launch {
            navi.addWaypoint(rec.station)
                .onSuccess {
                    navi.requestReRoute()
                    // 1순위 대신 대안을 골랐다면 1순위는 거절한 것으로 친다.
                    if (rec.station.id != current.primary.station.id) gate.markDeclined(current.primary.station.id)
                    gate.markAnnounced(rec.station.id)
                    m.accepted = rec
                    m.offer = null
                    transition(TripEvent.Accepted)
                    announcer.announce(phrases.waypointAdded(rec.station.name))
                }
                .onFailure { announcer.announce(phrases.waypointFailed(), Priority.HIGH) }
        }
    }

    /** [다음 충전소]: 이번 1순위를 거절하고 다음 후보를 기다린다. */
    fun decline() {
        val current = m.offer ?: return
        gate.markDeclined(current.primary.station.id)
        m.offer = null
        transition(TripEvent.Declined)
    }

    /** 충전소에 도착한 뒤 [충전 시작]. 충전 세션(남은 시간 추적 + 알림)을 새로 만든다. */
    fun startCharging() {
        val rec = m.accepted ?: return
        if (m.phase != TripPhase.Arrived) return
        val soc = m.lastVehicle?.socPercent ?: return
        // 이미 목표 이상이면 충전할 필요 없이 바로 완료 처리한다.
        if (rec.targetSocPercent <= soc) {
            transition(TripEvent.ChargingStarted)
            transition(TripEvent.ChargingFinished)
            return
        }
        val now = demo.state.value.simTimeSec
        m.endedByLimit = false
        val session = newSession(rec, rec.targetSocPercent, now, soc).also { m.charge = it }
        // 충전 시작 감지 SDK가 없으므로 버튼으로 시작한다(§8.1). 데모에선 충전기가 제한 시각에 스스로 끊는다.
        demo.startCharging(rec.targetSocPercent, rec.timeLimitMin)
        transition(TripEvent.ChargingStarted)
        // 시간 제한이 있으면 "이 충전기는 N분 제한입니다" 안내
        val started = session.start(now, soc)
        scope.launch { started.forEach { speakAlert(it) } }
    }

    /** 운전자가 직접 [충전 종료]를 누른 경우. */
    fun stopCharging() {
        if (m.phase != TripPhase.Charging) return
        demo.stopCharging()
        recordCharge()
        transition(TripEvent.ChargingFinished)
    }

    /**
     * F2: 충전 중(또는 충전 전) 목표를 [deltaPercent]만큼 바꾼다. 예) +5 → 50%를 55%로.
     * 출발 예정 시각과 시간 제한 경고가 새 목표로 다시 계산된다.
     */
    fun adjustTarget(deltaPercent: Int) {
        val rec = m.accepted ?: return
        val soc = m.lastVehicle?.socPercent ?: return
        val current = m.charge?.targetSoc ?: rec.targetSocPercent
        val next = (current + deltaPercent).coerceAtMost(TripPresenter.MAX_TARGET)
        if (next == current || next <= soc + TripPresenter.MIN_GAP) return
        when (m.phase) {
            TripPhase.Arrived -> m.accepted = rec.copy(targetSocPercent = next)
            TripPhase.Charging -> {
                val session = m.charge ?: return
                session.retarget(next, demo.state.value.simTimeSec, soc)
                m.accepted = rec.copy(targetSocPercent = next)
                demo.setChargeTarget(next)
                scope.launch { announcer.announce(phrases.targetChanged(next)) }
            }
            else -> return
        }
        publish()
        saveNow()
    }

    /** §8.3 알림을 음성으로 낸다. 문구 속 숫자는 같은 스냅숏에서 가져온다. */
    private suspend fun speakAlert(type: AlertType) {
        val s = m.charge?.snapshot
        val rec = m.accepted
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
        val charged = m.phase == TripPhase.Done
        demo.depart()
        transition(TripEvent.Depart)
        m.accepted = null
        m.charge = null
        m.endedByLimit = false
        if (charged) scope.launch { announcer.announce(phrases.resume()) }
    }

    /** F5: 설정을 바꾼다. 저장소가 있으면 거기에 쓰고, 바뀐 값은 구독으로 돌아와 반영된다. */
    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        val store = settingsStore
        if (store == null) {
            applySettings(transform(settings))
        } else {
            scope.launch { store.update(transform) }
        }
    }

    /** F5: 음성 명령 하나를 처리한다. 지금 단계에서 의미 없는 명령은 무시한다. */
    fun onVoiceCommand(cmd: VoiceCommand) {
        when (cmd) {
            VoiceCommand.ACCEPT -> m.offer?.let { o ->
                // 혼잡 비교 중이면 "수락"은 더 빨리 도착하는 대안을 고른다는 뜻으로 본다.
                val alt = o.alternatives.firstOrNull()
                accept(if (o.congested && alt != null) alt.station.id else o.primary.station.id)
            }
            VoiceCommand.NEXT_STOP -> decline()
            VoiceCommand.START_CHARGING -> startCharging()
            VoiceCommand.DEPART -> if (m.phase == TripPhase.Arrived || m.phase == TripPhase.Done) depart()
        }
    }

    /** 마이크 권한을 새로 받았을 때처럼, 음성 명령 듣기를 다시 시작한다(F5). */
    fun refreshVoice() = updateListening()

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
        m.lastVehicle = vehicleState
        if (route == null) {
            publish()
            return
        }
        trackOdometer(route, vehicleState)
        m.lastRoute = route

        when (m.phase) {
            TripPhase.TripPlanned -> {
                val result = evaluate(route, vehicleState)
                // 충전이 몇 번 필요한지 센다. 두 번 들르는 계획이면 2번.
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
                    m.arrivedAtSec = demo.state.value.simTimeSec
                    transition(TripEvent.DestinationReached)
                } else {
                    when (val result = evaluate(route, vehicleState)) {
                        is RecommendationResult.Recommended ->
                            if (gate.shouldAnnounce(result.primary)) announce(result)
                        // 갈 수 있는 충전소가 없다는 경고는 한 번만 한다.
                        is RecommendationResult.NoSafeStop -> if (!m.noSafeWarned) {
                            m.noSafeWarned = true
                            announcer.announce(phrases.noSafeStop(), Priority.HIGH)
                        }
                        RecommendationResult.NotNeeded -> Unit
                    }
                }
            }
            TripPhase.AwaitingDecision -> refreshOffer(route, vehicleState)
            TripPhase.NavigatingToCharger -> {
                // 차가 멈췄다 = 충전소에 도착했다고 본다.
                if (!vehicleState.isDriving) {
                    transition(TripEvent.ArrivedAtStop)
                } else {
                    keepOrSwitchAccepted(route, vehicleState)
                }
            }
            TripPhase.Charging -> {
                val session = m.charge
                if (session != null) {
                    val due = session.update(demo.state.value.simTimeSec, vehicleState.socPercent)
                    val snap = session.snapshot
                    // 충전이 끝나는 경우: 목표 도달, 시간 제한 만료, 또는 충전기가 스스로 멈춤
                    val finished = snap != null && (snap.reached || snap.limitExpired || !demoCharging())
                    if (finished && snap != null) {
                        if (demoCharging()) demo.stopCharging()
                        m.endedByLimit = snap.limitExpired && !snap.reached
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
        // 진행 상황은 몇 초에 한 번만 저장한다(단계가 바뀔 때는 transition에서 바로 저장).
        if (++ticksSinceSave >= SAVE_EVERY_TICKS) saveNow()
    }

    /**
     * B3: 달린 거리와 배터리를 보정기에 넣는다.
     * 차량이 주는 누적 주행거리를 쓰면 거리와 배터리가 같은 순간의 값이라 짝이 어긋나지 않는다.
     * 차량이 주행거리를 주지 않을 때만 경로의 남은 거리가 줄어든 만큼 달렸다고 본다
     * (이때는 경로와 배터리가 따로 도착하므로, 보정기가 어긋난 짝을 걸러 낸다).
     */
    private fun trackOdometer(route: RouteState, v: VehicleState) {
        val prev = m.lastRoute
        val odo = v.odometerKm
        if (odo != null) {
            m.odometerKm = odo
        } else if (prev != null && v.isDriving) {
            m.odometerKm += max(0.0, prev.remainingDistanceKm - route.remainingDistanceKm)
        }
        if (m.phase != TripPhase.Idle && m.phase != TripPhase.Finished) calibrator.onSample(m.odometerKm, v.socPercent)
    }

    /** 지금 소비 배율: 기온·속도로 어림한 값(사전)에서 시작해, 달릴수록 실측 쪽으로 옮겨 간다(B3). */
    private fun consumptionFactor(route: RouteState?, v: VehicleState?): Double {
        val speed = route?.remainingTimeMin?.takeIf { it > 0 }?.let { route.remainingDistanceKm / (it / 60.0) }
        val prior = ConsumptionModel.priorFactor(v?.outsideTempC, speed)
        return calibrator.factor(ChargeMath.socPerKm(demo.scenario.profile), prior)
    }

    /** 경로상 충전소를 새로 받아(거절한 곳은 빼고) 추천 엔진을 돌린다. 지금 시각과 소비 배율을 함께 넘긴다. */
    private suspend fun evaluate(route: RouteState, v: VehicleState): RecommendationResult {
        m.stations = chargerData.stationsAlongRoute(route).filter { it.id !in gate.declinedIds }
        val context = RecommendContext(
            minuteOfDay = TripPresenter.Clock(demo.scenario.startHour).minuteOfDay(demo.state.value.simTimeSec),
            consumptionFactor = consumptionFactor(route, v)
        )
        return engine.recommend(route, v, m.stations, demo.scenario.profile, context).also { m.lastResult = it }
    }

    /** 추천을 운전자에게 알린다. 붐비면 대안과 비교하는 문구로, 아니면 일반 추천 문구로 말한다. */
    private suspend fun announce(result: RecommendationResult.Recommended) {
        m.offer = result
        gate.markAnnounced(result.primary.station.id)
        transition(TripEvent.RecommendationAnnounced)
        val p = result.primary
        val alt = result.alternatives.firstOrNull()
        if (result.congested && alt != null) {
            announcer.announce(phrases.congested(p.station.name, p.waitMinutes, alt.station.name))
            announceOffRoute(alt.station)
        } else {
            announcer.announce(phrases.recommend(p.station.distanceAlongRouteKm, p.station.name, p.targetSocPercent))
            announceOffRoute(p.station)
            val next = p.nextStop
            when {
                next != null -> announcer.announce(phrases.planTwoStops(next.station.name, next.targetSocPercent))
                p.needsAnotherStop -> announcer.announce(phrases.needAnother())
            }
        }
    }

    // 휴게소가 아니라 경로를 벗어나는 곳이면 더 달리는 거리(왕복)를 함께 알린다.
    private suspend fun announceOffRoute(station: ChargingStation) {
        if (station.detourKm > config.offRouteKm) {
            announcer.announce(phrases.offRoute(station.name, (2 * station.detourKm).roundToInt()))
        }
    }

    /** 안내 후 결정 대기 중: 거리 표시를 갱신하고, 충전소를 지나쳤으면 거절로 처리한다. */
    private suspend fun refreshOffer(route: RouteState, v: VehicleState) {
        val current = m.offer ?: return
        val result = evaluate(route, v)
        val ids = m.stations.map { it.id }.toSet()
        if (current.primary.station.id !in ids) {
            decline()
            return
        }
        // 1순위가 그대로면 새 계산값을 쓰고(혼잡 여부는 처음 안내한 그대로 유지),
        // 1순위가 바뀌었으면 화면이 갑자기 바뀌지 않게 기존 추천의 거리만 갱신한다.
        m.offer = if (result is RecommendationResult.Recommended && result.primary.station.id == current.primary.station.id) {
            result.copy(congested = current.congested)
        } else {
            current.copy(primary = current.primary.withDistanceFrom(m.stations))
        }
    }

    /** §7.3 히스테리시스: 수락한 충전소는 크게 나빠지거나 사라질 때만 바꾼다. */
    private suspend fun keepOrSwitchAccepted(route: RouteState, v: VehicleState) {
        val acc = m.accepted ?: return
        val result = evaluate(route, v)
        if (m.stations.none { it.id == acc.station.id }) return // 도착 직전/직후 — 바꾸지 않는다
        val replacement = gate.replacementFor(acc, result)
        if (replacement != null && replacement.station.id != acc.station.id) {
            navi.addWaypoint(replacement.station).onSuccess {
                m.accepted = replacement
                gate.markAnnounced(replacement.station.id)
                announcer.announce(phrases.waypointAdded(replacement.station.name))
            }
        } else {
            m.accepted = acc.withDistanceFrom(m.stations)
        }
    }

    // 추천 내용은 그대로 두고 "남은 거리"만 최신 값으로 바꾼다.
    private fun Recommendation.withDistanceFrom(list: List<ChargingStation>): Recommendation {
        val s = list.firstOrNull { it.id == station.id } ?: return this
        return copy(station = station.copy(distanceAlongRouteKm = s.distanceAlongRouteKm))
    }

    private fun demoCharging() = demo.state.value.phase == SimPhase.CHARGING

    private fun newSession(rec: Recommendation, target: Double, startSec: Double, fromSoc: Double, fired: Set<AlertType> = emptySet()) =
        ChargeSession(
            profile = demo.scenario.profile,
            config = config,
            stationMaxKw = rec.station.maxKw,
            targetSoc = target,
            timeLimitMin = rec.timeLimitMin,
            startSec = startSec,
            fromSoc = fromSoc,
            firedAlerts = fired
        )

    /**
     * 이번 충전 결과(몇 %→몇 %, 몇 분)를 영수증에 남긴다.
     * F4 비교용으로 "같은 충전기에서 80%까지 채웠다면 몇 분"도 함께 계산해 둔다.
     */
    private fun recordCharge() {
        val rec = m.accepted ?: return
        val session = m.charge
        val from = session?.fromSoc ?: return
        val sim = demo.state.value
        val minutes = ceil((sim.simTimeSec - session.startSec) / 60.0 - 1e-9).toInt()
        val habit = max(HABIT_TARGET, sim.soc)
        val baseline = ceil(ChargeMath.chargeMinutes(from, habit, rec.station.maxKw, demo.scenario.profile, config) - 1e-9).toInt()
        m.charges += rec.station.id to ChargeRecord(
            stationName = rec.station.name,
            fromSoc = from,
            toSoc = sim.soc,
            minutes = minutes,
            endedByLimit = m.endedByLimit,
            // 제한으로 끊긴 충전은 "80%까지"도 같은 제한에 걸리므로 비교하지 않는다.
            baselineMinutes = if (m.endedByLimit) minutes else max(minutes, baseline)
        )
    }

    // 상태 머신에 사건을 넣어 다음 단계로 넘어가고, 화면을 갱신한다.
    private fun transition(event: TripEvent) {
        val next = TripStateMachine.reduce(m.phase, event)
        // 화면이 바뀌면 이전 화면의 자막은 지운다. 새 안내가 나오면 다시 채워진다.
        val changed = next != m.phase
        if (changed) _state.update { it.copy(caption = null, restored = false) }
        m.phase = next
        if (changed) {
            updateListening()
            saveNow()
        }
        publish()
    }

    // 새 여행을 위해 기억해 둔 값을 모두 처음으로 돌린다.
    private fun resetTrip() {
        m.reset()
        m.lastRoute = null
        calibrator.reset()
        gate.reset()
        announcer.reset()
        updateListening()
        saveNow()
    }

    /** 지금까지 계산한 모든 값을 화면용 상태([TripUiState]) 하나로 묶어 내보낸다. 화면은 이것만 보고 다시 그린다. */
    private fun publish() {
        val energy = m.lastRoute?.let {
            EnergyInfo(consumptionFactor(it, m.lastVehicle), calibrator.observedKm)
        }
        _state.update {
            presenter.present(it, m, demo.state.value, demo.scenario, gate.declinedIds, config, energy)
        }
    }

    // ---- 설정(F5) ----

    private fun selectedProfile(): VehicleProfile? = profiles.firstOrNull { it.id == settings.profileId }

    // 설정이 바뀌면 추천 기준값을 다시 만들고, 출발 전이면 차종도 바로 바꾼다.
    private fun applySettings(next: UserSettings) {
        settings = next
        config = next.applyTo(baseConfig)
        engine = RecommendationEngine(config)
        if (m.phase == TripPhase.Idle) selectedProfile()?.let(demo::useProfile)
        _state.update { it.copy(settings = next) }
        updateListening()
        publish()
    }

    // ---- 음성 명령(F5) ----

    /** 지금 단계에서 말할 수 있는 명령. 없으면 듣지 않는다. */
    private fun allowedCommands(): Set<VoiceCommand> = when (m.phase) {
        TripPhase.AwaitingDecision -> setOf(VoiceCommand.ACCEPT, VoiceCommand.NEXT_STOP)
        TripPhase.Arrived -> setOf(VoiceCommand.START_CHARGING, VoiceCommand.DEPART)
        TripPhase.Done -> setOf(VoiceCommand.DEPART)
        else -> emptySet()
    }

    // 음성 명령을 켜 두었고 지금 받을 명령이 있으면 듣기 시작하고, 아니면 멈춘다.
    private fun updateListening() {
        listenJob?.cancel()
        listenJob = null
        val allowed = allowedCommands()
        val source = stt
        val listening = settings.voiceCommands && source != null && allowed.isNotEmpty()
        _state.update { it.copy(voice = VoiceUi(settings.voiceCommands, listening, allowed.map { c -> c.name })) }
        if (listening && source != null) {
            listenJob = scope.launch { source.listenForCommand(allowed).collect(::onVoiceCommand) }
        }
    }

    // ---- 여행 저장·복원(B5) ----

    /** 지금 여행을 저장한다. 출발 전이나 도착 후에는 지운다. */
    private fun saveNow() {
        ticksSinceSave = 0
        if (tripStore == null) return
        saves.trySend(if (m.phase == TripPhase.Idle || m.phase == TripPhase.Finished) null else json.encodeToString(SavedTrip.serializer(), snapshotForSave()))
    }

    private fun snapshotForSave(): SavedTrip {
        val sim = demo.state.value
        // 결정 대기 중에 꺼졌다면, 다시 켰을 때 같은 추천을 다시 안내할 수 있게 "주행 중"으로 저장한다.
        val awaiting = m.phase == TripPhase.AwaitingDecision
        val offeredId = m.offer?.primary?.station?.id
        return SavedTrip(
            scenarioId = demo.scenario.id,
            profileId = demo.scenario.profile.id,
            sim = SavedSim.of(sim),
            phase = if (awaiting) TripPhase.Driving else m.phase,
            accepted = m.accepted?.let(SavedRec::of),
            charges = m.charges.map { SavedCharge.of(it.first, it.second) },
            handled = gate.handledIds.filter { !(awaiting && it == offeredId) || it in gate.declinedIds },
            declined = gate.declinedIds.toList(),
            noSafeWarned = m.noSafeWarned,
            arrivedAtSec = m.arrivedAtSec,
            endedByLimit = m.endedByLimit,
            charge = m.charge?.let { SavedChargeSession(it.targetSoc, it.startSec, it.fromSoc, it.firedAlerts.toList()) },
            odometerKm = m.odometerKm
        )
    }

    /** 저장해 둔 여행이 있으면 같은 자리에서 이어 간다. 형식이 다르거나 시나리오를 못 찾으면 버린다. */
    private suspend fun restoreSaved() {
        val store = tripStore ?: return
        val text = store.load() ?: return
        val saved = runCatching { json.decodeFromString(SavedTrip.serializer(), text) }.getOrNull()
        val base = saved?.let { s -> scenarios.firstOrNull { it.id == s.scenarioId } }
        if (saved == null || base == null || saved.version != SavedTrip.VERSION) {
            store.save(null)
            return
        }
        val profile = profiles.firstOrNull { it.id == saved.profileId } ?: base.profile
        val scenario = base.copy(profile = profile)
        demo.restore(scenario, saved.sim.toState(scenario.id, demo.state.value.speedMultiplier))
        m.reset()
        m.phase = saved.phase
        m.noSafeWarned = saved.noSafeWarned
        m.arrivedAtSec = saved.arrivedAtSec
        m.endedByLimit = saved.endedByLimit
        m.odometerKm = saved.odometerKm
        saved.charges.forEach { m.charges += it.toRecord() }
        gate.restore(saved.handled, saved.declined)
        // 수락한 충전소는 시나리오 목록에서 다시 찾아, 지금 위치 기준 거리로 바꿔 넣는다.
        m.accepted = saved.accepted?.let { r ->
            scenario.stations.firstOrNull { it.id == r.stationId }?.let { st ->
                r.toRecommendation(st.copy(distanceAlongRouteKm = st.distanceAlongRouteKm - saved.sim.traveledKm))
            }
        }
        val rec = m.accepted
        m.charge = saved.charge?.let { c ->
            rec?.let { newSession(it, c.targetSoc, c.startSec, c.fromSoc, c.fired.toSet()) }
        }
        _state.update { it.copy(restored = true) }
        updateListening()
        publish()
    }

    private companion object {
        const val ARRIVED_KM = 0.05      // 목적지까지 50m 이내면 도착으로 본다.
        const val CAPTION_MS = 8_000L    // 자막을 띄워 두는 시간(8초)
        const val SAVE_EVERY_TICKS = 25  // 진행 상황 저장 간격(0.2초 틱 기준 약 5초)
        const val HABIT_TARGET = 80.0    // F4 비교: 습관처럼 채우는 %
        val json = Json { ignoreUnknownKeys = true }
    }
}
