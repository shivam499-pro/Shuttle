package com.dynashuttle.models

import kotlinx.serialization.Serializable

@Serializable
data class RideRequestDto(
    val employeeId: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val pickupZoneId: String? = null
)

@Serializable
data class CreatePickupZoneRequest(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val address: String? = null
)

@Serializable
data class UpdatePickupZoneRequest(
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radius: Int? = null,
    val address: String? = null,
    val active: Boolean? = null
)

@Serializable
data class PickupZoneResponse(
    val id: String,
    val companyId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val address: String? = null,
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class AssignEmployeeZoneRequest(
    val pickupZoneId: String?
)

@Serializable
data class RideResponseDto(
    val rideRequestId: String,
    val status: String,
    val assignedVehicleId: String? = null,
    val etaMinutes: Int? = null
)

@Serializable
data class DriverOtpRequest(
    val phone: String
)

@Serializable
data class DriverOtpResponse(
    val otp: String?,
    val message: String
)

@Serializable
data class DriverVerifyRequest(
    val phone: String,
    val otp: String
)

@Serializable
data class DriverVerifyResponse(
    val token: String,
    val driverId: String,
    val role: String,
    val companyId: String
)

@Serializable
data class DriverLocationUpdate(
    val driverId: String,
    val tripId: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long
)

@Serializable
data class StopListUpdate(
    val tripId: String,
    val stops: List<Stop>
)

@Serializable
data class Stop(
    val rideRequestId: String,
    val lat: Double,
    val lng: Double,
    val sequenceOrder: Int,
    val employeeName: String
)

@Serializable
data class StopActionResponse(val stopId: String, val status: String)

@Serializable
data class ErrorResponse(val error: String, val message: String)

@Serializable
data class EmployeeOtpRequest(val email: String)

@Serializable
data class EmployeeOtpResponse(val otp: String?, val message: String)

@Serializable
data class EmployeeVerifyRequest(val email: String, val otp: String)

@Serializable
data class EmployeeVerifyResponse(
    val token: String,
    val employeeId: String,
    val role: String,
    val companyId: String? = null
)

@Serializable
data class AdminLoginRequest(val email: String, val password: String)

@Serializable
data class AdminLoginResponse(
    val token: String,
    val role: String,
    val companyId: String? = null
)

@Serializable
data class CreateShuttleScheduleRequest(
    val name: String,
    val direction: String,
    val departureTime: String,       // "HH:mm" local time, e.g. "07:30"
    val daysOfWeek: List<String>     // e.g. ["MONDAY","TUESDAY","FRIDAY"]
)

@Serializable
data class UpdateShuttleScheduleRequest(
    val name: String? = null,
    val direction: String? = null,
    val departureTime: String? = null,
    val daysOfWeek: List<String>? = null,
    val active: Boolean? = null
)

@Serializable
data class ShuttleScheduleResponse(
    val id: String,
    val companyId: String,
    val name: String,
    val direction: String,
    val departureTime: String,        // "HH:mm"
    val daysOfWeek: List<String>,
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class GenerateTripRequest(
    val date: String // "YYYY-MM-DD", e.g. "2026-09-28"
)

@Serializable
data class GeneratedTripResponse(
    val tripId: String,
    val scheduleId: String,
    val tripDate: String,
    val startTime: String,
    val vehicleId: String? = null,
    val driverId: String? = null,
    val status: String = "SCHEDULED"
)