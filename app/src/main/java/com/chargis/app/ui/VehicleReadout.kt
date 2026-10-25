package com.chargis.app.ui

import com.chargis.app.domain.engine.BatteryThermal
import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.engine.ConsumptionCalibrator
import com.chargis.app.domain.engine.ConsumptionModel
import com.chargis.app.domain.engine.ElevationModel
import com.chargis.app.domain.engine.ManualSocEstimator
import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.VehicleProfile
import com.chargis.app.domain.model.VehicleState
import kotlin.math.max

/**
 * 차량에서 들어온 값을 앱이 계산에 쓸 값으로 바꾼다(TripController에서 분리).
 * - 달린 거리와 오르내린 높이를 쌓는다([TripMemory.odometerKm], [TripMemory.elevationSoc]).
 * - 전비 실측 보정([ConsumptionCalibrator])에 값을 넣고 소비 배율을 낸다.
 * - 배터리 %를 직접 넣는 중이면 차량 값 대신 추정값([ManualSocEstimator])을 내보낸다.
 * - 배터리 온도를 반영한 충전 사양을 만든다.
 * 안드로이드 의존이 없어 JVM 테스트에서 그대로 쓴다. [profile]은 지금 시나리오의 차종을 돌려준다.
 */
class VehicleReadout(private val m: TripMemory, private val profile: () -> VehicleProfile) {
    private val calibrator = ConsumptionCalibrator()
    private val manual = ManualSocEstimator()

    /** 설정에서 "배터리 값 → 직접 입력"을 골랐는지 */
    var manualSetting = false
    /** 차량에서 배터리 %를 받을 수 있는지(VehiclePort.isSupported) */
    var socSupported = true

    /** 배터리 %를 직접 넣는 중인지: 설정에서 골랐거나, 차량에서 배터리 %를 받을 수 없을 때. */
    val manualMode: Boolean get() = manualSetting || !socSupported

    /** 실측으로 확인한 거리(km). 화면의 "실측 N km" 표시용 */
    val observedKm: Double get() = calibrator.observedKm

    /**
     * 새 경로·차량 값 한 쌍을 읽는다. 거리·고도를 쌓고, 화면과 엔진에 넘길 차량 상태를 돌려준다.
     * [tracking]이 참일 때(여행 중)만 전비 실측에 넣는다. [charger]가 있으면 충전 중 추정에 쓴다.
     */
    fun read(route: RouteState?, raw: VehicleState, nowSec: Double, tracking: Boolean, charger: ManualSocEstimator.Charger?): VehicleState {
        if (route != null) trackDistance(route, raw)
        val shown = shown(raw, route, nowSec, charger)
        if (route != null && tracking && !manualMode) {
            // 고도 변화로 쓴 몫을 되돌려, 평지에서 쓴 것처럼 비교해야 전비 보정이 오르막을 "전비가 나쁘다"로 오해하지 않는다.
            calibrator.onSample(m.odometerKm, shown.socPercent + m.elevationSoc)
        }
        return shown
    }

    /** 지금 소비 배율: 기온·속도로 어림한 값(사전)에서 시작해, 달릴수록 실측 쪽으로 옮겨 간다(B3). */
    fun consumptionFactor(route: RouteState?, v: VehicleState?): Double {
        val speed = route?.remainingTimeMin?.takeIf { it > 0 }?.let { route.remainingDistanceKm / (it / 60.0) }
        val prior = ConsumptionModel.priorFactor(v?.outsideTempC, speed)
        return calibrator.factor(ChargeMath.socPerKm(profile()), prior)
    }

    /** 지금 배터리 온도로 본 차량 사양. 배터리가 차가우면 충전을 덜 받는다(온도를 모르면 바깥 기온으로 어림). */
    fun chargeProfile(v: VehicleState?): VehicleProfile =
        profile().withChargePowerFactor(BatteryThermal.chargePowerFactor(v?.batteryTempC ?: v?.outsideTempC))

    /** 충전 중 직접 입력 추정에 쓸 값. 충전 중이 아니면 null. */
    fun chargerFor(rec: Recommendation?, targetSoc: Double?, raw: VehicleState, config: RecommendationConfig): ManualSocEstimator.Charger? {
        if (rec == null || targetSoc == null) return null
        return ManualSocEstimator.Charger(rec.station.maxKw, chargeProfile(raw), config, targetSoc)
    }

    /** 여행을 시작할 때 출발 배터리를 운전자가 넣은 첫 값으로 삼는다(데모에서는 시나리오의 출발 배터리). */
    fun anchorAtStart(startSoc: Double) {
        if (!manualMode) return
        manual.set(startSoc, 0.0, 0.0, 0.0, false)
        calibrator.onSample(0.0, startSoc)
    }

    /**
     * 운전자가 계기판을 보고 넣은(또는 "맞음"으로 확인한) 배터리 값. 새 기준점이 되고,
     * 다시 움직이거나 충전을 시작할 때([flushPending]) 전비 실측에 들어간다.
     */
    fun correct(soc: Double, nowSec: Double, charging: Boolean) {
        manual.set(soc, m.odometerKm, m.elevationSoc, nowSec, charging)
        m.pendingManualSample = m.odometerKm to soc + m.elevationSoc
    }

    /** 정차 중에 확인한 배터리 값을 전비 실측에 넣는다. */
    fun flushPending() {
        val sample = m.pendingManualSample ?: return
        calibrator.onSample(sample.first, sample.second)
        m.pendingManualSample = null
    }

    /** 저장해 둔 직접 입력 추정값에서 이어 간다(앱을 다시 켰을 때). */
    fun restore(soc: Double, nowSec: Double, charging: Boolean) = manual.set(soc, m.odometerKm, m.elevationSoc, nowSec, charging)

    /** 직접 입력을 끄면 다음에 켤 때 그때 차량 값에서 새로 시작한다. */
    fun forgetManual() = manual.reset()

    fun reset() {
        calibrator.reset()
        manual.reset()
    }

    /**
     * B3: 달린 거리와 오르내린 높이를 센다. 전비 보정과 배터리 직접 입력 추정에 쓴다.
     * 차량이 주는 누적 주행거리를 쓰면 거리와 배터리가 같은 순간의 값이라 짝이 어긋나지 않는다.
     * 차량이 주행거리를 주지 않을 때만 경로의 남은 거리가 줄어든 만큼 달렸다고 본다
     * (이때는 경로와 배터리가 따로 도착하므로, 보정기가 어긋난 짝을 걸러 낸다).
     */
    private fun trackDistance(route: RouteState, v: VehicleState) {
        val prev = m.lastRoute
        val odo = v.odometerKm
        if (odo != null) {
            m.odometerKm = odo
        } else if (prev != null && v.isDriving) {
            m.odometerKm += max(0.0, prev.remainingDistanceKm - route.remainingDistanceKm)
        }
        // 경로 고도의 첫 점이 지금 위치의 고도다. 바뀐 높이만큼 쓴(되찾은) 배터리를 쌓아 둔다.
        route.elevation?.firstOrNull()?.elevationM?.let { h ->
            m.lastElevationM?.let { m.elevationSoc += ElevationModel.socForClimb(h - it, profile()) }
            m.lastElevationM = h
        }
    }

    /**
     * 직접 입력 중이면 차량 값 대신 마지막으로 넣은 값에서 이어 간 추정값을 쓰고 출처를 "직접 입력"으로 바꾼다.
     * 처음 켰는데 넣은 값이 없으면 지금 차량 값(데모에서는 시뮬레이터 값)에서 시작한다.
     */
    private fun shown(raw: VehicleState, route: RouteState?, nowSec: Double, charger: ManualSocEstimator.Charger?): VehicleState {
        if (!manualMode) return raw
        if (!manual.hasValue) manual.set(raw.socPercent, m.odometerKm, m.elevationSoc, nowSec, charger != null)
        val perKm = ChargeMath.socPerKm(profile()) * consumptionFactor(route, raw)
        val soc = manual.estimate(m.odometerKm, m.elevationSoc, nowSec, perKm, charger) ?: raw.socPercent
        return raw.copy(socPercent = soc, rangeKm = null, source = DataSource.MANUAL)
    }
}

/**
 * 배터리 예열 안내 시점(TripController에서 분리). 수락한 충전소에서 차가운 배터리로 [MIN_COLD_EXTRA_MIN]분 넘게
 * 늦어질 것 같고, 도착까지 [BatteryThermal.PRECONDITION_LEAD_MIN]분 이내일 때 한 번 권한다.
 * 이미 권했거나, 이미 켜져 있거나, 차가 지원하지 않으면 권하지 않는다.
 */
object PreconditionAdvisor {
    const val MIN_COLD_EXTRA_MIN = 3

    fun shouldAdvise(
        accepted: Recommendation,
        v: VehicleState,
        route: RouteState,
        profile: VehicleProfile,
        alreadyAdvised: Boolean,
        defaultSpeedKmh: Double
    ): Boolean {
        if (alreadyAdvised || v.preconditioning == true || !profile.supportsPreconditioning) return false
        if (accepted.coldExtraMinutes < MIN_COLD_EXTRA_MIN) return false
        val speed = route.remainingTimeMin?.takeIf { it > 0 }?.let { route.remainingDistanceKm / (it / 60.0) } ?: defaultSpeedKmh
        val minutesAway = (accepted.station.distanceAlongRouteKm + accepted.station.detourKm) / speed * 60.0
        return minutesAway <= BatteryThermal.PRECONDITION_LEAD_MIN
    }
}
