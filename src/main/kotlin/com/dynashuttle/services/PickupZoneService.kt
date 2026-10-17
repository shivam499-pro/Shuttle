package com.dynashuttle.services

import com.dynashuttle.models.CreatePickupZoneRequest
import com.dynashuttle.models.PickupZone
import com.dynashuttle.models.UpdatePickupZoneRequest
import com.dynashuttle.repositories.PickupZoneRepository

class PickupZoneService(
    private val pickupZoneRepository: PickupZoneRepository
) {
    sealed class CreateResult {
        data class Success(val zone: PickupZone) : CreateResult()
        data class ValidationError(val message: String) : CreateResult()
    }

    sealed class UpdateResult {
        data class Success(val zone: PickupZone) : UpdateResult()
        object NotFound : UpdateResult()
        data class ValidationError(val message: String) : UpdateResult()
    }

    sealed class AssignmentResult {
        object Success : AssignmentResult()
        object EmployeeNotFound : AssignmentResult()
        object ZoneNotFound : AssignmentResult()
        object CrossCompanyMismatch : AssignmentResult()
    }

    fun validateCoordinates(lat: Double, lng: Double): String? {
        if (lat < -90.0 || lat > 90.0) return "Latitude must be between -90 and 90"
        if (lng < -180.0 || lng > 180.0) return "Longitude must be between -180 and 180"
        return null
    }

    fun validateRadius(radius: Int): String? {
        if (radius <= 0) return "Radius must be greater than 0"
        return null
    }

    fun validateName(name: String): String? {
        if (name.trim().isEmpty()) return "Name cannot be blank"
        return null
    }

    fun createZone(companyId: String, request: CreatePickupZoneRequest): CreateResult {
        val nameErr = validateName(request.name)
        if (nameErr != null) return CreateResult.ValidationError(nameErr)

        val coordErr = validateCoordinates(request.latitude, request.longitude)
        if (coordErr != null) return CreateResult.ValidationError(coordErr)

        val radiusErr = validateRadius(request.radius)
        if (radiusErr != null) return CreateResult.ValidationError(radiusErr)

        val zone = pickupZoneRepository.create(
            companyId = companyId,
            name = request.name.trim(),
            latitude = request.latitude,
            longitude = request.longitude,
            radius = request.radius,
            address = request.address?.trim()
        )
        return CreateResult.Success(zone)
    }

    fun getZone(id: String, callerCompanyId: String): PickupZone? {
        val zone = pickupZoneRepository.findById(id) ?: return null
        if (zone.companyId != callerCompanyId) return null
        return zone
    }

    fun getZonesByCompany(callerCompanyId: String, activeOnly: Boolean = false): List<PickupZone> {
        return if (activeOnly) {
            pickupZoneRepository.findActiveByCompany(callerCompanyId)
        } else {
            pickupZoneRepository.findByCompany(callerCompanyId)
        }
    }

    fun updateZone(id: String, callerCompanyId: String, request: UpdatePickupZoneRequest): UpdateResult {
        val existing = pickupZoneRepository.findById(id) ?: return UpdateResult.NotFound
        if (existing.companyId != callerCompanyId) return UpdateResult.NotFound

        if (request.name != null) {
            val nameErr = validateName(request.name)
            if (nameErr != null) return UpdateResult.ValidationError(nameErr)
        }

        if (request.latitude != null || request.longitude != null) {
            val lat = request.latitude ?: existing.latitude
            val lng = request.longitude ?: existing.longitude
            val coordErr = validateCoordinates(lat, lng)
            if (coordErr != null) return UpdateResult.ValidationError(coordErr)
        }

        if (request.radius != null) {
            val radiusErr = validateRadius(request.radius)
            if (radiusErr != null) return UpdateResult.ValidationError(radiusErr)
        }

        val updated = pickupZoneRepository.update(
            id = id,
            companyId = callerCompanyId,
            name = request.name?.trim(),
            latitude = request.latitude,
            longitude = request.longitude,
            radius = request.radius,
            address = request.address?.trim(),
            active = request.active
        ) ?: return UpdateResult.NotFound

        return UpdateResult.Success(updated)
    }

    fun deleteZone(id: String, callerCompanyId: String): Boolean {
        val existing = pickupZoneRepository.findById(id) ?: return false
        if (existing.companyId != callerCompanyId) return false
        return pickupZoneRepository.deactivate(id, callerCompanyId)
    }

    fun assignEmployeeToZone(employeeId: String, zoneId: String?, callerCompanyId: String): AssignmentResult {
        val empCompanyId = pickupZoneRepository.findEmployeeCompanyId(employeeId)
            ?: return AssignmentResult.EmployeeNotFound

        // Tenant isolation: employee must belong to caller's company
        if (empCompanyId != callerCompanyId) {
            return AssignmentResult.CrossCompanyMismatch
        }

        if (zoneId != null) {
            val zone = pickupZoneRepository.findById(zoneId)
                ?: return AssignmentResult.ZoneNotFound

            // Tenant isolation: zone must belong to caller's company and employee's company
            if (zone.companyId != callerCompanyId || zone.companyId != empCompanyId) {
                return AssignmentResult.CrossCompanyMismatch
            }
        }

        val ok = pickupZoneRepository.assignEmployeeToZone(employeeId, zoneId)
        return if (ok) AssignmentResult.Success else AssignmentResult.EmployeeNotFound
    }
}
