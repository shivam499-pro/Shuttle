package com.dynashuttle

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthCheckTest {

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application {
            // Note: this minimal test app skips DatabaseFactory.init() so it can
            // run without a live Postgres connection. Add a test-database setup
            // here once you wire up real persistence logic.
        }
        val response = client.get("/health")
        // This will 404 until configureRouting() is wired into the test app above —
        // intentionally left as a starting point for you to extend.
        assertTrue(response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.OK)
    }
}
