package com.dynashuttle.models

import kotlinx.serialization.Serializable

@Serializable
data class RideRequestDto(
    val employeeId: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val destinationLat: Double,
    val destinationLng: Double
)

@Serializable
data class RideResponseDto(
    val rideRequestId: String,
    val status: String,
    val assignedVehicleId: String? = null,
    val etaMinutes: Int? = null
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
    val role: String
)

@Serializable
data class AdminLoginRequest(val email: String, val password: String)

@Serializable
data class AdminLoginResponse(
    val token: String,
    val role: String
)