package com.dynashuttle.repositories

import com.dynashuttle.models.PickupZone

interface PickupZoneRepository {
    fun create(
        companyId: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radius: Int,
        address: String? = null
    ): PickupZone

    fun findById(id: String): PickupZone?

    fun findByCompany(companyId: String): List<PickupZone>

    fun findActiveByCompany(companyId: String): List<PickupZone>

    fun update(
        id: String,
        companyId: String,
        name: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        radius: Int? = null,
        address: String? = null,
        active: Boolean? = null
    ): PickupZone?

    fun deactivate(id: String, companyId: String): Boolean

    fun findEmployeeCompanyId(employeeId: String): String?

    fun assignEmployeeToZone(employeeId: String, zoneId: String?): Boolean

    fun findEmployeePickupZoneId(employeeId: String): String?
}
