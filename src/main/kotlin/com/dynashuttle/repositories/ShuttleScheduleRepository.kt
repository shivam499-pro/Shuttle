package com.dynashuttle.repositories

import com.dynashuttle.models.ScheduleDay
import com.dynashuttle.models.ScheduleDirection
import com.dynashuttle.models.ShuttleSchedule
import java.time.LocalTime

/**
 * Narrow interface for ShuttleSchedule persistence.
 *
 * Only the operations actually required by [com.dynashuttle.services.ShuttleScheduleService]
 * are declared here. Exposed/PostgreSQL details stay in the implementation class.
 */
interface ShuttleScheduleRepository {

    fun create(
        companyId: String,
        name: String,
        direction: ScheduleDirection,
        departureTime: LocalTime,
        daysOfWeek: List<ScheduleDay>
    ): ShuttleSchedule

    fun findById(id: String): ShuttleSchedule?

    fun findByCompany(companyId: String): List<ShuttleSchedule>

    fun findActiveByCompany(companyId: String): List<ShuttleSchedule>

    fun update(
        id: String,
        companyId: String,
        name: String? = null,
        direction: ScheduleDirection? = null,
        departureTime: LocalTime? = null,
        daysOfWeek: List<ScheduleDay>? = null,
        active: Boolean? = null
    ): ShuttleSchedule?

    fun deactivate(id: String, companyId: String): Boolean
}
