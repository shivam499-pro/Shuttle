package com.dynashuttle.services

import kotlin.random.Random

class RandomOtpGenerator : OtpGenerator {
    override fun generate(email: String): String {
        val number = Random.nextInt(100_000, 1000_000)
        return number.toString()
    }
}
