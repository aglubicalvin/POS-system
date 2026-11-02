import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "com.businessstore"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.0"
val postgresBinariesVersion = "18.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Real PostgreSQL for tests without Docker: zonky downloads actual PG binaries.
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:$postgresBinariesVersion"))
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64:$postgresBinariesVersion")
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:$postgresBinariesVersion")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

application {
    mainClass.set("com.businessstore.pos.ApplicationKt")
}

/**
 * Explicit schema provisioning. Normal startup never writes schema; this task
 * does, so it requires PROVISION_CONFIRM=yes and an explicit POS_DATABASE_URL.
 * There is no default target in the task or in the code it runs.
 */
tasks.register<JavaExec>("provisionDatabase") {
    group = "database"
    description = "Create the canonical POS schema on an explicitly confirmed, disposable database."
    mainClass.set("com.businessstore.pos.tools.ProvisionKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

/**
 * Read-only staging identity probe. This task runs ProbeState against a database
 * nominated by CHECK_URL with PROBE_CONFIRM=yes required. It never writes, never
 * provisions, and never applies migrations. Used by
 * .github/workflows/probe-staging-identity.yml.
 */
tasks.register<JavaExec>("execProbeState") {
    group = "verification"
    description = "Run the read-only ProbeState audit against a nominated database (workflow use only)."
    mainClass.set("com.businessstore.pos.tools.ProbeState")
    classpath = sourceSets.main.get().runtimeClasspath
    environment("PROBE_CONFIRM", "yes")
    doFirst {
        systemProperty("CHECK_URL", System.getProperty("CHECK_URL"))
        systemProperty("CHECK_USER", System.getProperty("CHECK_USER"))
        systemProperty("CHECK_PASS", System.getProperty("CHECK_PASS"))
        systemProperty("PROBE_CONFIRM", System.getProperty("PROBE_CONFIRM"))
    }
}
