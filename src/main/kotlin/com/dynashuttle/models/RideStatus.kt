package com.dynashuttle.models

/**
 * Canonical ride-request lifecycle states.
 *
 * As of V7 schema (Task 8.1), QUEUED and EXPIRED are real production states:
 *
 *   QUEUED   — request is inside an open batch window, awaiting dispatch.
 *   EXPIRED  — no vehicle was found within the maximum wait threshold (terminal).
 *
 * The legacy QUEUED→WAITLISTED normalisation (V3 migration) is no longer applied;
 * QUEUED rows written by the dispatch worker are legitimate and must round-trip cleanly.
 *
 * Terminal states: COMPLETED, CANCELLED, NO_SHOW, EXPIRED — no further transitions allowed.
 */
enum class RideStatus {
    REQUESTED,
    QUEUED,
    MATCHED,
    WAITLISTED,
    PICKUP_PENDING,
    ONBOARD,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
    EXPIRED;

    val isTerminal: Boolean get() = this in setOf(COMPLETED, CANCELLED, NO_SHOW, EXPIRED)

    companion object {
        /**
         * Parses a status string from the database.
         *
         * Legacy normalisations retained for backwards compatibility:
         *   PICKED_UP  → PICKUP_PENDING  (old column value used in pre-V3 rows)
         *
         * QUEUED is no longer normalised to WAITLISTED — it is a valid production
         * state as of V7 and must be preserved as-is.
         */
        fun fromString(value: String): RideStatus = when (value.uppercase()) {
            "PICKED_UP" -> PICKUP_PENDING   // legacy normalisation (old column value)
            else -> valueOf(value.uppercase())
        }
    }
}
