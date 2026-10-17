package com.dynashuttle

import com.dynashuttle.models.TripStatus
import com.dynashuttle.services.InvalidTripTransitionException
import com.dynashuttle.services.TripStateMachine
import kotlin.test.*

class TripLifecycleStateMachineTest {

    // ---------------------------------------------------------------------------
    // Valid Forward Transitions
    // ---------------------------------------------------------------------------

    @Test
    fun `A - SCHEDULED to ASSIGNED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.SCHEDULED, TripStatus.ASSIGNED))
        val result = TripStateMachine.transition(TripStatus.SCHEDULED, TripStatus.ASSIGNED)
        assertEquals(TripStatus.ASSIGNED, result)
    }

    @Test
    fun `B - ASSIGNED to BOARDING is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.ASSIGNED, TripStatus.BOARDING))
        val result = TripStateMachine.transition(TripStatus.ASSIGNED, TripStatus.BOARDING)
        assertEquals(TripStatus.BOARDING, result)
    }

    @Test
    fun `C - BOARDING to IN_PROGRESS is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.BOARDING, TripStatus.IN_PROGRESS))
        val result = TripStateMachine.transition(TripStatus.BOARDING, TripStatus.IN_PROGRESS)
        assertEquals(TripStatus.IN_PROGRESS, result)
    }

    @Test
    fun `D - IN_PROGRESS to COMPLETED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.IN_PROGRESS, TripStatus.COMPLETED))
        val result = TripStateMachine.transition(TripStatus.IN_PROGRESS, TripStatus.COMPLETED)
        assertEquals(TripStatus.COMPLETED, result)
    }

    // ---------------------------------------------------------------------------
    // Valid Cancellation Transitions
    // ---------------------------------------------------------------------------

    @Test
    fun `E - SCHEDULED to CANCELLED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.SCHEDULED, TripStatus.CANCELLED))
        assertEquals(TripStatus.CANCELLED, TripStateMachine.transition(TripStatus.SCHEDULED, TripStatus.CANCELLED))
    }

    @Test
    fun `F - ASSIGNED to CANCELLED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.ASSIGNED, TripStatus.CANCELLED))
        assertEquals(TripStatus.CANCELLED, TripStateMachine.transition(TripStatus.ASSIGNED, TripStatus.CANCELLED))
    }

    @Test
    fun `G - BOARDING to CANCELLED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.BOARDING, TripStatus.CANCELLED))
        assertEquals(TripStatus.CANCELLED, TripStateMachine.transition(TripStatus.BOARDING, TripStatus.CANCELLED))
    }

    @Test
    fun `H - IN_PROGRESS to CANCELLED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.IN_PROGRESS, TripStatus.CANCELLED))
        assertEquals(TripStatus.CANCELLED, TripStateMachine.transition(TripStatus.IN_PROGRESS, TripStatus.CANCELLED))
    }

    // ---------------------------------------------------------------------------
    // Valid Abort Transitions
    // ---------------------------------------------------------------------------

    @Test
    fun `I - SCHEDULED to ABORTED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.SCHEDULED, TripStatus.ABORTED))
        assertEquals(TripStatus.ABORTED, TripStateMachine.transition(TripStatus.SCHEDULED, TripStatus.ABORTED))
    }

    @Test
    fun `J - ASSIGNED to ABORTED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.ASSIGNED, TripStatus.ABORTED))
        assertEquals(TripStatus.ABORTED, TripStateMachine.transition(TripStatus.ASSIGNED, TripStatus.ABORTED))
    }

    @Test
    fun `K - BOARDING to ABORTED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.BOARDING, TripStatus.ABORTED))
        assertEquals(TripStatus.ABORTED, TripStateMachine.transition(TripStatus.BOARDING, TripStatus.ABORTED))
    }

    @Test
    fun `L - IN_PROGRESS to ABORTED is valid`() {
        assertTrue(TripStateMachine.canTransition(TripStatus.IN_PROGRESS, TripStatus.ABORTED))
        assertEquals(TripStatus.ABORTED, TripStateMachine.transition(TripStatus.IN_PROGRESS, TripStatus.ABORTED))
    }

    // ---------------------------------------------------------------------------
    // Terminal States Cannot Transition
    // ---------------------------------------------------------------------------

    @Test
    fun `M - COMPLETED is terminal and allows no transitions`() {
        for (target in TripStatus.values()) {
            assertFalse(TripStateMachine.canTransition(TripStatus.COMPLETED, target))
            assertFailsWith<InvalidTripTransitionException> {
                TripStateMachine.transition(TripStatus.COMPLETED, target)
            }
        }
    }

    @Test
    fun `N - CANCELLED is terminal and allows no transitions`() {
        for (target in TripStatus.values()) {
            assertFalse(TripStateMachine.canTransition(TripStatus.CANCELLED, target))
            assertFailsWith<InvalidTripTransitionException> {
                TripStateMachine.transition(TripStatus.CANCELLED, target)
            }
        }
    }

    @Test
    fun `O - ABORTED is terminal and allows no transitions`() {
        for (target in TripStatus.values()) {
            assertFalse(TripStateMachine.canTransition(TripStatus.ABORTED, target))
            assertFailsWith<InvalidTripTransitionException> {
                TripStateMachine.transition(TripStatus.ABORTED, target)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Invalid Skip-Ahead Transitions
    // ---------------------------------------------------------------------------

    @Test
    fun `P - SCHEDULED cannot skip directly to IN_PROGRESS`() {
        assertFalse(TripStateMachine.canTransition(TripStatus.SCHEDULED, TripStatus.IN_PROGRESS))
        assertFailsWith<InvalidTripTransitionException> {
            TripStateMachine.transition(TripStatus.SCHEDULED, TripStatus.IN_PROGRESS)
        }
    }

    @Test
    fun `Q - SCHEDULED cannot skip directly to COMPLETED`() {
        assertFalse(TripStateMachine.canTransition(TripStatus.SCHEDULED, TripStatus.COMPLETED))
        assertFailsWith<InvalidTripTransitionException> {
            TripStateMachine.transition(TripStatus.SCHEDULED, TripStatus.COMPLETED)
        }
    }

    @Test
    fun `R - ASSIGNED cannot skip directly to IN_PROGRESS`() {
        assertFalse(TripStateMachine.canTransition(TripStatus.ASSIGNED, TripStatus.IN_PROGRESS))
        assertFailsWith<InvalidTripTransitionException> {
            TripStateMachine.transition(TripStatus.ASSIGNED, TripStatus.IN_PROGRESS)
        }
    }

    @Test
    fun `S - ASSIGNED cannot skip directly to COMPLETED`() {
        assertFalse(TripStateMachine.canTransition(TripStatus.ASSIGNED, TripStatus.COMPLETED))
        assertFailsWith<InvalidTripTransitionException> {
            TripStateMachine.transition(TripStatus.ASSIGNED, TripStatus.COMPLETED)
        }
    }

    @Test
    fun `T - BOARDING cannot skip directly to COMPLETED`() {
        assertFalse(TripStateMachine.canTransition(TripStatus.BOARDING, TripStatus.COMPLETED))
        assertFailsWith<InvalidTripTransitionException> {
            TripStateMachine.transition(TripStatus.BOARDING, TripStatus.COMPLETED)
        }
    }

    // ---------------------------------------------------------------------------
    // Invalid Backward Transitions
    // ---------------------------------------------------------------------------

    @Test
    fun `U - IN_PROGRESS cannot regress to BOARDING, ASSIGNED, or SCHEDULED`() {
        val regressive = listOf(TripStatus.BOARDING, TripStatus.ASSIGNED, TripStatus.SCHEDULED)
        for (target in regressive) {
            assertFalse(TripStateMachine.canTransition(TripStatus.IN_PROGRESS, target))
            assertFailsWith<InvalidTripTransitionException> {
                TripStateMachine.transition(TripStatus.IN_PROGRESS, target)
            }
        }
    }

    @Test
    fun `V - BOARDING cannot regress to ASSIGNED or SCHEDULED`() {
        val regressive = listOf(TripStatus.ASSIGNED, TripStatus.SCHEDULED)
        for (target in regressive) {
            assertFalse(TripStateMachine.canTransition(TripStatus.BOARDING, target))
            assertFailsWith<InvalidTripTransitionException> {
                TripStateMachine.transition(TripStatus.BOARDING, target)
            }
        }
    }

    @Test
    fun `W - ASSIGNED cannot regress to SCHEDULED`() {
        assertFalse(TripStateMachine.canTransition(TripStatus.ASSIGNED, TripStatus.SCHEDULED))
        assertFailsWith<InvalidTripTransitionException> {
            TripStateMachine.transition(TripStatus.ASSIGNED, TripStatus.SCHEDULED)
        }
    }

    // ---------------------------------------------------------------------------
    // Enum Properties and Parsing
    // ---------------------------------------------------------------------------

    @Test
    fun `X - isTerminal flags match expected domain definitions`() {
        assertTrue(TripStatus.COMPLETED.isTerminal)
        assertTrue(TripStatus.CANCELLED.isTerminal)
        assertTrue(TripStatus.ABORTED.isTerminal)

        assertFalse(TripStatus.SCHEDULED.isTerminal)
        assertFalse(TripStatus.ASSIGNED.isTerminal)
        assertFalse(TripStatus.BOARDING.isTerminal)
        assertFalse(TripStatus.IN_PROGRESS.isTerminal)
    }

    @Test
    fun `Y - fromString parses canonical and case-insensitive strings`() {
        assertEquals(TripStatus.SCHEDULED, TripStatus.fromString("SCHEDULED"))
        assertEquals(TripStatus.ASSIGNED, TripStatus.fromString("assigned"))
        assertEquals(TripStatus.BOARDING, TripStatus.fromString("Boarding"))
        assertEquals(TripStatus.IN_PROGRESS, TripStatus.fromString("in_progress"))
        assertEquals(TripStatus.COMPLETED, TripStatus.fromString("COMPLETED"))
        assertEquals(TripStatus.CANCELLED, TripStatus.fromString("cancelled"))
        assertEquals(TripStatus.ABORTED, TripStatus.fromString("ABORTED"))
    }
}
