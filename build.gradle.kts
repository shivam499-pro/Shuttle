val ktorVersion = "2.3.12"
val kotlinVersion = "1.9.24"
val exposedVersion = "0.50.1"
val logbackVersion = "1.5.6"
val postgresVersion = "42.7.3"
val hikariVersion = "5.1.0"

plugins {
    kotlin("jvm") version "1.9.24"
    id("io.ktor.plugin") version "2.3.12"
    kotlin("plugin.serialization") version "1.9.24"
}

group = "com.dynashuttle"
version = "0.1.0"

application {
    mainClass.set("com.dynashuttle.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server core
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // Auth (JWT)
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    implementation("com.auth0:java-jwt:4.4.0")

    // WebSockets — real-time GPS broadcast
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")

    // HTTP client — outbound calls to third-party routing APIs (TomTom, see EtaEstimator)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    // Request validation, CORS, status pages (error handling)
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")

    // Database — Exposed ORM + PostgreSQL driver + connection pooling
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1") // confirmed published on Maven Central

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")

    // Password Hashing
    implementation("at.favre.lib:bcrypt:0.10.2")
}