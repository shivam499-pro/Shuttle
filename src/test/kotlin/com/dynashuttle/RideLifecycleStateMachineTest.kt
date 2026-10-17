package com.dynashuttle

import com.dynashuttle.models.RideStatus
import com.dynashuttle.services.InvalidTransitionException
import com.dynashuttle.services.RideRequestStateMachine
import kotlin.test.*

class RideLifecycleStateMachineTest {

    // ---------------------------------------------------------------------------
    // Valid Transitions (Tests A–K) — original lifecycle (pre-Task 8.2)
    // ---------------------------------------------------------------------------

    @Test
    fun `A - REQUESTED to MATCHED is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.REQUESTED, RideStatus.MATCHED))
        val result = RideRequestStateMachine.transition(RideStatus.REQUESTED, RideStatus.MATCHED)
        assertEquals(RideStatus.MATCHED, result)
    }

    @Test
    fun `B - REQUESTED to WAITLISTED is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.REQUESTED, RideStatus.WAITLISTED))
        val result = RideRequestStateMachine.transition(RideStatus.REQUESTED, RideStatus.WAITLISTED)
        assertEquals(RideStatus.WAITLISTED, result)
    }

    @Test
    fun `C - REQUESTED to CANCELLED is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.REQUESTED, RideStatus.CANCELLED))
        val result = RideRequestStateMachine.transition(RideStatus.REQUESTED, RideStatus.CANCELLED)
        assertEquals(RideStatus.CANCELLED, result)
    }

    @Test
    fun `D - MATCHED to PICKUP_PENDING is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.MATCHED, RideStatus.PICKUP_PENDING))
        val result = RideRequestStateMachine.transition(RideStatus.MATCHED, RideStatus.PICKUP_PENDING)
        assertEquals(RideStatus.PICKUP_PENDING, result)
    }

    @Test
    fun `E - MATCHED to CANCELLED is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.MATCHED, RideStatus.CANCELLED))
        val result = RideRequestStateMachine.transition(RideStatus.MATCHED, RideStatus.CANCELLED)
        assertEquals(RideStatus.CANCELLED, result)
    }

    @Test
    fun `F - WAITLISTED to MATCHED is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.WAITLISTED, RideStatus.MATCHED))
        val result = RideRequestStateMachine.transition(RideStatus.WAITLISTED, RideStatus.MATCHED)
        assertEquals(RideStatus.MATCHED, result)
    }

    @Test
    fun `G - WAITLISTED to CANCELLED is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.WAITLISTED, RideStatus.CANCELLED))
        val result = RideRequestStateMachine.transition(RideStatus.WAITLISTED, RideStatus.CANCELLED)
        assertEquals(RideStatus.CANCELLED, result)
    }

    @Test
    fun `H - PICKUP_PENDING to ONBOARD is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.PICKUP_PENDING, RideStatus.ONBOARD))
        val result = RideRequestStateMachine.transition(RideStatus.PICKUP_PENDING, RideStatus.ONBOARD)
        assertEquals(RideStatus.ONBOARD, result)
    }

    @Test
    fun `I - PICKUP_PENDING to NO_SHOW is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.PICKUP_PENDING, RideStatus.NO_SHOW))
        val result = RideRequestStateMachine.transition(RideStatus.PICKUP_PENDING, RideStatus.NO_SHOW)
        assertEquals(RideStatus.NO_SHOW, result)
    }

    @Test
    fun `J - PICKUP_PENDING to CANCELLED is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.PICKUP_PENDING, RideStatus.CANCELLED))
        val result = RideRequestStateMachine.transition(RideStatus.PICKUP_PENDING, RideStatus.CANCELLED)
        assertEquals(RideStatus.CANCELLED, result)
    }

    @Test
    fun `K - ONBOARD to COMPLETED is valid`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.ONBOARD, RideStatus.COMPLETED))
        val result = RideRequestStateMachine.transition(RideStatus.ONBOARD, RideStatus.COMPLETED)
        assertEquals(RideStatus.COMPLETED, result)
    }

    // ---------------------------------------------------------------------------
    // Invalid Transitions & Terminal State Enforcements (Tests L–R)
    // ---------------------------------------------------------------------------

    @Test
    fun `L - COMPLETED is terminal and cannot transition to any state`() {
        assertTrue(RideStatus.COMPLETED.isTerminal)
        for (target in RideStatus.entries) {
            assertFalse(RideRequestStateMachine.canTransition(RideStatus.COMPLETED, target),
                "COMPLETED must not transition to $target")
            assertFailsWith<InvalidTransitionException> {
                RideRequestStateMachine.validateTransition(RideStatus.COMPLETED, target)
            }
        }
    }

    @Test
    fun `M - CANCELLED is terminal and cannot transition to any state`() {
        assertTrue(RideStatus.CANCELLED.isTerminal)
        for (target in RideStatus.entries) {
            assertFalse(RideRequestStateMachine.canTransition(RideStatus.CANCELLED, target),
                "CANCELLED must not transition to $target")
            assertFailsWith<InvalidTransitionException> {
                RideRequestStateMachine.validateTransition(RideStatus.CANCELLED, target)
            }
        }
    }

    @Test
    fun `N - NO_SHOW is terminal and cannot transition to any state`() {
        assertTrue(RideStatus.NO_SHOW.isTerminal)
        for (target in RideStatus.entries) {
            assertFalse(RideRequestStateMachine.canTransition(RideStatus.NO_SHOW, target),
                "NO_SHOW must not transition to $target")
            assertFailsWith<InvalidTransitionException> {
                RideRequestStateMachine.validateTransition(RideStatus.NO_SHOW, target)
            }
        }
    }

    @Test
    fun `O - ONBOARD cannot transition directly to CANCELLED`() {
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.ONBOARD, RideStatus.CANCELLED),
            "Cannot cancel a ride once the rider is onboard")
        val ex = assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.ONBOARD, RideStatus.CANCELLED)
        }
        assertEquals(RideStatus.ONBOARD, ex.from)
        assertEquals(RideStatus.CANCELLED, ex.to)
    }

    @Test
    fun `P - REQUESTED cannot jump directly to ONBOARD or COMPLETED`() {
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.REQUESTED, RideStatus.ONBOARD))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.REQUESTED, RideStatus.COMPLETED))
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.REQUESTED, RideStatus.ONBOARD)
        }
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.REQUESTED, RideStatus.COMPLETED)
        }
    }

    @Test
    fun `Q - WAITLISTED cannot jump directly to ONBOARD or COMPLETED`() {
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.WAITLISTED, RideStatus.ONBOARD))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.WAITLISTED, RideStatus.COMPLETED))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.WAITLISTED, RideStatus.PICKUP_PENDING))
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.WAITLISTED, RideStatus.ONBOARD)
        }
    }

    @Test
    fun `R - CANCELLED cannot transition to MATCHED or any other state`() {
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.CANCELLED, RideStatus.MATCHED))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.CANCELLED, RideStatus.WAITLISTED))
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.transition(RideStatus.CANCELLED, RideStatus.MATCHED)
        }
    }

    // ---------------------------------------------------------------------------
    // Normalization & String Parsing Tests
    // ---------------------------------------------------------------------------

    @Test
    fun `RideStatus fromString parses QUEUED as QUEUED (real production state since V7 schema)`() {
        // V7 schema: QUEUED is a genuine production state; must round-trip cleanly.
        // The old V3-era QUEUED->WAITLISTED normalisation has been removed.
        assertEquals(RideStatus.QUEUED, RideStatus.fromString("QUEUED"))
        assertEquals(RideStatus.QUEUED, RideStatus.fromString("queued"))
    }

    @Test
    fun `RideStatus fromString normalises legacy PICKED_UP to PICKUP_PENDING`() {
        assertEquals(RideStatus.PICKUP_PENDING, RideStatus.fromString("PICKED_UP"))
        assertEquals(RideStatus.PICKUP_PENDING, RideStatus.fromString("picked_up"))
    }

    @Test
    fun `RideStatus fromString parses all canonical states case-insensitively`() {
        for (status in RideStatus.entries) {
            assertEquals(status, RideStatus.fromString(status.name))
            assertEquals(status, RideStatus.fromString(status.name.lowercase()))
        }
    }

    @Test
    fun `RideStatus fromString throws IllegalArgumentException on unknown status`() {
        assertFailsWith<IllegalArgumentException> {
            RideStatus.fromString("UNKNOWN_STATUS")
        }
    }

    // ---------------------------------------------------------------------------
    // Task 8.2 — QUEUED state transitions (Tests S–X)
    // ---------------------------------------------------------------------------

    @Test
    fun `S - REQUESTED to QUEUED is valid (BatchingService places request into batch window)`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.REQUESTED, RideStatus.QUEUED))
        val result = RideRequestStateMachine.transition(RideStatus.REQUESTED, RideStatus.QUEUED)
        assertEquals(RideStatus.QUEUED, result)
    }

    @Test
    fun `T - QUEUED to MATCHED is valid (dispatch worker assigns vehicle and batch)`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.QUEUED, RideStatus.MATCHED))
        val result = RideRequestStateMachine.transition(RideStatus.QUEUED, RideStatus.MATCHED)
        assertEquals(RideStatus.MATCHED, result)
    }

    @Test
    fun `U - QUEUED to WAITLISTED is valid (batch window closed without eligible vehicle)`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.QUEUED, RideStatus.WAITLISTED))
        val result = RideRequestStateMachine.transition(RideStatus.QUEUED, RideStatus.WAITLISTED)
        assertEquals(RideStatus.WAITLISTED, result)
    }

    @Test
    fun `V - QUEUED to EXPIRED is valid (maximum wait threshold exceeded)`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.QUEUED, RideStatus.EXPIRED))
        val result = RideRequestStateMachine.transition(RideStatus.QUEUED, RideStatus.EXPIRED)
        assertEquals(RideStatus.EXPIRED, result)
    }

    @Test
    fun `W - QUEUED to CANCELLED is valid (employee cancels during batch window)`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.QUEUED, RideStatus.CANCELLED))
        val result = RideRequestStateMachine.transition(RideStatus.QUEUED, RideStatus.CANCELLED)
        assertEquals(RideStatus.CANCELLED, result)
    }

    @Test
    fun `X - QUEUED cannot jump directly to PICKUP_PENDING, ONBOARD, or COMPLETED`() {
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.QUEUED, RideStatus.PICKUP_PENDING))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.QUEUED, RideStatus.ONBOARD))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.QUEUED, RideStatus.COMPLETED))
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.QUEUED, RideStatus.PICKUP_PENDING)
        }
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.QUEUED, RideStatus.ONBOARD)
        }
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.QUEUED, RideStatus.COMPLETED)
        }
    }

    // ---------------------------------------------------------------------------
    // Task 8.2 — WAITLISTED extended transitions (Tests Y–Z)
    // ---------------------------------------------------------------------------

    @Test
    fun `Y - WAITLISTED to QUEUED is valid (re-queued on next dispatch cycle)`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.WAITLISTED, RideStatus.QUEUED))
        val result = RideRequestStateMachine.transition(RideStatus.WAITLISTED, RideStatus.QUEUED)
        assertEquals(RideStatus.QUEUED, result)
    }

    @Test
    fun `Z - WAITLISTED to EXPIRED is valid (absolute expiry threshold exceeded)`() {
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.WAITLISTED, RideStatus.EXPIRED))
        val result = RideRequestStateMachine.transition(RideStatus.WAITLISTED, RideStatus.EXPIRED)
        assertEquals(RideStatus.EXPIRED, result)
    }

    // ---------------------------------------------------------------------------
    // Task 8.2 — EXPIRED terminal state enforcement (Tests AA–AD)
    // ---------------------------------------------------------------------------

    @Test
    fun `AA - EXPIRED is a terminal state`() {
        assertTrue(RideStatus.EXPIRED.isTerminal)
    }

    @Test
    fun `AB - EXPIRED cannot transition to any state`() {
        for (target in RideStatus.entries) {
            assertFalse(RideRequestStateMachine.canTransition(RideStatus.EXPIRED, target),
                "EXPIRED must not transition to $target")
            assertFailsWith<InvalidTransitionException> {
                RideRequestStateMachine.validateTransition(RideStatus.EXPIRED, target)
            }
        }
    }

    @Test
    fun `AC - EXPIRED is not reachable from MATCHED, PICKUP_PENDING, or ONBOARD`() {
        // Once a vehicle is assigned and execution begins, expiry cannot occur
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.MATCHED, RideStatus.EXPIRED))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.PICKUP_PENDING, RideStatus.EXPIRED))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.ONBOARD, RideStatus.EXPIRED))
    }

    @Test
    fun `AD - EXPIRED is not reachable from COMPLETED, CANCELLED, or NO_SHOW`() {
        // Terminal states cannot re-enter any lifecycle
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.COMPLETED, RideStatus.EXPIRED))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.CANCELLED, RideStatus.EXPIRED))
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.NO_SHOW, RideStatus.EXPIRED))
    }

    // ---------------------------------------------------------------------------
    // Task 8.2 — Terminal state completeness (Tests AE–AH)
    // ---------------------------------------------------------------------------

    @Test
    fun `AE - exactly four terminal states exist`() {
        val terminals = RideStatus.entries.filter { it.isTerminal }
        assertEquals(
            setOf(RideStatus.COMPLETED, RideStatus.CANCELLED, RideStatus.NO_SHOW, RideStatus.EXPIRED),
            terminals.toSet(),
            "Unexpected set of terminal states"
        )
    }

    @Test
    fun `AF - non-terminal states include REQUESTED, QUEUED, MATCHED, WAITLISTED, PICKUP_PENDING, ONBOARD`() {
        val nonTerminals = RideStatus.entries.filterNot { it.isTerminal }
        assertEquals(
            setOf(
                RideStatus.REQUESTED,
                RideStatus.QUEUED,
                RideStatus.MATCHED,
                RideStatus.WAITLISTED,
                RideStatus.PICKUP_PENDING,
                RideStatus.ONBOARD
            ),
            nonTerminals.toSet(),
            "Unexpected set of non-terminal states"
        )
    }

    @Test
    fun `AG - QUEUED cannot loop back to REQUESTED`() {
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.QUEUED, RideStatus.REQUESTED))
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.QUEUED, RideStatus.REQUESTED)
        }
    }

    @Test
    fun `AH - REQUESTED cannot directly reach EXPIRED (must pass through QUEUED or WAITLISTED)`() {
        assertFalse(RideRequestStateMachine.canTransition(RideStatus.REQUESTED, RideStatus.EXPIRED))
        assertFailsWith<InvalidTransitionException> {
            RideRequestStateMachine.validateTransition(RideStatus.REQUESTED, RideStatus.EXPIRED)
        }
    }
}
