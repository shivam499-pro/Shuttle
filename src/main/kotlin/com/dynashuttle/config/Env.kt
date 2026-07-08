package com.dynashuttle.config

import io.github.cdimascio.dotenv.dotenv

/**
 * Single place the whole app reads configuration from.
 *
 * Locally: loads values from the .env file in the project root (so IntelliJ
 * run configs don't need manual environment variables — just fill in .env
 * and run).
 *
 * In production (Railway/Render): there usually is no .env file deployed,
 * so this falls back to real system environment variables set in the
 * hosting dashboard. Same code path works in both places.
 */
object Env {

    private val dotenv = dotenv {
        ignoreIfMissing = true // don't crash in production where .env doesn't exist
        ignoreIfMalformed = true
    }

    fun get(key: String, default: String? = null): String? {
        return System.getenv(key) ?: dotenv[key] ?: default
    }

    fun require(key: String): String {
        return get(key) ?: error("Missing required environment variable: $key (check your .env file)")
    }
}
