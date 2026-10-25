package com.chargis.app.data.sdk

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import com.chargis.app.port.NaviPort
import com.chargis.app.port.TripEstimate
import com.chargis.app.port.TripEstimator
import com.chargis.app.port.WaypointsFullException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Pleos 순정 내비(NaviHelper SDK) 연결.
 * - B7: 수락한 충전소를 내비 경유지로 넣고 뺀다. 경유지는 두 자리뿐이라, 다른 곳으로 차 있으면 [WaypointsFullException]을 돌려준다.
 * - B8: 충전소까지의 실제 도로 거리·도착 시간·도착 배터리를 묻는다(requestTrip).
 *
 * NaviHelper는 요청 함수를 부르면 결과가 리스너로 따로 온다. 요청마다 짝을 맞출 번호가 없어 [mutex]로 한 번에 하나씩만 묻는다.
 * 퍼미션: NAVI_ROUTE(get*Info·requestTrip), NAVI_ROUTE_SEARCH(경유지 추가·삭제·재탐색).
 */
class PleosNavi(context: Context) : TripEstimator {
    private val helper = NaviHelper(context.applicationContext)
    private val mutex = Mutex()
    @Volatile private var ready = false
    @Volatile private var trip: CompletableDeferred<TripInfo?>? = null
    @Volatile private var waypoints: CompletableDeferred<List<WaypointInfo>>? = null
    @Volatile private var location: CompletableDeferred<CurrentLocationInfo>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onTripInfo(tripInfo: TripInfo) { trip?.complete(tripInfo) }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) { waypoints?.complete(waypointInfoList) }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { location?.complete(currentLocationInfo) }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
            if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) trip?.complete(null)
        }
    }

    /** 내비 서비스에 연결한다. Pleos가 아니거나 연결하지 못하면 false. */
    suspend fun connect(): Boolean {
        if (ready) return true
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    helper.initialize({ if (cont.isActive) cont.resume(true) }, { e -> Log.w(TAG, "init failed", e); if (cont.isActive) cont.resume(false) })
                }.onFailure { Log.w(TAG, "init threw", it); if (cont.isActive) cont.resume(false) }
            }
        } ?: false
        if (ok) helper.addListener(listener)
        ready = ok
        return ok
    }

    val isReady: Boolean get() = ready

    override suspend fun currentLocation(): GeoPoint? = ask<CurrentLocationInfo>({ location = it }) { helper.getCurrentLocationInfo() }
        ?.let { GeoPoint(it.latitude, it.longitude) }
        ?.also { Log.d(TAG, "location ${it.lat},${it.lon}") }

    override suspend fun estimate(station: ChargingStation): TripEstimate? {
        val info = ask<TripInfo?>({ trip = it }) {
            helper.requestTrip(TripRequest(latitude = station.location.lat, longitude = station.location.lon, poiId = poiId(station), poiName = station.name, poiSubId = "0"))
        } ?: return null
        Log.i(TAG, "trip ${station.id}: distance=${info.distance} eta=${info.eta} soc=${info.estimatedSocAtDest}")
        return toEstimate(info.distance, info.eta, info.estimatedSocAtDest)
    }

    /** 경유지 추가. 이미 우리가 넣은 곳이면 그대로 성공, 두 자리가 다른 곳으로 차 있으면 [WaypointsFullException]. */
    suspend fun addWaypoint(station: ChargingStation): Result<Unit> {
        if (!ready) return Result.failure(IllegalStateException("navi not connected"))
        val current = ask<List<WaypointInfo>>({ waypoints = it }) { helper.getWaypointInfo() } ?: emptyList()
        if (current.any { it.matches(station) }) return Result.success(Unit)
        if (current.size >= MAX_WAYPOINTS) return Result.failure(WaypointsFullException())
        val slot = if (current.isEmpty()) WaypointIndex.FIRST else WaypointIndex.SECOND
        return runCatching {
            helper.addWaypoint(
                RequestWaypointInfo(
                    latitude = station.location.lat, longitude = station.location.lon, poiName = station.name, waypointIndex = slot,
                    poiId = poiId(station), address = "", poiSubId = "0", poiProvider = ""
                )
            )
        }
    }

    /** 우리가 넣은 경유지만 찾아 뺀다. 내비에서 이미 지웠으면 그대로 성공. */
    suspend fun removeWaypoint(station: ChargingStation): Result<Unit> {
        if (!ready) return Result.failure(IllegalStateException("navi not connected"))
        val current = ask<List<WaypointInfo>>({ waypoints = it }) { helper.getWaypointInfo() } ?: return Result.success(Unit)
        val index = current.indexOfFirst { it.matches(station) }
        if (index < 0) return Result.success(Unit)
        return runCatching { helper.removeWaypoint(if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND) }
    }

    fun requestReRoute(): Result<Unit> = if (ready) runCatching { helper.requestReRoute() } else Result.failure(IllegalStateException("navi not connected"))

    fun release() {
        if (!ready) return
        runCatching { helper.removeListener(listener); helper.release() }
        ready = false
    }

    // 요청 하나를 보내고 리스너로 올 답을 기다린다. 시간 안에 오지 않으면 null.
    private suspend fun <T> ask(slot: (CompletableDeferred<T>?) -> Unit, request: () -> Unit): T? {
        if (!ready) return null
        return mutex.withLock {
            val d = CompletableDeferred<T>()
            slot(d)
            try {
                runCatching(request).onFailure { Log.w(TAG, "request failed", it); return@withLock null }
                withTimeoutOrNull(ANSWER_TIMEOUT_MS) { d.await() }
            } finally {
                slot(null)
            }
        }
    }

    private fun WaypointInfo.matches(station: ChargingStation) =
        poiId == poiId(station) || (name == station.name && RoadDistanceRefiner.distanceKm(GeoPoint(latitude, longitude), station.location) < 0.3)

    companion object {
        private const val TAG = "ChargisNavi"
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val ANSWER_TIMEOUT_MS = 3_000L
        const val MAX_WAYPOINTS = 2

        /** 내비에 넣는 POI id. 우리가 넣은 경유지를 다시 찾을 때 쓴다. */
        fun poiId(station: ChargingStation) = "chargis-${station.id}"

        /** TripInfo 값(거리 m, 도착 시간 초, 도착 배터리 %)을 앱 단위로. 배터리를 모르면(음수) null. */
        fun toEstimate(distanceM: Int, etaSec: Int, socAtDest: Int): TripEstimate =
            TripEstimate(distanceKm = distanceM / 1000.0, etaMin = (etaSec / 60.0).roundToInt(), arrivalSocPercent = socAtDest.takeIf { it in 0..100 })
    }
}

/**
 * 하이브리드 내비: 경로·위치는 데모 시뮬레이터([demo])가 맡고, 수락한 경유지는 순정 내비([pleos])에도 넣는다.
 * 순정 내비에 연결하지 못했거나 일시적으로 실패하면 데모만으로 계속한다. 다만 내비 경유지 두 자리가 다른 곳으로 차 있으면
 * 데모 쪽도 되돌리고 실패를 돌려준다(운전자에게 내비에서 직접 정리하라고 안내해야 하므로).
 */
class HybridNavi(private val demo: NaviPort, private val pleos: PleosNavi) : NaviPort {
    override val routeState: Flow<RouteState?> = demo.routeState

    override suspend fun addWaypoint(station: ChargingStation): Result<Unit> {
        demo.addWaypoint(station).onFailure { return Result.failure(it) }
        if (!pleos.isReady) return Result.success(Unit)
        val real = pleos.addWaypoint(station)
        val full = real.exceptionOrNull() as? WaypointsFullException
        if (full != null) {
            demo.removeWaypoint(station)
            return Result.failure(full)
        }
        real.onFailure { Log.w("ChargisNavi", "navi waypoint add failed; demo continues", it) }
        return Result.success(Unit)
    }

    override suspend fun removeWaypoint(station: ChargingStation): Result<Unit> {
        if (pleos.isReady) pleos.removeWaypoint(station).onFailure { Log.w("ChargisNavi", "navi waypoint remove failed", it) }
        return demo.removeWaypoint(station)
    }

    override suspend fun requestReRoute(): Result<Unit> {
        if (pleos.isReady) pleos.requestReRoute()
        return demo.requestReRoute()
    }
}
