package com.chargis.app.domain.session

/*
 * 여행 한 번의 진행 단계를 정하는 "상태 머신"이다.
 * 상태 머신이란 "지금 어느 단계에 있는지(상태)"와 "무슨 일이 일어나면(이벤트) 어느 단계로 넘어가는지"를
 * 표로 정해 둔 것이다. 정해진 길로만 움직이므로, 충전 중에 갑자기 추천 화면이 뜨는 식의 꼬임을 막는다.
 *
 * 기본 흐름
 *   Idle(대기) → TripPlanned(목적지 설정) → Driving(주행)
 *   → AwaitingDecision(추천 안내, 운전자 결정 대기)
 *       ├ 수락 → NavigatingToCharger(충전소로 이동) → Arrived(도착) → Charging(충전 중) → Done(충전 완료)
 *       │        → Resuming(다시 출발) → Driving
 *       └ 거절 → Driving (다음 충전소를 기다림)
 *   목적지에 도착하면 어느 단계에서든 Finished(여행 종료)
 */

/** plan.md §8.1 상태. 목적지 도착을 표현하려고 Finished를 더했다. */
enum class TripPhase {
    Idle, TripPlanned, Driving, AwaitingDecision, NavigatingToCharger,
    Arrived, Charging, Done, Resuming, Finished
}

/** 상태를 바꾸는 계기가 되는 사건들. 예) Accepted = 운전자가 [수락]을 누름 */
enum class TripEvent {
    DestinationSet, PlanEvaluated, RecommendationAnnounced, Accepted, Declined,
    ArrivedAtStop, ChargingStarted, ChargingFinished, Depart, ResumeComplete,
    DestinationReached, Reset
}

object TripStateMachine {
    /**
     * 지금 상태 [phase]에서 사건 [event]가 일어났을 때 다음 상태를 돌려준다.
     * 허용되지 않은 전이는 현재 상태를 그대로 돌려준다.
     */
    fun reduce(phase: TripPhase, event: TripEvent): TripPhase = when (event) {
        // 초기화와 목적지 도착은 어느 상태에서든 바로 적용한다.
        TripEvent.Reset -> TripPhase.Idle
        TripEvent.DestinationReached -> TripPhase.Finished
        else -> transitions[phase to event] ?: phase
    }

    // 전이표: (현재 상태, 사건) → 다음 상태. 여기 없는 조합은 무시된다.
    private val transitions: Map<Pair<TripPhase, TripEvent>, TripPhase> = mapOf(
        (TripPhase.Idle to TripEvent.DestinationSet) to TripPhase.TripPlanned,
        (TripPhase.TripPlanned to TripEvent.PlanEvaluated) to TripPhase.Driving,
        (TripPhase.Driving to TripEvent.RecommendationAnnounced) to TripPhase.AwaitingDecision,
        (TripPhase.AwaitingDecision to TripEvent.Accepted) to TripPhase.NavigatingToCharger,
        (TripPhase.AwaitingDecision to TripEvent.Declined) to TripPhase.Driving,
        (TripPhase.NavigatingToCharger to TripEvent.ArrivedAtStop) to TripPhase.Arrived,
        (TripPhase.Arrived to TripEvent.ChargingStarted) to TripPhase.Charging,
        (TripPhase.Arrived to TripEvent.Depart) to TripPhase.Resuming,      // 충전하지 않고 바로 출발
        (TripPhase.Charging to TripEvent.ChargingFinished) to TripPhase.Done,
        (TripPhase.Done to TripEvent.Depart) to TripPhase.Resuming,
        (TripPhase.Resuming to TripEvent.ResumeComplete) to TripPhase.Driving
    )
}
