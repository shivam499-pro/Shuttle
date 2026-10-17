package com.dynashuttle.models

import java.time.Instant

data class PickupZone(
    val id: String,
    val companyId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val address: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)
