package com.chargis.app.data.sdk

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import com.chargis.app.port.CarSignalsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * B5·B6: Pleos 차량 신호.
 * - Vehicle SDK: 배터리 %(변경 알림), 기어(변경 알림), 충전 스위치(알림이 없어 [POLL_MS]마다 읽는다).
 * - 안드로이드 차량 서비스: CarUxRestrictions. 차가 움직여 화면 제한이 걸리면 [uxRestricted]가 true가 된다.
 *
 * 자동차용 안드로이드(FEATURE_AUTOMOTIVE)가 아니면 아무것도 연결하지 않는다(일반 기기·테스트에서는 android.car 클래스가 없다).
 * 퍼미션: CAR_ENERGY(배터리·충전), CAR_DRIVE(기어).
 */
class PleosCarSignals(context: Context, private val scope: CoroutineScope) : CarSignalsPort {
    private val app = context.applicationContext
    private val _available = MutableStateFlow(false)
    private val _soc = MutableStateFlow<Int?>(null)
    private val _parked = MutableStateFlow<Boolean?>(null)
    private val _charge = MutableStateFlow<Boolean?>(null)
    private val _ux = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()
    override val socPercent: StateFlow<Int?> = _soc.asStateFlow()
    override val parked: StateFlow<Boolean?> = _parked.asStateFlow()
    override val chargeSwitchOn: StateFlow<Boolean?> = _charge.asStateFlow()
    override val uxRestricted: StateFlow<Boolean> = _ux.asStateFlow()

    private var vehicle: Vehicle? = null
    private var car: Car? = null

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(soc: Int?) { if (soc != null && soc in 0..100) _soc.value = soc }
        override fun onFailed(e: Exception) { Log.w(TAG, "soc listener failed", e) }
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { _parked.value = gear?.let(::parkedOf) }
        override fun onFailed(e: Exception) { Log.w(TAG, "gear listener failed", e) }
    }

    /** 자동차용 안드로이드면 SDK와 차량 서비스에 연결한다. 실패해도 앱은 데모 값으로 계속한다. */
    fun start() {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        connectUx()
        runCatching {
            val v = Vehicle(app).also { it.initialize() }
            vehicle = v
            v.getEvBattery().getEvBatteryStateOfCharge({ soc -> socListener.onEvBatteryStateOfChargeUpdated(soc); _available.value = true }, { Log.w(TAG, "soc read failed", it) })
            v.getEvBattery().registerEvBatteryStateOfCharge(socListener)
            v.getGear().getCurrentGearSelectionState({ g -> gearListener.onSelected(g) }, { Log.w(TAG, "gear read failed", it) })
            v.getGear().registerGearSelection(gearListener)
            scope.launch {
                while (isActive) {
                    v.getEvBattery().getEvChargeSwitchState({ _charge.value = it }, { })
                    delay(POLL_MS)
                }
            }
        }.onFailure { Log.w(TAG, "vehicle sdk unavailable", it) }
    }

    private fun connectUx() {
        runCatching {
            car = Car.createCar(app, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) return@createCar
                val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return@createCar
                _ux.value = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
                ux.registerListener { r -> _ux.value = r.isRequiresDistractionOptimization }
            }
        }.onFailure { Log.w(TAG, "car ux restrictions unavailable", it) }
    }

    fun release() {
        vehicle?.let { v ->
            runCatching {
                v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
                v.getGear().unregisterGearSelection(gearListener)
                v.release()
            }
        }
        runCatching { car?.disconnect() }
        vehicle = null
        car = null
    }

    companion object {
        private const val TAG = "ChargisCar"
        private const val POLL_MS = 2_000L

        /** 기어를 정차 여부로. P면 정차, 그 밖에 알려진 기어면 주행 쪽, 모르면 null. */
        fun parkedOf(gear: VehicleGear): Boolean? = when (gear) {
            VehicleGear.GEAR_PARK -> true
            VehicleGear.GEAR_UNKNOWN -> null
            else -> false
        }
    }
}

/** Pleos가 아닌 기기·테스트용: 아무 신호도 없다. */
object NoCarSignals : CarSignalsPort {
    override val available: StateFlow<Boolean> = MutableStateFlow(false)
    override val socPercent: StateFlow<Int?> = MutableStateFlow(null)
    override val parked: StateFlow<Boolean?> = MutableStateFlow(null)
    override val chargeSwitchOn: StateFlow<Boolean?> = MutableStateFlow(null)
    override val uxRestricted: StateFlow<Boolean> = MutableStateFlow(false)
}
