plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("io.ktor.plugin") version "2.3.8"
    application
}

group = "com.beauty"
version = "1.0.0"

application {
    mainClass.set("com.beauty.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.8"
val exposedVersion = "0.47.0"
val postgresVersion = "42.7.1"
val logbackVersion = "1.4.14"

dependencies {
    // Ktor Server Core & Netty
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")

    // Database: PostgreSQL & Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.postgresql:postgresql:$postgresVersion")
    // Connection pool. Without it Exposed opens a new JDBC connection per
    // transaction, which exhausts Postgres' connection limit under load.
    implementation("com.zaxxer:HikariCP:5.1.0")
    runtimeOnly("com.h2database:h2:2.2.224")

    // Security & Utilities
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Testing
    testImplementation("io.ktor:ktor-server-tests-jvm:$ktorVersion")
    // was pinned to $ktorVersion, which is not a valid Kotlin version
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
}
