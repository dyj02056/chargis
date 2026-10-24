package com.chargis.app.data.publicapi

import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import kotlin.math.cos

/**
 * 고속도로 휴게소의 진행 방향 판단.
 *
 * 공공데이터에는 같은 휴게소가 "OO휴게소(서울방향)", "OO휴게소(부산방향)"처럼 방향별로 따로 들어 있다.
 * 반대편 휴게소는 본선에서 들어갈 수 없는데, 내비 경로(폴리라인)가 없는 지금은 위치만으로 구분할 수 없다.
 * 그래서 이름의 "(○○방향)"에 적힌 도시가 우리 진행 방향(지금 위치 → 목적지) 쪽에 있는지로 판단한다.
 *
 * 예) 서울→속초로 가는 중, "가상휴게소(서울방향)": 휴게소에서 서울 쪽은 진행 방향의 반대 → 반대편
 *
 * 도시 좌표는 방향을 가르는 데만 쓰는 대략값이다. 표에 없는 도시, "양방향", 방향 표기가 없는 이름은 판단하지 않는다(남긴다).
 * 내비 SDK에서 실제 경로를 받으면 이 추정 대신 경로 위 위치로 판단해야 한다(docs/OPEN_ISSUES.md).
 */
object RestStopDirection {

    /** 이름의 방향 도시가 진행 방향의 반대쪽이면 true. 모르면 false(후보로 남긴다). */
    fun isOpposite(name: String, location: GeoPoint, route: RouteState): Boolean {
        val dest = route.destination ?: return false
        val city = directionCity(name) ?: return false
        val target = CITIES[city] ?: return false
        // 진행 방향(지금 위치 → 목적지)과 휴게소 → 방향 도시를 km 단위 벡터로 바꿔 내적의 부호를 본다.
        val (rx, ry) = toKm(route.current, dest)
        val (tx, ty) = toKm(location, target)
        return rx * tx + ry * ty < 0
    }

    /** "OO휴게소(서울방향)" → "서울". "(양방향)"이나 방향 표기가 없으면 null. */
    fun directionCity(name: String): String? {
        val city = DIRECTION.find(name)?.groupValues?.get(1) ?: return null
        return city.takeIf { it != "양" }
    }

    private fun toKm(from: GeoPoint, to: GeoPoint): Pair<Double, Double> =
        (to.lon - from.lon) * 111.32 * cos(Math.toRadians(from.lat)) to (to.lat - from.lat) * 110.57

    private val DIRECTION = Regex("""\(\s*([가-힣]+?)\s*방향\s*\)""")

    // 휴게소 방향 표기에 자주 쓰이는 도시의 대략 좌표(위도, 경도)
    private val CITIES: Map<String, GeoPoint> = mapOf(
        "서울" to GeoPoint(37.57, 126.98), "부산" to GeoPoint(35.18, 129.08), "인천" to GeoPoint(37.46, 126.71),
        "대전" to GeoPoint(36.35, 127.38), "대구" to GeoPoint(35.87, 128.60), "광주" to GeoPoint(35.16, 126.85),
        "울산" to GeoPoint(35.54, 129.31), "세종" to GeoPoint(36.48, 127.29), "강릉" to GeoPoint(37.75, 128.88),
        "춘천" to GeoPoint(37.88, 127.73), "원주" to GeoPoint(37.34, 127.92), "속초" to GeoPoint(38.21, 128.59),
        "양양" to GeoPoint(38.08, 128.62), "동해" to GeoPoint(37.52, 129.11), "삼척" to GeoPoint(37.45, 129.17),
        "목포" to GeoPoint(34.81, 126.39), "순천" to GeoPoint(34.95, 127.49), "여수" to GeoPoint(34.76, 127.66),
        "광양" to GeoPoint(34.94, 127.70), "통영" to GeoPoint(34.85, 128.43), "진주" to GeoPoint(35.18, 128.11),
        "창원" to GeoPoint(35.23, 128.68), "마산" to GeoPoint(35.21, 128.57), "포항" to GeoPoint(36.02, 129.34),
        "경주" to GeoPoint(35.86, 129.22), "안동" to GeoPoint(36.57, 128.73), "영덕" to GeoPoint(36.42, 129.37),
        "상주" to GeoPoint(36.41, 128.16), "김천" to GeoPoint(36.14, 128.11), "영천" to GeoPoint(35.97, 128.94),
        "청주" to GeoPoint(36.64, 127.49), "천안" to GeoPoint(36.82, 127.11), "당진" to GeoPoint(36.89, 126.63),
        "서산" to GeoPoint(36.78, 126.45), "보령" to GeoPoint(36.33, 126.61), "서천" to GeoPoint(36.08, 126.69),
        "평택" to GeoPoint(36.99, 127.11), "군산" to GeoPoint(35.97, 126.74), "전주" to GeoPoint(35.82, 127.15),
        "익산" to GeoPoint(35.95, 126.96), "남원" to GeoPoint(35.42, 127.39), "함양" to GeoPoint(35.52, 127.73),
        "무안" to GeoPoint(34.99, 126.48), "고창" to GeoPoint(35.44, 126.70), "하남" to GeoPoint(37.54, 127.21),
        "구리" to GeoPoint(37.59, 127.13), "양평" to GeoPoint(37.49, 127.49), "홍천" to GeoPoint(37.70, 127.89),
        "인제" to GeoPoint(38.07, 128.17), "여주" to GeoPoint(37.30, 127.64), "제천" to GeoPoint(37.13, 128.19),
        "충주" to GeoPoint(36.99, 127.93), "판교" to GeoPoint(37.39, 127.11), "성남" to GeoPoint(37.42, 127.13),
        "수원" to GeoPoint(37.26, 127.03), "일산" to GeoPoint(37.66, 126.77), "김포" to GeoPoint(37.62, 126.72),
        "안산" to GeoPoint(37.32, 126.83), "시흥" to GeoPoint(37.38, 126.80), "남해" to GeoPoint(34.84, 127.89)
    )
}
