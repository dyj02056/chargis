package com.chargis.app.domain.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TripStateMachineTest {
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

    @Test
    fun declineReturnsToDriving() {
        assertEquals(TripPhase.Driving, TripStateMachine.reduce(TripPhase.AwaitingDecision, TripEvent.Declined))
    }

    @Test
    fun invalidTransitionKeepsPhase() {
        assertEquals(TripPhase.Driving, TripStateMachine.reduce(TripPhase.Driving, TripEvent.ChargingStarted))
    }
}
