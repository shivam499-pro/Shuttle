package com.dynashuttle.services

import com.dynashuttle.config.Env
import com.dynashuttle.models.CreateShuttleScheduleRequest
import com.dynashuttle.models.GenerateTripRequest
import com.dynashuttle.models.ScheduleDay
import com.dynashuttle.models.ScheduleDirection
import com.dynashuttle.models.ShuttleSchedule
import com.dynashuttle.models.Trip
import com.dynashuttle.models.UpdateShuttleScheduleRequest
import com.dynashuttle.repositories.ShuttleScheduleRepository
import com.dynashuttle.repositories.TripRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Business logic for ShuttleSchedule management and Trip generation.
 *
 * Responsibilities:
 *  - Input validation (name, direction, departureTime, daysOfWeek)
 *  - Tenant ownership enforcement
 *  - CRUD orchestration through [ShuttleScheduleRepository]
 *  - Schedule-to-Trip generation through [TripRepository]
 *
 * Routes must remain thin — all business checks happen here, not in Ktor handlers.
 */
class ShuttleScheduleService(
    private val scheduleRepository: ShuttleScheduleRepository,
    private val tripRepository: TripRepository? = null,
    private val zoneId: ZoneId = ZoneId.of(Env.get("APP_TIMEZONE", "UTC") ?: "UTC")
) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // =========================================================================
    // Sealed result types (mirrors PickupZoneService conventions)
    // =========================================================================

    sealed class CreateResult {
        data class Success(val schedule: ShuttleSchedule) : CreateResult()
        data class ValidationError(val message: String) : CreateResult()
    }

    sealed class UpdateResult {
        data class Success(val schedule: ShuttleSchedule) : UpdateResult()
        object NotFound : UpdateResult()
        data class ValidationError(val message: String) : UpdateResult()
    }

    sealed class GenerateTripResult {
        data class Success(val trip: Trip, val isCreated: Boolean = true) : GenerateTripResult()
        object NotFound : GenerateTripResult()
        data class InactiveSchedule(val message: String = "Cannot generate trip from inactive schedule") : GenerateTripResult()
        data class ValidationError(val message: String) : GenerateTripResult()
    }

    // =========================================================================
    // Validation helpers
    // =========================================================================

    private fun validateName(name: String): String? {
        if (name.trim().isEmpty()) return "Name cannot be blank"
        if (name.trim().length > 255) return "Name must not exceed 255 characters"
        return null
    }

    private fun validateDirection(direction: String): String? {
        return if (ScheduleDirection.entries.none { it.name == direction })
            "Direction must be one of: ${ScheduleDirection.entries.joinToString(", ") { it.name }}"
        else null
    }

    private fun validateDepartureTime(time: String): String? {
        return try {
            LocalTime.parse(time, timeFormatter)
            null
        } catch (_: DateTimeParseException) {
            "Departure time must be in HH:mm format (e.g. '07:30')"
        }
    }

    private fun validateDaysOfWeek(days: List<String>): String? {
        if (days.isEmpty()) return "At least one day of week is required"

        val validNames = ScheduleDay.entries.map { it.name }.toSet()
        val invalid = days.filter { it !in validNames }
        if (invalid.isNotEmpty())
            return "Invalid day(s): ${invalid.joinToString(", ")}. Must be one of: ${validNames.sorted().joinToString(", ")}"

        if (days.size != days.toSet().size)
            return "Days of week must not contain duplicates"

        return null
    }

    private fun parseDepartureTime(time: String): LocalTime =
        LocalTime.parse(time, timeFormatter)

    private fun parseDays(days: List<String>): List<ScheduleDay> =
        days.map { ScheduleDay.valueOf(it) }

    // =========================================================================
    // Service operations
    // =========================================================================

    fun createSchedule(companyId: String, request: CreateShuttleScheduleRequest): CreateResult {
        val nameErr = validateName(request.name)
        if (nameErr != null) return CreateResult.ValidationError(nameErr)

        val dirErr = validateDirection(request.direction)
        if (dirErr != null) return CreateResult.ValidationError(dirErr)

        val timeErr = validateDepartureTime(request.departureTime)
        if (timeErr != null) return CreateResult.ValidationError(timeErr)

        val daysErr = validateDaysOfWeek(request.daysOfWeek)
        if (daysErr != null) return CreateResult.ValidationError(daysErr)

        val schedule = scheduleRepository.create(
            companyId = companyId,
            name = request.name.trim(),
            direction = ScheduleDirection.valueOf(request.direction),
            departureTime = parseDepartureTime(request.departureTime),
            daysOfWeek = parseDays(request.daysOfWeek)
        )
        return CreateResult.Success(schedule)
    }

    fun getSchedule(id: String, callerCompanyId: String): ShuttleSchedule? {
        val schedule = scheduleRepository.findById(id) ?: return null
        // Tenant isolation: 404 rather than 403 — do not leak existence
        if (schedule.companyId != callerCompanyId) return null
        return schedule
    }

    fun getSchedulesByCompany(callerCompanyId: String, activeOnly: Boolean = false): List<ShuttleSchedule> {
        return if (activeOnly) {
            scheduleRepository.findActiveByCompany(callerCompanyId)
        } else {
            scheduleRepository.findByCompany(callerCompanyId)
        }
    }

    fun updateSchedule(
        id: String,
        callerCompanyId: String,
        request: UpdateShuttleScheduleRequest
    ): UpdateResult {
        val existing = scheduleRepository.findById(id) ?: return UpdateResult.NotFound
        // Tenant isolation: 404 — do not reveal another company's resource
        if (existing.companyId != callerCompanyId) return UpdateResult.NotFound

        if (request.name != null) {
            val nameErr = validateName(request.name)
            if (nameErr != null) return UpdateResult.ValidationError(nameErr)
        }

        if (request.direction != null) {
            val dirErr = validateDirection(request.direction)
            if (dirErr != null) return UpdateResult.ValidationError(dirErr)
        }

        if (request.departureTime != null) {
            val timeErr = validateDepartureTime(request.departureTime)
            if (timeErr != null) return UpdateResult.ValidationError(timeErr)
        }

        if (request.daysOfWeek != null) {
            val daysErr = validateDaysOfWeek(request.daysOfWeek)
            if (daysErr != null) return UpdateResult.ValidationError(daysErr)
        }

        val updated = scheduleRepository.update(
            id = id,
            companyId = callerCompanyId,
            name = request.name?.trim(),
            direction = request.direction?.let { ScheduleDirection.valueOf(it) },
            departureTime = request.departureTime?.let { parseDepartureTime(it) },
            daysOfWeek = request.daysOfWeek?.let { parseDays(it) },
            active = request.active
        ) ?: return UpdateResult.NotFound

        return UpdateResult.Success(updated)
    }

    fun deleteSchedule(id: String, callerCompanyId: String): Boolean {
        val existing = scheduleRepository.findById(id) ?: return false
        // Tenant isolation: return false (→ 404) rather than 403
        if (existing.companyId != callerCompanyId) return false
        return scheduleRepository.deactivate(id, callerCompanyId)
    }

    fun generateTrip(
        scheduleId: String,
        callerCompanyId: String,
        request: GenerateTripRequest
    ): GenerateTripResult {
        val repo = tripRepository ?: error("TripRepository is required for trip generation")

        // 1. Verify existence & tenant isolation (404 if not found or cross-company)
        val schedule = getSchedule(scheduleId, callerCompanyId)
            ?: return GenerateTripResult.NotFound

        // 2. Reject inactive schedules
        if (!schedule.active) {
            return GenerateTripResult.InactiveSchedule("Cannot generate trip from inactive schedule")
        }

        // 3. Parse date
        val tripDate = runCatching { LocalDate.parse(request.date.trim()) }.getOrNull()
            ?: return GenerateTripResult.ValidationError("Invalid date format. Expected YYYY-MM-DD")

        // 4. Reject past dates
        val today = LocalDate.now(zoneId)
        if (tripDate.isBefore(today)) {
            return GenerateTripResult.ValidationError("Cannot generate trip for a past date: $tripDate")
        }

        // 5. Day-of-week validation
        val requestedDay = ScheduleDay.valueOf(tripDate.dayOfWeek.name)
        if (requestedDay !in schedule.daysOfWeek) {
            return GenerateTripResult.ValidationError(
                "Schedule does not operate on $requestedDay. Operating days: ${schedule.daysOfWeek.joinToString(", ")}"
            )
        }

        // 6. Idempotency check: return existing if already generated
        val existing = repo.findByScheduleAndDate(schedule.id, tripDate)
        if (existing != null) {
            return GenerateTripResult.Success(existing, isCreated = false)
        }

        // 7. Calculate start_time = requested date + schedule.departureTime in application timezone
        val startDateTime = LocalDateTime.of(tripDate, schedule.departureTime)
        val startTime = startDateTime.atZone(zoneId).toInstant()

        // 8. Create trip with vehicle_id = null, driver_id = null, status = SCHEDULED
        val newTrip = Trip(
            id = UUID.randomUUID().toString(),
            scheduleId = schedule.id,
            tripDate = tripDate,
            vehicleId = null,
            driverId = null,
            startTime = startTime,
            status = "SCHEDULED"
        )

        val created = repo.create(newTrip)
        val isCreated = created.id == newTrip.id
        return GenerateTripResult.Success(created, isCreated = isCreated)
    }
}
