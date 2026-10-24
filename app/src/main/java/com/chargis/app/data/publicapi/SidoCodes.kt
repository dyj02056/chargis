package com.chargis.app.data.publicapi

import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 경로가 지나는 시도 코드(공공데이터 API의 zcode = 행정구역코드 앞 두 자리)를 고른다.
 *
 * 충전소 목록은 시도 단위로만 받을 수 있어서, 예전처럼 서울·경기·강원(11/41/51)으로 고정하면
 * 서울→부산 같은 경로에서 충청·경상 구간 충전소를 아예 받지 못한다.
 *
 * 내비 경로(폴리라인)가 아직 없으므로 지금 위치→목적지 직선 위를 [stepKm]마다 찍고,
 * 각 점에서 가장 가까운 시도 중심 [nearest]곳을 모두 모은다. 시도 경계는 중심점 거리로 어림하므로
 * 경계 근처를 놓치지 않게 한 점에 두 곳씩 넣는다(조금 더 받는 대신 빠뜨리지 않는 쪽).
 * 시도 중심 좌표는 대략값이다. 강원(51)·전북(52)은 특별자치도 출범 뒤 코드다. API가 옛 코드(42, 45)를 쓰는지는
 * 실제 응답으로 확인해야 한다(docs/OPEN_ISSUES.md).
 */
object SidoCodes {

    fun along(route: RouteState, stepKm: Double = 5.0, nearest: Int = 2): List<String> {
        val dest = route.destination ?: return nearestCodes(route.current, nearest)
        val (dx, dy) = toKm(route.current, dest)
        val steps = ceil(sqrt(dx * dx + dy * dy) / stepKm).toInt().coerceAtLeast(1)
        return (0..steps).flatMap { i ->
            val t = i.toDouble() / steps
            nearestCodes(
                GeoPoint(route.current.lat + (dest.lat - route.current.lat) * t, route.current.lon + (dest.lon - route.current.lon) * t),
                nearest
            )
        }.distinct().sorted()
    }

    private fun nearestCodes(p: GeoPoint, n: Int): List<String> =
        CENTERS.entries.sortedBy { (_, c) -> toKm(p, c).let { (x, y) -> x * x + y * y } }.take(n).map { it.key }

    private fun toKm(from: GeoPoint, to: GeoPoint): Pair<Double, Double> =
        (to.lon - from.lon) * 111.32 * cos(Math.toRadians(from.lat)) to (to.lat - from.lat) * 110.57

    // 시도 코드 → 대략 중심(위도, 경도)
    private val CENTERS: Map<String, GeoPoint> = mapOf(
        "11" to GeoPoint(37.56, 126.98),   // 서울
        "26" to GeoPoint(35.17, 129.07),   // 부산
        "27" to GeoPoint(35.87, 128.60),   // 대구
        "28" to GeoPoint(37.46, 126.70),   // 인천
        "29" to GeoPoint(35.16, 126.85),   // 광주
        "30" to GeoPoint(36.35, 127.38),   // 대전
        "31" to GeoPoint(35.54, 129.31),   // 울산
        "36" to GeoPoint(36.48, 127.29),   // 세종
        "41" to GeoPoint(37.45, 127.30),   // 경기
        "43" to GeoPoint(36.80, 127.70),   // 충북
        "44" to GeoPoint(36.52, 126.80),   // 충남
        "46" to GeoPoint(34.87, 126.99),   // 전남
        "47" to GeoPoint(36.49, 128.89),   // 경북
        "48" to GeoPoint(35.46, 128.21),   // 경남
        "50" to GeoPoint(33.49, 126.50),   // 제주
        "51" to GeoPoint(37.82, 128.16),   // 강원
        "52" to GeoPoint(35.72, 127.15)    // 전북
    )
}
