package com.dynashuttle.services

interface OtpGenerator {
    fun generate(email: String): String
}
