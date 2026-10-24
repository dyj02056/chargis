package com.chargis.app.domain.session

/** plan.md §8.1 상태. 목적지 도착을 표현하려고 Finished를 더했다. */
enum class TripPhase {
    Idle, TripPlanned, Driving, AwaitingDecision, NavigatingToCharger,
    Arrived, Charging, Done, Resuming, Finished
}

enum class TripEvent {
    DestinationSet, PlanEvaluated, RecommendationAnnounced, Accepted, Declined,
    ArrivedAtStop, ChargingStarted, ChargingFinished, Depart, ResumeComplete,
    DestinationReached, Reset
}

object TripStateMachine {
    /** 허용되지 않은 전이는 현재 상태를 그대로 돌려준다. */
    fun reduce(phase: TripPhase, event: TripEvent): TripPhase = when (event) {
        TripEvent.Reset -> TripPhase.Idle
        TripEvent.DestinationReached -> TripPhase.Finished
        else -> transitions[phase to event] ?: phase
    }

    private val transitions: Map<Pair<TripPhase, TripEvent>, TripPhase> = mapOf(
        (TripPhase.Idle to TripEvent.DestinationSet) to TripPhase.TripPlanned,
        (TripPhase.TripPlanned to TripEvent.PlanEvaluated) to TripPhase.Driving,
        (TripPhase.Driving to TripEvent.RecommendationAnnounced) to TripPhase.AwaitingDecision,
        (TripPhase.AwaitingDecision to TripEvent.Accepted) to TripPhase.NavigatingToCharger,
        (TripPhase.AwaitingDecision to TripEvent.Declined) to TripPhase.Driving,
        (TripPhase.NavigatingToCharger to TripEvent.ArrivedAtStop) to TripPhase.Arrived,
        (TripPhase.Arrived to TripEvent.ChargingStarted) to TripPhase.Charging,
        (TripPhase.Arrived to TripEvent.Depart) to TripPhase.Resuming,
        (TripPhase.Charging to TripEvent.ChargingFinished) to TripPhase.Done,
        (TripPhase.Done to TripEvent.Depart) to TripPhase.Resuming,
        (TripPhase.Resuming to TripEvent.ResumeComplete) to TripPhase.Driving
    )
}
