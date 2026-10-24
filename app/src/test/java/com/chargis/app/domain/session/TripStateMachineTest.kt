package com.chargis.app.domain.session

import org.junit.Assert.assertEquals
import org.junit.Test

/* 여행 단계 전이표(TripStateMachine) 테스트 */
class TripStateMachineTest {
    // 정상 흐름: 목적지 설정부터 충전 후 다시 주행까지 정해진 순서대로 단계가 바뀌는지
    // (runningFold는 사건을 하나씩 적용하면서 거쳐 간 단계를 모두 목록으로 남긴다.)
    @Test
    fun happyPath() {
        val events = listOf(
            TripEvent.DestinationSet, TripEvent.PlanEvaluated, TripEvent.RecommendationAnnounced,
            TripEvent.Accepted, TripEvent.ArrivedAtStop, TripEvent.ChargingStarted,
            TripEvent.ChargingFinished, TripEvent.Depart, TripEvent.ResumeComplete
        )
        val visited = events.runningFold(TripPhase.Idle, TripStateMachine::reduce)
        assertEquals(
            listOf(
                TripPhase.Idle, TripPhase.TripPlanned, TripPhase.Driving, TripPhase.AwaitingDecision,
                TripPhase.NavigatingToCharger, TripPhase.Arrived, TripPhase.Charging, TripPhase.Done,
                TripPhase.Resuming, TripPhase.Driving
            ),
            visited
        )
    }

    // 추천을 거절하면 다시 주행 단계로 돌아가는지
    @Test
    fun declineReturnsToDriving() {
        assertEquals(TripPhase.Driving, TripStateMachine.reduce(TripPhase.AwaitingDecision, TripEvent.Declined))
    }

    // 말이 안 되는 사건(주행 중에 충전 시작)은 무시하고 단계를 유지하는지
    @Test
    fun invalidTransitionKeepsPhase() {
        assertEquals(TripPhase.Driving, TripStateMachine.reduce(TripPhase.Driving, TripEvent.ChargingStarted))
    }
}
